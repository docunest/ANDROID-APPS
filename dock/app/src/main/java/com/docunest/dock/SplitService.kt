package com.docunest.dock

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Presses Android's own "toggle split screen" and tells whether split screen
 * is on; and watches the *window list* (never their content) for the
 * floating button: where the keyboard is, and whether another app's overlay
 * covers the button — so the button moves out of the keyboard's way and
 * stays on top ([Watch]).
 */
class SplitService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    /** What the floating button needs to know about the other windows. */
    data class Watch(val keyboard: android.graphics.Rect?, val coveredBy: String?)

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val report = Runnable { onWindows?.let { l -> runCatching { l(watch()) } } }
    private var lastReport = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (type != AccessibilityEvent.TYPE_WINDOWS_CHANGED && type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (onWindows == null) return
        // Waze changes windows constantly: one look per second at most (coalesced).
        main.removeCallbacks(report)
        val wait = (1_000L - (android.os.SystemClock.elapsedRealtime() - lastReport)).coerceIn(150L, 1_000L)
        main.postDelayed({ lastReport = android.os.SystemClock.elapsedRealtime(); report.run() }, wait)
    }

    /**
     * Keyboard bounds (null = none showing), and whether a window sits above our button.
     *
     * Only window *bounds, types and layers* — answered by Android itself. Never a window's content
     * (`window.root`): Android forwards that to the owning app's main thread, and doing it on every window
     * change flooded System UI until "System UI isn't responding" (2026-11-14), besides leaking memory.
     */
    fun watch(): Watch {
        val all = runCatching { windows }.getOrDefault(emptyList())
        val r = android.graphics.Rect()
        val kb = all.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }?.let { w ->
            android.graphics.Rect().also { w.getBoundsInScreen(it) }.takeIf { !it.isEmpty }
        }
        var covered: String? = null
        val me = bubble
        if (me != null) {
            val mine = all.firstOrNull { w -> w.getBoundsInScreen(r); r == me }
            if (mine != null) for (w in all) {
                if (w === mine || w.layer <= mine.layer) continue
                if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD || w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
                    w.type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER) continue
                w.getBoundsInScreen(r)
                // Full-screen windows above us are system bars / shades (the button stays clear of the status bar): skip.
                if (!android.graphics.Rect.intersects(r, me) || (r.width() >= me.width() * 8 && r.height() >= me.height() * 8)) continue
                covered = "window type ${w.type} layer ${w.layer}"
                break
            }
        }
        @Suppress("DEPRECATION") all.forEach { runCatching { it.recycle() } }
        return Watch(kb, covered)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun inSplit(): Boolean = runCatching {
        val all = windows
        val split = all.any { it.type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER }
        @Suppress("DEPRECATION") all.forEach { runCatching { it.recycle() } }
        split
    }.getOrDefault(false)

    fun toggleSplit(): Boolean = performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)

    companion object {
        @Volatile var instance: SplitService? = null
        /** Called (main thread) whenever the window list changes (at most twice a second). */
        @Volatile var onWindows: ((Watch) -> Unit)? = null
        /** The floating button's bounds on screen (kept current by the dock service). */
        @Volatile var bubble: android.graphics.Rect? = null
    }
}
