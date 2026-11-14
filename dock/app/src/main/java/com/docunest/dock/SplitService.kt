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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (type != AccessibilityEvent.TYPE_WINDOWS_CHANGED && type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (onWindows == null) return
        // Waze changes windows constantly: one look per half second at most.
        main.removeCallbacks(report)
        main.postDelayed(report, 500)
    }

    /**
     * Keyboard bounds (null = none showing), and the package of a window above our button, if any.
     * Our button's window is found by its position ([bubble]), not by reading every window's content:
     * that read created a node per window per change — never released on Android 10, it leaked memory
     * until the dock was killed (seen 2026-11-14). Only windows overlapping the button are read, and released.
     */
    fun watch(): Watch {
        val all = runCatching { windows }.getOrDefault(emptyList())
        val kb = all.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }?.let { w ->
            android.graphics.Rect().also { w.getBoundsInScreen(it) }.takeIf { !it.isEmpty }
        }
        val me = bubble
        var covered: String? = null
        if (me != null) {
            val r = android.graphics.Rect()
            val mine = all.firstOrNull { w -> w.getBoundsInScreen(r); r == me }
            if (mine != null) for (w in all) {
                if (w === mine || w.layer <= mine.layer || w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                w.getBoundsInScreen(r)
                if (!android.graphics.Rect.intersects(r, me)) continue
                val node = runCatching { w.root }.getOrNull() ?: continue
                val p = node.packageName?.toString()
                @Suppress("DEPRECATION") runCatching { node.recycle() }
                if (p == null || p == packageName || p == "com.android.systemui") continue
                covered = p
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
