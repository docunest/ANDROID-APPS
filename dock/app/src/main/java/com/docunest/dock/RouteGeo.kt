package com.docunest.dock

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Metres along a road, local equirectangular (same maths as MapPal lib/dock/road.ts). */
object RouteGeo {
    private const val R = 6_371_000.0
    private const val RAD = Math.PI / 180

    fun distM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val x = (lng2 - lng1) * RAD * cos((lat1 + lat2) / 2 * RAD)
        val y = (lat2 - lat1) * RAD
        return hypot(x, y) * R
    }

    /** Cumulative metres at each vertex. */
    fun cumulative(lat: DoubleArray, lng: DoubleArray): DoubleArray {
        val out = DoubleArray(lat.size)
        for (i in 1 until lat.size) out[i] = out[i - 1] + distM(lat[i - 1], lng[i - 1], lat[i], lng[i])
        return out
    }

    data class Proj(val along: Double, val off: Double, val left: Boolean)

    /** Where a point is along the line (segments [from, to)): metres from the start, metres off it, side. */
    fun project(lat: DoubleArray, lng: DoubleArray, cum: DoubleArray, pLat: Double, pLng: Double,
                from: Int = 1, to: Int = lat.size): Proj {
        var best = Proj(0.0, Double.MAX_VALUE, false)
        val c = cos(pLat * RAD)
        for (i in max(1, from) until min(to, lat.size)) {
            val bx = (lng[i] - lng[i - 1]) * RAD * c * R
            val by = (lat[i] - lat[i - 1]) * RAD * R
            val px = (pLng - lng[i - 1]) * RAD * c * R
            val py = (pLat - lat[i - 1]) * RAD * R
            val len2 = bx * bx + by * by
            val t = if (len2 > 0) ((px * bx + py * by) / len2).coerceIn(0.0, 1.0) else 0.0
            val d = hypot(px - t * bx, py - t * by)
            if (d < best.off) best = Proj(cum[i - 1] + t * (cum[i] - cum[i - 1]), d, bx * py - by * px > 0)
        }
        return best
    }

    /** The point [m] metres along the line. */
    fun pointAt(lat: DoubleArray, lng: DoubleArray, cum: DoubleArray, m: Double): Pair<Double, Double> {
        if (lat.isEmpty()) return 0.0 to 0.0
        if (m <= 0) return lat[0] to lng[0]
        for (i in 1 until lat.size) {
            if (cum[i] >= m) {
                val seg = cum[i] - cum[i - 1]
                val t = if (seg > 0) (m - cum[i - 1]) / seg else 0.0
                return lat[i - 1] + (lat[i] - lat[i - 1]) * t to lng[i - 1] + (lng[i] - lng[i - 1]) * t
            }
        }
        return lat.last() to lng.last()
    }

    /** A straight line from a to b with a point every [stepM] metres (no router: the "as the crow flies" route). */
    fun straight(aLat: Double, aLng: Double, bLat: Double, bLng: Double, stepM: Double = 1000.0): Pair<DoubleArray, DoubleArray> {
        val n = max(1, (distM(aLat, aLng, bLat, bLng) / stepM).toInt())
        return DoubleArray(n + 1) { aLat + (bLat - aLat) * it / n } to DoubleArray(n + 1) { aLng + (bLng - aLng) * it / n }
    }
}
