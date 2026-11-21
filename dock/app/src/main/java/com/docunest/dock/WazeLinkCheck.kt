package com.docunest.dock

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * Waze sometimes takes a link from the dock and does nothing (2026-11-17: after killing Waze the same
 * link worked at once). The first fix restarted Waze when it hadn't started the search 12 s after a
 * link — the owner (2026-11-20): "too aggressive — it catches cases when Waze works as expected but
 * takes longer to calculate the route, or I'm inspecting the route and haven't clicked start".
 *
 * Now the owner's own rule: **the same place sent twice within [WINDOW_MS]** is the signal ("there
 * is no other practical need to do this unless Waze does shit"). Then, unless Waze's log shows our
 * first link already started navigation, Waze is restarted (root) and the link sent again, and a
 * notice stays on screen until navigation is seen working (Waze's log: startNavigationFlow from the
 * link, or the destination appearing in Waze's recent places). Tap the notice to close it.
 * Nothing happens on a single send.
 */
object WazeLinkCheck {
    /** "Same session": the second send of the same place within 5 min. */
    const val WINDOW_MS = 5 * 60_000L
    private val main by lazy { Handler(Looper.getMainLooper()) }

    private class Sent(val uri: String, val title: String?, val lat: Double?, val lng: Double?, val at: Long)
    @Volatile private var last: Sent? = null
    /** Waze's log showed navigation started from a link (time). */
    @Volatile private var navStartedAt = 0L
    /** A restart we did, waiting to see navigation work. */
    @Volatile private var waiting: Sent? = null
    private var notice: TextView? = null

    /** Same place as last time, within the window, and nothing showed the first one working → restart. Pure, for tests. */
    fun shouldRestart(prevUri: String?, prevAt: Long, uri: String, now: Long, navStartedAt: Long, restartPending: Boolean): Boolean =
        prevUri != null && prevUri == uri && now - prevAt in 0..WINDOW_MS && navStartedAt < prevAt && !restartPending

    /**
     * Called right before Nav sends [uri] to Waze. True = handled here (Waze restarted and the link
     * re-sent); false = send it as usual.
     */
    fun beforeSend(ctx: Context, uri: String, title: String?, lat: Double?, lng: Double?): Boolean {
        val now = System.currentTimeMillis()
        val prev = last
        last = Sent(uri, title, lat, lng, now)
        if (!shouldRestart(prev?.uri, prev?.at ?: 0, uri, now, navStartedAt, waiting != null)) return false
        val app = ctx.applicationContext
        val name = title ?: "the place"
        DebugLog.log("NAV", "same place sent twice in ${(now - prev!!.at) / 1000} s and Waze didn't start it — restarting Waze: $uri")
        waiting = last
        show(app, "⟳ Waze didn't start “$name” — restarting Waze and sending it again…", Color.rgb(217, 119, 6))
        Thread {
            val ok = Root.run("am force-stop com.waze", "sleep 1",
                "am start -a android.intent.action.VIEW -d '${uri.replace("'", "%27")}' com.waze")
            main.post {
                if (!ok) {
                    DebugLog.log("NAV", "couldn't restart Waze (root)")
                    waiting = null
                    show(app, "Couldn't restart Waze (no root) — close Waze and send “$name” again. Tap to close.", Color.rgb(220, 38, 38))
                } else show(app, "⟳ Waze restarted — waiting for it to start navigating to “$name”… (tap to close)", Color.rgb(217, 119, 6))
            }
        }.start()
        // Also notice it from Waze's recent places (if its log isn't readable).
        Trip.addListener(tripListener)
        return true
    }

    private val tripListener: () -> Unit = {
        val w = waiting
        val d = Trip.dest
        if (w != null && d != null && d.source == "waze" && d.at >= w.at - 5_000 && w.lat != null && w.lng != null &&
            RouteGeo.distM(d.lat, d.lng, w.lat, w.lng) < 1_000) navWorks("Waze's recent places")
    }

    /** Every line of Waze's log (from [Police]'s reader, worker thread). */
    fun line(l: String) {
        if (startedNavigation(l)) {
            navStartedAt = System.currentTimeMillis()
            if (waiting != null) main.post { navWorks("Waze's log") }
        }
    }

    /** Waze starts navigation for a link (the search alone isn't enough: the owner may be looking at the route). */
    fun startedNavigation(l: String) = l.contains("startNavigationFlow") && l.contains("caller=DeepLink")

    private fun navWorks(how: String) {
        val w = waiting ?: return
        waiting = null
        Trip.removeListener(tripListener)
        DebugLog.log("NAV", "Waze is navigating to ${w.title ?: w.uri} after the restart ($how, ${(System.currentTimeMillis() - w.at) / 1000} s)")
        notice?.let { n ->
            n.text = "✓ Waze is navigating to “${w.title ?: "the place"}”"
            n.background = rounded(Color.argb(240, 22, 163, 74), 22f * n.resources.displayMetrics.density)
            main.postDelayed({ hide() }, 4_000)
        }
    }

    private fun show(ctx: Context, text: String, color: Int) {
        val d = ctx.resources.displayMetrics.density
        val n = notice ?: TextView(ctx).also { tv ->
            tv.textSize = 18f
            tv.setTextColor(Color.WHITE)
            tv.setPadding((18 * d).toInt(), (10 * d).toInt(), (18 * d).toInt(), (10 * d).toInt())
            tv.maxWidth = (700 * d).toInt()
            tv.setOnClickListener { waiting = null; Trip.removeListener(tripListener); hide() }
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = (72 * d).toInt() }
            val ok = runCatching { (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(tv, p) }.isSuccess
            if (!ok) { DebugLog.log("NAV", "notice: $text (couldn't show it)"); return }
            notice = tv
        }
        n.text = text
        n.background = rounded(Color.argb(240, Color.red(color), Color.green(color), Color.blue(color)), 22f * d)
    }

    private fun hide() {
        val n = notice ?: return
        notice = null
        runCatching { (n.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(n) }
    }
}
