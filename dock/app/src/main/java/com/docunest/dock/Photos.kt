package com.docunest.dock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * One photo per place (MapPal `/api/dock/items/<id>/photo`: Wikipedia when
 * there's an article that is the place, else Google's), kept as a file for
 * [KEEP_MS] — 30 days, the owner's call — so the slide menu and list show it
 * instantly and offline, never re-downloaded on every look. "No photo" is
 * remembered for [MISS_MS]. Fetched ahead of time for the places up front.
 */
object Photos {
    const val KEEP_MS = 30L * 24 * 60 * 60_000
    const val MISS_MS = 7L * 24 * 60 * 60_000
    private const val MAX_PX = 360

    private val memory = LruCache<String, Bitmap>(40)
    private val pending = mutableSetOf<String>()
    private val failedAt = HashMap<String, Long>()
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** The photo if we have a fresh one; otherwise starts fetching it and returns null. */
    fun get(context: Context, item: Item): Bitmap? {
        if (!item.hasPoint || item.pending) return null
        memory.get(item.id)?.let { return it }
        val f = file(context, item.id, "jpg")
        if (f.exists() && fresh(f, KEEP_MS)) {
            BitmapFactory.decodeFile(f.path)?.let { memory.put(item.id, it); return it }
        }
        fetch(context.applicationContext, item.id)
        return null
    }

    /** "Wikipedia (pt) · Praia do Camilo" / "Google Maps · <author>" — shown with the photo. */
    fun credit(context: Context, item: Item): String? =
        runCatching { file(context, item.id, "credit").readText() }.getOrNull()?.takeIf { it.isNotBlank() }

    fun prefetch(context: Context, items: List<Item>) {
        for (i in items) if (i.hasPoint && !i.pending && memory.get(i.id) == null) {
            val f = file(context, i.id, "jpg")
            if (!(f.exists() && fresh(f, KEEP_MS))) fetch(context.applicationContext, i.id)
        }
    }

    private fun fresh(f: File, ms: Long) = System.currentTimeMillis() - f.lastModified() < ms

    private fun fetch(context: Context, id: String) {
        val none = file(context, id, "none")
        if (none.exists() && fresh(none, MISS_MS)) return
        synchronized(pending) {
            if (id in pending) return
            if ((failedAt[id] ?: 0L) > System.currentTimeMillis() - 30 * 60_000L) return // no signal / server: later
            pending += id
        }
        worker.execute {
            val got = runCatching { download(context, id) }
            synchronized(pending) {
                pending -= id
                if (got.isFailure) failedAt[id] = System.currentTimeMillis()
            }
            if (got.getOrNull() == true) main.post { Store.changed() }
        }
    }

    /** true = new photo saved; false = server has none (remembered); throws = try again later. */
    private fun download(context: Context, id: String): Boolean {
        val prefs = Prefs(context)
        val c = URL(prefs.serverUrl.trimEnd('/') + "/api/dock/items/$id/photo").openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", "Bearer ${prefs.token}")
        c.connectTimeout = 10_000
        c.readTimeout = 40_000 // the server may look in six Wikipedias, then Google
        try {
            when (c.responseCode) {
                200 -> {
                    val raw = c.inputStream.use { BitmapFactory.decodeStream(it) } ?: return false
                    val scale = MAX_PX.toFloat() / maxOf(raw.width, raw.height)
                    val bmp = if (scale < 1f) Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt(), (raw.height * scale).toInt(), true) else raw
                    val dir = File(context.filesDir, "photos").apply { mkdirs() }
                    val tmp = File(dir, "$id.jpg.tmp")
                    tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                    tmp.renameTo(file(context, id, "jpg"))
                    val credit = c.getHeaderField("X-Photo-Credit")?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() } ?: ""
                    file(context, id, "credit").writeText(credit)
                    file(context, id, "none").delete()
                    memory.put(id, bmp)
                    return true
                }
                // Only the server's explicit "looked, there is none" — a plain 404 (server not
                // updated yet, item gone) is retried later instead of remembered for a week.
                404 -> {
                    if (c.getHeaderField("X-Photo-Source") != "none") throw java.io.IOException("photo 404 (no answer)")
                    File(context.filesDir, "photos").mkdirs()
                    file(context, id, "none").writeText("")
                    return false
                }
                else -> throw java.io.IOException("photo ${c.responseCode}")
            }
        } finally {
            c.disconnect()
        }
    }

    private fun file(context: Context, id: String, ext: String) = File(context.filesDir, "photos/$id.$ext")
}
