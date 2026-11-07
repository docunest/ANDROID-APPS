package com.docunest.dock

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * GPS troubleshooting. Listens to the GPS chip itself (satellites, signal,
 * raw NMEA, fixes) and says in plain words what's wrong; [fix] then works
 * down a ladder of fixes, each followed by up to a few minutes of waiting
 * for a real GPS fix, and stops at the first one that brings it back.
 *
 * Every fix tried is recorded ([FixHistory]): did a fix follow, and how
 * long it took. [Planner] orders the next run from that — fixes still short
 * of [FixStats.MIN_TRIES] go first so every one gets measured, then the
 * fastest expected. Tries spoiled by a suddenly blocked sky (tunnel) or
 * Stop are kept but not counted.
 *
 * Only fixes from the GPS provider count — never Wi-Fi/mobile positions.
 * Times are taken from [SystemClock.elapsedRealtime] because a wrong wall
 * clock is one of the faults being looked for.
 */
object GpsDoctor {
    /** A fix this good (or better) from the GPS chip = working. */
    private const val GOOD_ACCURACY_M = 100f
    private const val FRESH_MS = 15_000L
    private const val CLOCK_OFF_MS = 2 * 60_000L
    private const val WEAK_CN0 = 20f

    enum class State { PENDING, RUNNING, OK, NO_FIX, SKIPPED, VOID }

    /** The fixes that compete (the planner orders them): id, title, what, default wait. */
    private val REMEDIES = listOf(
        Step("listen", "Just wait", "Keep asking the GPS and wait (the baseline every fix is compared with)", 60_000),
        Step("clock", "Fix the clock", "Compare with internet time; set it if wrong", 90_000),
        Step("assist", "Fresh satellite data", "Download orbit data (XTRA) + time", 120_000),
        Step("warm", "Warm restart", "Forget position + orbits, reload them", 150_000),
        Step("toggle", "Restart GPS", "Switch GPS off and on (root)", 120_000),
        Step("cold", "Cold start", "Forget everything, search the sky from scratch", 180_000),
        Step("driver", "Restart GPS driver", "Restart the GPS driver process (root)", 180_000),
    )
    val remedyIds: List<String> get() = REMEDIES.map { it.id }
    fun title(id: String) = (REMEDIES.firstOrNull { it.id == id } ?: when (id) {
        "permission" -> Step(id, "Allow location", "", 0)
        "location" -> Step(id, "Switch location on", "", 0)
        else -> null
    })?.title ?: id

    class Step(val id: String, val title: String, val what: String, val waitMs: Long) {
        @Volatile var state = State.PENDING
        @Volatile var note = ""
        /** elapsedRealtime when the wait ends — for the countdown. */
        @Volatile var endsAt = 0L
    }

    // --- Live readings (written on the main thread, read anywhere) ----------
    @Volatile var satsHeard = 0
    @Volatile var satsUsed = 0
    /** Satellites heard at ≥ 25 dB-Hz: four or more = the antenna sees open sky. */
    @Volatile var strongSats = 0
    /** When strong satellites last numbered 4+, and when they then collapsed to ≤ 1 within seconds (tunnel). */
    @Volatile var lastOpenSkyAt = 0L
    @Volatile var skyCollapsedAt = 0L
    /** Average of the best four signals, dB-Hz (≥ 25 good, < 20 weak). */
    @Volatile var topCn0 = 0f
    @Volatile var systems = ""
    @Volatile var nmeaCount = 0
    @Volatile var lastNmeaAt = 0L
    @Volatile var lastStatusAt = 0L
    @Volatile var fix: Location? = null
    @Volatile var fixAt = 0L
    @Volatile var ttffMs: Int? = null
    @Volatile var watchingSince = 0L
    /** System clock minus internet time; null = not measured / no internet. */
    @Volatile var clockOffsetMs: Long? = null
    @Volatile var hasRoot: Boolean? = null

    // --- The ladder ---------------------------------------------------------
    @Volatile var running = false
        private set
    @Volatile var steps: List<Step> = emptyList()
        private set
    @Volatile var summary = ""
        private set
    /** The run going on (or last run) was started by the auto doctor. */
    @Volatile var auto = false
        private set
    /** Called on the worker thread when a run ends: "fixed", "failed", "stopped", "paused", "blocked". */
    @Volatile var onRunEnd: ((String) -> Unit)? = null
    private val log = ArrayList<String>()
    fun logLines(): List<String> = synchronized(log) { log.toList() }

