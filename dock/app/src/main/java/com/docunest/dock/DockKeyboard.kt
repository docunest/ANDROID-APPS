package com.docunest.dock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.widget.TextView

/**
 * The Dock keyboard: a compact panel on one side of the screen, so typing in
 * Waze's search never covers the results list (the stock keyboard takes the
 * whole lower half). Its window spans the screen but is transparent and
 * tells Android not to shrink or move the app; only the panel takes touches,
 * so Waze keeps working everywhere else (Gboard's floating mode, the same way).
 *
 *  - Side: opposite the text cursor (Waze's search on the left → keyboard right); ⇄ flips it (remembered per app).
 *  - ▾ shrinks it to a small floating button (remembered per app, so re-focusing a field doesn't bring the
 *    full panel back); tap the button to bring it back.
 *  - iDrive: push = move to the neighbouring key, turn = next / previous key, press = type the highlighted key
 *    (bright yellow, thick border). Every hardware key it receives is logged (tag KEY) to map other controllers.
 */
class DockKeyboard : InputMethodService() {
    enum class Side { LEFT, RIGHT }

    private lateinit var root: FullHeight
    private lateinit var keys: KeysView
    private lateinit var pill: TextView
    private var side = Side.RIGHT
    private var sideChosenByHand = false
    private var minimized = false
    private var pkg = ""
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        DebugLog.init(this)
        // Draw on the GPU: the keyboard window otherwise may paint in software on this slow chip.
        runCatching {
            window?.window?.setFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        }
    }

    override fun onEvaluateFullscreenMode() = false // no full-screen "extract" mode in landscape

    override fun onCreateInputView(): View {
        root = FullHeight(this)
        keys = KeysView(this) { k -> onKey(k) }
        pill = TextView(this).apply {
            text = "⌨"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(Color.argb(235, 17, 24, 39), dp(28).toFloat(), Color.WHITE, dp(2))
            setOnClickListener { setMinimized(false) }
        }
        root.addView(keys)
        root.addView(pill)
        return root
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        pkg = info.packageName ?: ""
        val saved = getSharedPreferences("dock", Context.MODE_PRIVATE).getString("kbdSide:$pkg", null)
        sideChosenByHand = saved != null
        if (saved != null) side = Side.valueOf(saved)
        // ▾ sticks per app: re-focusing Waze's search doesn't throw the full panel back up.
        minimized = getSharedPreferences("dock", Context.MODE_PRIVATE).getBoolean("kbdMin:$pkg", false)
        keys.reset(info)
        // Where the text cursor is → keyboard on the other side.
        runCatching { currentInputConnection?.requestCursorUpdates(InputConnection.CURSOR_UPDATE_IMMEDIATE) }
        layoutPanel()
        MainWatch.start()
        DebugLog.log("KEY", "keyboard up in $pkg (side $side${if (sideChosenByHand) ", chosen by hand" else ""})")
    }

    override fun onUpdateCursorAnchorInfo(info: CursorAnchorInfo) {
        if (sideChosenByHand || info.insertionMarkerHorizontal.isNaN()) return
        val pt = floatArrayOf(info.insertionMarkerHorizontal, info.insertionMarkerTop)
        info.matrix.mapPoints(pt)
        val want = if (pt[0] < screenWidth() / 2f) Side.RIGHT else Side.LEFT
        if (want != side) {
            side = want
            layoutPanel()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        panelOnScreen = null
        onPanel?.invoke()
        MainWatch.stop()
    }

    /** Only the panel (or the small button) takes touches; the app isn't resized or moved. */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        if (!::root.isInitialized) return
        val h = root.height
        outInsets.contentTopInsets = h
        outInsets.visibleTopInsets = h
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        val v = if (minimized) pill else keys
        outInsets.touchableRegion.set(v.left, v.top, v.right, v.bottom)
    }

    private fun screenWidth(): Int {
        val m = DisplayMetrics()
        @Suppress("DEPRECATION") (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(m)
        return m.widthPixels
    }

    /** Panel ~46 % wide, ~60 % high, bottom corner on [side]; or the small button there when minimized. */
    private fun layoutPanel() {
        if (!::root.isInitialized) return
        val w = screenWidth()
        val m = DisplayMetrics().also { @Suppress("DEPRECATION") (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(it) }
        val pw = (w * 0.46f).toInt().coerceAtLeast(dp(420))
        val ph = (m.heightPixels * 0.60f).toInt().coerceAtLeast(dp(275))
        val g = Gravity.BOTTOM or if (side == Side.RIGHT) Gravity.END else Gravity.START
        root.want = ph + dp(6)
        keys.layoutParams = FrameLayout.LayoutParams(pw, ph, g).apply { setMargins(dp(6), 0, dp(6), dp(6)) }
        pill.layoutParams = FrameLayout.LayoutParams(dp(56), dp(56), g).apply { setMargins(dp(10), 0, dp(10), dp(10)) }
        keys.visibility = if (minimized) View.GONE else View.VISIBLE
        pill.visibility = if (minimized) View.VISIBLE else View.GONE
        root.requestLayout()
        root.post {
            val v = if (minimized) pill else keys
            val at = IntArray(2).also { v.getLocationOnScreen(it) }
            panelOnScreen = Rect(at[0], at[1], at[0] + v.width, at[1] + v.height)
            DebugLog.change("KEY", "panel", "${panelOnScreen?.toShortString()}", "screen ${w}×${m.heightPixels} · keyboard area ${root.width}×${root.height}")
            onPanel?.invoke() // the floating dock button moves out of the panel's way
        }
    }

    private fun setMinimized(m: Boolean) {
        minimized = m
        getSharedPreferences("dock", Context.MODE_PRIVATE).edit().putBoolean("kbdMin:$pkg", m).apply()
        layoutPanel()
    }

    private fun onKey(k: KeyGrid.Key) {
        val ic = currentInputConnection ?: return
        when (k.kind) {
            KeyGrid.Kind.CHAR -> {
                ic.commitText(if (keys.shift) k.text.uppercase() else k.text, 1)
                if (keys.shift) keys.setShift(false)
            }
            KeyGrid.Kind.SPACE -> ic.commitText(" ", 1)
            KeyGrid.Kind.BACKSPACE -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            KeyGrid.Kind.SHIFT -> keys.setShift(!keys.shift)
            KeyGrid.Kind.PAGE -> keys.flipPage()
            KeyGrid.Kind.ENTER -> {
                val info = currentInputEditorInfo
                val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
                val noAction = (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
                if (!noAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) ic.performEditorAction(action)
                else sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            }
            KeyGrid.Kind.SIDE -> {
                side = if (side == Side.LEFT) Side.RIGHT else Side.LEFT
                sideChosenByHand = true
                getSharedPreferences("dock", Context.MODE_PRIVATE).edit().putString("kbdSide:$pkg", side.name).apply()
                layoutPanel()
            }
            KeyGrid.Kind.HIDE -> setMinimized(true)
        }
    }

    // --- iDrive / hardware keys -------------------------------------------------------------------

    private val KEYS = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_NAVIGATE_NEXT, KeyEvent.KEYCODE_NAVIGATE_PREVIOUS, KeyEvent.KEYCODE_PAGE_DOWN,
        KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BUTTON_A, TURN_BACK, TURN_ON,
    )

    /**
     * An iDrive key for the panel (the car's software injects them as a "Virtual" keyboard; they
     * reach us ~4 ms after the press). Returns true when the panel used it.
     */
    private fun hardKey(event: KeyEvent): Boolean {
        if (!::keys.isInitialized) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            val lag = SystemClock.uptimeMillis() - event.eventTime
            DebugLog.log("KEY", "down ${KeyEvent.keyCodeToString(event.keyCode)} · ${lag} ms after the press" +
                " · source 0x${Integer.toHexString(event.source)} repeat ${event.repeatCount}")
        }
        if (!isInputViewShown || minimized || event.keyCode !in KEYS) return false
        if (event.action != KeyEvent.ACTION_DOWN) return true // the matching "up": swallowed too
        keys.pressAt = event.eventTime
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> keys.move(KeyGrid.Dir.UP)
            KeyEvent.KEYCODE_DPAD_DOWN -> keys.move(KeyGrid.Dir.DOWN)
            KeyEvent.KEYCODE_DPAD_LEFT -> keys.move(KeyGrid.Dir.LEFT)
            KeyEvent.KEYCODE_DPAD_RIGHT -> keys.move(KeyGrid.Dir.RIGHT)
            KeyEvent.KEYCODE_TAB -> keys.step(!event.isShiftPressed)
            KeyEvent.KEYCODE_NAVIGATE_NEXT, KeyEvent.KEYCODE_PAGE_DOWN -> keys.step(true)
            KeyEvent.KEYCODE_NAVIGATE_PREVIOUS, KeyEvent.KEYCODE_PAGE_UP -> keys.step(false)
            TURN_ON -> keys.step(true)
            TURN_BACK -> keys.step(false)
            else -> if (event.repeatCount == 0) keys.focused()?.let { onKey(it) } ?: keys.showFocus()
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        hardKey(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        hardKey(event) || super.onKeyUp(keyCode, event)

    /** Turning a rotary controller (if the unit reports it as scrolling): one step per detent. */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_SCROLL) return super.onGenericMotionEvent(event)
        val v = event.getAxisValue(MotionEvent.AXIS_SCROLL).takeIf { it != 0f } ?: event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        DebugLog.log("KEY", "scroll $v source 0x${Integer.toHexString(event.source)} rotary ${event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)}")
        if (!isInputViewShown || minimized || v == 0f) return super.onGenericMotionEvent(event)
        keys.step(v < 0)
        return true
    }

    /** The IME's root: as tall as the panel (transparent around it), never more than Android gives. */
    private class FullHeight(context: Context) : FrameLayout(context) {
        var want = 0
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(m)
            // Never taller than the space Android gives the keyboard window (the screen minus the
            // status bar): asking for the full screen height pushed the bottom row off screen.
            val given = MeasureSpec.getSize(heightMeasureSpec)
            val most = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED || given <= 0) m.heightPixels
                else minOf(given, m.heightPixels)
            val h = if (want > 0) minOf(want, most) else most
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        }
    }

    /** Draws the keys, handles touch, and the iDrive highlight. */
    @SuppressLint("ViewConstructor")
    private class KeysView(context: Context, val onKey: (KeyGrid.Key) -> Unit) : View(context) {
        private var page = KeyGrid.LETTERS
        var shift = false
            private set
        /** iDrive highlight; null until the controller is first used. */
        private var focus: KeyGrid.Pos? = null
        private var pressed: KeyGrid.Pos? = null
        private var enterLabel = "⏎"
        private val handler = Handler(Looper.getMainLooper())
        private val repeat = object : Runnable {
            override fun run() {
                val p = pressed ?: return
                val k = page.getOrNull(p.row)?.getOrNull(p.col) ?: return
                if (k.kind == KeyGrid.Kind.BACKSPACE) {
                    onKey(k)
                    handler.postDelayed(this, 70)
                }
            }
        }

        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(245, 15, 23, 42) }
        private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }

        fun reset(info: EditorInfo) {
            page = KeyGrid.LETTERS
            shift = false
            focus = null
            enterLabel = when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_SEARCH -> "Search"
                EditorInfo.IME_ACTION_GO -> "Go"
                EditorInfo.IME_ACTION_DONE -> "Done"
                EditorInfo.IME_ACTION_NEXT -> "Next"
                else -> "⏎"
            }
            invalidate()
        }

        fun setShift(on: Boolean) { shift = on; invalidate() }
        fun flipPage() {
            page = if (page === KeyGrid.LETTERS) KeyGrid.SYMBOLS else KeyGrid.LETTERS
            if (focus != null) focus = KeyGrid.home(page)
            invalidate()
        }
        fun focused(): KeyGrid.Key? = focus?.let { page[it.row][it.col] }
        fun showFocus() { focus = KeyGrid.home(page); invalidate() }
        fun move(d: KeyGrid.Dir) { focus = focus?.let { KeyGrid.move(page, it, d) } ?: KeyGrid.home(page); invalidate() }
        fun step(forward: Boolean) { focus = focus?.let { KeyGrid.step(page, it, forward) } ?: KeyGrid.home(page); invalidate() }

        private fun rect(r: Int, k: KeyGrid.Key): RectF {
            val pad = width * 0.006f
            val unit = (width - 2 * pad) / 10f
            val rowH = (height - 2 * pad) / page.size
            return RectF(pad + k.x * unit + pad, pad + r * rowH + pad, pad + (k.x + k.w) * unit - pad, pad + (r + 1) * rowH - pad)
        }

        private fun label(k: KeyGrid.Key): String = when (k.kind) {
            KeyGrid.Kind.CHAR -> if (shift) k.label.uppercase() else k.label
            KeyGrid.Kind.ENTER -> enterLabel
            KeyGrid.Kind.SPACE -> "space"
            else -> k.label
        }

        /** When the last iDrive key was pressed (uptime ms), to log how long until it was drawn. */
        var pressAt = 0L

        override fun onDraw(c: Canvas) {
            val t0 = SystemClock.uptimeMillis()
            val d = resources.displayMetrics.density
            c.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 14 * d, 14 * d, bg)
            for ((r, row) in page.withIndex()) for ((i, k) in row.withIndex()) {
                val box = rect(r, k)
                val isFocus = focus?.let { it.row == r && it.col == i } == true
                val isPressed = pressed?.let { it.row == r && it.col == i } == true
                keyPaint.color = when {
                    isFocus -> Color.parseColor("#FACC15") // the iDrive highlight: unmistakable
                    isPressed -> Palette.BLUE
                    k.kind == KeyGrid.Kind.CHAR || k.kind == KeyGrid.Kind.SPACE -> Color.parseColor("#334155")
                    k.kind == KeyGrid.Kind.ENTER -> Palette.BLUE
                    k.kind == KeyGrid.Kind.SHIFT && shift -> Color.parseColor("#64748B")
                    else -> Color.parseColor("#1E293B")
                }
                c.drawRoundRect(box, 7 * d, 7 * d, keyPaint)
                if (isFocus) {
                    ring.color = Color.WHITE
                    ring.strokeWidth = 4 * d
                    c.drawRoundRect(RectF(box).apply { inset(-2 * d, -2 * d) }, 9 * d, 9 * d, ring)
                }
                text.color = if (isFocus) Color.BLACK else Color.WHITE
                text.textSize = minOf(box.height() * 0.5f, if (label(k).length > 2) box.width() * 0.32f else box.width() * 0.6f)
                c.drawText(label(k), box.centerX(), box.centerY() + text.textSize * 0.36f, text)
            }
            if (pressAt > 0) {
                val now = SystemClock.uptimeMillis()
                DebugLog.log("KEY", "drawn ${now - pressAt} ms after the press (drawing took ${now - t0} ms, " +
                    if (c.isHardwareAccelerated) "GPU)" else "software)")
                pressAt = 0
            }
        }

        private fun hit(x: Float, y: Float): KeyGrid.Pos? {
            for ((r, row) in page.withIndex()) for ((i, k) in row.withIndex()) {
                if (rect(r, k).apply { inset(-3f, -3f) }.contains(x, y)) return KeyGrid.Pos(r, i)
            }
            return null
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressed = hit(e.x, e.y)
                    performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    handler.postDelayed(repeat, 450)
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    val now = hit(e.x, e.y)
                    if (now != pressed) { pressed = now; handler.removeCallbacks(repeat); invalidate() }
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(repeat)
                    pressed?.let { p -> page.getOrNull(p.row)?.getOrNull(p.col)?.let { onKey(it) } }
                    pressed = null
                    invalidate()
                }
                MotionEvent.ACTION_CANCEL -> { handler.removeCallbacks(repeat); pressed = null; invalidate() }
            }
            return true
        }
    }

    companion object {
        /** Where the panel (or its small button) is on screen while shown, for the floating dock button. */
        @Volatile var panelOnScreen: Rect? = null
            private set
        @Volatile var onPanel: (() -> Unit)? = null

        /** Android's own name for it ("com.docunest.dock/.DockKeyboard", the short form): `ime set` and
         *  Settings.Secure.DEFAULT_INPUT_METHOD use exactly this; the long form is "unknown input method". */
        /** The car's iDrive knob turns, as the KSW software injects them (unit's log: 691, 692 ~4 a second). */
        const val TURN_BACK = 691
        const val TURN_ON = 692

        fun id(context: Context) = android.content.ComponentName(context, DockKeyboard::class.java).flattenToShortString()
    }
}
