package com.docunest.dock

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update: GitHub Actions publishes every build as a release tagged
 * "dock-v<build>" in [BuildConfig.UPDATE_REPO] with dock.apk attached. If a
 * newer one exists, download it and `pm install -r` it as root — no prompt.
 * Without root (a phone) Android's installer is used: one tap the first
 * time, silent afterwards on Android 12+.
 * The app restarts itself afterwards (BootReceiver: MY_PACKAGE_REPLACED).
 */
object Updater {
    private const val EVERY_MS = 3 * 60 * 60_000L

    data class Release(val build: Int, val apkUrl: String)

    fun latest(): Release? {
        val conn = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases?per_page=30").openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        val arr = try {
            JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
        return (0 until arr.length()).map { arr.getJSONObject(it) }.mapNotNull { r ->
            val build = Regex("^dock-v(\\d+)$").find(r.optString("tag_name"))?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            val assets = r.optJSONArray("assets") ?: return@mapNotNull null
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk") }
                ?: return@mapNotNull null
            Release(build, apk.getString("browser_download_url"))
        }.maxByOrNull { it.build }
    }

    /** Returns a short status line. Blocking. */
    fun check(context: Context, force: Boolean = false): String {
        val prefs = Prefs(context)
        if (!force && (!prefs.autoUpdate || System.currentTimeMillis() - prefs.lastUpdateCheck < EVERY_MS)) return "Skipped"
        prefs.lastUpdateCheck = System.currentTimeMillis()
        val rel = runCatching { latest() }.getOrElse { return "Update check failed: ${it.message}" }
            ?: return "No releases found"
        if (rel.build <= BuildConfig.VERSION_CODE) return "Up to date (build ${BuildConfig.VERSION_CODE})"
        val file = File(context.cacheDir, "dock-${rel.build}.apk")
        runCatching {
            URL(rel.apkUrl).openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
        }.onFailure { return "Download failed: ${it.message}" }
        // pm can't always read app-private files: stage in /data/local/tmp.
        val staged = "/data/local/tmp/dock-update.apk"
        val ok = Root.run("cp '${file.absolutePath}' $staged", "chmod 644 $staged", "pm install -r -d $staged", "rm -f $staged")
        if (ok) {
            file.delete()
            return "Updated to build ${rel.build}"
        }
        // No root (a phone): Android's own installer. First time = one "Update"
        // tap; after that Android 12+ lets the app update itself silently.
        return try {
            installWithoutRoot(context, file)
        } finally {
            file.delete()
        }
    }

    private fun installWithoutRoot(context: Context, apk: File): String {
        if (!context.packageManager.canRequestPackageInstalls()) {
            UpdateReceiver.notify(
                context, "Allow Dock to update itself",
                "Tap, then switch on \"Allow from this source\" (once).",
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
            )
            return "Needs permission to install updates — see the notification"
        }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("dock.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val result = PendingIntent.getBroadcast(
                context, id, Intent(context, UpdateReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(result.intentSender)
        }
        return "Installing update…"
    }
}
