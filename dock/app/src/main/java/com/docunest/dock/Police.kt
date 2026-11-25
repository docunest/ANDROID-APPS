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
    /**
     * What the overlay shows: [onRoute] = on the route ahead ([m] along it) → the full flash; otherwise
     * nearby ([m] straight line) → a soft glow on the side it's on, with an arrow ([relDeg]: 0 = ahead,
     * 90 = right, -90 = left, 180 = behind; null = no heading, so no arrow).
     */
    data class Hit(val report: Report, val m: Double, val onRoute: Boolean, val relDeg: Float?)
    @Volatile var active: Hit? = null
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
        WazeTap.enabled = Prefs(app).wazeRecord
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
        for (i in 0 until lines.size - 1) {
            val inReport = depth > 0 || lines[i].contains("add_alert_action {")
            line(lines[i])
            runCatching { WazeLinkCheck.line(lines[i]) }
            if (!inReport) runCatching { WazeTap.line(lines[i]) }
        }
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
        Regex("""\btype:\s*([A-Z_]+)""").find(b)?.groupValues?.get(1)?.let { runCatching { WazeTap.alert(it, b) } }
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

    /**
     * Every second. Owner 2026-11-25: "it alerts only if I'm looking straight at the police … I need
     * something that goes either on my route or then shows proximity or the direction".
     *  - **On the route**: along the dock's route (≤ 300 m off it), or — with no dock route — a report
     *    Waze marks as on its route and roughly ahead (±100°). Full flash from ~90 s before (1.5–4 km)
     *    until passed.
     *  - **Nearby**, any direction: within 400 m in town (< 50 km/h), 800 m faster. Soft glow on the
     *    side it's on + an arrow; "5 blocks away" doesn't get the full flash any more.
     */
    fun evaluate(here: Location?) {
        if (here == null || reports.isEmpty()) { active = null; return }
        val speed = if (here.hasSpeed()) here.speed else 0f
        val routeWarnM = (speed * 90.0).coerceIn(1500.0, 4000.0)
        val nearM = if (speed * 3.6f < 50f) 400.0 else 800.0
        val r = Trip.route
        val p = Trip.progress(here.latitude, here.longitude)?.takeIf { r != null && !r.straight && it.offM < 300 }
        val heading = if (here.hasBearing() && speed > 1.5f) here.bearing else null
        var route: Hit? = null
        var near: Hit? = null
        for (rep in reports.values) {
            if (rep.done) continue
            val d = RouteGeo.distM(here.latitude, here.longitude, rep.lat, rep.lng)
            val rel = heading?.let { h -> ((bearing(here.latitude, here.longitude, rep.lat, rep.lng) - h + 540f) % 360f) - 180f }
            val along: Double? = when {
                p != null && r != null -> RouteGeo.project(r.lat, r.lng, r.cum, rep.lat, rep.lng).let { pr -> if (pr.off < 300) pr.along - p.alongM else null }
                rep.wazeOnRoute && rel != null && abs(rel) <= 100f -> d
                else -> null
            }
            if (along != null) {
                // Passed it on the route: never again.
                if (rep.lastAheadM < 600 && along < -150) { rep.done = true; continue }
                rep.lastAheadM = along
                if (along >= -100 && along <= routeWarnM && (route == null || along < route.m)) route = Hit(rep, along, true, rel)
                if (along >= -100 && along <= routeWarnM) continue
            }
            if (d <= nearM && (near == null || d < near.m)) near = Hit(rep, d, false, rel)
        }
        val best = route ?: near
        if (best != null && (active?.report?.id != best.report.id || active?.onRoute != best.onRoute)) {
            DebugLog.log("POLICE", (if (best.onRoute) "FLASH (on the route)" else "NEARBY") + ": ${best.m.toInt()} m " +
                (best.relDeg?.let { "at ${it.toInt()}° " } ?: "") + "(${best.report.street ?: "?"}), ${(speed * 3.6f).toInt()} km/h, " +
                if (p != null) "dock route" else if (best.report.wazeOnRoute) "Waze says on its route" else "no route")
        }
        active = best
    }

    private fun bearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val x = (lng2 - lng1) * cos(Math.toRadians((lat1 + lat2) / 2))
        return ((Math.toDegrees(atan2(x, lat2 - lat1)) + 360) % 360).toFloat()
    }
}

