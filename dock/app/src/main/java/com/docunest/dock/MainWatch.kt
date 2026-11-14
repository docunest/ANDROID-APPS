package com.docunest.dock

import android.os.Looper
import android.os.SystemClock
import android.util.Printer

/**
 * While the Dock keyboard is up: logs anything that holds the app's main thread
 * for more than [SLOW_MS] — the keyboard is drawn on that same thread, so this is
 * what makes iDrive moves appear late. Off while the keyboard is hidden (watching
 * every message costs a little).
 */
object MainWatch {
    private const val SLOW_MS = 40L
    private var startedAt = 0L
    private var what = ""

    private val printer = Printer { line ->
        if (line.startsWith(">>>>> Dispatching")) {
            startedAt = SystemClock.uptimeMillis()
            what = line
        } else if (line.startsWith("<<<<< Finished") && startedAt > 0) {
            val took = SystemClock.uptimeMillis() - startedAt
            startedAt = 0
            if (took > SLOW_MS) DebugLog.log("KEY", "main thread busy $took ms: ${what.removePrefix(">>>>> Dispatching to ").take(160)}")
        }
    }

    fun start() = Looper.getMainLooper().setMessageLogging(printer)
    fun stop() {
        Looper.getMainLooper().setMessageLogging(null)
        startedAt = 0
    }
}
