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
 * When Wi-Fi drops (owner 2026-11-25: "a pop-up or a semi-transparent tooltip, don't make this too
 * annoying, showing the network status and issues for the last few minutes … clicking it should open a
 * full dash"): a see-through card at the left edge with the last 3 minutes — signal, iPhone ping (red
 * ticks = lost), internet checks (red ticks = failed) — and a one-line reading of what failed first.
 * Live while down; closes 15 s after Wi-Fi is back (or after 4 min). Tap = [NetDashActivity].
 */
object DropCard {
    private val main = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var title: TextView? = null
    private var diag: TextView? = null
    private var charts: List<TimeChart> = emptyList()
    private var lostAt = 0L
    private var lostName = ""
    private var backAt = 0L
    private var why: String? = null

    private val refresh = object : Runnable {
        override fun run() {
            val r = root ?: return
            val now = System.currentTimeMillis()
            if ((backAt > 0 && now - backAt > 15_000) || now - lostAt > 4 * 60_000) { hide(); return }
            draw(r.context, now)
            main.postDelayed(this, 2_000)
        }
    }

    fun show(ctx: Context, name: String, before: String?) {
        if (!Prefs(ctx).dropCard) return
        lostAt = System.currentTimeMillis(); lostName = name; backAt = 0; why = null
        val app = ctx.applicationContext
        if (root == null) build(app)
        draw(app, lostAt)
        main.removeCallbacks(refresh)
        main.postDelayed(refresh, 2_000)
    }

    fun reason(text: String) = main.post { why = text; root?.let { draw(it.context, System.currentTimeMillis()) } }
    fun back(ssid: String?) { if (root != null) { backAt = System.currentTimeMillis(); lostName = ssid ?: lostName; draw(root!!.context, backAt) } }

    private fun build(ctx: Context) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(Color.argb(190, 11, 18, 32), dp(14).toFloat(), Color.argb(160, 239, 68, 68), dp(1))
            setOnClickListener {
                hide()
                ctx.startActivity(Intent(ctx, NetDashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(NetDashActivity.EXTRA_AT, lostAt))
            }
        }
        title = TextView(ctx).apply { textSize = 15f; setTextColor(Color.WHITE); paint.isFakeBoldText = true }
        box.addView(title)
        charts = List(3) { TimeChart(ctx).apply { compact = true; alpha = 0.92f } }
        charts.forEach { box.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)).apply { topMargin = dp(4) }) }
        diag = TextView(ctx).apply { textSize = 12f; setTextColor(Color.rgb(209, 213, 219)); setPadding(0, dp(4), 0, 0); maxLines = 3 }
        box.addView(diag)
        box.addView(TextView(ctx).apply { text = "Tap for the network dashboard"; textSize = 11f; setTextColor(Color.rgb(156, 163, 175)) })
        val p = WindowManager.LayoutParams(
            dp(380), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL; x = dp(8) }
        if (runCatching { (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(box, p) }.isSuccess) root = box
        else DebugLog.log("NET", "drop card: couldn't show it")
    }

    private fun draw(ctx: Context, now: Long) {
        val from = lostAt - 3 * 60_000L
        val to = maxOf(now, lostAt + 10_000)
        val s = NetHistory.samples(from)
        val c = NetHistory.checks(from)
        val marks = NetCharts.eventMarks(NetHistory.events(from))
        NetCharts.signal(charts[0], s); NetCharts.ping(charts[1], s); NetCharts.internet(charts[2], c)
        charts.forEach { it.t0 = from; it.t1 = to; it.marks = marks; it.bands = NetCharts.stateBands(s, 45); it.invalidate() }
        val down = ((if (backAt > 0) backAt else now) - lostAt) / 1000
        title?.text = if (backAt > 0) "✓ Back on $lostName after ${down / 60}:${"%02d".format(down % 60)}"
        else "📶 Lost $lostName · ${down / 60}:${"%02d".format(down % 60)}"
        diag?.text = listOfNotNull(why, NetHistory.diagnose(s, c, lostAt)).joinToString("\n")
    }

    private fun hide() {
        main.removeCallbacks(refresh)
        val r = root ?: return
        root = null
        runCatching { (r.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(r) }
    }
}
