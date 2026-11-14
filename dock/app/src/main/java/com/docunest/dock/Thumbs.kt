package com.docunest.dock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * A small map of the area around each place (OpenStreetMap, ~3.7 km across,
 * pin in the middle), so places are recognised by where they are — coast,
 * river, town — not only by name. Made once per place from up to four map
 * tiles and kept as a file, so the slide menu shows them with no signal.
 * Light use of OSM's tile server: same User-Agent as the dock map, ≤ 4
 * tiles per place, never re-fetched.
 */
object Thumbs {
    const val ZOOM = 13
    private const val SIZE = 256

    /** Up to 8 MB of decoded pictures in memory (by size, not count); the files keep the rest. */
    private val memory = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pending = mutableSetOf<String>()
    private val failedAt = HashMap<String, Long>()
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** The thumbnail if made already (memory or file); otherwise starts making it and returns null. */
    fun get(context: Context, item: Item): Bitmap? {
        if (!item.hasPoint) return null
        val key = key(item.lat!!, item.lng!!)
        memory.get(key)?.let { return it }
        val f = file(context, key)
        if (f.exists()) {
            BitmapFactory.decodeFile(f.path)?.let { memory.put(key, it); return it }
        }
        make(context.applicationContext, key, item.lat, item.lng)
        return null
    }

    /** Make the ones the slide menu / list will need, ahead of time (while there's signal). */
    fun prefetch(context: Context, items: List<Item>) {
        for (i in items) if (i.hasPoint) {
            val key = key(i.lat!!, i.lng!!)
            if (memory.get(key) == null && !file(context, key).exists()) make(context.applicationContext, key, i.lat, i.lng)
        }
    }

    private fun make(context: Context, key: String, lat: Double, lng: Double) {
        synchronized(pending) {
            if (key in pending) return
            // No signal: try again in a few minutes, not on every redraw.
            if ((failedAt[key] ?: 0L) > System.currentTimeMillis() - 5 * 60_000L) return
            pending += key
        }
        worker.execute {
            val bmp = runCatching { render(context, lat, lng) }.getOrNull()
            synchronized(pending) {
                pending -= key
                if (bmp == null) failedAt[key] = System.currentTimeMillis()
            }
            if (bmp != null) {
                runCatching {
                    val f = file(context, key)
                    f.parentFile?.mkdirs()
                    val tmp = File(f.path + ".tmp")
                    tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    tmp.renameTo(f)
                }
                memory.put(key, bmp)
                main.post { Store.changed() } // redraw lists / menu with the picture
            }
        }
    }

    private fun render(context: Context, lat: Double, lng: Double): Bitmap? {
        val (gx, gy) = TileMath.pixel(lat, lng, ZOOM)
        val left = gx - SIZE / 2.0
        val top = gy - SIZE / 2.0
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.parseColor("#E5E3DF"))
        for ((tx, ty) in TileMath.tiles(gx, gy, SIZE, ZOOM)) {
            val tile = fetch(context, ZOOM, tx, ty) ?: return null
            c.drawBitmap(tile, (tx * 256 - left).toFloat(), (ty * 256 - top).toFloat(), null)
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = SIZE / 16f
        p.color = Color.WHITE
        c.drawCircle(SIZE / 2f, SIZE / 2f, r + 4, p)
        p.color = Color.parseColor("#DC2626")
        c.drawCircle(SIZE / 2f, SIZE / 2f, r, p)
        return out
    }

    private fun fetch(context: Context, z: Int, x: Int, y: Int): Bitmap? {
        val n = 1 shl z
        val c = URL("https://tile.openstreetmap.org/$z/${Math.floorMod(x, n)}/$y.png").openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", context.packageName)
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        return try {
            if (c.responseCode != 200) null else c.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            c.disconnect()
        }
    }

    private fun file(context: Context, key: String) = File(context.filesDir, "thumbs/$key.png")

    /** One file per spot (not per item): a re-sent place reuses it; a moved pin gets a new one. */
    fun key(lat: Double, lng: Double) = String.format(Locale.US, "z%d_%.4f_%.4f", ZOOM, lat, lng)
}

/** Map-tile maths for [Thumbs] — pure, unit-tested. */
object TileMath {
    /** Web-Mercator global pixel (256-px tiles) of a point at zoom [z]. */
    fun pixel(lat: Double, lng: Double, z: Int): Pair<Double, Double> {
        val n = 256.0 * (1 shl z)
        val x = (lng + 180.0) / 360.0 * n
        val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        val y = (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * n
        return x to y
    }

    /** The tiles a [size]-px square centred on global pixel (gx, gy) needs (1, 2 or 4). */
    fun tiles(gx: Double, gy: Double, size: Int, z: Int): List<Pair<Int, Int>> {
        val half = size / 2.0
        val maxY = (1 shl z) - 1
        val xs = floor((gx - half) / 256).toInt()..floor((gx + half - 1) / 256).toInt()
        val ys = floor((gy - half) / 256).toInt().coerceAtLeast(0)..floor((gy + half - 1) / 256).toInt().coerceAtMost(maxY)
        return xs.flatMap { x -> ys.map { y -> x to y } }
    }
}
