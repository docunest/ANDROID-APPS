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
 * Making a sent link actually land in Waze.
 *
 * What the unit shows (logs 2026-11-17 … 11-28): a Waze that is already running sometimes swallows a
 * link — Android hands it over, Waze's Java side even says "URL to handle", but Waze's engine never
 * applies it, so nothing happens however often the owner taps (4 taps on 2026-11-28 12:56–12:58).
 * Only a Waze restart helps. When Waze does take a link, its engine logs it within 0–2 s
 * ("Url is not valid … Falling back to init" from urlscheme.cc, then "Executing query"); every link
 * that worked on 11-18/11-20 had that line, the stuck ones (11-20 08:30, 09:05) didn't.
 *
 * So after each tap ([sent]): if Waze's engine logs the link within [RECEIPT_MS] → done, never touch
 * Waze again for it, however long the route planning or the preview takes (that was the old 12 s
 * check's mistake: it waited for the *search/drive*, not for the receipt). If Waze's log keeps going
 * but the link never reaches the engine → Waze is stuck: restart it and send again ([restartAndSend]),
 * with a notice saying so. If Waze's log is silent (can't read it / Waze not up) → leave Waze alone.
 * Holding the button 3 s still restarts straight away.
 */
object WazeLinkCheck {
    /** Waze's engine logs a link within 0–2 s; the log is read every 2 s. A Waze that is still starting gets longer. */
    const val RECEIPT_MS = 8_000L
    const val COLD_RECEIPT_MS = 25_000L
    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var gotAt = 0L
    @Volatile private var navAt = 0L
    @Volatile private var linesSince = 0
    @Volatile var busy = false
        private set
    @Volatile private var pendingSeq = 0
    private var notice: TextView? = null

    /** Every line of Waze's log (from [Police]'s reader, worker thread). */
    fun line(l: String) {
        linesSince++
        val now = System.currentTimeMillis()
        if (reachedEngine(l)) {
            if (gotAt < now - 1_000) DebugLog.log("NAV", "Waze: " + l.substringAfter("] ").take(160))
            gotAt = now
        }
        if (startedNavigation(l)) navAt = now
    }

    /** Waze's engine has the link (it may still be planning, or showing the route to confirm). */
    fun reachedEngine(l: String) = l.contains("urlscheme_apply") || l.contains("Url is not valid") ||
        l.contains("Falling back to init") || tookLink(l) || startedNavigation(l) || l.contains("Auto nav to address")
    /** Waze started the search for a link. */
    fun tookLink(l: String) = l.contains("Executing query:")
    /** Waze started driving from a link. */
    fun startedNavigation(l: String) = l.contains("startNavigationFlow") && l.contains("caller=DeepLink")

    enum class Verdict { GOT_IT, STUCK, CANT_TELL }
    /** After the wait: Waze's engine logged the link → fine; Waze logged other things but not the link → stuck. */
    fun verdict(gotIt: Boolean, linesSince: Int): Verdict = when {
        gotIt -> Verdict.GOT_IT
        linesSince == 0 -> Verdict.CANT_TELL
        else -> Verdict.STUCK
    }

    /** A tap sent [uri] to Waze (startActivity returned): check that it reached Waze's engine. */
    fun sent(ctx: Context, uri: String, title: String?) {
        if (busy) return
        val app = ctx.applicationContext
        val seq = ++pendingSeq
        val sentAt = System.currentTimeMillis()
        linesSince = 0
        DebugLog.log("NAV", "sent to Waze: $uri")
        Thread {
            // A Waze that wasn't running needs its start-up first.
            val cold = Root.output("pidof com.waze")?.trim().isNullOrEmpty()
            val until = sentAt + if (cold) COLD_RECEIPT_MS else RECEIPT_MS
            while (System.currentTimeMillis() < until && gotAt < sentAt) Thread.sleep(500)
            if (seq != pendingSeq || busy) return@Thread // a newer tap / a restart took over
            when (verdict(gotAt >= sentAt, linesSince)) {
                Verdict.GOT_IT -> DebugLog.log("NAV", "Waze got the link after ${(gotAt - sentAt) / 1000} s${if (cold) " (Waze was starting)" else ""}")
                Verdict.CANT_TELL -> DebugLog.log("NAV", "no Waze log lines since the link — can't tell; leaving Waze alone")
                Verdict.STUCK -> {
                    DebugLog.log("NAV", "Waze stuck: $linesSince log lines in ${(until - sentAt) / 1000} s but the link never reached its engine — restarting Waze")
                    post { restartAndSend(app, uri, title, null, null, why = "Waze didn't take “${title ?: "the place"}” — restarting Waze…") }
                }
            }
        }.start()
    }

    fun restartAndSend(ctx: Context, uri: String, title: String?, lat: Double?, lng: Double?,
                       why: String = "⟳ Restarting Waze…") {
        if (busy) return
        busy = true
        pendingSeq++
        val app = ctx.applicationContext
        val name = title ?: "the place"
        if (lat != null && lng != null) Trip.setDockDestination(app, lat, lng, title)
        DebugLog.log("NAV", "restart Waze, then send $uri")
        show(app, why, AMBER)
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
                    while (System.currentTimeMillis() < until && gotAt < sentAt) Thread.sleep(500)
                    if (gotAt >= sentAt) {
                        val driving = navAt >= sentAt
                        DebugLog.log("NAV", "restart: Waze ${if (driving) "is navigating" else "got the link"} (send $attempt)")
                        post { show(app, if (driving) "✓ Waze is navigating to “$name”" else "✓ Waze has “$name”", GREEN); main.postDelayed({ hide() }, 4_000) }
                        return@Thread
                    }
                    DebugLog.log("NAV", "restart: no sign from Waze 12 s after send $attempt")
                }
                post { show(app, "Waze didn't show it took “$name”. Check Waze — or hold the button 3 s. (tap to close)", RED) }
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
