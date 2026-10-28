package com.docunest.dock

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Background recorder for bad GPS data (e.g. 110 km/h while the car says
 * 80). Listens passively — it only sees fixes some app (Waze, the dock)
 * already asked for, so it costs nothing — and checks every fix against
 * the one before:
 *  - impossible jump: speed rising faster than a towing van can
 *    accelerate, or falling faster than the hardest braking;
 *  - speed vs movement: the chip's reported speed disagrees with the
 *    distance actually covered between the two fixes;
 *  - position jump: the position leaps further than any car could go.
 * Glitches go to a list (last [KEEP]) shown on the GPS doctor screen.
 */
object GpsWatch {
    private const val KEEP = 40
    private const val MERGE_MS = 15_000L

    @Volatile var fixesSeen = 0L
        private set
    @Volatile var since = 0L
        private set
    private val detector = GlitchDetector()
    private var satsUsed = 0
    private var started = false
    private val main = Handler(Looper.getMainLooper())

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = check(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Called on Android 10 and older only")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val status = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(s: GnssStatus) {
            satsUsed = (0 until s.satelliteCount).count { s.usedInFix(it) }
        }
    }

    private lateinit var app: Context

    /** From the dock service (idempotent, main thread). */
    @SuppressLint("MissingPermission") // checked by Locator.hasPermission()
    fun start(context: Context) {
        if (started || !Locator.hasPermission(context)) return
        app = context.applicationContext
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val ok = runCatching {
            lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 0L, 0f, listener, Looper.getMainLooper())
        }.isSuccess
        if (!ok) return
        runCatching { lm.registerGnssStatusCallback(status, main) }
        started = true
        since = System.currentTimeMillis()
    }

    private fun check(cur: Location) {
        if (cur.provider != LocationManager.GPS_PROVIDER) return
        fixesSeen++
        val what = detector.feed(
            GlitchDetector.Fix(
                cur.elapsedRealtimeNanos / 1e9, cur.latitude, cur.longitude,
                if (cur.hasSpeed()) cur.speed else null, if (cur.hasAccuracy()) cur.accuracy else null,
            ),
        )
        if (what != null) record(cur, what)
    }

    private fun record(loc: Location, what: String) {
        val sp = app.getSharedPreferences("dock", Context.MODE_PRIVATE)
        val list = runCatching { JSONArray(sp.getString("gpsGlitches", "[]")) }.getOrDefault(JSONArray())
        val now = System.currentTimeMillis()
        val last = if (list.length() > 0) list.optJSONObject(list.length() - 1) else null
        // A burst of bad fixes is one glitch: keep the first text, count the rest.
        if (last != null && now - last.optLong("at") < MERGE_MS) {
            last.put("count", last.optInt("count", 1) + 1).put("at", now)
        } else {
            list.put(JSONObject().apply {
                put("at", now)
                put("what", what)
                put("acc", if (loc.hasAccuracy()) loc.accuracy.toInt() else -1)
                put("speedAcc", if (android.os.Build.VERSION.SDK_INT >= 26 && loc.hasSpeedAccuracy()) loc.speedAccuracyMetersPerSecond.toDouble() * 3.6 else -1.0)
                put("sats", satsUsed)
                put("lat", "%.4f".format(java.util.Locale.US, loc.latitude))
                put("lng", "%.4f".format(java.util.Locale.US, loc.longitude))
                put("count", 1)
            })
        }
        while (list.length() > KEEP) list.remove(0)
        sp.edit().putString("gpsGlitches", list.toString()).apply()
    }

    /** Newest first, one line each, for the doctor screen. */
    fun lines(context: Context): List<String> {
        val list = runCatching {
            JSONArray(context.getSharedPreferences("dock", Context.MODE_PRIVATE).getString("gpsGlitches", "[]"))
        }.getOrDefault(JSONArray())
        val fmt = java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.US)
        return (list.length() - 1 downTo 0).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            val bits = mutableListOf<String>()
            if (o.optInt("count", 1) > 1) bits += "×${o.optInt("count")}"
            if (o.optInt("acc", -1) >= 0) bits += "±${o.optInt("acc")} m"
            if (o.optDouble("speedAcc", -1.0) >= 0) bits += "speed ±%.0f km/h".format(o.optDouble("speedAcc"))
            bits += "${o.optInt("sats")} sats"
            bits += "${o.optString("lat")}, ${o.optString("lng")}"
            "${fmt.format(java.util.Date(o.optLong("at")))}  ${o.optString("what")}\n      " + bits.joinToString(" · ")
        }
    }

    fun clear(context: Context) =
        context.getSharedPreferences("dock", Context.MODE_PRIVATE).edit().remove("gpsGlitches").apply()
}
