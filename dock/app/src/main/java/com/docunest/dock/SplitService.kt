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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (type != AccessibilityEvent.TYPE_WINDOWS_CHANGED && type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val l = onWindows ?: return
        runCatching { l(watch()) }
    }

    /** Keyboard bounds (null = none showing), and the package of a window above our button, if any. */
    fun watch(): Watch {
        val all = runCatching { windows }.getOrDefault(emptyList())
        val kb = all.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }?.let { w ->
            android.graphics.Rect().also { w.getBoundsInScreen(it) }.takeIf { !it.isEmpty }
        }
        val pkg = packageName
        val mine = all.firstOrNull { w -> runCatching { w.root?.packageName == pkg && w.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }.getOrDefault(false) }
        var covered: String? = null
        if (mine != null) {
            val me = android.graphics.Rect().also { mine.getBoundsInScreen(it) }
            for (w in all) {
                if (w === mine || w.layer <= mine.layer) continue
                // Other apps' overlays are reported as TYPE_SYSTEM too; the status bar is skipped by its package below.
                if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                val r = android.graphics.Rect().also { w.getBoundsInScreen(it) }
                if (!android.graphics.Rect.intersects(r, me)) continue
                val p = runCatching { w.root?.packageName?.toString() }.getOrNull() ?: continue
                if (p == pkg || p == "com.android.systemui") continue
                covered = p
                break
            }
        }
        return Watch(kb, covered)
    }
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun inSplit(): Boolean = runCatching {
        windows.any { it.type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER }
    }.getOrDefault(false)

    fun toggleSplit(): Boolean = performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)

    companion object {
        @Volatile var instance: SplitService? = null
        /** Called (main thread) whenever the window list changes. */
        @Volatile var onWindows: ((Watch) -> Unit)? = null
    }
}
