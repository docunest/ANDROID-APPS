package com.docunest.dock

import android.content.Context

/**
 * Root for the dock: `su` when the app may use it (Magisk etc.), else the head
 * unit's own adbd ([AdbShell], root on this userdebug build). `su` is checked
 * once (and every 10 min), so a unit without usable `su` doesn't pay for a
 * refused `su` on every call.
 */
object Root {
    @Volatile private var suOk: Boolean? = null
    @Volatile private var suCheckedAt = 0L

    /** "su", "adb (root)", "adb (shell)" or "none" — for the doctors and the debug log. */
    fun via(): String = when {
        suOk == true -> "su" // cached: cheap enough for the UI thread
        AdbShell.ready -> if (AdbShell.asRoot == true) "adb (root)" else "adb (shell)"
        else -> "none (${AdbShell.status})"
    }

    private fun suUsable(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (suOk == null || now - suCheckedAt > 10 * 60_000L) {
            suCheckedAt = now
            suOk = runCatching {
                val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor() == 0 && out.contains("uid=0")
            }.getOrDefault(false)
            DebugLog.change("ROOT", "su", if (suOk == true) "usable" else "not usable for apps")
        }
        return suOk == true
    }

    fun run(vararg commands: String): Boolean =
        if (suUsable()) runCatching {
            val p = ProcessBuilder("su", "-c", commands.joinToString("; ")).redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText()
            p.waitFor() == 0
        }.getOrDefault(false)
        else AdbShell.run(*commands)

    /** [run] for slow commands (pm install): the adb route gets [timeoutMs] instead of 30 s. */
    fun runLong(timeoutMs: Int, vararg commands: String): Boolean =
        if (suUsable()) run(*commands) else AdbShell.output(*commands, timeoutMs = timeoutMs) != null

    /** Like [run], but returns what the commands printed (null if refused / failed). */
    fun output(vararg commands: String): String? =
        if (suUsable()) runCatching {
            val p = ProcessBuilder("su", "-c", commands.joinToString("; ")).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            if (p.waitFor() == 0) out else null
        }.getOrNull()
        else AdbShell.output(*commands)

    /** Overlay, location, notifications, no battery kill. Idempotent. */
    fun grantAll(context: Context): Boolean {
        val pkg = context.packageName
        return run(
            "appops set $pkg SYSTEM_ALERT_WINDOW allow",
            "pm grant $pkg android.permission.ACCESS_FINE_LOCATION",
            "pm grant $pkg android.permission.ACCESS_COARSE_LOCATION",
            "pm grant $pkg android.permission.ACCESS_BACKGROUND_LOCATION",
            // Parked position after a cold start in a garage (mock location provider).
            "appops set $pkg android:mock_location allow",
            "pm grant $pkg android.permission.POST_NOTIFICATIONS 2>/dev/null; true",
            // Bluetooth kick (Android 12+ runtime permission; older ones are install-time).
            "pm grant $pkg android.permission.BLUETOOTH_CONNECT 2>/dev/null; true",
            "dumpsys deviceidle whitelist +$pkg",
        ).also { enableSplitService(context) }
    }

    /** Switch on [SplitService] (Accessibility) without the settings screen; keeps other services on. */
    fun enableSplitService(context: Context): Boolean {
        val comp = "${context.packageName}/${SplitService::class.java.name}"
        return run(
            "cur=\$(settings get secure enabled_accessibility_services)",
            "case \"\$cur\" in *SplitService*) ;; null|\"\") settings put secure enabled_accessibility_services $comp ;; " +
                "*) settings put secure enabled_accessibility_services \"\$cur:$comp\" ;; esac",
            "settings put secure accessibility_enabled 1",
        )
    }
}
