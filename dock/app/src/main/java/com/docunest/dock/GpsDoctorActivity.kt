package com.docunest.dock

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * GPS doctor (from the 📡 chip on the dock map): what the GPS chip is doing
 * right now, what's wrong in one sentence, and one button that tries the
 * fixes in [GpsDoctor] one by one until a real GPS fix comes back. Also the
 * auto doctor switch ([AutoDoctor]) and what each fix has achieved so far
 * ([FixStats]).
 */
class GpsDoctorActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var verdict: TextView
    private lateinit var live: TextView
    private lateinit var summary: TextView
    private lateinit var fixButton: TextView
    private lateinit var stepsBox: LinearLayout
    private lateinit var logText: TextView
    private lateinit var sensorsText: TextView
    private lateinit var glitchText: TextView
    private lateinit var autoButton: TextView
    private lateinit var autoText: TextView
    private lateinit var parkedText: TextView
    private lateinit var parkedButton: TextView
    private lateinit var statsText: TextView
    private lateinit var runsText: TextView
    private var ticks = 0
    private var wasRunning = false
    private var rendered: List<GpsDoctor.Step>? = null
    private val rows = mutableListOf<Pair<TextView, TextView>>()
    private val doctorListener: () -> Unit = { render() }
    private val ticker = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Fixes take minutes; the screen staying on keeps the GPS request alive.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        GpsDoctor.askUser = { what -> runOnUiThread { askUser(what) } }
        DebugLog.init(this)
        AutoDoctor.start(this)
        build()
        if (!Locator.hasPermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        }
        GpsDoctor.watch(this)
    }

    override fun onResume() {
        super.onResume()
        GpsDoctor.addListener(doctorListener)
        GpsDoctor.watch(this)
        Sensors.start(this)
        main.post(ticker)
    }

    override fun onPause() {
        GpsDoctor.removeListener(doctorListener)
        main.removeCallbacks(ticker)
        Sensors.stop(this)
        super.onPause()
    }

    override fun onDestroy() {
        if (isFinishing) {
            // A manual run needs this screen; an auto run carries on in the background.
            if (!GpsDoctor.auto) GpsDoctor.cancel()
            GpsDoctor.unwatch(this)
            GpsDoctor.askUser = null
        }
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        build()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        GpsDoctor.watch(this)
    }

    private fun askUser(what: String) {
        when (what) {
            "permission" -> requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
            "location" -> openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS, "Switch on Location / GPS, then come back")
            "date" -> openSettings(Settings.ACTION_DATE_SETTINGS, "Switch on automatic date & time, then come back")
        }
    }

    private fun openSettings(action: String, hint: String) {
        Toast.makeText(this, hint, Toast.LENGTH_LONG).show()
        runCatching { startActivity(Intent(action)) }
    }

    // --- Layout -------------------------------------------------------------

    private fun build() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(label("📡  GPS doctor", 22f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(bigButton("✕", Palette.CARD, 20f) { finish() }, LinearLayout.LayoutParams(dp(64), dp(64)))
        left.addView(head)

        verdict = label("", 19f, bold = true).apply { setPadding(0, dp(8), 0, dp(8)) }
        left.addView(verdict)
        live = label("", 15f).apply {
            background = rounded(Palette.CARD, dp(14).toFloat())
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        left.addView(live)
        left.addView(space(10))
        fixButton = bigButton("Fix GPS", Palette.BLUE, 20f) {
            if (GpsDoctor.running) GpsDoctor.cancel() else GpsDoctor.fix(this)
            render()
        }
        left.addView(fixButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
        summary = label("", 15f, Palette.AMBER).apply { setPadding(0, dp(8), 0, dp(4)) }
        left.addView(summary)
        left.addView(label("Each fix waits up to 1–3 min for a real GPS fix, then moves on. Parked with open sky is best. Every try is measured (statistics on the right).", 13f, Palette.MUTED))
        left.addView(space(10))
        autoButton = bigButton("", Palette.CARD_HI, 16f) {
            AutoDoctor.setEnabled(this, !AutoDoctor.enabled(this))
            render()
        }
        left.addView(autoButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        autoText = label("", 13f, Palette.MUTED).apply { setPadding(0, dp(4), 0, 0) }
        left.addView(autoText)
        left.addView(space(10))
        left.addView(label("Parked position", 16f, bold = true))
        parkedText = label("", 13f, Palette.MUTED)
        left.addView(parkedText)
        left.addView(space(4))
        parkedButton = bigButton("", Palette.CARD_HI, 15f) {
            if (ParkedPosition.mocking) ParkedPosition.stop("Handed back to the real GPS (asked for)")
            else ParkedPosition.useNow(this)
            render()
        }
        left.addView(parkedButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        left.addView(space(10))
        val manual = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        manual.addView(bigButton("Location settings", Palette.CARD_HI, 14f) {
            runCatching { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        }, LinearLayout.LayoutParams(0, dp(56), 1f))
        manual.addView(space(8))
        manual.addView(bigButton("Restart head unit", Palette.CARD_HI, 14f) { confirmReboot() }, LinearLayout.LayoutParams(0, dp(56), 1f))
        left.addView(manual)
        left.addView(space(8))
        val more = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        more.addView(bigButton("📊 Fix report", Palette.CARD_HI, 14f) { startActivity(Intent(this, FixReportActivity::class.java)) }, LinearLayout.LayoutParams(0, dp(56), 1f))
        more.addView(space(8))
        more.addView(bigButton("🐞 Debug log", Palette.CARD_HI, 14f) {
            startActivity(Intent(this, DebugActivity::class.java).putExtra(DebugActivity.EXTRA_TAG, "GPS"))
        }, LinearLayout.LayoutParams(0, dp(56), 1f))
        left.addView(more)

        left.addView(space(14))
        left.addView(label("Sensors", 18f, bold = true))
        sensorsText = label("", 14f).apply {
            background = rounded(Palette.CARD, dp(14).toFloat())
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        left.addView(sensorsText)
        left.addView(space(6))
        left.addView(bigButton("Set as level (parked on flat ground)", Palette.CARD_HI, 14f) {
            Sensors.setLevel(this)
            Toast.makeText(this, "Saved — this is level now", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        right.addView(label("Fixes, in order", 18f, bold = true))
        right.addView(label("Fixes with fewer than ${FixStats.MIN_TRIES} tries go first so every one gets measured; then the fastest expected.", 12f, Palette.MUTED))
        stepsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        right.addView(stepsBox)
        rendered = null
        right.addView(space(14))
        val shead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        shead.addView(label("What works (statistics)", 16f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        shead.addView(bigButton("Reset", Palette.CARD, 13f) { confirmReset() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        right.addView(shead)
        statsText = label("", 13f).apply {
            background = rounded(Palette.CARD, dp(14).toFloat())
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setLineSpacing(dp(2).toFloat(), 1f)
            setTextIsSelectable(true)
        }
        right.addView(statsText)
        right.addView(space(8))
        right.addView(label("Recent runs", 15f, bold = true))
        runsText = label("", 12f, Palette.MUTED).apply { setTextIsSelectable(true) }
        right.addView(runsText)
        right.addView(space(10))
        right.addView(label("Log", 16f, bold = true))
        logText = label("", 12f, Palette.MUTED).apply { setTextIsSelectable(true) }
        right.addView(logText)
        right.addView(space(14))
        val ghead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        ghead.addView(label("Speed / position glitches", 16f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        ghead.addView(bigButton("Clear", Palette.CARD, 13f) { GpsWatch.clear(this); render() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        right.addView(ghead)
        glitchText = label("", 12f, Palette.MUTED).apply { setTextIsSelectable(true) }
        right.addView(glitchText)

        val root = LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        if (landscape) {
            root.addView(scroll(left), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            root.addView(space(14))
            root.addView(scroll(right), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f))
            setContentView(root)
        } else {
            root.addView(left)
            root.addView(space(14))
            root.addView(right)
            setContentView(scroll(root).apply { setBackgroundColor(Palette.BG) })
        }
        render()
    }

    private fun scroll(v: View) = ScrollView(this).apply { addView(v) }
    private fun space(d: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(d), dp(d)) }

    private fun confirmReset() {
        val zones = FixHistory.deadZones(this).size
        AlertDialog.Builder(this)
            .setTitle("Reset GPS statistics?")
            .setMessage("Forgets every measured fix try and run. The learning starts again from zero." +
                if (zones > 0) "\n\nKnown no-GPS spots (garages, car parks): $zones — kept unless you forget them too." else "")
            .setPositiveButton("Reset statistics") { _, _ -> FixHistory.clear(this); ticks = 0; render() }
            .apply { if (zones > 0) setNeutralButton("Forget no-GPS spots") { _, _ -> FixHistory.clearDeadZones(this@GpsDoctorActivity) } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmReboot() {
        AlertDialog.Builder(this)
            .setTitle("Restart the head unit?")
            .setMessage("The screen goes black for about a minute. Uses root.")
            .setPositiveButton("Restart") { _, _ ->
                Thread {
                    if (!Root.run("svc power reboot || reboot")) {
                        runOnUiThread { Toast.makeText(this, "Root refused — hold the power button instead", Toast.LENGTH_LONG).show() }
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Content ------------------------------------------------------------

    private fun render() {
        if (!::verdict.isInitialized) return
        val d = GpsDoctor
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val ok = d.hasFix()
        verdict.text = d.verdict(this)
        verdict.setTextColor(if (ok) Palette.GMAPS else Palette.AMBER)

        val now = SystemClock.elapsedRealtime()
        val f = d.fix
        val signal = when {
            d.satsHeard == 0 -> "none"
            d.topCn0 >= 25 -> "%.0f dB-Hz · good".format(d.topCn0)
            d.topCn0 >= 20 -> "%.0f dB-Hz · fair".format(d.topCn0)
            else -> "%.0f dB-Hz · weak".format(d.topCn0)
        }
        live.text = listOf(
            "Location ${onOff(d.locationOn(this))} · GPS ${onOff(d.gpsEnabled(lm))} · Root ${Root.via()}",
            "Satellites: heard ${d.satsHeard}, strong ${d.strongSats}, used ${d.satsUsed}" + (d.systems.takeIf { it.isNotEmpty() }?.let { "  ($it)" } ?: ""),
            "Signal: $signal",
            "Per system: " + d.perSystem.ifEmpty { "—" },
            "Raw measurements: ${d.rawMeasurements}",
            "Chip data: " + if (d.nmeaCount > 0) "${d.nmeaCount} NMEA lines, last ${d.duration(now - d.lastNmeaAt)} ago" else "no NMEA lines yet",
            "Last GPS fix: " + if (f != null && d.fixAt > 0) "±${f.accuracy.toInt()} m, ${d.duration(now - d.fixAt)} ago" else "none",
            "Time to first fix: " + (d.ttffMs?.let { d.duration(it.toLong()) } ?: "—"),
            "Clock: " + (d.clockOffsetMs?.let { if (kotlin.math.abs(it) < 120_000) "right" else "${d.duration(kotlin.math.abs(it))} off" } ?: "checked when fixing"),
        ).joinToString("\n")

        fixButton.text = if (d.running) (if (d.auto) "Stop auto fix" else "Stop") else if (ok) "Fix GPS (works now)" else "Fix GPS"
        val autoOn = AutoDoctor.enabled(this)
        autoButton.text = if (autoOn) "Auto doctor: ON" else "Auto doctor: off"
        autoButton.background = rounded(if (autoOn) Palette.GMAPS else Palette.CARD_HI, dp(14).toFloat())
        val saved = ParkedPosition.saved(this)
        parkedText.text = listOfNotNull(
            "After a start with no sky (garage), Waze gets the last saved position until the van moves, so you can set a route. " +
                if (ParkedPosition.enabled(this)) "On." else "Off (⚙ → GPS).",
            saved?.let { "Saved: %.5f, %.5f ±%d m, %s".format(java.util.Locale.US, it.lat, it.lng, it.acc.toInt(), ago(it.at)) } ?: "Saved: nothing yet (saved while the GPS works).",
            ParkedPosition.logic?.let { "Now: ${it.why}" },
            ParkedPosition.problem.takeIf { it.isNotEmpty() },
        ).joinToString("\n")
        parkedText.setTextColor(if (ParkedPosition.problem.isNotEmpty()) Palette.AMBER else Palette.MUTED)
        parkedButton.text = if (ParkedPosition.mocking) "Hand back to real GPS" else "Use parked position now"
        parkedButton.background = rounded(if (ParkedPosition.mocking) Palette.WAZE else Palette.CARD_HI, dp(14).toFloat())
        autoText.text = AutoDoctor.status() + (AutoDoctor.call?.takeIf { !autoOn && it.mode != SkyJudge.Mode.OK }?.let { "\nNow: ${it.why}" } ?: "")
        fixButton.background = rounded(if (d.running) Palette.RED else Palette.BLUE, dp(14).toFloat())
        summary.text = d.summary
        summary.visibility = if (d.summary.isEmpty()) View.GONE else View.VISIBLE
        renderSteps(d.steps, now)
        logText.text = d.logLines().takeLast(40).joinToString("\n").ifEmpty { "Tap Fix GPS to start." }
        renderSensors()
        // Statistics read ~1500 stored lines: every 5 s is plenty (and at once after a run).
        if (ticks++ % 5 == 0 || (wasRunning && !d.running)) renderStats()
        wasRunning = d.running
        val glitches = GpsWatch.lines(this)
        glitchText.text = "Recorded in the background whenever Waze or any app uses GPS: speed jumps a towing van can't do, " +
            "reported speed that disagrees with the distance covered, and position leaps. " +
            "${GpsWatch.fixesSeen} fixes checked since the dock started.\n\n" +
            glitches.take(15).joinToString("\n").ifEmpty { "None so far." }
    }

    private fun renderSensors() {
        val found = Sensors.list(this)
        val lines = mutableListOf(found.joinToString(" · ") { "${it.label} ${if (it.sensor != null) "✓" else "✗"}" })
        val accel = found.first().sensor
        if (accel == null) {
            lines += "No accelerometer in this unit — no level, no movement check."
        } else {
            lines += "Accelerometer: ${accel.vendor} ${accel.name}".trim()
            lines += "Level: " + Sensors.levelText() + if (Sensors.levelSet(this)) "" else "  (not calibrated yet — tap Set as level once, parked flat)"
            if (Sensors.hasReading) lines += "Movement: %.2f g (avg %.2f) · driving: %s".format(Sensors.shake, Sensors.shakeAvg, if (Sensors.driving() == true) "yes" else "no")
        }
        sensorsText.text = lines.joinToString("\n")
    }

    private fun renderSteps(steps: List<GpsDoctor.Step>, now: Long) {
        if (rendered !== steps) {
            rendered = steps
            stepsBox.removeAllViews()
            rows.clear()
            for ((i, s) in steps.withIndex()) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = rounded(Palette.CARD, dp(12).toFloat())
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, dp(3), 0, dp(3))
                    }
                }
                val title = label("${i + 1}. ${s.title}", 16f, bold = true)
                val note = label("", 13f, Palette.MUTED)
                row.addView(title)
                row.addView(note)
                stepsBox.addView(row)
                rows += title to note
            }
        }
        for ((i, s) in steps.withIndex()) {
            val (title, note) = rows.getOrNull(i) ?: continue
            val (icon, color) = when (s.state) {
                GpsDoctor.State.PENDING -> "○" to Palette.TEXT
                GpsDoctor.State.RUNNING -> "⏳" to Palette.AMBER
                GpsDoctor.State.OK -> "✓" to Palette.GMAPS
                GpsDoctor.State.NO_FIX -> "✗" to Palette.RED
                GpsDoctor.State.SKIPPED -> "–" to Palette.MUTED
                GpsDoctor.State.VOID -> "⊘" to Palette.MUTED
            }
            title.text = "$icon  ${i + 1}. ${s.title}"
            title.setTextColor(color)
            val left = s.endsAt - now
            note.text = when {
                s.state == GpsDoctor.State.RUNNING && left > 0 -> "${s.note.ifEmpty { s.what }} — ${GpsDoctor.duration(left)} left"
                s.note.isNotEmpty() -> s.note
                else -> s.what + if (s.waitMs > 0) " · waits up to ${GpsDoctor.duration(s.waitMs)}" else ""
            }
        }
    }

    private fun renderStats() {
        val attempts = FixHistory.attempts(this)
        val runs = FixHistory.runs(this)
        val stats = FixStats.summarize(attempts)
        val min = FixStats.MIN_TRIES
        val ids = GpsDoctor.remedyIds
        val counted = attempts.count { it.outcome != FixStats.Outcome.VOID }
        val out = mutableListOf<String>()
        if (counted == 0) {
            out += "Nothing measured yet. Every Fix GPS run — yours or the auto doctor's — adds to this. " +
                "Each fix needs $min tries before its numbers count; until then it's tried first."
        } else {
            val fixedRuns = runs.filter { it.result == "fixed" }
            val ready = ids.count { (stats[it]?.tries ?: 0) >= min }
            out += "${runs.size} runs (${runs.count { it.auto }} auto) · GPS back in ${pct(fixedRuns.size, runs.count { it.result != "stopped" && it.result != "paused" })}" +
                (FixStats.percentile(fixedRuns.map { it.secs }, 0.5)?.let { " · typically after ${GpsDoctor.duration(it * 1000L)}" } ?: "")
            out += "Measured enough: $ready of ${ids.size} fixes ($min+ tries each)"
            val base = stats["listen"]?.takeIf { it.tries > 0 }
            val sorted = ids.sortedWith(compareBy<String>({ (stats[it]?.tries ?: 0) < min }, { -(stats[it]?.rate ?: -1.0) }))
            for (id in sorted) {
                val st = stats[id]
                out += ""
                if (st == null || st.tries == 0) {
                    out += "○ ${GpsDoctor.title(id)} — not tried yet" + (st?.voids?.takeIf { it > 0 }?.let { " ($it spoiled)" } ?: "")
                    continue
                }
                val (lo, hi) = st.interval()
                val mark = if (st.learning) "○" else if (st.rate >= 0.5) "✓" else "·"
                out += "$mark ${GpsDoctor.title(id)}: ${pct(st.wins, st.tries)} (${st.wins} of ${st.tries})" +
                    (st.medianFixSecs?.let { " · fix in ~${GpsDoctor.duration(it * 1000L)}" } ?: "")
                val bits = mutableListOf("95 % range ${(lo * 100).toInt()}–${(hi * 100).toInt()} %")
                if (st.firstTries > 0) bits += "as 1st fix ${st.firstWins}/${st.firstTries}"
                if (base != null && id != "listen" && !st.learning && !base.learning) {
                    val lift = ((st.rate - base.rate) * 100).toInt()
                    bits += if (lift >= 0) "+$lift pts vs just waiting" else "$lift pts vs just waiting"
                }
                if (st.voids > 0) bits += "${st.voids} spoiled"
                if (st.learning) bits += "learning: ${min - st.tries} more"
                out += "    " + bits.joinToString(" · ")
            }
            // Per kind of trouble: the best fix so far, once it has a few tries there.
            val bySymptom = attempts.filter { it.outcome != FixStats.Outcome.VOID }.groupBy { it.symptom }
            val best = bySymptom.mapNotNull { (sym, list) ->
                val top = FixStats.summarize(list).values.filter { it.tries >= 3 }.maxByOrNull { it.rate } ?: return@mapNotNull null
                "${GpsDoctor.symptomLabel(sym)} → ${GpsDoctor.title(top.step)} ${top.wins}/${top.tries}"
            }
            if (best.isNotEmpty()) {
                out += ""
                out += "Best by trouble: " + best.joinToString(" · ")
            }
        }
        statsText.text = out.joinToString("\n")

        val fmt = java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.US)
        runsText.text = runs.takeLast(10).reversed().joinToString("\n") { r ->
            val what = when (r.result) {
                "fixed" -> "fixed by ${GpsDoctor.title(r.fixedBy ?: "?")}"
                "paused" -> "paused (sky blocked)"
                "blocked" -> "needs you (location/permission)"
                "stopped" -> "stopped"
                else -> "nothing helped"
            }
            "${fmt.format(java.util.Date(r.id))}  ${if (r.auto) "auto" else "manual"} · ${GpsDoctor.symptomLabel(r.symptom)} · $what · ${GpsDoctor.duration(r.secs * 1000L)}, ${r.tried} tried"
        }.ifEmpty { "None yet." }
    }

    private fun pct(k: Int, n: Int) = if (n == 0) "–" else "${(100.0 * k / n).toInt()} %"

    private fun onOff(on: Boolean) = if (on) "on ✓" else "off ✗"
}
