package com.docunest.dock

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.StatFs
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Offline POI packs (owner 2026-11-16: "POI now, packs later" — maps and routing come later).
 * One small SQLite file per country, built monthly from OpenStreetMap by GitHub Actions
 * (.github/workflows/dock-poi.yml → release "dock-poi" + poi-manifest.json), downloaded only over
 * Starlink Wi-Fi (unmetered) unless the owner says "now, on this network".
 *
 * Downloads resume where they stopped (HTTP Range) — the car turns off mid-download all the time —
 * and are checked against the manifest's SHA-256 before the old pack is replaced.
 * On Starlink the dock looks for newer packs once a week and updates the installed ones by itself.
 */
object OfflinePacks {
    val COUNTRIES = linkedMapOf(
        "es" to "🇪🇸 Spain", "pt" to "🇵🇹 Portugal", "fr" to "🇫🇷 France", "it" to "🇮🇹 Italy", "de" to "🇩🇪 Germany",
    )
    private const val BASE = "https://github.com/${BuildConfig.UPDATE_REPO}/releases/download/dock-poi/"
    private const val CHECK_EVERY_MS = 7 * 24 * 3600_000L

    data class Pack(val cc: String, val file: String, val bytes: Long, val sqliteBytes: Long, val sha256: String,
                    val count: Int, val sourceDate: String)
    data class Installed(val sha256: String, val sourceDate: String, val count: Int, val bytes: Long, val at: Long)

    @Volatile var manifest: Map<String, Pack>? = null
        private set
    @Volatile var status = "Not checked yet"
        private set
    @Volatile var busy = false
        private set
    /** Country being downloaded and how far (0..1). */
    @Volatile var progress: Pair<String, Float>? = null
        private set
    /** Owner said "download now on this network" — until this run ends. */
    @Volatile private var anyNetwork = false

