package com.docunest.dock

import android.content.Context

/** The head unit is rooted: grant our own permissions and install updates without prompts. */
object Root {
    fun run(vararg commands: String): Boolean = runCatching {
        val p = ProcessBuilder("su", "-c", commands.joinToString("; ")).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText()
        p.waitFor() == 0
    }.getOrDefault(false)

    /**
     * null when root works, else why not — "no su" (not rooted / su not on the
     * path) or "su refused" (the superuser app — Magisk, SuperSU — denies Dock).
     */
    fun check(): String? = try {
        val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        val code = p.waitFor()
        if (code == 0 && out.contains("uid=0")) null
        else "su refused (exit $code${if (out.isNotEmpty()) ": " + out.take(60) else ""}) — allow Dock in Magisk / SuperSU"
    } catch (e: java.io.IOException) {
        val m = e.message.orEmpty()
        when {
            "error=13" in m || "ermission denied" in m -> "su exists but Dock may not run it — allow Dock in the root manager (Magisk / SuperSU)"
            "error=2" in m || "No such file" in m -> "no su found — not rooted, or the root manager isn't installed"
            else -> "can't start su (${m.take(60)})"
        }
    }

    /** Like [run], but returns what the commands printed (null if root refused). */
    fun output(vararg commands: String): String? = runCatching {
        val p = ProcessBuilder("su", "-c", commands.joinToString("; ")).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (p.waitFor() == 0) out else null
    }.getOrNull()

    /** Overlay, location, notifications, no battery kill. Idempotent. */
    fun grantAll(context: Context): Boolean {
        val pkg = context.packageName
        return run(
            "appops set $pkg SYSTEM_ALERT_WINDOW allow",
            "pm grant $pkg android.permission.ACCESS_FINE_LOCATION",
            "pm grant $pkg android.permission.ACCESS_COARSE_LOCATION",
            "pm grant $pkg android.permission.ACCESS_BACKGROUND_LOCATION",
            "pm grant $pkg android.permission.POST_NOTIFICATIONS 2>/dev/null; true",
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
