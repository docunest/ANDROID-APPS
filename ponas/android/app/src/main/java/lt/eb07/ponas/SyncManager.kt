package lt.eb07.ponas

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * Talks to GitHub Pages. All work runs on one low-priority background thread, one job at a time.
 *
 *  - [maybeSync]: download the catalog (manifest.json) and auto-update installed games whose
 *    hash changed. At most every 15 min unless forced. Never installs or removes games by itself.
 *  - [install] / [uninstall]: what the parent picks on the install screen.
 *
 * A game version is switched only after every file downloaded and matched size + sha256.
 * On any failure the last good version stays.
 */
object SyncManager {
    private const val TAG = "PonasSync"

    const val BASE_URL = "https://eb07-lab.github.io/ponas-app/"
    const val MANIFEST_URL = BASE_URL + "manifest.json"

    private const val MIN_INTERVAL_MS = 15L * 60 * 1000
    private const val EMPTY_RETRY_MS = 30L * 1000
    private const val MAX_MANIFEST_BYTES = 1024L * 1024
    private const val MAX_GAME_BYTES = 4L * 1024 * 1024 // build-site enforces 2 MB; safety cap
    private const val MAX_ICON_BYTES = 512L * 1024
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    val ID_RE = Regex("^[a-z0-9][a-z0-9-]{0,39}$")
    private val HASH_RE = Regex("^[a-f0-9]{8,64}$")
    private val PATH_RE = Regex("^[A-Za-z0-9._/-]{1,200}$")
    private val SHA_RE = Regex("^[a-f0-9]{64}$")

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ponas-sync").apply { priority = Thread.MIN_PRIORITY }
    }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Runnable>()

    /** Game ids with an install/update in progress (for the install screen). */
    val busy: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    var catalogRunning = false
        private set

    val isRunning: Boolean get() = catalogRunning || busy.isNotEmpty()

    fun addListener(l: Runnable) { listeners.add(l) }
    fun removeListener(l: Runnable) { listeners.remove(l) }
    private fun notifyListeners() { main.post { for (l in listeners) l.run() } }

    // ---- public jobs --------------------------------------------------------------------

    /** Refresh the catalog and auto-update installed games, if due (or [force]). */
    fun maybeSync(ctx: Context, force: Boolean = false): Boolean {
        val app = ctx.applicationContext
        if (catalogRunning) return false
        if (!force) {
            val now = System.currentTimeMillis()
            val last = Store.prefs(app).getLong(Store.KEY_LAST_ATTEMPT, 0L)
            val never = Store.loadCatalog(app).isEmpty()
            val interval = if (never) EMPTY_RETRY_MS else MIN_INTERVAL_MS
            if (last in 1..now && now - last < interval) return false
        }
        catalogRunning = true
        notifyListeners()
        executor.execute {
            try {
                refresh(app)
            } catch (e: Throwable) {
                recordError(app, "Sync crashed: $e")
            } finally {
                catalogRunning = false
                notifyListeners()
            }
        }
        return true
    }

    fun install(ctx: Context, id: String) {
        val app = ctx.applicationContext
        if (!busy.add(id)) return
        notifyListeners()
        executor.execute {
            try {
                val entry = Store.loadCatalog(app).firstOrNull { it.first.id == id }
                    ?: throw IOException("not in catalog")
                installEntry(app, entry.first, entry.second)
                clearError(app)
            } catch (e: Throwable) {
                recordError(app, "$id: $e")
            } finally {
                busy.remove(id)
                notifyListeners()
            }
        }
    }

    fun uninstall(ctx: Context, id: String) {
        val app = ctx.applicationContext
        executor.execute {
            try {
                Store.saveInstalled(app, Store.loadInstalled(app).filter { it.id != id })
                File(Store.gamesRoot(app), id).deleteRecursively()
            } catch (e: Throwable) {
                recordError(app, "remove $id: $e")
            } finally {
                notifyListeners()
            }
        }
    }

    /** Downloads a catalog game's icon for the install screen (blocking; call off the main thread). */
    fun fetchIcon(ctx: Context, g: Game): File? {
        val dir = File(ctx.cacheDir, "icons").apply { mkdirs() }
        val f = File(dir, "${g.id}-${g.hash}.png")
        if (f.isFile) return f
        if (!PATH_RE.matches(g.icon) || !isOnline(ctx)) return null
        return try {
            val bytes = fetchBytes(URL(BASE_URL + "games/${g.id}/${g.icon}?h=${g.hash}"), MAX_ICON_BYTES)
            dir.listFiles()?.filter { it.name.startsWith("${g.id}-") }?.forEach { it.delete() }
            f.writeBytes(bytes)
            f
        } catch (e: Exception) {
            null
        }
    }

    // ---- work ---------------------------------------------------------------------------

    private fun refresh(ctx: Context) {
        val now = System.currentTimeMillis()
        Store.prefs(ctx).edit().putLong(Store.KEY_LAST_ATTEMPT, now).apply()
        cleanup(ctx)

        if (!isOnline(ctx)) {
            recordError(ctx, "Offline: no network connection")
            return
        }

        val text: String
        val manifest: JSONObject
        try {
            text = String(fetchBytes(URL("$MANIFEST_URL?t=$now"), MAX_MANIFEST_BYTES), Charsets.UTF_8)
            manifest = JSONObject(text)
            if (manifest.optJSONArray("games") == null) throw IOException("no \"games\" list")
        } catch (e: Exception) {
            recordError(ctx, "Manifest download failed: $e")
            return
        }
        Store.saveCatalog(ctx, text)

        // Auto-update installed games whose hash changed; refresh their titles/colours/etc.
        val catalog = Store.loadCatalog(ctx).associateBy { it.first.id }
        val errors = ArrayList<String>()
        for (g in Store.loadInstalled(ctx)) {
            val entry = catalog[g.id] ?: continue // removed from the catalog: keep it installed
            try {
                installEntry(ctx, entry.first, entry.second)
            } catch (e: Exception) {
                errors.add("${g.id}: $e")
            }
        }
        cleanup(ctx)

        val edit = Store.prefs(ctx).edit()
            .putLong(Store.KEY_MANIFEST_VERSION, manifest.optLong("version", 0L))
            .putLong(Store.KEY_LAST_OK, System.currentTimeMillis())
        if (errors.isEmpty()) {
            edit.remove(Store.KEY_LAST_ERROR).remove(Store.KEY_LAST_ERROR_AT)
        } else {
            edit.putString(Store.KEY_LAST_ERROR, errors.joinToString("\n"))
                .putLong(Store.KEY_LAST_ERROR_AT, System.currentTimeMillis())
        }
        edit.apply()
    }

    /** Installs or updates one game from its catalog entry, then records it as installed. */
    private fun installEntry(ctx: Context, g: Game, raw: JSONObject) {
        if (!ID_RE.matches(g.id)) throw IOException("invalid id")
        if (!HASH_RE.matches(g.hash)) throw IOException("invalid hash")
        val current = Store.loadInstalled(ctx).firstOrNull { it.id == g.id }
        if (current == null || current.hash != g.hash) {
            val files = raw.optJSONArray("files") ?: throw IOException("no files list")
            download(ctx, g, files)
        }
        val list = Store.loadInstalled(ctx).filter { it.id != g.id }.toMutableList()
        val prev = when {
            current == null -> null
            current.hash != g.hash -> current.hash
            else -> current.prev
        }
        list.add(g.copy(prev = prev))
        Store.saveInstalled(ctx, list)
        Log.i(TAG, "installed ${g.id} @ ${g.hash}")
    }

    private fun download(ctx: Context, g: Game, files: JSONArray) {
        val root = File(Store.gamesRoot(ctx), g.id)
        val finalDir = File(root, g.hash)
        if (File(finalDir, "index.html").isFile) return // complete from an earlier run

        val tmp = File(root, g.hash + ".tmp")
        tmp.deleteRecursively()
        if (!tmp.mkdirs()) throw IOException("cannot create ${tmp.path}")

        var total = 0L
        var hasIndex = false
        try {
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                val path = f.getString("path")
                val size = f.getLong("size")
                val sha = f.optString("sha256", "")
                if (!PATH_RE.matches(path) || path.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
                    throw IOException("bad path '$path'")
                }
                total += size
                if (size < 0 || total > MAX_GAME_BYTES) throw IOException("game too large")
                val out = File(tmp, path)
                out.parentFile?.mkdirs()
                // ?h= busts the GitHub Pages CDN cache so old and new files never mix.
                val url = URL(BASE_URL + "games/" + g.id + "/" + path + "?h=" + g.hash)
                downloadTo(url, out, size, if (SHA_RE.matches(sha)) sha else null)
                if (path == "index.html") hasIndex = true
            }
            if (!hasIndex) throw IOException("no index.html")
            if (finalDir.exists()) finalDir.deleteRecursively()
            if (!tmp.renameTo(finalDir)) throw IOException("rename failed")
        } catch (e: Exception) {
            tmp.deleteRecursively()
            throw e
        }
    }

    /** Deletes temp folders, uninstalled games and versions other than current + previous. */
    private fun cleanup(ctx: Context) {
        val dirs = Store.gamesRoot(ctx).listFiles() ?: return
        val keep = Store.loadInstalled(ctx).associate { it.id to setOfNotNull(it.hash, it.prev) }
        for (gameDir in dirs) {
            val versions = keep[gameDir.name]
            if (versions == null) {
                gameDir.deleteRecursively()
                continue
            }
            for (v in gameDir.listFiles() ?: emptyArray()) {
                if (v.name !in versions) v.deleteRecursively()
            }
        }
    }

    // ---- helpers ------------------------------------------------------------------------

    private fun recordError(ctx: Context, msg: String) {
        Log.w(TAG, msg)
        Store.prefs(ctx).edit()
            .putString(Store.KEY_LAST_ERROR, msg)
            .putLong(Store.KEY_LAST_ERROR_AT, System.currentTimeMillis())
            .apply()
    }

    private fun clearError(ctx: Context) {
        Store.prefs(ctx).edit().remove(Store.KEY_LAST_ERROR).remove(Store.KEY_LAST_ERROR_AT).apply()
    }

    fun isOnline(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun open(url: URL): HttpURLConnection {
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = CONNECT_TIMEOUT_MS
        c.readTimeout = READ_TIMEOUT_MS
        c.useCaches = false
        c.instanceFollowRedirects = true
        c.setRequestProperty("Cache-Control", "no-cache")
        c.setRequestProperty("User-Agent", "Ponas")
        val code = c.responseCode
        if (code != 200) {
            c.disconnect()
            throw IOException("HTTP $code for ${url.path}")
        }
        return c
    }

    private fun fetchBytes(url: URL, max: Long): ByteArray {
        val c = open(url)
        try {
            c.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > max) throw IOException("response too large")
                    out.write(buf, 0, n)
                }
                return out.toByteArray()
            }
        } finally {
            c.disconnect()
        }
    }

    private fun downloadTo(url: URL, out: File, expectedSize: Long, expectedSha: String?) {
        val c = open(url)
        val md = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            c.inputStream.use { input ->
                FileOutputStream(out).use { fos ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > expectedSize) throw IOException("${url.path}: larger than expected $expectedSize bytes")
                        md.update(buf, 0, n)
                        fos.write(buf, 0, n)
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        if (total != expectedSize) {
            throw IOException("${url.path}: size $total, expected $expectedSize (Pages may still be deploying)")
        }
        if (expectedSha != null) {
            val got = md.digest().joinToString("") { "%02x".format(it) }
            if (got != expectedSha) throw IOException("${url.path}: checksum mismatch")
        }
    }
}
