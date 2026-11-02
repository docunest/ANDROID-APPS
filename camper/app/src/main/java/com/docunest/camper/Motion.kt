package com.docunest.camper

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Vibration and impact recorder for the wall-mounted Z1: bumps on the road, hard hits, crash-level shocks.
 * Uses the gravity-free "linear acceleration" sensor at ~50 Hz (raw accelerometer minus a low-pass gravity
 * estimate as a fallback). Keeps per-minute RMS/peak for 24 h and the last 300 events, saved to files.
 */
class Motion(ctx: Context) : SensorEventListener {
    companion object {
        const val G = 9.81
        const val BUMP = 0.4    // pothole, speed bump
        const val HARD = 1.0    // heavy hit
        const val SEVERE = 2.5  // crash level
        private const val END = 0.15 // an event ends after 1 s below this
    }

    private val sm = ctx.getSystemService(SensorManager::class.java)
    private val linear = sm?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val sensor = linear ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val thread = HandlerThread("camper-motion").apply { start() }
    private val minutesFile = File(ctx.filesDir, "motion-minutes.json")
    private val eventsFile = File(ctx.filesDir, "motion-events.json")

    private val gravity = DoubleArray(3)
    private var sumSq = 0.0
    private var n = 0
    private var peak = 0.0
    private var minuteStart = System.currentTimeMillis()
    private var evStart = 0L
    private var evPeak = 0.0
    private var evLastHigh = 0L
    @Volatile private var lastG = 0.0

    private val minutes: ArrayDeque<JSONObject> = load(minutesFile)
    private val events: ArrayDeque<JSONObject> = load(eventsFile)

    val available get() = sensor != null

    fun start() {
        if (sensor != null) sm!!.registerListener(this, sensor, 20_000, Handler(thread.looper))
    }

    private fun load(f: File): ArrayDeque<JSONObject> = ArrayDeque<JSONObject>().also { q ->
        runCatching { JSONArray(f.readText()) }.getOrNull()?.let { a -> for (i in 0 until a.length()) q.add(a.getJSONObject(i)) }
    }

    private fun save(f: File, q: Collection<JSONObject>) = runCatching {
        val tmp = File(f.path + ".tmp"); tmp.writeText(JSONArray(q.toList()).toString()); tmp.renameTo(f)
    }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        val x: Double; val y: Double; val z: Double
        if (linear != null) { x = e.values[0].toDouble(); y = e.values[1].toDouble(); z = e.values[2].toDouble() }
        else {
            for (i in 0..2) gravity[i] = 0.9 * gravity[i] + 0.1 * e.values[i]
            x = e.values[0] - gravity[0]; y = e.values[1] - gravity[1]; z = e.values[2] - gravity[2]
        }
        val g = sqrt(x * x + y * y + z * z) / G
        lastG = g
        val now = System.currentTimeMillis()
        sumSq += g * g; n++; peak = max(peak, g)

        // events: start above BUMP, end after 1 s below END
        if (g >= BUMP) {
            if (evStart == 0L) evStart = now
            evPeak = max(evPeak, g); evLastHigh = now
        } else if (evStart != 0L && g < END && now - evLastHigh > 1000) {
            closeEvent(now)
        }

        if (now - minuteStart >= 60_000) {
            synchronized(this) {
                minutes.addLast(JSONObject().put("t", minuteStart / 1000).put("rms", round(sqrt(sumSq / max(1, n)))).put("peak", round(peak)))
                while (minutes.size > 1440) minutes.removeFirst()
                if (minutes.size % 10 == 0) save(minutesFile, minutes)
            }
            sumSq = 0.0; n = 0; peak = 0.0; minuteStart = now
        }
    }

    private fun closeEvent(now: Long) {
        val level = when { evPeak >= SEVERE -> "severe"; evPeak >= HARD -> "hard"; else -> "bump" }
        synchronized(this) {
            events.addLast(JSONObject().put("t", evStart / 1000).put("peak", round(evPeak))
                .put("ms", evLastHigh - evStart).put("level", level))
            while (events.size > 300) events.removeFirst()
            save(eventsFile, events)
        }
        evStart = 0L; evPeak = 0.0
    }

    private fun round(v: Double) = Math.round(v * 100) / 100.0

    /** What the screens show: state, recent levels, 24 h counts, last events. */
    fun summary(): JSONObject = synchronized(this) {
        val now = System.currentTimeMillis() / 1000
        val day = events.filter { now - it.getLong("t") < 86_400 }
        val recent = minutes.filter { now - it.getLong("t") < 600 }
        val rms10 = recent.maxOfOrNull { it.getDouble("rms") } ?: 0.0
        val peak10 = recent.maxOfOrNull { it.getDouble("peak") } ?: 0.0
        val state = when {
            !available -> "no sensor"
            day.any { it.getString("level") == "severe" && now - it.getLong("t") < 3600 } -> "impact"
            peak10 >= BUMP -> "shaken"
            rms10 >= 0.03 -> "vibrating"
            else -> "steady"
        }
        JSONObject()
            .put("state", state)
            .put("now_g", round(lastG))
            .put("rms_10m", round(rms10)).put("peak_10m", round(peak10))
            .put("counts_24h", JSONObject().put("bump", day.count { it.getString("level") == "bump" })
                .put("hard", day.count { it.getString("level") == "hard" }).put("severe", day.count { it.getString("level") == "severe" }))
            .put("events", JSONArray(events.toList().takeLast(20).reversed()))
            .put("minutes", JSONArray(minutes.toList().takeLast(60)))
            .put("sensor", if (linear != null) "linear" else if (sensor != null) "accelerometer" else "none")
    }

    /** Most recent severe event (epoch s), or 0. */
    fun lastSevere(): Long = synchronized(this) { events.lastOrNull { it.getString("level") == "severe" }?.getLong("t") ?: 0 }
}
