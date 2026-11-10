package com.docunest.dock

/**
 * The status marks on the floating button: small arcs that grow from the
 * bottom of its outline — GPS towards the left, Wi-Fi towards the right.
 * Nothing extra while all is fine (just the normal white outline); a short
 * arc (~1/10 of the circle) for a warning, a longer one (~1/6) when it's
 * gone. [Debounce] keeps blips off the screen.
 */
object Ring {
    data class Arc(val color: Int, val dashed: Boolean = false, val fine: Boolean = false, val major: Boolean = false)

    val FINE = Arc(0xFFFFFFFF.toInt(), fine = true)

    const val MINOR_DEG = 36f
    const val MAJOR_DEG = 60f

    /**
     * Show a problem only once it has lasted [SHOW_MS]; back to fine once fine for [CLEAR_MS].
     * Changing from one problem to another shows at once. Pure, unit-tested.
     */
    class Debounce(private val showMs: Long = SHOW_MS, private val clearMs: Long = CLEAR_MS) {
        var shown: Arc = FINE
            private set
        private var pendingSince = -1L
        private var pendingFine = true

        fun update(t: Long, now: Arc): Arc {
            if (now == shown) {
                pendingSince = -1
                return shown
            }
            if (!shown.fine && !now.fine) { // one problem → another: at once
                shown = now
                pendingSince = -1
                return shown
            }
            if (pendingSince < 0 || pendingFine != now.fine) {
                pendingSince = t
                pendingFine = now.fine
            }
            if (t - pendingSince >= if (now.fine) clearMs else showMs) {
                shown = now
                pendingSince = -1
            }
            return shown
        }
    }

    const val SHOW_MS = 8_000L
    const val CLEAR_MS = 3_000L
}
