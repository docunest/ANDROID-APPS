package com.docunest.dock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.location.Location
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Police ahead (owner 2026-11-17: "Add police alert. Like big red flashing outline with glow …
 * Waze default is not enough to catch eyes if focused on road. I need something flashy! But don't
 * cover maps").
 *
 * Source: Waze's own DEBUG log (/data/data/com.waze/spdlog.logdata, switched on 2026-11-15 —
 * monorepo docs/ksw/docs/waze-debug-log.md). Waze logs every community report it receives as an
 * `add_alert_action { realtime_alert { … type: POLICE … position { lon_times_1000000 … } … } }`
 * block. Read through root every 2 s (only the new bytes), parsed here, deleted from memory after
 * 90 min. Nothing leaves the car except the dock's own debug log lines.
 */
object Police {
    data class Report(
        val id: String, val lat: Double, val lng: Double, val wazeOnRoute: Boolean,
        val street: String?, val city: String?, val thumbs: Int, val seenAt: Long,
    ) {
        @Volatile var done = false
        @Volatile var lastAheadM = Double.MAX_VALUE
    }

    private const val LOG = "/data/data/com.waze/spdlog.logdata"
    private const val KEEP_MS = 90 * 60_000L

    val reports = java.util.concurrent.ConcurrentHashMap<String, Report>()
    /** The report flashing now and metres to it (null = nothing ahead). */
    @Volatile var active: Pair<Report, Double>? = null
        private set
    @Volatile var debugLog: Boolean? = null
        private set
    @Volatile var status = "Not started"
        private set

    private val worker by lazy { Handler(HandlerThread("waze-log").apply { start() }.looper) }
    @Volatile private var running = false
    private var offset = -1L
    private var carry = ""
    private val block = StringBuilder()
    private var depth = 0
    private var rawLogged = 0
    private var lastCheckAt = 0L

    fun start(ctx: Context) {
        if (running) return
        running = true
        val app = ctx.applicationContext
        worker.post(object : Runnable {
            override fun run() {
                if (!running) return
                runCatching { poll(app) }.onFailure { status = "Can't read Waze's log: ${it.message}" }
                worker.postDelayed(this, 2000)
            }
        })
    }

    fun stop() { running = false }

    // --- Reading Waze's log ---------------------------------------------------------------------

    private fun poll(app: Context) {
        if (!Apps.installed(app, Apps.WAZE)) { status = "Waze not installed"; return }
        val now = SystemClock.elapsedRealtime()
        if (now - lastCheckAt > 30 * 60_000L || lastCheckAt == 0L) { lastCheckAt = now; checkDebug() }
        reports.values.removeIf { System.currentTimeMillis() - it.seenAt > KEEP_MS }
        val off = offset
        val cmd = if (off < 0) "stat -c %s $LOG"
        else "s=\$(stat -c %s $LOG); echo \"@@\$s\"; if [ \"\$s\" -gt $off ]; then n=\$((s-$off)); [ \$n -gt 1048576 ] && n=0; tail -c +${off + 1} $LOG | head -c \$n; fi"
        val out = Root.output(cmd) ?: run { status = "Can't read Waze's log (root)"; return }
        if (off < 0) {
            // Start at the end: only reports from now on.
            offset = out.trim().toLongOrNull() ?: return
            status = "Watching Waze for police reports"
            DebugLog.log("POLICE", "watching Waze's log from byte $offset (detail log ${debugLog ?: "?"})")
            return
        }
        val nl = out.indexOf('\n')
        val size = out.substring(0, if (nl < 0) out.length else nl).removePrefix("@@").trim().toLongOrNull() ?: return
        if (size < off) { offset = 0; carry = ""; return } // Waze rotated the file: read the new one from the top
        if (size - off > 1_048_576) { offset = size; carry = ""; return } // too far behind: skip ahead
        offset = size
        if (nl < 0) return
        val text = carry + out.substring(nl + 1)
        val lines = text.split('\n')
        carry = lines.last()
        for (i in 0 until lines.size - 1) line(lines[i])
    }

    internal fun line(l: String) {
        if (depth == 0) {
            val k = l.indexOf("add_alert_action {")
            if (k < 0) return
            block.setLength(0)
            val tail = l.substring(k)
            block.append(tail).append('\n')
            depth = tail.count { it == '{' } - tail.count { it == '}' }
            if (depth <= 0) { depth = 0; parse(block.toString()) }
            return
        }
        block.append(l).append('\n')
        depth += l.count { it == '{' } - l.count { it == '}' }
        if (depth <= 0 || block.length > 20_000) { depth = 0; parse(block.toString()) }
    }

