package com.docunest.dock

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 🧪 TEMPORARY iDrive test (owner's request, 2026-11-14): one action per step — slow / fast
 * wheel turns, pushes, holds, every console and steering-wheel button. For each step it records
 * what this screen receives (keys, scroll) and, through root, the raw input events (getevent)
 * and the system log since the step began — then logs it all under IDRIVE and uploads it, so
 * the Dock keyboard can be driven from the best source. Keys are swallowed here (their normal
 * action blocked) where Android lets an app do that; buttons the car's software handles itself
 * may still leave the app — that is recorded too. Remove once the keyboard is tuned.
 */
class IDriveLabActivity : Activity() {
    private data class Step(val id: String, val text: String)

    private val steps = listOf(
        Step("wheel-slow-right", "Turn the WHEEL RIGHT (clockwise) SLOWLY:\n5 clicks, about one per second"),
        Step("wheel-slow-left", "Turn the WHEEL LEFT SLOWLY:\n5 clicks, about one per second"),
        Step("wheel-fast-right", "Turn the WHEEL RIGHT FAST:\n10 clicks in about one second"),
        Step("wheel-fast-left", "Turn the WHEEL LEFT FAST:\n10 clicks in about one second"),
        Step("push-up", "PUSH the knob UP and let go —\n3 times, slowly"),
        Step("push-down", "PUSH the knob DOWN and let go —\n3 times, slowly"),
        Step("push-left", "PUSH the knob LEFT and let go —\n3 times, slowly"),
        Step("push-right", "PUSH the knob RIGHT and let go —\n3 times, slowly"),
        Step("hold-right", "PUSH RIGHT and HOLD it 3 seconds,\nthen let go"),
        Step("hold-down", "PUSH DOWN and HOLD it 3 seconds,\nthen let go"),
        Step("press", "PRESS the knob in (click) —\n3 times, slowly"),
        Step("press-hold", "PRESS the knob in and HOLD 3 seconds,\nthen let go"),
        Step("press-double", "DOUBLE-CLICK the knob (press in twice, quickly)"),
        Step("btn-back", "Press BACK (below the knob) once — short"),
        Step("btn-back-long", "Press and HOLD BACK 3 seconds"),
        Step("btn-option", "Press OPTION once — short"),
        Step("btn-option-long", "Press and HOLD OPTION 3 seconds"),
        Step("combo-back-wheel", "COMBO: hold BACK and turn the wheel\n3 clicks right, then let go"),
        Step("combo-option-push", "COMBO: hold OPTION and push the knob\nRIGHT once, then let go"),
        Step("combo-back-option", "COMBO: press BACK and OPTION together"),
        Step("combo-press-wheel", "COMBO: press the knob in, keep it pressed\nand turn 3 clicks right"),
        Step("btn-menu", "Press MENU once — short\n(may leave this screen — come back: Settings → 🧪 iDrive test)"),
        Step("btn-menu-long", "Press and HOLD MENU 3 seconds\n(may leave this screen)"),
        Step("btn-media", "Press MEDIA once — short\n(may leave this screen)"),
        Step("btn-media-long", "Press and HOLD MEDIA 3 seconds\n(may leave this screen)"),
        Step("btn-radio", "Press RADIO once — short\n(may leave this screen)"),
        Step("btn-radio-long", "Press and HOLD RADIO 3 seconds\n(may leave this screen)"),
        Step("btn-tel", "Press TEL once — short\n(may leave this screen)"),
        Step("btn-tel-long", "Press and HOLD TEL 3 seconds\n(may leave this screen)"),
        Step("btn-nav", "Press NAV once — short\n(may leave this screen)"),
        Step("btn-nav-long", "Press and HOLD NAV 3 seconds\n(may leave this screen)"),
        Step("wheel-mode", "Steering wheel: press MODE once — short"),
        Step("wheel-mode-long", "Steering wheel: HOLD MODE 3 seconds"),
        Step("wheel-plus", "Steering wheel: press + once — short"),
        Step("wheel-plus-long", "Steering wheel: HOLD + 3 seconds"),
        Step("wheel-minus", "Steering wheel: press − once — short"),
        Step("wheel-minus-long", "Steering wheel: HOLD − 3 seconds"),
        Step("wheel-blank", "Steering wheel: press the unlabelled button\n(left of −, under MODE) once — short"),
        Step("wheel-blank-long", "Steering wheel: HOLD the unlabelled button 3 seconds"),
        Step("wheel-phone", "Steering wheel: press 📞 phone once — short"),
        Step("wheel-phone-long", "Steering wheel: HOLD 📞 phone 3 seconds"),
        Step("wheel-voice", "Steering wheel: press 🗣 voice once — short"),
        Step("wheel-voice-long", "Steering wheel: HOLD 🗣 voice 3 seconds"),
        Step("combo-plus-minus", "COMBO: press + and − together"),
        Step("combo-mode-wheel", "COMBO: hold MODE and turn the iDrive wheel\n3 clicks right"),
    )

    private val main = Handler(Looper.getMainLooper())
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private lateinit var title: TextView
    private lateinit var instruction: TextView
    private lateinit var events: TextView
    private lateinit var raw: TextView
    private var index = 0
    private var stepStartUptime = 0L
    private var stepStartWall = 0L
    private val got = mutableListOf<String>()
    /** One line per complete press: "DPAD_RIGHT 120 ms" / "BACK 3010 ms (long, 14 repeats)". */
    private val presses = mutableListOf<String>()
    private val downAt = HashMap<Int, Long>()
    private val repeats = HashMap<Int, Int>()
    private var left = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLog.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        index = prefs().getInt("idriveLabStep", 0).coerceIn(0, steps.lastIndex)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        title = label("", 18f, Palette.MUTED, bold = true)
        head.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(bigButton("✕ End", Palette.CARD, 16f) { finishLab() }, LinearLayout.LayoutParams(dp(110), dp(56)))
        root.addView(head)

        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val leftCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        instruction = label("", 28f, bold = true).apply { setPadding(0, dp(10), 0, dp(14)) }
        leftCol.addView(instruction)
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        nav.addView(bigButton("◀ Previous", Palette.CARD_HI, 17f) { go(index - 1) }, LinearLayout.LayoutParams(0, dp(72), 1f).apply { marginEnd = dp(8) })
        nav.addView(bigButton("↺ Again", Palette.CARD_HI, 17f) { go(index) }, LinearLayout.LayoutParams(0, dp(72), 1f).apply { marginEnd = dp(8) })
        nav.addView(bigButton("Done ▶", Palette.BLUE, 20f) { go(index + 1) }, LinearLayout.LayoutParams(0, dp(72), 1.4f))
        leftCol.addView(nav)
        val jump = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((name, prefix) in listOf("Wheel" to "wheel-slow", "Push" to "push-", "Press" to "press", "Buttons" to "btn-", "Steering" to "wheel-mode", "Combos" to "combo-")) {
            jump.addView(bigButton(name, Palette.CARD, 14f) { steps.indexOfFirst { it.id.startsWith(prefix) }.takeIf { it >= 0 }?.let { go(it) } },
                LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6); topMargin = dp(8) })
        }
        leftCol.addView(jump)
        leftCol.addView(label("Tap the screen to move on — the iDrive is being recorded, not used. Each step is saved and sent when you tap Done.", 13f, Palette.MUTED).apply { setPadding(0, dp(10), 0, 0) })
        body.addView(leftCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f).apply { marginEnd = dp(14) })

        val rightCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        events = label("", 14f).apply { typeface = android.graphics.Typeface.MONOSPACE }
        raw = label("", 12f, Palette.MUTED).apply { typeface = android.graphics.Typeface.MONOSPACE }
        rightCol.addView(label("This screen received:", 14f, Palette.MUTED, bold = true))
        rightCol.addView(ScrollView(this).apply {
            background = rounded(Palette.CARD, dp(12).toFloat())
            setPadding(dp(10), dp(8), dp(10), dp(8))
            addView(LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; addView(events); addView(raw) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        body.addView(rightCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        DebugLog.log("IDRIVE", "test opened at step ${index + 1}/${steps.size} (${steps[index].id}) · controller: ${McuKeys.status}")
        McuKeys.start(this)
        McuKeys.rawListeners += rawListener
        begin()
    }

    private fun prefs() = getSharedPreferences("dock", Context.MODE_PRIVATE)

    /** Controller messages and the KSW service's injected keys, as they happen ([McuKeys]). */
    private val rawListener: (String) -> Unit = { line -> note("⚙ $line") }

    override fun onDestroy() {
        McuKeys.rawListeners -= rawListener
        super.onDestroy()
    }

    private fun begin() {
        got.clear()
        presses.clear()
        downAt.clear()
        repeats.clear()
        left = false
        stepStartUptime = SystemClock.uptimeMillis()
        stepStartWall = System.currentTimeMillis()
        prefs().edit().putInt("idriveLabStep", index).apply()
        title.text = "🧪 iDrive test · step ${index + 1} of ${steps.size}"
        instruction.text = steps[index].text
        events.text = "(nothing yet)"
        raw.text = ""
    }

    /** Finish the current step (record it) and show step [to]. */
    private fun go(to: Int) {
        val step = steps[index]
        val start = stepStartWall
        val mine = got.toList()
        val leftApp = left
        val presses = this.presses.toList()
        val end = System.currentTimeMillis()
        worker.execute { record(step, start, end, mine, leftApp, presses) }
        if (to > steps.lastIndex) {
            DebugLog.log("IDRIVE", "all ${steps.size} steps done")
            prefs().edit().putInt("idriveLabStep", 0).apply()
            finishLab()
            return
        }
        index = to.coerceIn(0, steps.lastIndex)
        begin()
    }

    private fun record(step: Step, startWall: Long, endWall: Long, mine: List<String>, leftApp: Boolean, presses: List<String>) {
        DebugLog.log("IDRIVE", "── ${step.id} · this screen got ${mine.size} event(s)${if (leftApp) " · LEFT THE APP" else ""}")
        mine.forEach { DebugLog.log("IDRIVE", "  app  $it") }
        if (presses.isNotEmpty()) DebugLog.log("IDRIVE", "  presses: " + presses.joinToString(" · "))
        val since = "%d.%03d".format(startWall / 1000, startWall % 1000)
        val until = "%d.%03d".format(endWall / 1000, endWall % 1000)
        val lc = Root.output(
            // Up to the moment Done was tapped, minus the controller chatter (speed, climate, heartbeats).
            "logcat -d -v epoch -t '$since' 2>/dev/null | awk '\$1 <= $until' | grep -i -E 'key|idrive|knob|rotat|encoder|wheel|button|mcu|swc|mode|media|voice|phone' " +
                "| grep -v -i -E 'docunest|IDRIVE|keystore|keyguard|keychain|data:19-|data:12-|data:10-|cmdType:68|cmdType:1C|mcuJson|acData|gear|CenterCallBack|ContextImpl|onMcuMessageChange' | head -200; true",
        )?.lines()?.filter { it.isNotBlank() }.orEmpty()
        DebugLog.log("IDRIVE", "  system log: ${lc.size} line(s)")
        lc.forEach { DebugLog.log("IDRIVE", "  log  ${it.trim().take(220)}") }
        main.post { if (!isFinishing) raw.text = "\nlast step: system log ${lc.size} line(s) — sent" }
        runCatching { DebugLog.uploadNow(applicationContext) }
    }

    private fun note(s: String) {
        val t = "+%.2f s  %s".format((SystemClock.uptimeMillis() - stepStartUptime) / 1000f, s)
        got += t
        events.text = got.takeLast(40).joinToString("\n")
    }

    /** Every key: recorded and swallowed (BACK included), so it does nothing else here. */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val action = when (e.action) { KeyEvent.ACTION_DOWN -> "down"; KeyEvent.ACTION_UP -> "up"; else -> "multi" }
        note("${KeyEvent.keyCodeToString(e.keyCode)} $action · repeat ${e.repeatCount} · scan ${e.scanCode} · held ${e.eventTime - e.downTime} ms" +
            " · late ${SystemClock.uptimeMillis() - e.eventTime} ms · dev ${e.device?.name ?: e.deviceId} · src 0x${Integer.toHexString(e.source)}" +
            (if (e.flags != 0) " · flags 0x${Integer.toHexString(e.flags)}" else "") + (if (e.metaState != 0) " · meta ${e.metaState}" else ""))
        val name = KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_")
        when (e.action) {
            KeyEvent.ACTION_DOWN -> if (e.repeatCount == 0) {
                downAt[e.keyCode] = e.eventTime
                val others = downAt.keys.filter { it != e.keyCode }
                if (others.isNotEmpty()) note("↳ COMBO: $name while holding ${others.joinToString { KeyEvent.keyCodeToString(it).removePrefix("KEYCODE_") }}")
            } else repeats[e.keyCode] = e.repeatCount
            KeyEvent.ACTION_UP -> {
                val held = e.eventTime - (downAt.remove(e.keyCode) ?: e.downTime)
                val rep = repeats.remove(e.keyCode) ?: 0
                val kind = when {
                    e.isCanceled -> "canceled"
                    held >= 500 || rep > 0 || e.flags and KeyEvent.FLAG_LONG_PRESS != 0 -> "long"
                    else -> "short"
                }
                presses += "$name $held ms ($kind${if (rep > 0) ", $rep repeats" else ""})"
                note("↳ $name: $kind press, held $held ms${if (rep > 0) ", $rep repeats" else ""}")
            }
        }
        return true
    }

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        val axes = listOf(MotionEvent.AXIS_SCROLL to "scroll", MotionEvent.AXIS_VSCROLL to "v", MotionEvent.AXIS_HSCROLL to "h")
            .mapNotNull { (a, n) -> e.getAxisValue(a).takeIf { it != 0f }?.let { "$n $it" } }
        note("motion ${MotionEvent.actionToString(e.action)} ${axes.joinToString(" ")} · dev ${e.device?.name ?: e.deviceId} · src 0x${Integer.toHexString(e.source)}")
        return true
    }

    override fun onPause() {
        super.onPause()
        if (!isFinishing) {
            left = true
            note("⚠ LEFT THE APP (this screen was covered or closed)")
        }
    }

    override fun onResume() {
        super.onResume()
        if (left) note("back on this screen")
    }

    private fun finishLab() {
        worker.execute {
            DebugLog.log("IDRIVE", "test closed")
            runCatching { DebugLog.uploadNow(applicationContext) }
        }
        finish()
    }

}
