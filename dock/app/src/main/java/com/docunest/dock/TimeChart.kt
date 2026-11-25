package com.docunest.dock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * A Grafana-style time-series panel: dark card, title + legend with the latest values, a few grid lines,
 * lines (gaps where there's no data), red ticks along the top for failures (lost pings, failed checks),
 * coloured bands (connection state) and vertical marks for events (drop = red, back = green).
 */
class TimeChart(ctx: Context) : View(ctx) {
    class Line(val label: String, val color: Int, val xs: LongArray, val ys: FloatArray, val dots: Boolean = false, val unit: String = "")
    class Band(val from: Long, val to: Long, val color: Int)
    class Mark(val t: Long, val color: Int)

    var title = ""
    var lines: List<Line> = emptyList()
    var bands: List<Band> = emptyList()
    var ticks: List<Mark> = emptyList()
    var marks: List<Mark> = emptyList()
    /** Coloured horizontal lines: (value, colour), e.g. -70 / -80 dBm. */
    var thresholds: List<Pair<Float, Int>> = emptyList()
    var t0 = 0L
    var t1 = 0L
    var yMin: Float? = null
    var yMax: Float? = null
    /** Small version (drop card): fewer labels. */
    var compact = false
    /** x labels relative to the end ("−2 min") when the end is now. */
    var relative = true

    private val d = ctx.resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(17, 24, 39) }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(31, 41, 55); style = Paint.Style.STROKE; strokeWidth = 1 * d }
    private val grid = Paint().apply { color = Color.argb(60, 156, 163, 175); strokeWidth = 1f }
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(156, 163, 175); textSize = 10 * d }
    private val titleP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(229, 231, 235); textSize = 12 * d; isFakeBoldText = true }
    private val legend = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11 * d }
    private val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dash = Paint().apply { strokeWidth = 1.5f * d; pathEffect = DashPathEffect(floatArrayOf(4 * d, 4 * d), 0f) }
    private val path = Path()
    private val r = RectF()
    private val clock = SimpleDateFormat("HH:mm", Locale.US)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        r.set(0.5f, 0.5f, w - 0.5f, h - 0.5f)
        c.drawRoundRect(r, 8 * d, 8 * d, bg)
        c.drawRoundRect(r, 8 * d, 8 * d, border)
        val padL = if (compact) 6 * d else 38 * d
        val padT = (if (compact) 16 else 22) * d
        val padB = if (compact) 4 * d else 16 * d
        val padR = 6 * d
        val pl = padL; val pt = padT; val pr = w - padR; val pb = h - padB
        // Title + legend (latest values).
        c.drawText(title, 8 * d, (if (compact) 12 else 15) * d, titleP)
        var lx = 8 * d + titleP.measureText(title) + 12 * d
        for (ln in lines) {
            val last = ln.ys.lastOrNull { !it.isNaN() }
            val txt = "● ${ln.label}" + (last?.let { " ${fmt(it)}${ln.unit}" } ?: "")
            legend.color = ln.color
            c.drawText(txt, lx, (if (compact) 12 else 15) * d, legend)
            lx += legend.measureText(txt) + 10 * d
        }
        if (t1 <= t0 || pr <= pl || pb <= pt) return
        fun x(t: Long) = pl + (t - t0).toFloat() / (t1 - t0) * (pr - pl)
        // Bands (state) behind everything.
        for (b in bands) {
            fill.color = b.color
            c.drawRect(maxOf(pl, x(b.from)), pt, minOf(pr, x(b.to)), pb, fill)
        }
        // y range.
        val all = lines.flatMap { l -> l.ys.filter { !it.isNaN() } } + thresholds.map { it.first }
        var lo = yMin ?: (all.minOrNull() ?: 0f)
        var hi = yMax ?: (all.maxOrNull() ?: 1f)
        if (yMin == null && yMax == null && hi - lo < 1e-3f) { lo -= 1; hi += 1 }
        if (yMax == null) hi += (hi - lo) * 0.1f
        val step = niceStep((hi - lo) / 3)
        if (yMin == null) lo = floor(lo / step) * step
        if (yMax == null) hi = ceil(hi / step) * step
        fun y(v: Float) = pb - (v - lo) / (hi - lo) * (pb - pt)
        // Grid + y labels.
        var g = lo
        while (g <= hi + step / 2) {
            val yy = y(g)
            c.drawLine(pl, yy, pr, yy, grid)
            if (!compact) c.drawText(fmt(g), 4 * d, yy + 4 * d, axis)
            g += step
        }
        for ((v, col) in thresholds) { dash.color = col; c.drawLine(pl, y(v), pr, y(v), dash) }
        // Lines with gaps (no value, or more than 7 s between points).
        for (ln in lines) {
            lineP.color = ln.color
            fill.color = ln.color
            path.reset()
            var pen = false
            var lastT = 0L
            for (i in ln.xs.indices) {
                val v = ln.ys[i]
                if (v.isNaN() || ln.xs[i] < t0 || ln.xs[i] > t1) { pen = false; continue }
                val xx = x(ln.xs[i]); val yy = y(v.coerceIn(lo, hi))
                if (ln.dots) { c.drawCircle(xx, yy, 2.5f * d, fill); continue }
                if (!pen || ln.xs[i] - lastT > 7_000) path.moveTo(xx, yy) else path.lineTo(xx, yy)
                pen = true; lastT = ln.xs[i]
            }
            if (!ln.dots) c.drawPath(path, lineP)
        }
        // Failure ticks along the top; event marks as dashed verticals.
        for (m in ticks) if (m.t in t0..t1) { fill.color = m.color; val xx = x(m.t); c.drawRect(xx - 1.2f * d, pt, xx + 1.2f * d, pt + 7 * d, fill) }
        for (m in marks) if (m.t in t0..t1) { dash.color = m.color; val xx = x(m.t); c.drawLine(xx, pt, xx, pb, dash) }
        // x labels.
        if (!compact) {
            val now = System.currentTimeMillis()
            for (k in 0..4) {
                val t = t0 + (t1 - t0) * k / 4
                val txt = if (relative && abs(t1 - now) < 10_000) { val m = (t1 - t) / 60_000.0; if (m < 0.05) "now" else "−" + fmt(m.toFloat()) + " min" } else clock.format(Date(t))
                val tw = axis.measureText(txt)
                c.drawText(txt, (x(t) - tw / 2).coerceIn(pl, pr - tw), h - 4 * d, axis)
            }
        }
    }

    private fun niceStep(raw: Float): Float {
        if (raw <= 0f) return 1f
        val p = 10f.pow(floor(log10(raw)))
        val n = raw / p
        return (if (n <= 1) 1f else if (n <= 2) 2f else if (n <= 5) 5f else 10f) * p
    }

    private fun fmt(v: Float) = if (abs(v) >= 100 || v == v.toInt().toFloat()) v.toInt().toString() else "%.1f".format(Locale.US, v)
}

