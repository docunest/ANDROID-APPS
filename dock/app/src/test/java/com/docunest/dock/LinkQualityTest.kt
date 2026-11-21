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
        assertEquals(Level.POOR, q(411, 380, 420, 400).level()) // iPhone power save: slow but nothing lost
        assertEquals(Level.BAD, q(900, 1700, 1200).level())
        assertEquals(Level.BAD, q(30, null, null, 30, null).level()) // 60 % lost
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

    @Test fun thresholdsFollowTheLinksOwnNormal() {
        // A link that's always ~80 ms (e.g. through Starlink) is normal for it, not "ok".
        val slow = LinkQuality()
        repeat(40) { slow.add(80) }
        assertEquals(80, slow.baseline())
        assertEquals(Level.GREAT, slow.level())
        // The live log: normal ~30 ms, then a slow spell — the reference must not drift up with it.
        val live = LinkQuality()
        repeat(200) { live.add(30) }
        repeat(60) { live.add(130) }
        assertEquals(30, live.baseline())
        // A fast hotspot (~8 ms) jumping to 120 ms: still within the fixed floors (ok), never "great"-inflated.
        val fast = LinkQuality()
        repeat(40) { fast.add(8) }
        repeat(15) { fast.add(120) }
        assertEquals(Level.OK, fast.level())
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
