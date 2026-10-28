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
 * for a real GPS fix, and stops at the first one that brings it back. The
 * one that worked is remembered and tried first next time.
 *
 * Only fixes from the GPS provider count — never Wi-Fi/mobile positions.
 * Times are taken from [SystemClock.elapsedRealtime] because a wrong wall
 * clock is one of the faults being looked for.
 */
object GpsDoctor {
    /** A fix this good (or better) from the GPS chip = working. */
    /** A fix this good (or better) is one Waze can navigate on. */
    const val GOOD_ACCURACY_M = 30f
    private const val FRESH_MS = 5_000L
    /** "Fixed" = this many good fixes in a row (~5 s at 1 Hz), not one lucky one. */
    private const val STABLE_FIXES = 5
    private const val CLOCK_OFF_MS = 2 * 60_000L
    /** Best-four average below this = too weak for a reliable fix (open sky gives 35–45). */
    private const val WEAK_CN0 = 28f
    /** A satellite this strong is usable; a fix needs at least 4. */
    const val STRONG_CN0 = 30f

    enum class State { PENDING, RUNNING, OK, NO_FIX, SKIPPED }

    class Step(val id: String, val title: String, val what: String, val waitMs: Long) {
        @Volatile var state = State.PENDING
        @Volatile var note = ""
        /** elapsedRealtime when the wait ends — for the countdown. */
        @Volatile var endsAt = 0L
    }

