package com.docunest.dock

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update: GitHub Actions publishes every build as a release tagged
 * "dock-v<build>" in [BuildConfig.UPDATE_REPO] with dock.apk attached. If a
 * newer one exists, download it and `pm install -r` it as root — no prompt.
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
        file.delete()
        return if (ok) "Updated to build ${rel.build}" else "Install failed (root?) — build ${rel.build} is available"
    }
}
