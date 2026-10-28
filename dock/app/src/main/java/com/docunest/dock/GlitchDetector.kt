package com.docunest.dock

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The maths behind [GpsWatch], kept free of Android classes so it can be
 * unit-tested. Feed GPS fixes in order; [feed] returns a sentence when a
 * fix is physically implausible compared with the ones before it.
 */
class GlitchDetector {
    /** [t] seconds (monotonic), [speed] m/s or null, [acc] metres or null. */
    data class Fix(val t: Double, val lat: Double, val lng: Double, val speed: Float?, val acc: Float?)

    private var prev: Fix? = null
    /** Recent fixes (~6 s) for the speed-vs-movement check — one second alone is too noisy. */
    private val recent = ArrayDeque<Fix>()

    fun feed(cur: Fix): String? {
        val p = prev
        prev = cur
        recent.addLast(cur)
        while (recent.size > 1 && cur.t - recent.first().t > 6.5) recent.removeFirst()
        if (p == null) return null
        val dt = (cur.t - p.t).toFloat()
        if (dt < 0.5f || dt > 5f) {
            recent.clear()
            recent.addLast(cur)
            return null
        }
        val moved = meters(p, cur)
        val derived = moved / dt
        if (cur.speed != null && p.speed != null) {
            val a = (cur.speed - p.speed) / dt
            if (a > MAX_ACCEL) return "Speed jumped %s → %s km/h in %.1f s (impossible for the van)".format(kmh(p.speed), kmh(cur.speed), dt)
            if (-a > MAX_BRAKE) return "Speed dropped %s → %s km/h in %.1f s (harder than any braking)".format(kmh(p.speed), kmh(cur.speed), dt)
        }
        if (derived > 70f && (cur.speed == null || derived > cur.speed + 15f)) {
            return "Position jumped ${moved.toInt()} m in %.1f s (%s km/h)".format(dt, kmh(derived))
        }
        return speedVsMovement(cur)
    }

    /** Average reported speed over the last ~4–6 s vs straight-line distance covered in that time. */
    private fun speedVsMovement(cur: Fix): String? {
        val from = recent.firstOrNull { cur.t - it.t >= 4.0 } ?: return null
        val window = recent.filter { it.t >= from.t }
        if (window.any { it.speed == null || (it.acc != null && it.acc > 25f) }) return null
        val reported = window.map { it.speed!! }.average().toFloat()
        val span = (cur.t - from.t).toFloat()
        val derived = meters(from, cur) / span
        val diff = abs(reported - derived)
        if (max(reported, derived) < 8f || diff < 5f || diff < 0.2f * max(reported, derived)) return null
        recent.clear() // don't report the same stretch again on the next fix
        recent.addLast(cur)
        return "Reported ~${kmh(reported)} km/h but moved at ${kmh(derived)} km/h (over %.0f s)".format(span)
    }

    companion object {
        /** m/s² — a car with caravan does 0–100 km/h in 20 s+ (1.4 m/s²). */
        const val MAX_ACCEL = 3.5f
        /** m/s² — beyond ~0.9 g braking. */
        const val MAX_BRAKE = 9f

        fun kmh(mps: Float) = (mps * 3.6f).toInt().toString()

        fun meters(a: Fix, b: Fix): Float {
            val r = 6_371_000.0
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLng = Math.toRadians(b.lng - a.lng)
            val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2) * sin(dLng / 2)
            return (2 * r * asin(sqrt(h))).toFloat()
        }
    }
}
