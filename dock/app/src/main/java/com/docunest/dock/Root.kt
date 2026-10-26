package com.docunest.dock

import android.content.Context

/** The head unit is rooted: grant our own permissions and install updates without prompts. */
object Root {
    fun run(vararg commands: String): Boolean = runCatching {
        val p = ProcessBuilder("su", "-c", commands.joinToString("; ")).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText()
        p.waitFor() == 0
    }.getOrDefault(false)

    /** Overlay, location, notifications, no battery kill. Idempotent. */
    fun grantAll(context: Context): Boolean {
        val pkg = context.packageName
        return run(
            "appops set $pkg SYSTEM_ALERT_WINDOW allow",
            "pm grant $pkg android.permission.ACCESS_FINE_LOCATION",
            "pm grant $pkg android.permission.ACCESS_COARSE_LOCATION",
            "pm grant $pkg android.permission.POST_NOTIFICATIONS 2>/dev/null; true",
            "dumpsys deviceidle whitelist +$pkg",
        )
    }
}