    /** Asks the open screen to do what needs a person: "permission", "location", "date". */
    @Volatile var askUser: ((String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val worker = Handler(HandlerThread("gps-doctor").apply { start() }.looper)
    @Volatile private var cancelled = false
    private val listeners = mutableSetOf<() -> Unit>()
    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }
    private fun changed() {
        val ls = synchronized(listeners) { listeners.toList() }
        main.post { ls.forEach { it() } }
    }

    private fun now() = SystemClock.elapsedRealtime()

    // --- Listening to the chip ----------------------------------------------

    private var watching = false
    /** Who wants the chip listened to: "screen" (doctor open), "auto" (auto doctor on). */
    private val wanted = mutableSetOf<String>()
    val isWatching: Boolean get() = watching

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (location.isMockFix()) return // our parked position (or a fake-GPS app) is not the chip
            fix = location
            fixAt = now()
            changed()
        }
        override fun onProviderEnabled(provider: String) = changed()
        override fun onProviderDisabled(provider: String) {
            fixAt = 0L // an old fix doesn't count once GPS is off
            changed()
        }
        @Deprecated("Called on Android 10 and older only")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val statusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var heard = 0
            var used = 0
            val cn0s = ArrayList<Float>()
            val bySystem = LinkedHashMap<String, Int>()
            for (i in 0 until status.satelliteCount) {
                val cn0 = status.getCn0DbHz(i)
                if (cn0 <= 0f) continue
                heard++
                cn0s += cn0
                if (status.usedInFix(i)) used++
                val name = systemName(status.getConstellationType(i))
                bySystem[name] = (bySystem[name] ?: 0) + 1
            }
            satsHeard = heard
            satsUsed = used
            val strong = cn0s.count { it >= 25f }
            val t = now()
            if (strong >= 4) lastOpenSkyAt = t
            else if (strong <= 1 && lastOpenSkyAt > 0 && t - lastOpenSkyAt < 6_000) skyCollapsedAt = t
            strongSats = strong
            topCn0 = cn0s.sortedDescending().take(4).takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f
            systems = bySystem.entries.joinToString(" · ") { "${it.key} ${it.value}" }
            lastStatusAt = now()
            changed()
        }
        override fun onFirstFix(ttffMillis: Int) {
            ttffMs = ttffMillis
            changed()
        }
    }

    private val nmeaListener = OnNmeaMessageListener { _, _ ->
        nmeaCount++
        lastNmeaAt = now()
    }

    /** Start listening (main thread). Keeps the GPS chip working while the doctor screen is open or auto is on. */
    fun watch(context: Context, who: String = "screen") {
        wanted += who
        listen(context.applicationContext)
    }

    fun unwatch(context: Context, who: String = "screen") {
        wanted -= who
        if (wanted.isEmpty()) stopListening(context.applicationContext)
    }

    @SuppressLint("MissingPermission") // checked by Locator.hasPermission()
    private fun listen(context: Context) {
        if (watching || wanted.isEmpty() || !Locator.hasPermission(context)) return
        val lm = lm(context)
        runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper()) }
            .onFailure { note("Can't ask the GPS provider: ${it.message}") }
        runCatching { lm.registerGnssStatusCallback(statusCallback, main) }
        runCatching { lm.addNmeaListener(nmeaListener, main) }
        watching = true
        watchingSince = now()
        if (steps.isEmpty()) steps = runCatching { plan(context) }.getOrDefault(emptyList())
        changed()
    }

    private fun stopListening(context: Context) {
        if (!watching) return
        val lm = lm(context)
        runCatching { lm.removeUpdates(locationListener) }
        runCatching { lm.unregisterGnssStatusCallback(statusCallback) }
        runCatching { lm.removeNmeaListener(nmeaListener) }
        watching = false
    }

    /** Drop and renew our GPS request — restarts the chip's search session. */
    private fun rewatch(context: Context) = onMain { stopListening(context); listen(context) }

    // --- What's wrong, in one sentence --------------------------------------

    fun hasFix(): Boolean {
        val f = fix ?: return false
        return now() - fixAt < FRESH_MS && (!f.hasAccuracy() || f.accuracy <= GOOD_ACCURACY_M)
    }

    fun chipTalks(): Boolean {
        val t = now()
        return t - lastNmeaAt < 10_000 || t - lastStatusAt < 10_000
    }

    fun settingsOk(context: Context) = Locator.hasPermission(context) && locationOn(context) && gpsEnabled(lm(context))

    fun verdict(context: Context): String = diagnose(context).second

    /** (symptom code, sentence). The code groups the fix statistics: what helps with *this* kind of trouble. */
    fun diagnose(context: Context): Pair<String, String> {
        val lm = lm(context)
        if (!Locator.hasPermission(context)) return "perm" to "Dock isn't allowed to use location."
        if (!locationOn(context)) return "off" to "Location is switched off on this device."
        if (!gpsEnabled(lm)) return "off" to "GPS is switched off (only Wi-Fi / mobile location is on)."
        if (ParkedPosition.mocking) return "parked" to "Showing the parked position — the real GPS is paused until the van moves (or press Fix GPS)."
        if (hasFix()) return "ok" to "GPS works: ±${fix!!.accuracy.toInt()} m${satellitesInUse()}."
        val t = now()
        if (!chipTalks()) {
            return if (t - watchingSince < 20_000) "starting" to "Listening to the GPS chip…"
            else "silent" to "No data from the GPS chip at all — its driver isn't running, or the chip is unplugged."
        }
        clockOffsetMs?.let { if (abs(it) > CLOCK_OFF_MS) return "clock" to "The clock is ${duration(abs(it))} off — the GPS can't use its saved satellite data." }
        if (satsHeard == 0) return "nosats" to "The GPS chip runs but hears no satellites — antenna cable/connector, something metal above it, or a tunnel / car park."
        if (topCn0 < WEAK_CN0) return "weak" to "Satellites heard, but very weak (%.0f dB-Hz) — antenna or what's above it.".format(topCn0)
        return "nofix" to "${plural(satsHeard, "satellite")} heard well but no fix yet — usually stale satellite data (a reset helps)."
    }

    fun symptomLabel(code: String) = when (code) {
        "perm" -> "no permission"
        "off" -> "location off"
        "silent" -> "chip silent"
        "clock" -> "wrong clock"
        "nosats" -> "no satellites"
        "weak" -> "weak signal"
        "nofix" -> "satellites, no fix"
        "nosky" -> "no sky for long"
        "starting" -> "just started"
        "ok" -> "was working"
        else -> code
    }

    // --- Fixes --------------------------------------------------------------

    /** Prerequisites first (they only act when needed), then the remedies in the planner's order. */
    private fun plan(context: Context, symptom: String = diagnose(context).first): List<Step> {
        val attempts = FixHistory.attempts(context)
        val overall = FixStats.summarize(attempts)
        val order = Planner.order(REMEDIES.map { it.id to (it.waitMs / 1000).toInt() }, attempts, symptom)
        val remedies = order.map { id ->
            val r = REMEDIES.first { it.id == id }
            val wait = Planner.waitSecs(overall[id], (r.waitMs / 1000).toInt()) * 1000L
            Step(r.id, r.title, r.what, wait)
        }
        return listOf(
            Step("permission", "Allow location", "Give Dock location access", 30_000),
            Step("location", "Switch location on", "Location + GPS on", 60_000),
        ) + remedies
    }

    /** Run the ladder. Call [watch] first (the doctor screen and the auto doctor do). */
    fun fix(context: Context, byAuto: Boolean = false) {
        if (running) return
        val app = context.applicationContext
        // The parked position pauses the real chip: no fix can work through that.
        if (ParkedPosition.holdsGps) ParkedPosition.stop("GPS doctor is fixing the real GPS")
        val (symptom, _) = diagnose(app)
        steps = plan(app, symptom)
        cancelled = false
        running = true
        auto = byAuto
        summary = if (byAuto) "Auto doctor working…" else "Working…"
        synchronized(log) { log.clear() }
        changed()
        worker.post { ladder(app, symptom, byAuto) }
    }

    fun cancel() {
        if (running) cancelled = true
    }

    private fun ladder(context: Context, symptom: String, byAuto: Boolean) {
        val runId = System.currentTimeMillis()
        val runStart = now()
        hasRoot = Root.run("id")
        note("${if (byAuto) "Auto doctor" else "Fix GPS"} · trouble: ${symptomLabel(symptom)} · root: ${if (hasRoot == true) "yes" else "no — root-only fixes are skipped"}")
        // Pressing Fix while the GPS works tests the buttons, not the fixes: don't count it.
        val counted = symptom != "ok"
        if (!counted) note("GPS was working at the start — this run isn't counted in the statistics")
        clockOffsetMs = networkTime(context)?.let { System.currentTimeMillis() - it }
        var fixedBy: Step? = null
        var blocked = false
        var paused = false
        var order = 0
        for (step in steps) {
            if (cancelled) break
            step.state = State.RUNNING
            changed()
            val stepStart = now()
            val ready = runCatching { act(context, step) }.getOrElse { step.note = "Error: ${it.message}"; false }
            if (!ready) {
                if (step.state == State.RUNNING) step.state = State.SKIPPED
                note("${step.title}: ${step.note}")
                changed()
                if (step.id in setOf("permission", "location") && step.state == State.NO_FIX) {
                    blocked = true // nothing else can work
                    break
                }
                continue
            }
            order++
            note("${step.title}: ${step.note.ifEmpty { "done" }} — waiting up to ${duration(step.waitMs)} for a fix")
            val outcome = waitForFix(step)
            val secs = ((now() - stepStart) / 1000).toInt()
            step.state = when (outcome) {
                FixStats.Outcome.FIXED -> State.OK
                FixStats.Outcome.NO_FIX -> State.NO_FIX
                FixStats.Outcome.VOID -> State.VOID
            }
            if (counted && step.id in remedyIds) {
                FixHistory.add(context, FixStats.Attempt(runId, System.currentTimeMillis(), step.id, symptom, byAuto, outcome, secs, (step.waitMs / 1000).toInt(), order))
            }
            changed()
            if (outcome == FixStats.Outcome.FIXED) {
                fixedBy = step
                break
            }
            if (outcome == FixStats.Outcome.VOID && !cancelled) {
                paused = true
                break
            }
        }
        running = false
        val total = now() - runStart
        val f = fix
        val result = when {
            fixedBy != null -> "fixed"
            cancelled -> "stopped"
            paused -> "paused"
            blocked -> "blocked"
            else -> "failed"
        }
        summary = when (result) {
            "fixed" -> "Fixed by \"${fixedBy!!.title}\" in ${duration(total)} — ±${f?.accuracy?.toInt() ?: "?"} m${satellitesInUse()}."
            "stopped" -> "Stopped."
            "paused" -> "Paused: the sky was suddenly blocked (tunnel / car park?) — that try isn't counted. " +
                if (byAuto) "The auto doctor carries on once the sky is back." else "Try again in the open."
            "blocked" -> "Can't go on: ${verdict(context)}"
            else -> "Nothing brought the GPS back (${duration(total)}). ${verdict(context)}" + advice()
        }
        note(summary)
        if (counted) FixHistory.add(context, FixStats.Run(runId, byAuto, symptom, result, fixedBy?.id, (total / 1000).toInt(), order))
        steps.filter { it.state == State.PENDING }.forEach { it.state = State.SKIPPED; it.note = when {
            cancelled -> "stopped"
            paused -> "paused"
            fixedBy != null -> "not needed"
            else -> "not tried"
        } }
        changed()
        runCatching { onRunEnd?.invoke(result) }
    }

    /** Some chips never say which satellites they use — then say nothing. */
    private fun satellitesInUse() = if (satsUsed > 0) ", ${plural(satsUsed, "satellite")} in use" else ""

    private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

    private fun advice(): String = when {
        nmeaCount == 0 && lastStatusAt == 0L -> " Try a full head unit restart (button below); if it stays silent the GPS module or its driver is faulty."
        satsHeard == 0 -> " Check the GPS antenna: connector at the back of the head unit, cable not pinched, antenna not under metal."
        topCn0 < WEAK_CN0 -> " Signal is weak: move the antenna to the windscreen/dash edge, away from a heated or metallised windscreen."
        else -> " Restart the head unit (button below) and park with open sky for 5 minutes."
    }

    /**
     * Do one step. Returns true when the GPS should now be given time to get
     * a fix; false (with [Step.note] set) when the step didn't apply.
     */
    private fun act(context: Context, step: Step): Boolean {
        val lm = lm(context)
        val root = hasRoot == true
        when (step.id) {
            "permission" -> {
                if (Locator.hasPermission(context)) return skip(step, "already allowed")
                if (root) Root.grantAll(context) else askUser?.invoke("permission") ?: return noFix(step, "needs you — open the GPS doctor and allow location")
                if (!waitUntil(60_000) { Locator.hasPermission(context) }) return noFix(step, "still not allowed — Dock can't see the GPS")
                onMain { listen(context) }
                step.note = "allowed"
                return true
            }
            "location" -> {
                if (locationOn(context) && gpsEnabled(lm)) return skip(step, "already on")
                if (root) {
                    if (Build.VERSION.SDK_INT >= 30) Root.run("cmd location set-location-enabled true")
                    else Root.run(
                        "settings put secure location_mode 3",
                        "settings put secure location_providers_allowed +gps",
                        "settings put secure location_providers_allowed +network",
                    )
                } else askUser?.invoke("location") ?: return noFix(step, "needs you — switch on Location / GPS")
                if (!waitUntil(60_000) { locationOn(context) && gpsEnabled(lm) }) return noFix(step, "location / GPS still off")
                rewatch(context)
                step.note = "switched on"
                return true
            }
            "listen" -> {
                rewatch(context)
                step.note = "asking the GPS once a second, changing nothing"
                return true
            }
            "clock" -> {
                val off = clockOffsetMs ?: return skip(step, "no internet to compare with")
                if (abs(off) <= CLOCK_OFF_MS) return skip(step, "clock is right (${duration(abs(off))} off)")
                if (root) {
                    val utc = SimpleDateFormat("MMddHHmmyyyy.ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                    val netNow = System.currentTimeMillis() - off
                    Root.run(
                        "settings put global auto_time 1",
                        "date -u ${utc.format(Date(netNow))}",
                        "am broadcast -a android.intent.action.TIME_SET",
                    )
                } else if (askUser != null) {
                    askUser?.invoke("date")
                    waitUntil(60_000) { networkOffsetOk(context) }
                }
                extra(lm, "force_time_injection")
                clockOffsetMs = networkTime(context)?.let { System.currentTimeMillis() - it }
                val after = clockOffsetMs
                step.note = "was ${duration(abs(off))} off, now " + (after?.let { duration(abs(it)) + " off" } ?: "unknown")
                return true
            }
            "assist" -> {
                if (!online(context)) return skip(step, "no internet")
                step.note = inject(lm)
                return true
            }
            "warm" -> {
                pauseGps(context) {
                    extra(lm, "delete_aiding_data", Bundle().apply {
                        for (k in listOf("ephemeris", "position", "time", "iono", "utc", "health")) putBoolean(k, true)
                    })
                }
                step.note = "forgot orbits + position; " + inject(lm)
                return true
            }
            "toggle" -> {
                if (!root) return skip(step, "needs root")
                pauseGps(context) {
                    if (Build.VERSION.SDK_INT >= 30) Root.run("cmd location set-location-enabled false", "sleep 3", "cmd location set-location-enabled true")
                    else Root.run("settings put secure location_providers_allowed -gps", "sleep 3", "settings put secure location_providers_allowed +gps")
                }
                step.note = if (gpsEnabled(lm)) "GPS off → on" else "GPS didn't come back on!"
                return true
            }
            "cold" -> {
                pauseGps(context) { extra(lm, "delete_aiding_data") }
                step.note = "forgot everything; " + inject(lm)
                return true
            }
            "driver" -> {
                if (!root) return skip(step, "needs root")
                val procs = gpsProcesses(context.packageName)
                if (procs.isEmpty()) return skip(step, "no GPS driver process found")
                pauseGps(context) {
                    Root.run("kill ${procs.joinToString(" ") { it.first }}", "sleep 5")
                }
                val back = gpsProcesses(context.packageName).map { it.second }
                step.note = "restarted " + procs.joinToString { it.second } + if (back.isEmpty()) " (not back yet)" else ""
                return true
            }
        }
        return skip(step, "unknown step")
    }

    private fun skip(step: Step, why: String): Boolean {
        step.state = State.SKIPPED
        step.note = why
        return false
    }

    private fun noFix(step: Step, why: String): Boolean {
        step.state = State.NO_FIX
        step.note = why
        return false
    }

    /**
     * The self-check: a GPS fix that arrived after the step, good enough to
     * drive on. VOID when the sky was suddenly blocked meanwhile (all strong
     * satellites gone within seconds = tunnel / car park entrance) or Stop.
     */
    private fun waitForFix(step: Step): FixStats.Outcome {
        val start = now()
        step.endsAt = start + step.waitMs
        changed()
        while (!cancelled && now() < step.endsAt) {
            if (fixAt > start && hasFix()) {
                step.note += " → fix after ${duration(now() - start)}"
                return FixStats.Outcome.FIXED
            }
            if (skyCollapsedAt > start) {
                step.note += " → sky suddenly blocked (tunnel?) — not counted"
                return FixStats.Outcome.VOID
            }
            SystemClock.sleep(1000)
            changed()
        }
        if (cancelled) {
            step.note += " → stopped (not counted)"
            return FixStats.Outcome.VOID
        }
        step.note += " → no fix in ${duration(step.waitMs)} (heard $satsHeard, strong $strongSats, used $satsUsed)"
        return FixStats.Outcome.NO_FIX
    }

    private fun waitUntil(ms: Long, ok: () -> Boolean): Boolean {
        val end = now() + ms
        while (!cancelled && now() < end) {
            if (ok()) return true
            SystemClock.sleep(1000)
        }
        return ok()
    }

    /** Stop asking GPS, do [block], ask again — a reset only bites when the engine restarts. */
    private fun pauseGps(context: Context, block: () -> Unit) {
        onMain { stopListening(context) }
        SystemClock.sleep(1500)
        block()
        SystemClock.sleep(1500)
        onMain { listen(context) }
    }

    private fun inject(lm: LocationManager): String {
        val time = extra(lm, "force_time_injection")
        // "psds" is the Android 12+ name for the same download.
        val xtra = extra(lm, "force_xtra_injection") or extra(lm, "force_psds_injection")
        return "time ${if (time) "✓" else "✗"}, orbit data ${if (xtra) "✓" else "✗ (chip may not support it)"}"
    }

    private fun extra(lm: LocationManager, command: String, extras: Bundle? = null): Boolean =
        runCatching { lm.sendExtraCommand(LocationManager.GPS_PROVIDER, command, extras) }.getOrDefault(false)

    /** (pid, name) of GPS/GNSS driver processes: HAL services, MediaTek mnld, gpsd… never apps. */
    private fun gpsProcesses(ownPackage: String): List<Pair<String, String>> {
        val out = Root.output("ps -A -o PID,NAME") ?: return emptyList()
        val match = Regex("gnss|gps|mnld|lbs", RegexOption.IGNORE_CASE)
        return out.lines().drop(1).mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            if (parts.size < 2) return@mapNotNull null
            val name = parts[1]
            val isApp = name.startsWith("com.") || name.startsWith(ownPackage)
            if (match.containsMatchIn(name) && !isApp) parts[0] to name else null
        }
    }

    // --- Small helpers ------------------------------------------------------

    private fun lm(context: Context) = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    fun gpsEnabled(lm: LocationManager) = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)

    fun locationOn(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 28) lm(context).isLocationEnabled
        else @Suppress("DEPRECATION") Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE, 0) != 0

    private fun online(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetwork != null
    }

    /** Internet time from an HTTP Date header (our own server, else Google). */
    private fun networkTime(context: Context): Long? {
        for (url in listOf(Prefs(context).serverUrl, "https://www.google.com")) {
            val t = runCatching {
                val c = URL(url).openConnection() as HttpURLConnection
                c.requestMethod = "HEAD"
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.connect()
                val d = c.date
                c.disconnect()
                d
            }.getOrDefault(0L)
            if (t > 0) return t
        }
        return null
    }

    private fun networkOffsetOk(context: Context) =
        networkTime(context)?.let { abs(System.currentTimeMillis() - it) <= CLOCK_OFF_MS } ?: false

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun note(line: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        synchronized(log) { log += "$t  $line" }
        changed()
    }

    private fun systemName(type: Int) = when (type) {
        GnssStatus.CONSTELLATION_GPS -> "GPS"
        GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
        GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
        GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
        GnssStatus.CONSTELLATION_QZSS -> "QZSS"
        GnssStatus.CONSTELLATION_SBAS -> "SBAS"
        else -> "other"
    }

    fun duration(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "${s} s"
            s < 3600 -> if (s % 60 == 0L) "${s / 60} min" else "${s / 60} min ${s % 60} s"
            s < 48 * 3600 -> "${s / 3600} h ${(s % 3600) / 60} min"
            else -> "${s / 86400} days"
        }
    }
}
