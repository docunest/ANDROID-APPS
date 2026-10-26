package com.docunest.dock

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.widget.Toast

data class AppEntry(val pkg: String, val label: String, val icon: Drawable?)

/** Installed apps and launching them. */
object Apps {
    const val WAZE = "com.waze"
    const val GMAPS = "com.google.android.apps.maps"
    const val YOUTUBE = "com.google.android.youtube"
    const val SPOTIFY = "com.spotify.music"

    fun entry(context: Context, pkg: String): AppEntry? {
        val pm = context.packageManager
        return runCatching {
            val info = pm.getApplicationInfo(pkg, 0)
            AppEntry(pkg, pm.getApplicationLabel(info).toString(), pm.getApplicationIcon(info))
        }.getOrNull()
    }

    fun installed(context: Context, pkg: String) = entry(context, pkg) != null

    /** Every app with a launcher icon, A→Z. */
    fun launchable(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(main, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName }
            .mapNotNull { entry(context, it) }
            .sortedBy { it.label.lowercase() }
    }

    fun launch(context: Context, pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            Toast.makeText(context, "$pkg is not installed", Toast.LENGTH_SHORT).show()
            return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