/**
 * The overlay over Waze, touch-through, the middle of the screen clear:
 *  - police on the route: red / blue glow pulsing on all four edges (thin line, owner 2026-11-25:
 *    "the outline is a bit too much") + "🚓 POLICE · 650 m · street";
 *  - police nearby, off the route: a soft red glow only on the side it's on + "🚓 400 m" with an arrow;
 *  - in an average speed section ([AvgZone]): a steady glow — green under the limit, amber close, red
 *    over — and a panel with the limit, your average, your speed now, km left and the most you can
 *    do for the rest. Shown together with police.
 */
class PoliceFlash(private val ctx: Context, private val wm: WindowManager) {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = Prefs(ctx)
    private var view: FlashView? = null
    private var testUntil = 0L
    private var testZoneUntil = 0L

    private val tick = object : Runnable {
        override fun run() {
            val here = Trip.here(ctx)
            Police.evaluate(here)
            val zone = if (prefs.avgZones) AvgZone.update(ctx, here) else null
            val now = SystemClock.elapsedRealtime()
            val police = when {
                now < testUntil -> FlashView.P(true, "🚓 POLICE · 650 m  (test)", null)
                else -> Police.active?.takeIf { prefs.policeFlash }?.let { h ->
                    if (h.onRoute) FlashView.P(true, "🚓 POLICE · " + km(h.m.coerceAtLeast(0.0).toFloat()) + (h.report.street?.let { " · $it" } ?: ""), h.relDeg)
                    else FlashView.P(false, "🚓 " + km(h.m.toFloat()), h.relDeg)
                }
            }
            val z = if (now < testZoneUntil) AvgZone.State(100, 97.0, 104.0, 3200.0, 108.0, "A1 (test)") else zone
            if (police == null && z == null) hide() else show(police, z)
            main.postDelayed(this, 1000)
        }
    }

    fun start() { main.removeCallbacks(tick); main.post(tick) }
    fun stop() { main.removeCallbacks(tick); hide() }
    fun test() { testUntil = SystemClock.elapsedRealtime() + 6000; main.removeCallbacks(tick); main.post(tick) }
    fun testZone() { testZoneUntil = SystemClock.elapsedRealtime() + 8000; main.removeCallbacks(tick); main.post(tick) }

    private fun show(p: FlashView.P?, z: AvgZone.State?) {
        view?.let { it.police = p; it.zone = z; return }
        val v = FlashView(ctx).also { it.police = p; it.zone = z }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        if (runCatching { wm.addView(v, params) }.isSuccess) view = v
        else DebugLog.log("POLICE", "can't show the overlay (overlay permission?)")
    }

    private fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    class FlashView(ctx: Context) : View(ctx) {
        /** Police: [route] = full flash; [relDeg] = arrow (null = none). */
        data class P(val route: Boolean, val text: String, val relDeg: Float?)
        @Volatile var police: P? = null
        @Volatile var zone: AvgZone.State? = null
        private val d = ctx.resources.displayMetrics.density
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tagBg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tagText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 22 * d; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
        private val small = Paint(tagText).apply { textSize = 16 * d; isFakeBoldText = false }
        private val tagEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d; color = Color.WHITE }
        private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val rect = RectF()
        private val start = SystemClock.elapsedRealtime()

        private fun edgeGlow(c: Canvas, side: Int, col: Int, depth: Float) {
            val w = width.toFloat(); val h = height.toFloat()
            val clear = Color.argb(0, Color.red(col), Color.green(col), Color.blue(col))
            when (side) {
                0 -> { glow.shader = LinearGradient(0f, 0f, 0f, depth, col, clear, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w, depth, glow) }
                1 -> { glow.shader = LinearGradient(w, 0f, w - depth, 0f, col, clear, Shader.TileMode.CLAMP); c.drawRect(w - depth, 0f, w, h, glow) }
                2 -> { glow.shader = LinearGradient(0f, h, 0f, h - depth, col, clear, Shader.TileMode.CLAMP); c.drawRect(0f, h - depth, w, h, glow) }
                else -> { glow.shader = LinearGradient(0f, 0f, depth, 0f, col, clear, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, depth, h, glow) }
            }
        }

