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
 * fixes in [GpsDoctor] one by one until a real GPS fix comes back.
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
            GpsDoctor.cancel()
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
        left.addView(label("Each fix waits up to 1–3 min for a real GPS fix, then moves on. Keep this screen open; parked with open sky is best.", 13f, Palette.MUTED))
        left.addView(space(10))
        val manual = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        manual.addView(bigButton("Location settings", Palette.CARD_HI, 14f) {
            runCatching { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        }, LinearLayout.LayoutParams(0, dp(56), 1f))
        manual.addView(space(8))
        manual.addView(bigButton("Restart head unit", Palette.CARD_HI, 14f) { confirmReboot() }, LinearLayout.LayoutParams(0, dp(56), 1f))
        left.addView(manual)

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
        stepsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        right.addView(stepsBox)
        rendered = null
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
            "Location ${onOff(d.locationOn(this))} · GPS ${onOff(d.gpsEnabled(lm))} · Root ${when (d.hasRoot) { true -> "yes"; false -> "no"; null -> "?" }}",
            "Satellites: heard ${d.satsHeard}, used ${d.satsUsed}" + (d.systems.takeIf { it.isNotEmpty() }?.let { "  ($it)" } ?: ""),
            "Signal: $signal",
            "Chip data: " + if (d.nmeaCount > 0) "${d.nmeaCount} NMEA lines, last ${d.duration(now - d.lastNmeaAt)} ago" else "no NMEA lines yet",
            "Last GPS fix: " + if (f != null && d.fixAt > 0) "±${f.accuracy.toInt()} m, ${d.duration(now - d.fixAt)} ago" else "none",
            "Time to first fix: " + (d.ttffMs?.let { d.duration(it.toLong()) } ?: "—"),
            "Clock: " + (d.clockOffsetMs?.let { if (kotlin.math.abs(it) < 120_000) "right" else "${d.duration(kotlin.math.abs(it))} off" } ?: "checked when fixing"),
        ).joinToString("\n")

        fixButton.text = if (d.running) "Stop" else if (ok) "Fix GPS (works now)" else "Fix GPS"
        fixButton.background = rounded(if (d.running) Palette.RED else Palette.BLUE, dp(14).toFloat())
        summary.text = d.summary
        summary.visibility = if (d.summary.isEmpty()) View.GONE else View.VISIBLE
        renderSteps(d.steps, now)
        logText.text = d.logLines().takeLast(40).joinToString("\n").ifEmpty { "Tap Fix GPS to start." }
        renderSensors()
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
            if (Sensors.hasReading) lines += "Movement: %.2f g".format(Sensors.shake)
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

    private fun onOff(on: Boolean) = if (on) "on ✓" else "off ✗"
}