/** Turning [NetHistory] into chart series (shared by the drop card and the dashboard). */
object NetCharts {
    val GREEN = Color.rgb(34, 197, 94)
    val AMBER = Color.rgb(245, 158, 11)
    val RED = Color.rgb(239, 68, 68)
    val BLUE = Color.rgb(56, 189, 248)
    val PURPLE = Color.rgb(167, 139, 250)
    val GREY = Color.rgb(107, 114, 128)

    fun stateBands(s: List<NetHistory.Sample>, alpha: Int = 70): List<TimeChart.Band> {
        val out = ArrayList<TimeChart.Band>()
        var i = 0
        while (i < s.size) {
            var j = i
            while (j + 1 < s.size && s[j + 1].state == s[i].state && s[j + 1].t - s[j].t < 7_000) j++
            val col = when (s[i].state) { 1 -> GREEN; 3 -> AMBER; 0 -> RED; else -> GREY }
            out += TimeChart.Band(s[i].t, s[j].t + 2_000, Color.argb(alpha, Color.red(col), Color.green(col), Color.blue(col)))
            i = j + 1
        }
        return out
    }

    fun eventMarks(e: List<NetHistory.Event>) = e.mapNotNull { ev ->
        when (ev.kind) { "drop" -> TimeChart.Mark(ev.t, RED); "back" -> TimeChart.Mark(ev.t, GREEN); "off" -> TimeChart.Mark(ev.t, GREY); "ask" -> TimeChart.Mark(ev.t, AMBER); else -> null }
    }

