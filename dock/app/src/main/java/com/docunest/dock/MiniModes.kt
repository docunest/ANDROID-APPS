package com.docunest.dock

import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Mini-map modes (owner 2026-11-17, after driving build 72):
 *  - CITY: every place around, close-up, no diesel countdown ("there is no shortage of them").
 *  - AUTOBAHN: places along the road only, far out, "⛽ 23 km" countdown in whole km.
 *  - EXPLORE: close-up browsing, every place, no route line.
 * Auto picks CITY / AUTOBAHN from the speed averaged over minutes (a town on the way doesn't flip it).
 * Waze's road type isn't used yet: its numbers aren't mapped (no motorway seen in the log so far).
 */
object MiniModes {
    enum class Mode(val icon: String, val title: String) {
        CITY("🏙", "City"), AUTOBAHN("🛣", "Autobahn"), EXPLORE("🧭", "Explore")
    }
    const val AUTO = "auto"

    /** The chosen mode: [AUTO] or a [Mode] name. */
    fun parse(s: String): Mode? = Mode.entries.firstOrNull { it.name == s }

    /** Auto: AUTOBAHN above ~85 km/h averaged, back to CITY below ~70 (hysteresis). */
    fun detect(avgKmh: Double, prev: Mode): Mode =
        if (prev == Mode.AUTOBAHN) { if (avgKmh > 70) Mode.AUTOBAHN else Mode.CITY }
        else { if (avgKmh > 85) Mode.AUTOBAHN else Mode.CITY }

    /** Metres across the circle in each mode (when the dial isn't set by hand). */
    fun spanM(mode: Mode): Double = when (mode) {
        Mode.CITY -> 2_000.0
        Mode.AUTOBAHN -> 15_000.0
        Mode.EXPLORE -> 1_000.0
    }

    /** "⛽ 23 km": whole km (the kids watch it count down); 100 m steps in the last 2 km. */
    fun dieselText(m: Double): String =
        if (m < 2_000) "⛽ ${((m / 100).roundToInt() * 100).coerceAtLeast(100)} m"
        else "⛽ ${(m / 1000).roundToInt()} km"

    /**
     * Is the van on the dock's route? The dock route is OSRM's, not Waze's, so it only draws while
     * we actually drive it: shown after 8 s within 120 m, hidden after 10 s beyond 250 m.
     */
    class OnCourse {
        var on = false
            private set
        private var since = -1L
        fun feed(offM: Double?, nowMs: Long): Boolean {
            val wantOn = offM != null && offM < 120
            val wantOff = offM == null || offM > 250
            val flip = (!on && wantOn) || (on && wantOff)
            if (!flip) { since = -1L; return on }
            if (offM == null) { on = false; since = -1L; return on }
            if (since < 0) since = nowMs
            if (nowMs - since >= if (on) 10_000 else 8_000) { on = !on; since = -1L }
            return on
        }
    }

    /**
     * Where the van is now, between GPS fixes (they come once a second; drawing only on fixes made
     * the circle jump). Straight on along the last course at the last speed, at most 2.5 s ahead.
     */
    fun predict(lat: Double, lng: Double, speedMs: Float, bearingDeg: Float, ageS: Double): Pair<Double, Double> {
        if (speedMs < 1f) return lat to lng
        val d = speedMs * ageS.coerceIn(0.0, 2.5)
        val b = Math.toRadians(bearingDeg.toDouble())
        return (lat + d * cos(b) / 111_320.0) to (lng + d * sin(b) / (111_320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.2)))
    }

    /** Shortest turn from [from] to [to], degrees in −180..180. */
    fun turn(from: Float, to: Float): Float = ((to - from + 540f) % 360f) - 180f
}
