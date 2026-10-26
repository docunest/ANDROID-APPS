package com.docunest.dock

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper

/**
 * One current position, fast: asks GPS and network at once and takes the
 * first answer; if neither answers within the timeout, falls back to the
 * freshest last-known fix. No Google Play Services needed.
 */
object Locator {
    private const val TIMEOUT_MS = 8_000L
    private const val MAX_LAST_KNOWN_AGE_MS = 10 * 60 * 1000L

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // checked by hasPermission()
    fun current(context: Context, onResult: (Location?) -> Unit) {
        if (!hasPermission(context)) {
            onResult(null)
            return
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        val handler = Handler(Looper.getMainLooper())
        var done = false
        val cancels = mutableListOf<() -> Unit>()

        fun finish(loc: Location?) {
            if (done) return
            done = true
            cancels.forEach { runCatching { it() } }
            onResult(loc ?: lastKnown(lm, providers))
        }

        if (providers.isEmpty()) {
            finish(null)
            return
        }
        for (p in providers) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val signal = CancellationSignal()
                cancels += { signal.cancel() }
                lm.getCurrentLocation(p, signal, context.mainExecutor) { loc -> if (loc != null) finish(loc) }
            } else {
                val listener = LocationListener { loc -> finish(loc) }
                cancels += { lm.removeUpdates(listener) }
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(p, listener, Looper.getMainLooper())
            }
        }
        handler.postDelayed({ finish(null) }, TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(lm: LocationManager, providers: List<String>): Location? =
        providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < MAX_LAST_KNOWN_AGE_MS }
            .minByOrNull { it.accuracy }
}