    private fun num(b: String, key: String) = Regex("""\b$key:\s*(-?\d+)""").find(b)?.groupValues?.get(1)?.toLongOrNull()
    private fun str(b: String, key: String) = Regex("""\b$key:\s*"([^"]*)"""").find(b)?.groupValues?.get(1)

    private fun parse(b: String) {
        if (!Regex("""\btype:\s*POLICE""").containsMatchIn(b)) return
        if (rawLogged < 3) { rawLogged++; DebugLog.log("POLICE", "raw report: " + b.take(1500).replace('\n', ' ')) }
        val lat = num(b, "lat_times_1000000") ?: return
        val lng = num(b, "lon_times_1000000") ?: return
        val id = num(b, "id")?.toString() ?: "$lat,$lng"
        val reportS = num(b, "report_time")
        if (reportS != null && reportS > 1_000_000_000L && System.currentTimeMillis() / 1000 - reportS > 3 * 3600) return // stale
        if (reports.containsKey(id)) return
        val r = Report(id, lat / 1e6, lng / 1e6, Regex("""\bon_route:\s*true""").containsMatchIn(b),
            str(b, "street"), str(b, "city"), num(b, "thumbs_up_count")?.toInt() ?: 0, System.currentTimeMillis())
        reports[id] = r
        DebugLog.log("POLICE", "report ${r.id} at %.5f,%.5f %s%s (Waze on route: ${r.wazeOnRoute})".format(r.lat, r.lng, r.street ?: "", r.city?.let { ", $it" } ?: ""))
    }

    /** Waze's DEBUG log level (the police reports are only logged with it). */
    private fun checkDebug() {
        val v = Root.output("sqlite3 /data/data/com.waze/waze_config.db \"select value from config_values where category='Logger' and name='Default log level'\" 2>&1")?.trim()
        debugLog = v?.let { it.equals("DEBUG", ignoreCase = true) }
        DebugLog.change("POLICE", "waze log level", v ?: "unreadable")
    }

    /** Settings button: Waze's log level back to DEBUG (Waze stops for a few seconds; navigation resumes). */
    fun enableDebug(): Boolean {
        val ok = Root.run(
            "am force-stop com.waze",
            "cd /data/data/com.waze",
            "sqlite3 waze_config.db \"update config_values set value='1' where category='General' and name='Log level'; " +
                "update config_values set value='DEBUG' where category='Logger' and name='Default log level';\"",
            "chown \$(stat -c %u:%g /data/data/com.waze) waze_config.db*",
            "restorecon waze_config.db* 2>/dev/null",
            "monkey -p com.waze -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1",
            "true",
        )
        lastCheckAt = 0L
        offset = -1L
        return ok
    }

    // --- Is one ahead? ---------------------------------------------------------------------------

    /** Every second: the nearest report ahead within warning distance (along the route when on it, else in the heading's cone). */
    fun evaluate(here: Location?) {
        if (here == null || reports.isEmpty()) { active = null; return }
        val speed = if (here.hasSpeed()) here.speed else 0f
        val warnM = (speed * 45.0).coerceIn(700.0, 2500.0) // ~45 s ahead: 1.4 km at 110 km/h
        val r = Trip.route
        val p = Trip.progress(here.latitude, here.longitude)?.takeIf { r != null && !r.straight && it.offM < 300 }
        var best: Pair<Report, Double>? = null
        for (rep in reports.values) {
            if (rep.done) continue
            val d = RouteGeo.distM(here.latitude, here.longitude, rep.lat, rep.lng)
            val aheadM: Double? = if (p != null && r != null) {
                val pr = RouteGeo.project(r.lat, r.lng, r.cum, rep.lat, rep.lng)
                if (pr.off < 150) pr.along - p.alongM else null
            } else if (here.hasBearing() && speed > 2f) {
                val b = bearing(here.latitude, here.longitude, rep.lat, rep.lng)
                val diff = abs(((b - here.bearing + 540f) % 360f) - 180f)
                if (diff <= 35f || d < 120) d else null
            } else null
            // Passed it (behind, or getting further after having been close): never again.
            if (rep.lastAheadM < 400 && (aheadM == null && d > 150 || (aheadM != null && aheadM < -60))) rep.done = true
            if (aheadM != null) rep.lastAheadM = aheadM
            if (rep.done || aheadM == null || aheadM < -40 || aheadM > warnM) continue
            if (best == null || aheadM < best.second) best = rep to aheadM
        }
        if (best != null && active?.first?.id != best.first.id) {
            DebugLog.log("POLICE", "FLASH: police ${best.second.toInt()} m ahead (${best.first.street ?: "?"})")
        }
        active = best
    }

    private fun bearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val x = (lng2 - lng1) * cos(Math.toRadians((lat1 + lat2) / 2))
        return ((Math.toDegrees(atan2(x, lat2 - lat1)) + 360) % 360).toFloat()
    }
}

