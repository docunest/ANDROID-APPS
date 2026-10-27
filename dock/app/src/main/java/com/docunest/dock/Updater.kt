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
 *
 * Checked right away when the dock starts (car on) and every [EVERY_MS]
 * after, so a build pushed from the phone lands within minutes. GitHub
 * allows 60 anonymous API calls/hour; requests carry the last ETag, and an
 * unchanged answer (304) doesn't count against that.
 */
object Updater {
    const val EVERY_MS = 3 * 60_000L
    private const val RETRY_FAILED_BUILD_MS = 30 * 60_000L

    data class Release(val build: Int, val apkUrl: String)

    // Own thread: a download never blocks place syncing.
    private val thread = android.os.HandlerThread("dock-update").apply { start() }
    private val worker = android.os.Handler(thread.looper)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** What the version badge shows. */
    @Volatile var busy = false
        private set
    @Volatile var status = "Not checked yet"
        private set
    private val listeners = mutableSetOf<() -> Unit>()
    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }
    private fun set(busy: Boolean, status: String) {
        this.busy = busy
        this.status = status
        val ls = synchronized(listeners) { listeners.toList() }
        main.post { ls.forEach { it() } }
    }

    /** Check in the background (badge tap, car start, every few minutes). */
    fun checkSoon(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        worker.post { check(app, force) }
    }

    fun latest(context: Context): Release? {
        val prefs = Prefs(context)
        val conn = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases?per_page=30").openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        prefs.updateEtag.takeIf { it.isNotEmpty() }?.let { conn.setRequestProperty("If-None-Match", it) }
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        try {
            if (conn.responseCode == 304) return prefs.cachedRelease()
            if (conn.responseCode != 200) throw java.io.IOException("GitHub ${conn.responseCode}")
            val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            val rel = (0 until arr.length()).map { arr.getJSONObject(it) }.mapNotNull { r ->
                val build = Regex("^dock-v(\\d+)$").find(r.optString("tag_name"))?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
                val assets = r.optJSONArray("assets") ?: return@mapNotNull null
                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk") }
                    ?: return@mapNotNull null
                Release(build, apk.getString("browser_download_url"))
            }.maxByOrNull { it.build }
            prefs.cacheRelease(rel, conn.getHeaderField("ETag").orEmpty())
            return rel
        } finally {
            conn.disconnect()
        }
    }

    /** Returns a short status line. Blocking. */
    fun check(context: Context, force: Boolean = false): String {
        val prefs = Prefs(context)
        if (!force && (!prefs.autoUpdate || System.currentTimeMillis() - prefs.lastUpdateCheck < EVERY_MS - 5_000)) return status
        prefs.lastUpdateCheck = System.currentTimeMillis()
        set(true, "Checking…")
        val rel = runCatching { latest(context) }.getOrElse { return done("Check failed: ${it.message}") }
            ?: return done("No releases found")
        if (rel.build <= BuildConfig.VERSION_CODE) return done("Up to date")
        if (!force && prefs.failedBuild == rel.build && System.currentTimeMillis() - prefs.failedAt < RETRY_FAILED_BUILD_MS) {
            return done("v${rel.build} failed to install — retrying later")
        }
        set(true, "Downloading v${rel.build}…")
        val file = File(context.cacheDir, "dock-${rel.build}.apk")
        runCatching {
            URL(rel.apkUrl).openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
        }.onFailure { return done("Download failed: ${it.message}") }
        set(true, "Installing v${rel.build}…")
        // pm can't always read app-private files: stage in /data/local/tmp.
        val staged = "/data/local/tmp/dock-update.apk"
        val ok = Root.run("cp '${file.absolutePath}' $staged", "chmod 644 $staged", "pm install -r -d $staged", "rm -f $staged")
        if (ok) {
            file.delete()
            return done("Updated to v${rel.build} — restarting")
        }
        prefs.failedBuild = rel.build
        prefs.failedAt = System.currentTimeMillis()
        // No root (a phone): Android's own installer. First time = one "Update"
        // tap; after that Android 12+ lets the app update itself silently.
        return try {
            done(installWithoutRoot(context, file))
        } finally {
            file.delete()
        }
    }

    private fun done(status: String): String {
        set(false, status)
        return status
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
