package com.docunest.dock

import android.content.Context
import android.location.Location
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos

/**
 * Average speed sections (owner 2026-11-25: "a special mode when I'm in an average speed zone,
 * because Waze does not always show them … as long as I'm in the zone, show it").
 *
 * The sections come from the offline packs (OSM `enforcement=average_speed`: start, end, limit).
 * In: within 120 m of a start, moving (> 15 km/h) towards its end (±50°). Then the distance driven
 * is summed fix by fix and divided by the time since the start = your average, as the cameras see
 * it. Out: within 50 m of the end, or clearly away from it (400 m further than closest), or far too
 * long. The road is a bit longer than the straight line start → end: length ≈ straight × 1.05.
 */
object AvgZone {
    data class Zone(val osm: String, val lat1: Double, val lng1: Double, val lat2: Double, val lng2: Double, val maxKmh: Int?, val name: String?) {
        val lengthM get() = RouteGeo.distM(lat1, lng1, lat2, lng2) * 1.05
    }

    /** What the panel shows. [maxRestKmh] = the most you can drive for the rest and still average the limit (null = no limit known). */
    data class State(val limit: Int?, val avgKmh: Double?, val nowKmh: Double, val leftM: Double, val maxRestKmh: Double?, val name: String?) {
        /** 0 = fine, 1 = close to the limit, 2 = over. */
        fun level(): Int {
            val l = limit ?: return 0
            val a = avgKmh ?: return 0
            return when { a > l + 2 -> 2; a > l - 3 -> 1; else -> 0 }
        }
        fun line1() = "⏱ AVERAGE SPEED" + (limit?.let { " $it" } ?: "") + " · you " + (avgKmh?.let { "${it.toInt()}" } ?: "…") + " · now ${nowKmh.toInt()}"
        fun line2() = km(leftM.toFloat()) + " left" + when {
            limit == null -> " · limit unknown"
            maxRestKmh == null -> ""
            maxRestKmh <= 0 -> " · slow down: already over the average"
            else -> " · max ${maxRestKmh.toInt().coerceAtMost(199)} km/h for the rest"
        } + (name?.let { " · $it" } ?: "")
    }

    /** Pure tracker, unit-tested (times in ms). */
    class Tracker(private val zones: List<Zone>) {
        var zone: Zone? = null; private set
        private var enteredAt = 0L
        private var travelled = 0.0
        private var lastLat = 0.0; private var lastLng = 0.0
        private var minToEnd = Double.MAX_VALUE

        fun feed(lat: Double, lng: Double, t: Long, speedMs: Float, bearing: Float?): State? {
            val z = zone
            if (z == null) {
                if (speedMs * 3.6f < 15f || bearing == null) return null
                val hit = zones.firstOrNull { c ->
                    RouteGeo.distM(lat, lng, c.lat1, c.lng1) < 120 &&
                        abs(((bear(c.lat1, c.lng1, c.lat2, c.lng2) - bearing + 540f) % 360f) - 180f) < 50f
                } ?: return null
                zone = hit; enteredAt = t; travelled = 0.0; lastLat = lat; lastLng = lng; minToEnd = Double.MAX_VALUE
                DebugLog.log("ZONE", "entered average speed section ${hit.osm} ${hit.name ?: ""} limit ${hit.maxKmh ?: "?"}, ${km(hit.lengthM.toFloat())}")
                return state(hit, t, speedMs, RouteGeo.distM(lat, lng, hit.lat2, hit.lng2))
            }
            val step = RouteGeo.distM(lastLat, lastLng, lat, lng)
            if (step < 500) travelled += step // a GPS jump isn't driving
            lastLat = lat; lastLng = lng
            val toEnd = RouteGeo.distM(lat, lng, z.lat2, z.lng2)
            minToEnd = minOf(minToEnd, toEnd)
            val tooLong = t - enteredAt > maxOf(15 * 60_000L, (z.lengthM / (20 / 3.6) * 1000).toLong())
            if (toEnd < 50 || toEnd > minToEnd + 400 || tooLong) {
                val avg = if (t > enteredAt) travelled / ((t - enteredAt) / 1000.0) * 3.6 else 0.0
                DebugLog.log("ZONE", "left ${z.osm}: average ${avg.toInt()} km/h (limit ${z.maxKmh ?: "?"}) over ${km(travelled.toFloat())}" +
                    if (toEnd < 50) "" else " (left the section before its end)")
                zone = null
                return null
            }
            return state(z, t, speedMs, toEnd)
        }

        private fun state(z: Zone, t: Long, speedMs: Float, toEnd: Double): State {
            val secs = (t - enteredAt) / 1000.0
            val avg = if (secs >= 5 && travelled > 50) travelled / secs * 3.6 else null
            val left = maxOf(toEnd, z.lengthM - travelled).coerceAtLeast(0.0)
            val maxRest = z.maxKmh?.let { lim ->
                val budget = (travelled + left) / (lim / 3.6) - secs
                if (budget <= 0) 0.0 else left / budget * 3.6
            }
            return State(z.maxKmh, avg, speedMs * 3.6, left, maxRest, z.name)
        }
    }

    private var tracker: Tracker? = null
    private var loadedAt = 0L

    /** Every second from the overlay. */
    fun update(ctx: Context, here: Location?): State? {
        val now = System.currentTimeMillis()
        if (tracker == null || (tracker?.zone == null && now - loadedAt > 30 * 60_000L)) {
            loadedAt = now
            tracker = Tracker(runCatching { OfflinePois.zones(ctx) }.getOrDefault(emptyList()))
        }
        here ?: return null
        val speed = if (here.hasSpeed()) here.speed else 0f
        return tracker!!.feed(here.latitude, here.longitude, now, speed, if (here.hasBearing() && speed > 2f) here.bearing else null)
    }

    fun bear(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val x = (lng2 - lng1) * cos(Math.toRadians((lat1 + lat2) / 2))
        return ((Math.toDegrees(atan2(x, lat2 - lat1)) + 360) % 360).toFloat()
    }
}
