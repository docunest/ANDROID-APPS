package com.docunest.dock

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * When Wi-Fi drops (owner 2026-11-25: "a semi-transparent tooltip, don't make this too annoying, showing
 * the network status and issues for the last few minutes … clicking it should open a full dash").
 * Round 2 after the owner's photo (2026-11-27): only for drops still down after [SHOW_AFTER_MS] (a 6 s
 * blip got a card), at the right edge (it covered Waze's turn instruction), smaller and more solid, no
 * green state tint or guide lines, the down time shaded red, and ONE reading that matches the charts.
 * Charts: signal, iPhone ping (red ticks = lost), internet checks (red ticks = failed), last 3 minutes.
 * Closes 10 s after Wi-Fi is back (4 min max). Tap = [NetDashActivity].
 */
object DropCard {
    private val main = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var lostAt = 0L
    private var lostName = ""
    private var backAt = 0L
    private var why: String? = null
    const val SHOW_AFTER_MS = 8_000L
    private var pending: Runnable? = null

    private val refresh = object : Runnable {
        override fun run() {
            val r = root ?: return
            val now = System.currentTimeMillis()
            if ((backAt > 0 && now - backAt > 10_000) || now - lostAt > 4 * 60_000) { hide(); return }
            draw(r.context, now)
            main.postDelayed(this, 2_000)
        }
    }

    fun show(ctx: Context, name: String, before: String?) {
        if (!Prefs(ctx).dropCard) return
        lostAt = System.currentTimeMillis(); lostName = name; backAt = 0; why = null
        val app = ctx.applicationContext
        pending?.let { main.removeCallbacks(it) }
        // Short blips (back within 8 s) don't get a card — they're in the dashboard.
        pending = Runnable {
            pending = null
            if (backAt > 0) return@Runnable
            if (root == null) build(app)
            draw(app, System.currentTimeMillis())
            main.removeCallbacks(refresh)
            main.postDelayed(refresh, 2_000)
        }.also { main.postDelayed(it, SHOW_AFTER_MS) }
    }

    fun reason(text: String) = main.post { why = text; root?.let { draw(it.context, System.currentTimeMillis()) } }
    fun back(ssid: String?) {
        backAt = System.currentTimeMillis()
        lostName = ssid ?: lostName
        root?.let { draw(it.context, backAt) }
    }

    /** The card's views (also drawn by the unit test, so what the owner sees is what was checked). */
    class Card(val box: LinearLayout, val title: TextView, val diag: TextView, val charts: List<TimeChart>)

    fun createCard(ctx: Context, onTap: () -> Unit): Card {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(Color.argb(232, 11, 18, 32), dp(14).toFloat(), Color.argb(200, 239, 68, 68), dp(1))
            setOnClickListener { onTap() }
        }
        val title = TextView(ctx).apply { textSize = 15f; setTextColor(Color.WHITE); paint.isFakeBoldText = true; maxLines = 1 }
        box.addView(title)
        val charts = List(3) { TimeChart(ctx).apply { compact = true } }
        charts.forEach { box.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(4) }) }
        val diag = TextView(ctx).apply { textSize = 12f; setTextColor(Color.rgb(229, 231, 235)); setPadding(0, dp(4), 0, 0); maxLines = 4 }
        box.addView(diag)
        box.addView(TextView(ctx).apply { text = "Tap for the network dashboard"; textSize = 11f; setTextColor(Color.rgb(156, 163, 175)) })
        return Card(box, title, diag, charts)
    }

    /** Fill [card] for a drop at [lostAt] ([backAt] 0 = still down) from [s] / [c]. */
    fun fill(card: Card, s: List<NetHistory.Sample>, c: List<NetHistory.Check>, lostAt: Long, backAt: Long, now: Long, name: String, why: String?) {
        val from = lostAt - 3 * 60_000L
        val to = maxOf(now, lostAt + 10_000)
        NetCharts.signal(card.charts[0], s); NetCharts.ping(card.charts[1], s); NetCharts.internet(card.charts[2], c)
        // No green tint: only the time Wi-Fi was down, shaded red.
        val downBand = listOf(TimeChart.Band(lostAt, if (backAt > 0) backAt else to, Color.argb(70, 239, 68, 68)))
        card.charts.forEach { it.t0 = from; it.t1 = to; it.marks = emptyList(); it.bands = downBand; it.invalidate() }
        val down = ((if (backAt > 0) backAt else now) - lostAt) / 1000
        card.title.text = if (backAt > 0) "✓ Back on $name after ${down / 60}:${"%02d".format(down % 60)}"
        else "📶 Lost $name · ${down / 60}:${"%02d".format(down % 60)}"
        // One reading from the charts' own data; the doctor's line only when it adds a fact the charts can't show
        // (who ended it, from the disconnect code).
        val extra = why?.takeIf { it.startsWith("The iPhone") || it.startsWith("Android") }?.substringBefore(" — ")?.substringBefore(". ")
        card.diag.text = listOfNotNull(NetHistory.diagnose(s, c, lostAt), extra).joinToString("\n")
    }

    private var card: Card? = null

    private fun build(ctx: Context) {
        val d = ctx.resources.displayMetrics.density
        val cd = createCard(ctx) {
            hide()
            ctx.startActivity(Intent(ctx, NetDashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(NetDashActivity.EXTRA_AT, lostAt))
        }
        val p = WindowManager.LayoutParams(
            (300 * d).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; x = (8 * d).toInt() } // right edge: Waze's turn instruction is top-left
        if (runCatching { (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(cd.box, p) }.isSuccess) { root = cd.box; card = cd }
        else DebugLog.log("NET", "drop card: couldn't show it")
    }

    private fun draw(ctx: Context, now: Long) {
        val cd = card ?: return
        val from = lostAt - 3 * 60_000L
        fill(cd, NetHistory.samples(from), NetHistory.checks(from), lostAt, backAt, now, lostName, why)
    }

    private fun hide() {
        main.removeCallbacks(refresh)
        val r = root ?: return
        root = null
        card = null
        runCatching { (r.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(r) }
    }
}