    private val listeners = mutableSetOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }
    private fun changed() {
        val ls = synchronized(listeners) { listeners.toList() }
        main.post { ls.forEach { runCatching { it() } } }
    }
    private fun say(s: String) {
        status = s
        DebugLog.log("OFFLINE", s)
        changed()
    }

    private val worker by lazy { Handler(HandlerThread("offline-data").apply { start() }.looper) }

    private fun sp(ctx: Context) = ctx.getSharedPreferences("offline", Context.MODE_PRIVATE)
    fun dir(ctx: Context) = File(ctx.filesDir, "offline").apply { mkdirs() }
    fun dbFile(ctx: Context, cc: String) = File(dir(ctx), "poi-$cc.sqlite")

    fun wanted(ctx: Context): Set<String> = sp(ctx).getStringSet("wanted", null) ?: COUNTRIES.keys
    fun setWanted(ctx: Context, cc: String, on: Boolean) {
        val now = wanted(ctx).toMutableSet().apply { if (on) add(cc) else remove(cc) }
        sp(ctx).edit().putStringSet("wanted", now).apply()
        changed()
    }
    /** An install was asked for and isn't finished (survives car off). */
    fun queued(ctx: Context) = sp(ctx).getBoolean("queued", false)

    fun installed(ctx: Context): Map<String, Installed> = COUNTRIES.keys.mapNotNull { cc ->
        val j = sp(ctx).getString("installed.$cc", null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@mapNotNull null
        if (!dbFile(ctx, cc).exists()) return@mapNotNull null
        cc to Installed(j.optString("sha256"), j.optString("sourceDate"), j.optInt("count"), j.optLong("bytes"), j.optLong("at"))
    }.toMap()

    fun ssid(ctx: Context): String? = WifiDoctor.ssid ?: runCatching {
        @Suppress("DEPRECATION")
        (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo?.ssid
            ?.trim('"')?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
    }.getOrNull()

    fun onStarlink(ctx: Context) = WifiPrefer.unmetered(ssid(ctx))
    private fun allowed(ctx: Context) = anyNetwork || onStarlink(ctx)

    /** Settings button. [now] = don't wait for Starlink (uses whatever network the car is on). */
    fun install(ctx: Context, now: Boolean = false) {
        val app = ctx.applicationContext
        sp(app).edit().putBoolean("queued", true).apply()
        if (now) anyNetwork = true
        if (!allowed(app)) say("Waiting for Starlink Wi-Fi — starts by itself when the car is on it")
        else worker.post { run(app) }
    }

    fun checkNow() {
        worker.post { runCatching { fetchManifest() }.onFailure { say("Can't read the pack list: ${it.message}") } }
    }

    fun remove(ctx: Context, cc: String) {
        val app = ctx.applicationContext
        worker.post {
            OfflinePois.close(cc)
            dbFile(app, cc).delete()
            File(dir(app), "poi-$cc.sqlite.gz.part").delete()
            sp(app).edit().remove("installed.$cc").apply()
            say("${COUNTRIES[cc]} removed")
        }
    }

    /** Every few minutes from the dock service: resume a queued install, weekly update check on Starlink. */
    fun tick(ctx: Context) {
        val app = ctx.applicationContext
        if (busy || !onStarlink(app)) return
        val s = sp(app)
        if (s.getBoolean("queued", false)) { worker.post { run(app) }; return }
        if (installed(app).isEmpty() || System.currentTimeMillis() - s.getLong("checkedAt", 0) < CHECK_EVERY_MS) return
        worker.post {
            val m = runCatching { fetchManifest() }.getOrNull() ?: return@post
            s.edit().putLong("checkedAt", System.currentTimeMillis()).apply()
            val stale = installed(app).filter { (cc, i) -> m[cc] != null && m[cc]!!.sha256 != i.sha256 }.keys
            if (stale.isNotEmpty()) {
                DebugLog.log("OFFLINE", "newer packs for ${stale.joinToString()} — updating on Starlink")
                s.edit().putBoolean("queued", true).apply()
                run(app)
            }
        }
    }

    private fun fetchManifest(): Map<String, Pack> {
        val c = URL(BASE + "poi-manifest.json").openConnection() as HttpURLConnection
        c.connectTimeout = 20_000; c.readTimeout = 30_000
        try {
            if (c.responseCode == 404) throw IOException("packs not built yet (GitHub release dock-poi missing)")
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val packs = JSONObject(c.inputStream.bufferedReader().readText()).getJSONObject("packs")
            val out = packs.keys().asSequence().mapNotNull { cc ->
                val p = packs.getJSONObject(cc)
                cc to Pack(cc, p.getString("file"), p.getLong("bytes"), p.optLong("sqliteBytes"), p.getString("sha256"),
                    p.optInt("count"), p.optString("sourceDate"))
            }.toMap()
            manifest = out
            changed()
            return out
        } finally { c.disconnect() }
    }

    private class Paused : Exception()

    private fun run(app: Context) {
        if (busy) return
        busy = true
        changed()
        try {
            val m = fetchManifest()
            val have = installed(app)
            val todo = wanted(app).filter { cc -> m[cc] != null && have[cc]?.sha256 != m[cc]!!.sha256 }
            val missing = wanted(app).filter { m[it] == null }
            for (cc in COUNTRIES.keys.filter { it in todo }) {
                if (!allowed(app)) throw Paused()
                installOne(app, m.getValue(cc))
            }
            sp(app).edit().putBoolean("queued", false).putLong("checkedAt", System.currentTimeMillis()).apply()
            say(when {
                todo.isEmpty() && missing.isEmpty() -> "All up to date ✓"
                missing.isNotEmpty() -> "Done ✓ — not built yet: ${missing.joinToString { COUNTRIES[it] ?: it }}"
                else -> "Installed ✓ ${todo.joinToString { COUNTRIES[it] ?: it }}"
            })
        } catch (e: Paused) {
            say("Paused — off Starlink. Continues by itself on Starlink Wi-Fi")
        } catch (e: Exception) {
            say("Stopped: ${e.message ?: e.javaClass.simpleName} — retries on Starlink in a few minutes")
        } finally {
            busy = false
            anyNetwork = false
            progress = null
            changed()
        }
    }

    private fun installOne(app: Context, p: Pack) {
        val name = COUNTRIES[p.cc] ?: p.cc
        val free = StatFs(app.filesDir.path).availableBytes
        if (free < p.bytes + p.sqliteBytes + 50_000_000L) throw IOException("not enough space for $name (${free / 1_000_000} MB free)")
        val part = File(dir(app), "poi-${p.cc}.sqlite.gz.part")
        val partFor = File(dir(app), "poi-${p.cc}.part-for")
        if (!partFor.exists() || partFor.readText() != p.sha256) { part.delete(); partFor.writeText(p.sha256) }

        var tries = 0
        while (part.length() < p.bytes) {
            if (!allowed(app)) throw Paused()
            try {
                download(app, p, part)
            } catch (e: Paused) { throw e } catch (e: IOException) {
                if (++tries >= 5) throw IOException("$name: ${e.message}")
                DebugLog.log("OFFLINE", "$name download broke (${e.message}) at ${part.length() / 1_000_000} MB — resuming")
                Thread.sleep(5_000L * tries)
            }
        }
        say("$name: checking…")
        if (sha256(part) != p.sha256) { part.delete(); throw IOException("$name: download damaged — will download again") }
        val tmp = File(dir(app), "poi-${p.cc}.sqlite.tmp")
        GZIPInputStream(part.inputStream().buffered(1 shl 16)).use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
        val count = SQLiteDatabase.openDatabase(tmp.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT count(*) FROM poi", null).use { c -> c.moveToFirst(); c.getInt(0) }
        }
        if (p.count > 0 && count != p.count) { tmp.delete(); throw IOException("$name: pack has $count places, expected ${p.count}") }
        OfflinePois.close(p.cc)
        if (!tmp.renameTo(dbFile(app, p.cc))) throw IOException("$name: can't replace the old pack")
        part.delete(); partFor.delete()
        sp(app).edit().putString("installed.${p.cc}", JSONObject()
            .put("sha256", p.sha256).put("sourceDate", p.sourceDate).put("count", count)
            .put("bytes", dbFile(app, p.cc).length()).put("at", System.currentTimeMillis()).toString()).apply()
        DebugLog.log("OFFLINE", "$name installed: $count places, data from ${p.sourceDate}")
    }

    private fun download(app: Context, p: Pack, part: File) {
        val name = COUNTRIES[p.cc] ?: p.cc
        val from = part.length()
        val c = URL(BASE + p.file).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000; c.readTimeout = 30_000
        if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
        try {
            val code = c.responseCode
            val append = code == 206
            if (code == 416 && from >= p.bytes) return
            if (code != 200 && code != 206) throw IOException("HTTP $code")
            say(if (append) "$name: resuming at ${from / 1_000_000} of ${p.bytes / 1_000_000} MB" else "$name: downloading ${p.bytes / 1_000_000} MB")
            RandomAccessFile(part, "rw").use { out ->
                if (append) out.seek(from) else out.setLength(0)
                val buf = ByteArray(1 shl 16)
                var done = if (append) from else 0L
                var lastNote = 0L
                c.inputStream.use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastNote > 512 * 1024) {
                            lastNote = done
                            progress = p.cc to (done.toFloat() / p.bytes).coerceAtMost(1f)
                            changed()
                            if (!allowed(app)) throw Paused()
                        }
                    }
                }
            }
        } finally { c.disconnect() }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
