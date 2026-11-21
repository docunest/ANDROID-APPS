package com.docunest.dock

import android.content.Context
import android.os.SystemClock

/**
 * The owner's "☀ the sky is clear" hint: the GPS isn't working but there IS open sky, so don't
 * wait for a tunnel or a car park to end — fix now. For [MINUTES] min, or until a fix is back:
 * the auto doctor starts at once (no tunnel / under-cover / back-off waits), the fix run skips
 * "Just wait", waits less per step, and a satellite drop doesn't pause it.
 */
object ClearSky {
    const val MINUTES = 20
    @Volatile private var until = 0L

    val active: Boolean get() = SystemClock.elapsedRealtime() < until

    fun hint(context: Context) {
        until = SystemClock.elapsedRealtime() + MINUTES * 60_000L
        DebugLog.log("GPS", "☀ owner: the sky is clear — fixing now, no waiting (for $MINUTES min or until the GPS is back)")
        AutoDoctor.clearSkyHint(context)
    }

    fun clear(why: String) {
        if (until == 0L) return
        until = 0L
        DebugLog.log("GPS", "☀ clear-sky hint ended: $why")
    }
}
