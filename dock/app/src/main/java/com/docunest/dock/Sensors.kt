package com.docunest.dock

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.sqrt

/**
 * Which motion sensors the head unit has, and a spirit level for parking
 * the van. Level assumes the screen faces the driver (screen's right =
 * van's right, out of the screen = backwards); the screen's own lean is
 * taken out with [setLevel] once, parked on flat ground.
 */
object Sensors {
    data class Found(val label: String, val sensor: Sensor?)

    /** Pitch (front up = +) and roll (right side down = +), degrees, after calibration. */
    @Volatile var pitch = 0f
        private set
    @Volatile var roll = 0f
        private set
    /** Movement beyond gravity, in g (0 when standing still). */
    @Volatile var shake = 0f
        private set
    @Volatile var hasReading = false
        private set

    private var rawPitch = 0f
    private var rawRoll = 0f
    private val g = FloatArray(3)
    private var listening: SensorEventListener? = null

    fun list(context: Context): List<Found> {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        return listOf(
            "Accelerometer" to Sensor.TYPE_ACCELEROMETER,
            "Gravity" to Sensor.TYPE_GRAVITY,
            "Gyroscope" to Sensor.TYPE_GYROSCOPE,
            "Compass" to Sensor.TYPE_MAGNETIC_FIELD,
            "Rotation" to Sensor.TYPE_ROTATION_VECTOR,
            "Barometer" to Sensor.TYPE_PRESSURE,
        ).map { (label, type) -> Found(label, sm.getDefaultSensor(type)) }
    }

    fun start(context: Context) {
        if (listening != null) return
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        val sp = context.getSharedPreferences("dock", Context.MODE_PRIVATE)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                // Low-pass: the level wants gravity, not bumps.
                val k = if (hasReading) 0.1f else 1f
                for (i in 0..2) g[i] += k * (e.values[i] - g[i])
                val n = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]).takeIf { it > 1f } ?: return
                rawPitch = Math.toDegrees(asin((-g[2] / n).coerceIn(-1f, 1f).toDouble())).toFloat()
                rawRoll = Math.toDegrees(asin((-g[0] / n).coerceIn(-1f, 1f).toDouble())).toFloat()
                pitch = rawPitch - sp.getFloat("levelPitch0", 0f)
                roll = rawRoll - sp.getFloat("levelRoll0", 0f)
                val now = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                shake = abs(now - SensorManager.GRAVITY_EARTH) / SensorManager.GRAVITY_EARTH
                hasReading = true
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(l, accel, SensorManager.SENSOR_DELAY_UI)
        listening = l
    }

    fun stop(context: Context) {
        val l = listening ?: return
        (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(l)
        listening = null
        hasReading = false
    }

    /** "This is level" — parked on flat ground. */
    fun setLevel(context: Context) {
        context.getSharedPreferences("dock", Context.MODE_PRIVATE).edit()
            .putFloat("levelPitch0", rawPitch).putFloat("levelRoll0", rawRoll).putBoolean("levelSet", true).apply()
    }

    fun levelSet(context: Context) = context.getSharedPreferences("dock", Context.MODE_PRIVATE).getBoolean("levelSet", false)

    /** "Front 1.2° up · left 0.4° down" — or "Level ✓". */
    fun levelText(): String {
        if (!hasReading) return "waiting for the sensor…"
        if (abs(pitch) < 0.3f && abs(roll) < 0.3f) return "Level ✓ (within 0.3°)"
        val front = if (abs(pitch) < 0.3f) "front level" else "front %.1f° %s".format(abs(pitch), if (pitch > 0) "up" else "down")
        val side = if (abs(roll) < 0.3f) "sides level" else "%s side %.1f° down".format(if (roll > 0) "right" else "left", abs(roll))
        return "$front · $side"
    }
}
