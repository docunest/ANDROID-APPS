package lt.eb07.ponas

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update, same idea as the dock's: CI publishes every build as a GitHub release tagged
 * "ponas-v<build>" (build = versionCode) with ponas.apk attached. When a newer build exists,
 * download it and install it through PackageInstaller. The tablet has no root, so the install
 * uses Android's installer: after the parent allows "install unknown apps" for Ponas once, updates
 * install without a tap on Android 12 and later.
 *
 * Checked when the home screen comes back (at most every [EVERY_MS]), or from the parent menu.
 * A build that failed to install is retried after [RETRY_FAILED_MS]. Installing replaces the app;
 * Android restarts the home screen with the new build.
 */
object Updater {
    const val EVERY_MS = 15 * 60_000L
    private const val PREFS = "ponas-update"
    private const val KEY_LAST_CHECK = "lastCheck"
    private const val KEY_STATUS = "status"
    private const val KEY_FAILED_BUILD = "failedBuild"
    private const val KEY_FAILED_AT = "failedAt"
    private const val RETRY_FAILED_MS = 6 * 60 * 60_000L

    private val worker = android.os.HandlerThread("ponas-update").apply { start() }
    private val handler = android.os.Handler(worker.looper)

    data class Release(val build: Int, val apkUrl: String)

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Last result, for the parent menu (empty before the first check). */
    fun lastStatus(c: Context): String = prefs(c).getString(KEY_STATUS, "").orEmpty()

    /** Called when the home screen resumes. Does nothing if the last check was recent. */
    fun maybeCheck(context: Context) {
        val app = context.applicationContext
        val p = prefs(app)
        if (System.currentTimeMillis() - p.getLong(KEY_LAST_CHECK, 0L) < EVERY_MS) return
        // Stamp now, not on the worker: several resumes in a row must not queue several checks.
        p.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        handler.post { check(app) }
    }

    /** Parent menu: check right away, ignoring the 15-minute spacing and the failed-build pause. */
    fun checkNow(context: Context) {
        val app = context.applicationContext
        prefs(app).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        handler.post { check(app, force = true) }
    }

    private fun save(c: Context, status: String) {
        prefs(c).edit().putString(KEY_STATUS, status).apply()
    }

    /** Blocking; runs on the update thread. Returns a short status line. */
    fun check(app: Context, force: Boolean = false): String {
        val p = prefs(app)
        val rel = runCatching { latest() }.getOrElse { return finish(app, "Check failed: ${it.message}") }
            ?: return finish(app, "No release found")
        if (rel.build <= BuildConfig.VERSION_CODE) return finish(app, "Up to date (build ${BuildConfig.VERSION_CODE})")
        // Before downloading: without the one-time permission the APK can't be installed anyway,
        // and the tablet's data shouldn't pay for a download every 15 minutes.
        if (!app.packageManager.canRequestPackageInstalls()) {
            return finish(app, "Build ${rel.build} available. Parent: allow installs from Ponas once (parent menu → App update → Allow)")
        }
        if (!force && p.getInt(KEY_FAILED_BUILD, 0) == rel.build &&
            System.currentTimeMillis() - p.getLong(KEY_FAILED_AT, 0L) < RETRY_FAILED_MS
        ) {
            return finish(app, "Build ${rel.build} failed to install earlier; next try later")
        }
        val file = File(app.cacheDir, "ponas-update.apk")
        runCatching { download(rel.apkUrl, file) }.getOrElse {
            file.delete()
            return finish(app, "Download failed: ${it.message}")
        }
        return try {
            // Counted as failed until the new build runs (a successful install replaces this app,
            // and the new build starts with a higher VERSION_CODE, so the mark no longer matters).
            p.edit().putInt(KEY_FAILED_BUILD, rel.build).putLong(KEY_FAILED_AT, System.currentTimeMillis()).apply()
            install(app, file)
            finish(app, "Installing build ${rel.build}…")
        } catch (e: Exception) {
            finish(app, "Install failed: ${e.message}")
        } finally {
            file.delete()
        }
    }

    private fun finish(c: Context, status: String): String {
        save(c, status)
        android.util.Log.i("PonasUpdate", status)
        return status
    }

    /** Newest "ponas-v<build>" release that has ponas.apk. */
    fun latest(): Release? {
        val conn = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases?per_page=100").openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        try {
            if (conn.responseCode != 200) throw IOException("GitHub ${conn.responseCode}")
            val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            return (0 until arr.length()).map { arr.getJSONObject(it) }.mapNotNull { r ->
                val build = Regex("^ponas-v(\\d+)$").find(r.optString("tag_name"))?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@mapNotNull null
                val assets = r.optJSONArray("assets") ?: return@mapNotNull null
                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name") == "ponas.apk" } ?: return@mapNotNull null
                Release(build, apk.getString("browser_download_url"))
            }.maxByOrNull { it.build }
        } finally {
            conn.disconnect()
        }
    }

    /** Three tries with timeouts; checks the size against Content-Length. */
    private fun download(url: String, file: File) {
        var last: Exception? = null
        repeat(3) {
            val tmp = File(file.path + ".part")
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                c.instanceFollowRedirects = true
                try {
                    if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
                    val expected = c.contentLengthLong
                    c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    if (expected > 0 && tmp.length() != expected) throw IOException("got ${tmp.length()} of $expected bytes")
                } finally {
                    c.disconnect()
                }
                if (!tmp.renameTo(file)) throw IOException("can't move the download")
                return
            } catch (e: Exception) {
                last = e
                tmp.delete()
                Thread.sleep(3_000)
            }
        }
        throw last ?: IOException("download failed")
    }

    /** Writes the APK into a PackageInstaller session. The result comes back to [UpdateReceiver]. */
    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("ponas.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val result = PendingIntent.getBroadcast(
                context, id, Intent(context, UpdateReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(result.intentSender)
        }
    }

    /** Opens Android's settings page for "install unknown apps" for Ponas (used by the parent). */
    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
}
