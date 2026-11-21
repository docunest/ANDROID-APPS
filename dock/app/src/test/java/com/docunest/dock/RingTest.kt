package com.docunest.dock

import org.junit.Assert.assertEquals
import org.junit.Test

class RingTest {
    private val red = Ring.Arc(0xFFDC2626.toInt(), major = true)
    private val orange = Ring.Arc(0xFFF97316.toInt())

    @Test fun blipsUnder8sNeverShow() {
        val d = Ring.Debounce()
        var t = 0L
        repeat(7) { assertEquals(Ring.FINE, d.update(t, orange)); t += 1000 }
        assertEquals(Ring.FINE, d.update(t, Ring.FINE))
        repeat(7) { t += 1000; assertEquals(Ring.FINE, d.update(t, orange)) }
    }

    @Test fun lastingProblemShowsAfter8sAndClearsAfter3sFine() {
        val d = Ring.Debounce()
        var t = 0L
        repeat(8) { d.update(t, orange); t += 1000 }
        assertEquals(orange, d.update(t, orange))
        t += 1000; assertEquals(orange, d.update(t, Ring.FINE))
        t += 2000; assertEquals(orange, d.update(t, Ring.FINE)) // fine for 2 s: not yet
        t += 1000; assertEquals(Ring.FINE, d.update(t, Ring.FINE))
    }

    @Test fun worseningShowsAtOnce() {
        val d = Ring.Debounce()
        var t = 0L
        repeat(9) { d.update(t, orange); t += 1000 }
        assertEquals(red, d.update(t, red))
    }
}
