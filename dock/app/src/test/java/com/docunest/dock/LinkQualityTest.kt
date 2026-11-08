package com.docunest.dock

import com.docunest.dock.LinkQuality.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkQualityTest {
    private fun q(vararg ms: Int?) = LinkQuality().apply { ms.forEach { add(it) } }

    @Test fun levels() {
        assertEquals(Level.UNKNOWN, q(20, 30).level())
        assertEquals(Level.GREAT, q(20, 30, 45, 25).level())
        assertEquals(Level.OK, q(80, 120, 60, 90).level())
        assertEquals(Level.POOR, q(300, 250, 380, 200).level())
        assertEquals(Level.BAD, q(900, 700, 1200).level())
        assertEquals(Level.BAD, q(null, null, null).level())
    }

    @Test fun lossCountsEvenWithFastPings() {
        // 2 of 10 lost (20 %) → no longer great/ok even at 30 ms.
        assertEquals(Level.POOR, q(30, 30, null, 30, 30, null, 30, 30, 30, 30).level())
    }

    @Test fun rollingWindowForgetsOldPings() {
        val l = LinkQuality(window = 5)
        repeat(5) { l.add(800) }
        repeat(5) { l.add(25) }
        assertEquals(25, l.avgMs())
        assertEquals(Level.GREAT, l.level())
    }

    @Test fun dropJustifiedByBadReception() {
        val (justified, text) = LinkQuality.beforeDrop(-82, -60, q(300, 450, null, 500))
        assertTrue(justified)
        assertTrue(text, text.startsWith("Reception was bad"))
        assertTrue(text, text.contains("-82 dBm"))
    }

    @Test fun suddenDropOnAGoodLink() {
        val (justified, text) = LinkQuality.beforeDrop(-52, -50, q(25, 30, 28, 31))
        assertFalse(justified)
        assertTrue(text, text.startsWith("Link was fine"))
    }
}