/**
 * The flash: a red / blue glow along all four screen edges, pulsing, plus a small "🚓 POLICE 650 m"
 * tag at the top centre. Touch passes straight through; the middle of the screen (Waze's map) stays clear.
 */
class PoliceFlash(private val ctx: Context, private val wm: WindowManager) {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = Prefs(ctx)
    private var view: FlashView? = null
    private var testUntil = 0L

    private val tick = object : Runnable {
        override fun run() {
            val here = Trip.here(ctx)
            Police.evaluate(here)
            val a = Police.active
            val testing = SystemClock.elapsedRealtime() < testUntil
            when {
                testing -> show("🚓 POLICE · 650 m  (test)")
                a != null && prefs.policeFlash -> show("🚓 POLICE · " + km(a.second.coerceAtLeast(0.0).toFloat()) +
                    (a.first.street?.let { " · $it" } ?: ""))
                else -> hide()
            }
            main.postDelayed(this, 1000)
        }
    }

    fun start() { main.removeCallbacks(tick); main.post(tick) }
    fun stop() { main.removeCallbacks(tick); hide() }
    fun test() { testUntil = SystemClock.elapsedRealtime() + 6000; main.removeCallbacks(tick); main.post(tick) }

    private fun show(text: String) {
        view?.let { it.text = text; return }
        val v = FlashView(ctx).also { it.text = text }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        if (runCatching { wm.addView(v, params) }.isSuccess) view = v
        else DebugLog.log("POLICE", "can't show the flash (overlay permission?)")
    }

    private fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    private class FlashView(ctx: Context) : View(ctx) {
        private val d = ctx.resources.displayMetrics.density
        var text = ""
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tagBg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tagText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 22 * d; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
        private val tagEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d; color = Color.WHITE }
        private val rect = RectF()
        private val start = SystemClock.elapsedRealtime()

        override fun onDraw(c: Canvas) {
            val t = (SystemClock.elapsedRealtime() - start) / 1000.0
            // Red / blue every half second (police lights), each one pulsing bright → dim.
            val red = ((t * 2).toInt() % 2) == 0
            val pulse = 0.55 + 0.45 * (0.5 + 0.5 * sin(t * 4 * Math.PI))
            val base = if (red) Color.rgb(255, 30, 30) else Color.rgb(40, 100, 255)
            val a = (255 * pulse).toInt()
            val w = width.toFloat(); val h = height.toFloat()
            val g = 70 * d // glow depth: edges only, the map stays clear
            val col = Color.argb(a, Color.red(base), Color.green(base), Color.blue(base))
            val clear = Color.argb(0, Color.red(base), Color.green(base), Color.blue(base))
            glow.shader = LinearGradient(0f, 0f, 0f, g, col, clear, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w, g, glow)
            glow.shader = LinearGradient(0f, h, 0f, h - g, col, clear, Shader.TileMode.CLAMP); c.drawRect(0f, h - g, w, h, glow)
            glow.shader = LinearGradient(0f, 0f, g, 0f, col, clear, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, g, h, glow)
            glow.shader = LinearGradient(w, 0f, w - g, 0f, col, clear, Shader.TileMode.CLAMP); c.drawRect(w - g, 0f, w, h, glow)
            edge.strokeWidth = 12 * d
            edge.color = Color.argb(a, Color.red(base), Color.green(base), Color.blue(base))
            c.drawRect(6 * d, 6 * d, w - 6 * d, h - 6 * d, edge)
            // The tag, top centre (small: Waze's own panels are top-left and bottom).
            val tw = tagText.measureText(text) + 36 * d
            rect.set(w / 2 - tw / 2, 14 * d, w / 2 + tw / 2, 14 * d + 50 * d)
            tagBg.color = Color.argb(235, Color.red(base), Color.green(base), Color.blue(base))
            c.drawRoundRect(rect, 25 * d, 25 * d, tagBg)
            c.drawRoundRect(rect, 25 * d, 25 * d, tagEdge)
            c.drawText(text, w / 2, rect.centerY() + tagText.textSize / 3, tagText)
            postInvalidateDelayed(50) // ~20 fps: smooth enough, light on the head unit
        }
    }
}
