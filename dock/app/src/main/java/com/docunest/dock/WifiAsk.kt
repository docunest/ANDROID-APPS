package com.docunest.dock

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Owner 2026-11-20: "the Wi-Fi doctor is way too aggressive … keeps disconnecting my phone … if you
 * are dropping the Wi-Fi connection, it must be confirmed by me why you're willing to do this".
 *
 * Every action that would cut a working connection (forced reconnect of a quiet hotspot, switching
 * between Starlink and the iPhone) or switch Bluetooth off/on goes through here: a card at the top of
 * the screen says what's wrong and asks. Nothing happens without "Yes".
 *  - The card goes away by itself (nothing done) when the problem clears, or after [TIMEOUT_MS].
 *  - "Leave it" = no more questions of that kind for [QUIET_AFTER_NO_MS]; after any card, the same
 *    kind isn't asked again for [MIN_GAP_MS].
 * Joining a network while Wi-Fi is already down isn't asked: nothing working is cut.
 */
object WifiAsk {
    const val TIMEOUT_MS = 90_000L
    const val MIN_GAP_MS = 5 * 60_000L
    const val QUIET_AFTER_NO_MS = 30 * 60_000L

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val lastAskedAt = HashMap<String, Long>()
    private val quietUntil = HashMap<String, Long>()
    private var card: LinearLayout? = null
    private var cardKind: String? = null
    /** Asked / yes / no / cleared by itself, this session (for the doctor screen). */
    @Volatile var asked = 0; private set
    @Volatile var yes = 0; private set

    private fun now() = SystemClock.elapsedRealtime()

    /** Pure: may [kind] be asked now? */
    fun mayAsk(kind: String, now: Long, lastAsked: Long?, quiet: Long?, showing: Boolean): Boolean =
        !showing && (quiet == null || now >= quiet) && (lastAsked == null || now - lastAsked >= MIN_GAP_MS)

    /**
     * Ask the owner. [stillWrong] is checked every second (main thread); [onYes] runs on a new thread.
     * Returns false if not asked (a card is up, or this kind was asked / refused recently).
     */
    @Synchronized
    fun ask(ctx: Context, kind: String, title: String, why: String, yesLabel: String, stillWrong: () -> Boolean, onYes: () -> Unit): Boolean {
        if (!mayAsk(kind, now(), lastAskedAt[kind], quietUntil[kind], card != null || cardKind != null)) return false
        lastAskedAt[kind] = now()
        cardKind = kind
        asked++
        DebugLog.log("WIFI", "ASK [$kind] $title — $why")
        val app = ctx.applicationContext
        main.post { show(app, kind, title, why, yesLabel, stillWrong, onYes) }
        return true
    }

    private fun show(ctx: Context, kind: String, title: String, why: String, yesLabel: String, stillWrong: () -> Boolean, onYes: () -> Unit) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val shownAt = now()
        lateinit var watch: Runnable
        fun close(outcome: String) {
            main.removeCallbacks(watch)
            card?.let { runCatching { wm.removeView(it) } }
            card = null
            synchronized(this) { cardKind = null }
            DebugLog.log("WIFI", "ASK [$kind] → $outcome")
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rounded(Color.argb(245, 17, 24, 39), dp(18).toFloat(), Color.rgb(245, 158, 11), dp(3))
        }
        box.addView(TextView(ctx).apply { text = "📶 $title"; textSize = 18f; setTextColor(Color.WHITE); paint.isFakeBoldText = true })
        box.addView(TextView(ctx).apply { text = why; textSize = 14f; setTextColor(Color.rgb(209, 213, 219)); setPadding(0, dp(4), 0, dp(10)) })
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        fun button(label: String, color: Int, onClick: () -> Unit) = TextView(ctx).apply {
            text = label; textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            minHeight = dp(56); minWidth = dp(150); setPadding(dp(16), 0, dp(16), 0)
            background = rounded(color, dp(14).toFloat())
            setOnClickListener { onClick() }
        }
        row.addView(button("Leave it", Color.rgb(55, 65, 81)) {
            quietUntil[kind] = now() + QUIET_AFTER_NO_MS
            close("owner: leave it (no more '$kind' questions for 30 min)")
        })
        row.addView(button(yesLabel, Color.rgb(217, 119, 6)) {
            yes++
            close("owner: YES — doing it")
            Thread { runCatching(onYes).onFailure { DebugLog.log("WIFI", "ASK [$kind] action failed: ${it.message}") } }.start()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
        box.addView(row)
        val p = WindowManager.LayoutParams(
            dp(560), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(60) }
        if (runCatching { wm.addView(box, p) }.isFailure) {
            synchronized(this) { cardKind = null }
            DebugLog.log("WIFI", "ASK [$kind] couldn't show the card — nothing done")
            return
        }
        card = box
        watch = object : Runnable {
            override fun run() {
                when {
                    !runCatching(stillWrong).getOrDefault(false) -> close("fixed itself — nothing done")
                    now() - shownAt > TIMEOUT_MS -> close("no answer in ${TIMEOUT_MS / 1000} s — nothing done")
                    else -> main.postDelayed(this, 1000)
                }
            }
        }
        main.postDelayed(watch, 1000)
    }
}