    fun signal(ch: TimeChart, s: List<NetHistory.Sample>) {
        ch.title = "Wi-Fi signal"
        ch.lines = listOf(TimeChart.Line("dBm", BLUE, LongArray(s.size) { s[it].t },
            FloatArray(s.size) { s[it].rssi.takeIf { r -> r != 0 && r > -127 }?.toFloat() ?: Float.NaN }))
        ch.thresholds = listOf(-70f to AMBER, -80f to RED)
        ch.yMin = -95f; ch.yMax = -30f
    }

    fun ping(ch: TimeChart, s: List<NetHistory.Sample>) {
        ch.title = "iPhone ping"
        ch.lines = listOf(TimeChart.Line("ms", GREEN, LongArray(s.size) { s[it].t }, FloatArray(s.size) { s[it].pingMs.takeIf { p -> p >= 0 }?.toFloat() ?: Float.NaN }, unit = " ms"))
        ch.ticks = s.filter { it.pingMs == -1 }.map { TimeChart.Mark(it.t, RED) }
        ch.thresholds = listOf(150f to AMBER)
        ch.yMin = 0f; ch.yMax = null
    }

    fun internet(ch: TimeChart, c: List<NetHistory.Check>) {
        ch.title = "Internet checks"
        val xs = LongArray(c.size) { c[it].t }
        ch.lines = listOf(
            TimeChart.Line("web", PURPLE, xs, FloatArray(c.size) { c[it].webMs?.takeIf { _ -> c[it].webOk }?.toFloat() ?: Float.NaN }, dots = true, unit = " ms"),
            TimeChart.Line("DNS", BLUE, xs, FloatArray(c.size) { c[it].dnsMs?.toFloat() ?: Float.NaN }, dots = true, unit = " ms"),
        )
        ch.ticks = c.filter { !it.webOk }.map { TimeChart.Mark(it.t, RED) }
        ch.thresholds = listOf(3000f to AMBER)
        ch.yMin = 0f; ch.yMax = null
    }

    fun traffic(ch: TimeChart, s: List<NetHistory.Sample>) {
        ch.title = "Traffic"
        val xs = LongArray(s.size) { s[it].t }
        ch.lines = listOf(
            TimeChart.Line("in", BLUE, xs, FloatArray(s.size) { if (s[it].state == 1 || s[it].state == 3) s[it].rxKBs else Float.NaN }, unit = " kB/s"),
            TimeChart.Line("out", PURPLE, xs, FloatArray(s.size) { if (s[it].state == 1 || s[it].state == 3) s[it].txKBs else Float.NaN }, unit = " kB/s"),
        )
        ch.yMin = 0f; ch.yMax = null
    }

    fun speed(ch: TimeChart, s: List<NetHistory.Sample>) {
        ch.title = "Van speed"
        ch.lines = listOf(TimeChart.Line("km/h", AMBER, LongArray(s.size) { s[it].t }, FloatArray(s.size) { s[it].speedKmh }, unit = " km/h"))
        ch.yMin = 0f; ch.yMax = null
    }

    fun status(ch: TimeChart, s: List<NetHistory.Sample>) {
        ch.title = "Connection   ■ online  ■ no internet  ■ down  ■ off"
        ch.bands = stateBands(s, 200)
        ch.lines = emptyList()
        ch.yMin = 0f; ch.yMax = 1f
    }
}
