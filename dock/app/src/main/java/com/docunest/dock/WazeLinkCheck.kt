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
 * Restarting Waze for a link it ignored — only when the owner asks (2026-11-25: "short click = regular
 * navigation, hold 3 s = Waze reboot and restart … then you could stop guessing what happens in Waze").
 * The guessing versions (restart after 12 s, then "same place sent twice") are gone: both fired while
 * Waze was busy planning another route, and a restarted Waze asked "resume the previous drive?" and
 * dropped the link sent with the restart.
 *
 * Now: stop Waze → start it → wait until it's up (8 s) → send the link → watch Waze's log ([Police]
 * reads it) for "Executing query" (it took the link) or startNavigationFlow (driving) → if neither
 * within 12 s, send it once more. A notice at the top says each step; tap it to close.
 */
object WazeLinkCheck {
    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var tookAt = 0L
    @Volatile private var navAt = 0L
    @Volatile private var busy = false
    private var notice: TextView? = null

    /** Every line of Waze's log (from [Police]'s reader, worker thread). */
    fun line(l: String) {
        if (tookLink(l)) tookAt = System.currentTimeMillis()
        if (startedNavigation(l)) navAt = System.currentTimeMillis()
    }

    /** Waze started the search for a link (it has it; the owner may still pick a route). */
    fun tookLink(l: String) = l.contains("Executing query:")
    /** Waze started driving from a link. */
    fun startedNavigation(l: String) = l.contains("startNavigationFlow") && l.contains("caller=DeepLink")

    fun restartAndSend(ctx: Context, uri: String, title: String?, lat: Double?, lng: Double?) {
        if (busy) return
        busy = true
        val app = ctx.applicationContext
        val name = title ?: "the place"
        if (lat != null && lng != null) Trip.setDockDestination(app, lat, lng, title)
        DebugLog.log("NAV", "owner held Waze 3 s: restart Waze, then send $uri")
        show(app, "⟳ Restarting Waze…", AMBER)
        Thread {
            try {
                val ok = Root.run("am force-stop com.waze", "sleep 1", "monkey -p com.waze -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1", "true")
                if (!ok) {
                    post { show(app, "Couldn't restart Waze (no root). Close Waze by hand and tap again. (tap to close)", RED) }
                    DebugLog.log("NAV", "restart: root refused")
                    return@Thread
                }
                Thread.sleep(8_000) // Waze's start-up (and its "resume the previous drive?" question) first
                for (attempt in 1..2) {
                    val sentAt = System.currentTimeMillis()
                    post { show(app, "→ Sending “$name” to Waze" + if (attempt > 1) " again…" else "…", AMBER) }
                    Root.run("am start -a android.intent.action.VIEW -d '${uri.replace("'", "%27")}' com.waze >/dev/null 2>&1", "true")
                    val until = sentAt + 12_000
                    while (System.currentTimeMillis() < until && tookAt < sentAt && navAt < sentAt) Thread.sleep(500)
                    if (navAt >= sentAt || tookAt >= sentAt) {
                        val driving = navAt >= sentAt
                        DebugLog.log("NAV", "restart: Waze ${if (driving) "is navigating" else "took the link"} (send $attempt)")
                        post { show(app, if (driving) "✓ Waze is navigating to “$name”" else "✓ Waze has “$name” — pick the route there", GREEN); main.postDelayed({ hide() }, 5_000) }
                        return@Thread
                    }
                    DebugLog.log("NAV", "restart: no sign from Waze 12 s after send $attempt")
                }
                post { show(app, "Waze didn't show it took “$name”. Check Waze — or hold the button 3 s again. (tap to close)", RED) }
            } finally {
                busy = false
            }
        }.start()
    }

    private const val AMBER = 0xFFD97706.toInt()
    private const val GREEN = 0xFF16A34A.toInt()
    private const val RED = 0xFFDC2626.toInt()
    private fun post(r: () -> Unit) = main.post(r)

    private fun show(ctx: Context, text: String, color: Int) {
        val d = ctx.resources.displayMetrics.density
        val n = notice ?: TextView(ctx).also { tv ->
            tv.textSize = 18f
            tv.setTextColor(Color.WHITE)
            tv.setPadding((18 * d).toInt(), (10 * d).toInt(), (18 * d).toInt(), (10 * d).toInt())
            tv.maxWidth = (700 * d).toInt()
            tv.setOnClickListener { hide() }
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
