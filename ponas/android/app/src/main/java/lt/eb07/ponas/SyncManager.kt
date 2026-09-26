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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Downloads manifest.json from GitHub Pages and keeps filesDir/games/<id>/<hash>/ in sync.
 *
 * Rules:
 *  - at most every 15 min unless forced from the parent menu;
 *  - a game is switched only after all of its files downloaded and matched size + sha256;
 *  - on any failure the last good version stays.
 */
object SyncManager {
    private const val TAG = "PonasSync"

    const val BASE_URL = "https://eb07-lab.github.io/ponas-app/"
    const val MANIFEST_URL = BASE_URL + "manifest.json"

    private const val MIN_INTERVAL_MS = 15L * 60 * 1000
    private const val EMPTY_RETRY_MS = 30L * 1000 // retry quickly while nothing is installed
    private const val MAX_MANIFEST_BYTES = 512L * 1024
    private const val MAX_GAME_BYTES = 4L * 1024 * 1024 // build-manifest enforces 2 MB; this is a safety cap
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    private val ID_RE = Regex("^[a-z0-9][a-z0-9-]{0,39}$")
    private val HASH_RE = Regex("^[a-f0-9]{8,64}$")
    private val PATH_RE = Regex("^[A-Za-z0-9._/-]{1,200}$")
    private val SHA_RE = Regex("^[a-f0-9]{64}$")

    private val running = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    /** True while a GameActivity is open, so old version folders are not deleted under it. */
    @Volatile
    var gameOpen = false

    /** Called on the main thread when a sync starts and when it ends. Main thread only. */
    var listener: (() -> Unit)? = null

    val isRunning: Boolean get() = running.get()

    /** Starts a background sync if one is due (or [force]). Returns true if a sync was started. */
    fun maybeSync(ctx: Context, force: Boolean = false): Boolean {
        val app = ctx.applicationContext
        val now = System.currentTimeMillis()
        if (!force) {
            val last = Store.prefs(app).getLong(Store.KEY_LAST_ATTEMPT, 0L)
            val interval = if (Store.loadInstalled(app).isEmpty()) EMPTY_RETRY_MS else MIN_INTERVAL_MS
            if (last in 1..now && now - last < interval) return false
        }
        if (!running.compareAndSet(false, true)) return false
        notifyListener()
        val t = Thread({
            try {
                runSync(app)
            } catch (e: Throwable) {
                Log.e(TAG, "sync crashed", e)
                recordError(app, "Sync crashed: $e")
            } finally {
                running.set(false)
                notifyListener()
            }
        }, "ponas-sync")
        t.priority = Thread.MIN_PRIORITY
        t.start()
        return true
    }

    private fun notifyListener() {
        main.post { listener?.invoke() }
    }

    private fun recordError(ctx: Context, msg: String) {
        Log.w(TAG, msg)
        Store.prefs(ctx).edit()
            .putString(Store.KEY_LAST_ERROR, msg)
            .putLong(Store.KEY_LAST_ERROR_AT, System.currentTimeMillis())
            .apply()
    }

    private fun runSync(ctx: Context) {
        val now = System.currentTimeMillis()
        Store.prefs(ctx).edit().putLong(Store.KEY_LAST_ATTEMPT, now).apply()
        cleanup(ctx)

        if (!isOnline(ctx)) {
            recordError(ctx, "Offline: no network connection")
            return
        }

        val manifest: JSONObject
        try {
            val bytes = fetchBytes(URL("$MANIFEST_URL?t=$now"), MAX_MANIFEST_BYTES)
            manifest = JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            recordError(ctx, "Manifest download failed: $e")
            return
        }

        val version = manifest.optLong("version", 0L)
        val list = manifest.optJSONArray("games")
        if (list == null) {
            recordError(ctx, "Manifest has no \"games\" list")
            return
        }

        val installed = Store.loadInstalled(ctx).associateBy { it.id }
        val result = ArrayList<Game>()
        val errors = ArrayList<String>()
        val seen = HashSet<String>()

        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (!ID_RE.matches(id) || !seen.add(id)) {
                errors.add("Skipped invalid or duplicate id '$id'")
                continue
            }
            if (!o.optBoolean("enabled", true)) continue
            val hash = o.optString("hash")
            if (!HASH_RE.matches(hash)) {
                errors.add("$id: invalid hash")
                installed[id]?.let { result.add(it) }
                continue
            }
            val game = Game(
                id = id,
                title = o.optString("title", id),
                color = o.optString("color", "#4CAF50"),
                order = o.optInt("order", 100),
                icon = o.optString("icon", "icon.png"),
                hash = hash,
            )
            val old = installed[id]
            if (old != null && old.hash == hash) {
                result.add(game) // same files; title/colour/order may still have changed
                continue
            }
            try {
                val files = o.optJSONArray("files") ?: throw IOException("no files list")
                installGame(ctx, game, files)
                result.add(game)
                Log.i(TAG, "installed $id @ $hash")
            } catch (e: Exception) {
                errors.add("$id: $e")
                if (old != null) result.add(old) // keep the last good version
            }
        }

        // Games missing from the manifest (or disabled) are not in `result` and get removed.
        Store.saveInstalled(ctx, result)
        cleanup(ctx)

        val edit = Store.prefs(ctx).edit()
            .putLong(Store.KEY_MANIFEST_VERSION, version)
            .putLong(Store.KEY_LAST_OK, System.currentTimeMillis())
        if (errors.isEmpty()) {
            edit.remove(Store.KEY_LAST_ERROR).remove(Store.KEY_LAST_ERROR_AT)
        } else {
            edit.putString(Store.KEY_LAST_ERROR, errors.joinToString("\n"))
                .putLong(Store.KEY_LAST_ERROR_AT, System.currentTimeMillis())
        }
        edit.apply()
    }

    private fun installGame(ctx: Context, game: Game, files: JSONArray) {
        val root = File(Store.gamesRoot(ctx), game.id)
        val finalDir = File(root, game.hash)
        if (File(finalDir, "index.html").isFile) return // already complete from an earlier run

        val tmp = File(root, game.hash + ".tmp")
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
                // ?h= busts the GitHub Pages CDN cache so we never mix old and new files.
                val url = URL(BASE_URL + "games/" + game.id + "/" + path + "?h=" + game.hash)
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

    /** Removes temp folders, versions that are no longer current, and removed games. */
    private fun cleanup(ctx: Context) {
        val root = Store.gamesRoot(ctx)
        val dirs = root.listFiles() ?: return
        val current = Store.loadInstalled(ctx).associate { it.id to it.hash }
        val safe = !gameOpen
        for (gameDir in dirs) {
            val keep = current[gameDir.name]
            if (keep == null) {
                if (safe) gameDir.deleteRecursively()
                continue
            }
            for (v in gameDir.listFiles() ?: emptyArray()) {
                if (v.name.endsWith(".tmp")) {
                    v.deleteRecursively()
                } else if (v.name != keep && safe) {
                    v.deleteRecursively()
                }
            }
        }
    }

    private fun isOnline(ctx: Context): Boolean {
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
