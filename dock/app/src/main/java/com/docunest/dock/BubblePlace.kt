package com.docunest.dock

/**
 * Where the floating button may be (pure, unit-tested): always fully inside
 * the safe area (below the status bar, above the navigation bar, inside the
 * sides) so it can't be pushed somewhere it can't be touched — and never
 * over the keyboard: when one opens over it, it moves just above, or hides
 * if there's no room, and comes back when the keyboard closes.
 */
object BubblePlace {
    data class Area(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /** Fully inside [a]. */
    fun clamp(x: Int, y: Int, size: Int, a: Area): Pair<Int, Int> =
        x.coerceIn(a.left, maxOf(a.left, a.right - size)) to y.coerceIn(a.top, maxOf(a.top, a.bottom - size))

    /** Reset position: left edge, half-way down. */
    fun default(size: Int, a: Area): Pair<Int, Int> = a.left to (a.top + (a.bottom - a.top - size) / 2)

    /**
     * Out of the keyboard's way ([kb] = its bounds left, top, right, bottom; null = no keyboard).
     * Returns the position to use, or null = hide while the keyboard is up.
     */
    fun avoidKeyboard(x: Int, y: Int, size: Int, kb: Area?, a: Area, margin: Int): Pair<Int, Int>? {
        if (kb == null) return x to y
        val overlaps = x < kb.right && x + size > kb.left && y < kb.bottom && y + size + margin > kb.top
        if (!overlaps) return x to y
        val above = kb.top - margin - size
        return if (above >= a.top) x to above else null
    }
}
