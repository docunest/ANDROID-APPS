package com.docunest.dock

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.osmdroid.config.Configuration as OsmConfig
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The network dashboard (owner 2026-11-25: "a full dash … nice Grafana style dashboards, not random
 * logs"). Left: stat tiles + time-series panels (connection state, Wi-Fi signal, iPhone ping with lost
 * pings, internet checks with failures, traffic, van speed), red / green dashed lines = drop / back.
 * Range: 3 min · 15 min · 1 h · 2 h, live. Right: saved drops on a map and as a list ("same road?") —
 * tap one to see its last 5 minutes.
 */
class NetDashActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var rangeMs = 15 * 60_000L
    /** null = live; else a saved drop. */
    private var drop: NetHistory.Drop? = null
    private lateinit var charts: List<TimeChart>
    private lateinit var tiles: LinearLayout
    private lateinit var rangeRow: LinearLayout
    private lateinit var modeText: TextView
    private lateinit var dropList: LinearLayout
    private lateinit var map: MapView
    private lateinit var dropsOverlay: DropsOverlay
    private var drops: List<NetHistory.Drop> = emptyList()

    private val live = object : Runnable {
        override fun run() { if (drop == null) render(); main.postDelayed(this, 2_000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OsmConfig.getInstance().apply { load(this@NetDashActivity, getSharedPreferences("osmdroid", MODE_PRIVATE)); userAgentValue = packageName }
        if (intent?.hasExtra(EXTRA_AT) == true) rangeMs = 5 * 60_000L
        val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.rgb(11, 15, 25)); setPadding(dp(8), dp(6), dp(8), dp(6)) }

        // Left: header, tiles, panels.
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(label("📶 Network", 18f, bold = true))
        rangeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(rangeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
        modeText = label("", 13f, Palette.MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        head.addView(modeText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
        head.addView(bigButton("✕", Palette.CARD, 18f) { finish() }, LinearLayout.LayoutParams(dp(52), dp(44)))
        left.addView(head)
        tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        left.addView(tiles, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)).apply { topMargin = dp(6) })
        val panels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        charts = List(6) { TimeChart(this) }
        val heights = listOf(46, 120, 120, 120, 110, 90)
        charts.forEachIndexed { i, ch -> panels.addView(ch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heights[i])).apply { topMargin = dp(6) }) }
        left.addView(ScrollView(this).apply { addView(panels) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 7f))

        // Right: saved drops, map + list.
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, 0, 0) }
        right.addView(label("Where Wi-Fi dropped (last 40)", 14f, bold = true))
        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(7.0); controller.setCenter(GeoPoint(40.0, -8.0))
        }
        dropsOverlay = DropsOverlay()
        map.overlays.add(dropsOverlay)
        right.addView(map, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)).apply { topMargin = dp(4) })
        dropList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        right.addView(ScrollView(this).apply { addView(dropList) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(4) })
        root.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3f))
        setContentView(root)
        loadDrops()
        render()
    }

    override fun onResume() { super.onResume(); map.onResume(); main.post(live) }
    override fun onPause() { main.removeCallbacks(live); map.onPause(); super.onPause() }

    private fun chip(text: String, on: Boolean, click: () -> Unit) = label(text, 13f, bold = on).apply {
        background = rounded(if (on) Palette.BLUE else Palette.CARD, dp(10).toFloat())
        gravity = Gravity.CENTER; minHeight = dp(44); setPadding(dp(12), 0, dp(12), 0)
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginEnd = dp(6) }
    }

    private fun render() {
        rangeRow.removeAllViews()
        for ((label, ms) in listOf("3 min" to 3 * 60_000L, "15 min" to 15 * 60_000L, "1 h" to 60 * 60_000L, "2 h" to 120 * 60_000L)) {
            rangeRow.addView(chip(label, drop == null && rangeMs == ms) { drop = null; rangeMs = ms; dropsOverlay.selected = null; render() })
        }
        val now = System.currentTimeMillis()
        val d = drop
        val (t0, t1) = if (d != null) (d.samples.firstOrNull()?.t ?: (d.t - 300_000)) to d.t + 5_000 else (now - rangeMs) to now
        val s = d?.samples ?: NetHistory.samples(t0)
        val c = d?.checks ?: NetHistory.checks(t0)
        val ev = if (d != null) listOf(NetHistory.Event(d.t, "drop", "")) else NetHistory.events(t0)
        modeText.text = if (d != null) "Drop at ${clock.format(Date(d.t))} · ${d.ssid ?: "Wi-Fi"} — last 5 min before it · ${d.why}" else "Live · updates every 2 s"
        NetCharts.status(charts[0], s); NetCharts.signal(charts[1], s); NetCharts.ping(charts[2], s)
        NetCharts.internet(charts[3], c); NetCharts.traffic(charts[4], s); NetCharts.speed(charts[5], s)
        val marks = NetCharts.eventMarks(ev)
        charts.forEachIndexed { i, ch ->
            ch.t0 = t0; ch.t1 = t1; ch.marks = marks; ch.relative = d == null
            if (i > 0) ch.bands = NetCharts.stateBands(s, 28)
            ch.invalidate()
        }
        renderTiles(s, c, ev, t1)
    }

    private fun renderTiles(s: List<NetHistory.Sample>, c: List<NetHistory.Check>, ev: List<NetHistory.Event>, t1: Long) {
        tiles.removeAllViews()
        fun tile(title: String, value: String, sub: String, color: Int) {
            val t = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(4), dp(8), dp(4))
                background = rounded(Color.rgb(17, 24, 39), dp(8).toFloat(), Color.rgb(31, 41, 55), dp(1))
            }
            t.addView(label(title, 11f, Palette.MUTED))
            t.addView(label(value, 18f, color, bold = true).apply { maxLines = 1 })
            t.addView(label(sub, 10f, Palette.MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            tiles.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = dp(6) })
        }
        val last = s.lastOrNull()
        val (stText, stCol) = when (last?.state) { 1 -> "Online" to NetCharts.GREEN; 3 -> "No internet" to NetCharts.AMBER; 0 -> "Down" to NetCharts.RED; 2 -> "Wi-Fi off" to NetCharts.GREY; else -> "—" to Palette.MUTED }
        tile("Now", stText, last?.ssid ?: "", stCol)
        val r = s.map { it.rssi }.filter { it != 0 && it > -127 }
        tile("Signal", r.lastOrNull()?.let { "$it dBm" } ?: "—", r.minOrNull()?.let { "worst $it dBm" } ?: "", if ((r.lastOrNull() ?: 0) <= -80) NetCharts.RED else NetCharts.BLUE)
        val p = s.filter { it.pingMs != -2 }
        val loss = if (p.isEmpty()) 0 else p.count { it.pingMs == -1 } * 100 / p.size
        val avg = p.filter { it.pingMs >= 0 }.map { it.pingMs }.average().takeIf { !it.isNaN() }?.toInt()
        tile("iPhone ping", avg?.let { "$it ms" } ?: "—", "$loss % lost", if (loss >= 20) NetCharts.RED else if (loss >= 5) NetCharts.AMBER else NetCharts.GREEN)
        val bad = c.count { !it.webOk }
        tile("Internet checks", "${c.size - bad} ok", if (bad > 0) "$bad failed" else "none failed", if (bad > 0) NetCharts.RED else NetCharts.GREEN)
        val downMs = s.zipWithNext().filter { (a, _) -> a.state == 0 || a.state == 2 }.sumOf { (a, b) -> (b.t - a.t).coerceAtMost(7_000) }
        tile("Drops", "${ev.count { it.kind == "drop" }}", "down ${downMs / 60_000}:${"%02d".format(downMs / 1000 % 60)} in range", if (ev.any { it.kind == "drop" }) NetCharts.RED else NetCharts.GREEN)
    }

    private fun loadDrops() {
        drops = NetHistory.drops(this).asReversed()
        dropsOverlay.points = drops.filter { it.lat != 0.0 }
        dropList.removeAllViews()
        if (drops.isEmpty()) dropList.addView(label("No drops saved yet.", 13f, Palette.MUTED))
        val day = SimpleDateFormat("d MMM HH:mm", Locale.US)
        for (dr in drops) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(6), dp(8), dp(6))
                background = rounded(Color.rgb(17, 24, 39), dp(8).toFloat(), Color.rgb(31, 41, 55), dp(1))
                minimumHeight = dp(48)
                setOnClickListener { drop = dr; dropsOverlay.selected = dr; if (dr.lat != 0.0) map.controller.animateTo(GeoPoint(dr.lat, dr.lng), 13.0, 400L); render() }
            }
            row.addView(label("${day.format(Date(dr.t))} · ${dr.ssid ?: "Wi-Fi"}", 13f, bold = true))
            row.addView(label(dr.why.ifEmpty { NetHistory.diagnose(dr.samples, dr.checks, dr.t) }, 11f, Palette.MUTED).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            dropList.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })
        }
        val pts = dropsOverlay.points.map { GeoPoint(it.lat, it.lng) }
        if (pts.size >= 2) map.post { runCatching { map.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts).increaseByScale(1.3f), false) } }
        else pts.firstOrNull()?.let { map.controller.setCenter(it); map.controller.setZoom(12.0) }
    }

    /** Drops as red dots (the selected one bigger, yellow). */
    private inner class DropsOverlay : Overlay() {
        var points: List<NetHistory.Drop> = emptyList()
        var selected: NetHistory.Drop? = null
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * resources.displayMetrics.density; color = Color.WHITE }
        private val pt = Point()
        override fun draw(c: Canvas, mv: MapView, shadow: Boolean) {
            if (shadow) return
            val dd = resources.displayMetrics.density
            for (p in points) {
                mv.projection.toPixels(GeoPoint(p.lat, p.lng), pt)
                val sel = p === selected
                dot.color = if (sel) Color.rgb(250, 204, 21) else Color.argb(220, 239, 68, 68)
                c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), (if (sel) 9 else 6) * dd, dot)
                c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), (if (sel) 9 else 6) * dd, ring)
            }
        }
    }

    companion object {
        const val EXTRA_AT = "at"
        private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    }
}