        private fun pill(c: Canvas, text: String, cx: Float, top: Float, color: Int, paint: Paint, h: Float): RectF {
            val tw = paint.measureText(text) + 36 * d
            rect.set(cx - tw / 2, top, cx + tw / 2, top + h)
            tagBg.color = color
            c.drawRoundRect(rect, h / 2, h / 2, tagBg)
            c.drawRoundRect(rect, h / 2, h / 2, tagEdge)
            c.drawText(text, cx, rect.centerY() + paint.textSize / 3, paint)
            return RectF(rect)
        }

        override fun onDraw(c: Canvas) {
            val t = (SystemClock.elapsedRealtime() - start) / 1000.0
            val w = width.toFloat()
            val p = police
            val z = zone
            var top = 14 * d
            // Average speed section: steady glow by how you're doing (drawn first; police goes over it).
            if (z != null && (p == null || !p.route)) {
                val col = when (z.level()) { 0 -> Color.argb(150, 22, 163, 74); 1 -> Color.argb(170, 245, 158, 11); else -> Color.argb(190, 239, 68, 68) }
                for (side in 0..3) edgeGlow(c, side, col, 46 * d)
            }
            if (p != null && p.route) {
                // Police lights: red / blue every half second, pulsing; a thin line (was 12 dp).
                val red = ((t * 2).toInt() % 2) == 0
                val pulse = 0.55 + 0.45 * (0.5 + 0.5 * sin(t * 4 * Math.PI))
                val base = if (red) Color.rgb(255, 30, 30) else Color.rgb(40, 100, 255)
                val col = Color.argb((255 * pulse).toInt(), Color.red(base), Color.green(base), Color.blue(base))
                for (side in 0..3) edgeGlow(c, side, col, 70 * d)
                edge.strokeWidth = 4 * d
                edge.color = col
                c.drawRect(2 * d, 2 * d, width - 2 * d, height - 2 * d, edge)
                val r = pill(c, p.text, w / 2, top, Color.argb(235, Color.red(base), Color.green(base), Color.blue(base)), tagText, 50 * d)
                p.relDeg?.let { drawArrow(c, r.right + 30 * d, r.centerY(), it) }
                top = r.bottom + 8 * d
            } else if (p != null) {
                // Nearby, off the route: soft red glow on the side(s) it's on (no heading: all sides, faint).
                val pulse = 0.6 + 0.4 * (0.5 + 0.5 * sin(t * 2 * Math.PI))
                val rel = p.relDeg
                for (side in 0..3) {
                    val k = if (rel == null) 0.35 else cos(Math.toRadians((rel - side * 90f).toDouble())).coerceAtLeast(0.0)
                    if (k > 0.05) edgeGlow(c, side, Color.argb((200 * k * pulse).toInt(), 255, 40, 40), 60 * d)
                }
                val r = pill(c, p.text, w / 2, top, Color.argb(220, 185, 28, 28), tagText, 46 * d)
                rel?.let { drawArrow(c, r.right + 30 * d, r.centerY(), it) }
                top = r.bottom + 8 * d
            }
            if (z != null) {
                val col = when (z.level()) { 0 -> Color.argb(230, 21, 128, 61); 1 -> Color.argb(235, 180, 83, 9); else -> Color.argb(240, 185, 28, 28) }
                val r = pill(c, z.line1(), w / 2, top, col, tagText, 48 * d)
                pill(c, z.line2(), w / 2, r.bottom + 6 * d, Color.argb(200, 17, 24, 39), small, 34 * d)
            }
            postInvalidateDelayed(50) // ~20 fps
        }

        /** A white arrow in a dark circle pointing where it is (0 = straight ahead = up). */
        private fun drawArrow(c: Canvas, x: Float, y: Float, relDeg: Float) {
            tagBg.color = Color.argb(220, 17, 24, 39)
            c.drawCircle(x, y, 22 * d, tagBg)
            c.save(); c.rotate(relDeg, x, y)
            val s = 13 * d
            val path = android.graphics.Path().apply { moveTo(x, y - s); lineTo(x + s * 0.7f, y + s * 0.7f); lineTo(x, y + s * 0.3f); lineTo(x - s * 0.7f, y + s * 0.7f); close() }
            c.drawPath(path, arrow)
            c.restore()
        }
    }
}