    // --- Live readings (written on the main thread, read anywhere) ----------
    @Volatile var satsHeard = 0
    @Volatile var satsUsed = 0
    /** Average of the best four signals, dB-Hz (≥ 25 good, < 20 weak). */
    @Volatile var topCn0 = 0f
    /** Satellites at ≥ [STRONG_CN0] — need 4 for a fix. */
    @Volatile var strongSats = 0
    /** The four best signals, e.g. "27 · 24 · 22 · 19". */
    @Volatile var topSignals = ""
    /** Good fixes (≤ [GOOD_ACCURACY_M]) in a row. */
    @Volatile var goodStreak = 0
    /** Why root isn't available (null = works or not checked). */
    @Volatile var rootWhy: String? = null
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
    /** The doctor screen is open — fixes may restart listening only then. */
    private var wanted = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            fix = location
            fixAt = now()
            goodStreak = if (!location.hasAccuracy() || location.accuracy <= GOOD_ACCURACY_M) goodStreak + 1 else 0
            changed()
        }
        override fun onProviderEnabled(provider: String) = changed()
        override fun onProviderDisabled(provider: String) {
            fixAt = 0L // an old fix doesn't count once GPS is off
            goodStreak = 0
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
            val best = cn0s.sortedDescending().take(4)
            topCn0 = best.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f
            topSignals = best.joinToString(" · ") { it.toInt().toString() }
            strongSats = cn0s.count { it >= STRONG_CN0 }
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

    /** Start listening (main thread). Keeps the GPS chip working while the doctor screen is open. */
    fun watch(context: Context) {
        wanted = true
        listen(context)
    }

    fun unwatch(context: Context) {
        wanted = false
        stopListening(context)
    }

    @SuppressLint("MissingPermission") // checked by Locator.hasPermission()
    private fun listen(context: Context) {
        if (watching || !wanted || !Locator.hasPermission(context)) return
        val lm = lm(context)
        runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper()) }
            .onFailure { note("Can't ask the GPS provider: ${it.message}") }
        runCatching { lm.registerGnssStatusCallback(statusCallback, main) }
        runCatching { lm.addNmeaListener(nmeaListener, main) }
        watching = true
        watchingSince = now()
        if (steps.isEmpty()) steps = plan(context)
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

    fun verdict(context: Context): String {
        val lm = lm(context)
        if (!Locator.hasPermission(context)) return "Dock isn't allowed to use location."
        if (!locationOn(context)) return "Location is switched off on this device."
        if (!gpsEnabled(lm)) return "GPS is switched off (only Wi-Fi / mobile location is on)."
        if (hasFix()) return "GPS works: ±${fix!!.accuracy.toInt()} m${satellitesInUse()}."
        val t = now()
        val poor = fix?.takeIf { t - fixAt < FRESH_MS && it.hasAccuracy() }?.accuracy?.toInt()
        val chipTalks = t - lastNmeaAt < 10_000 || t - lastStatusAt < 10_000
        if (!chipTalks) {
            return if (t - watchingSince < 20_000) "Listening to the GPS chip…"
            else "No data from the GPS chip at all — its driver isn't running, or the chip is unplugged."
        }
        clockOffsetMs?.let { if (abs(it) > CLOCK_OFF_MS) return "The clock is ${duration(abs(it))} off — the GPS can't use its saved satellite data." }
        if (satsHeard == 0) return "The GPS chip runs but hears no satellites — antenna cable/connector, or something metal above it."
        if (strongSats < 4 || topCn0 < WEAK_CN0) {
            return "Signal too weak for a good fix: ${plural(strongSats, "satellite")} strong enough (need 4 at ${STRONG_CN0.toInt()}+ dB-Hz; best $topSignals)" +
                (poor?.let { ", fix only ±$it m" } ?: "") +
                ". Antenna, its cable/connector, or interference (dashcam, USB chargers) — a reset won't help."
        }
        if (poor != null) return "GPS fix is poor (±$poor m) although the signal is fine — the fixes below may help."
        return "${plural(satsHeard, "satellite")} heard well but no fix yet — usually stale satellite data (a reset helps)."
    }

    // --- Fixes --------------------------------------------------------------

    private fun plan(context: Context): List<Step> {
        val all = listOf(
            Step("permission", "Allow location", "Give Dock location access", 30_000),
            Step("location", "Switch location on", "Location + GPS on", 60_000),
            Step("listen", "Ask GPS and wait", "Keep the GPS busy and wait for a fix", 60_000),
            Step("clock", "Fix the clock", "Compare with internet time; set it if wrong", 90_000),
            Step("assist", "Fresh satellite data", "Download orbit data (XTRA) + time", 120_000),
            Step("warm", "Warm restart", "Forget position + orbits, reload them", 150_000),
            Step("toggle", "Restart GPS", "Switch GPS off and on (root)", 120_000),
            Step("cold", "Cold start", "Forget everything, search the sky from scratch", 180_000),
            Step("driver", "Restart GPS driver", "Restart the GPS driver process (root)", 180_000),
        )
        // What worked last time goes right after the quick "ask and wait".
        val worked = context.getSharedPreferences("dock", Context.MODE_PRIVATE).getString("gpsFixWorked", null)
        val remembered = all.firstOrNull { it.id == worked && it.id !in setOf("permission", "location", "listen") }
            ?: return all
        val rest = all - remembered
        val at = rest.indexOfFirst { it.id == "listen" } + 1
        return rest.take(at) + remembered + rest.drop(at)
    }

    /** Run the ladder. Call [watch] first (the doctor screen does). */
    fun fix(context: Context) {
        if (running) return
        val app = context.applicationContext
        steps = plan(app)
        cancelled = false
        running = true
        summary = "Working…"
        synchronized(log) { log.clear() }
        changed()
        worker.post { ladder(app) }
    }

    fun cancel() {
        if (running) cancelled = true
    }

    private fun ladder(context: Context) {
        rootWhy = Root.check()
        hasRoot = rootWhy == null
        note("Root: ${if (hasRoot == true) "yes" else "no ($rootWhy) — root-only fixes are skipped"}")
        clockOffsetMs = networkTime(context)?.let { System.currentTimeMillis() - it }
        var fixedBy: Step? = null
        var blocked = false
        for (step in steps) {
            if (cancelled) break
            step.state = State.RUNNING
            changed()
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
            note("${step.title}: ${step.note.ifEmpty { "done" }} — waiting up to ${duration(step.waitMs)} for a fix")
            if (waitForFix(step)) {
                step.state = State.OK
                fixedBy = step
                break
            }
            if (step.state == State.RUNNING) step.state = State.NO_FIX
            changed()
        }
        running = false
        val f = fix
        summary = when {
            fixedBy != null -> {
                context.getSharedPreferences("dock", Context.MODE_PRIVATE).edit().putString("gpsFixWorked", fixedBy.id).apply()
                "Fixed by \"${fixedBy.title}\" — ±${f?.accuracy?.toInt() ?: "?"} m${satellitesInUse()}."
            }
            cancelled -> "Stopped."
            blocked -> "Can't go on: ${verdict(context)}"
            else -> "Nothing brought the GPS back. ${verdict(context)}" + advice()
        }
        note(summary)
        steps.filter { it.state == State.PENDING }.forEach { it.state = State.SKIPPED; it.note = when {
            cancelled -> "stopped"
            fixedBy != null -> "not needed"
            else -> "not tried"
        } }
        changed()
    }

    /** Some chips never say which satellites they use — then say nothing. */
    private fun satellitesInUse() = if (satsUsed > 0) ", ${plural(satsUsed, "satellite")} in use" else ""

    private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

    private fun advice(): String = when {
        nmeaCount == 0 && lastStatusAt == 0L -> " Try a full head unit restart (button below); if it stays silent the GPS module or its driver is faulty."
        satsHeard == 0 -> " Check the GPS antenna: connector at the back of the head unit, cable not pinched, antenna not under metal."
        strongSats < 4 || topCn0 < WEAK_CN0 -> " Signal is weak: parked with open sky, unplug the dashcam and USB chargers one by one and watch the best signals here — if they jump, that device jams GPS. Otherwise check the antenna connector at the back of the head unit and put the antenna where it sees the sky (not under metal or a metallised/heated windscreen)."
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
                if (root) Root.grantAll(context) else askUser?.invoke("permission")
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
                } else askUser?.invoke("location")
                if (!waitUntil(60_000) { locationOn(context) && gpsEnabled(lm) }) return noFix(step, "location / GPS still off")
                rewatch(context)
                step.note = "switched on"
                return true
            }
            "listen" -> {
                rewatch(context)
                step.note = "asking the GPS once a second"
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
                } else {
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

    /** The self-check: a GPS fix that arrived after the step, good enough to drive on. */
    private fun waitForFix(step: Step): Boolean {
        val start = now()
        step.endsAt = start + step.waitMs
        changed()
        while (!cancelled && now() < step.endsAt) {
            if (fixAt > start && hasFix() && goodStreak >= STABLE_FIXES) {
                step.note += " → fix after ${duration(now() - start)}"
                return true
            }
            SystemClock.sleep(1000)
            changed()
        }
        step.note += if (cancelled) " → stopped" else " → no fix in ${duration(step.waitMs)} (heard $satsHeard, used $satsUsed)"
        return false
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
