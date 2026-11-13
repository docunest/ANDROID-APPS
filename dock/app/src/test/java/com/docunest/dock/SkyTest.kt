package com.docunest.dock

import com.docunest.dock.Sky.Sat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyTest {
    private fun sat(sys: String, id: Int, cn0: Float, used: Boolean = false, eph: Boolean = false, az: Float = 0f, el: Float = 45f) =
        Sat(sys, id, cn0, used, eph, az, el)

    /** The owner's case: 8 good BeiDou, no fix — right after a cold start, orbits not downloaded yet. */
    @Test fun eightStrongBeiDouNoOrbitsNoFix() {
        val sats = (1..8).map { sat("BeiDou", 10 + it, 32f, az = it * 45f) } + sat("GPS", 5, 18f, eph = true)
        val why = Sky.explain(sats, hasFix = false)
        assertTrue(why.toString(), why.first().contains("8 strong satellites (8 BeiDou) but only 0 with orbit data"))
        assertTrue(why.toString(), why.any { it.contains("All strong satellites are BeiDou") })
    }

    @Test fun strongWithOrbitsSaysAFixIsComing() {
        val sats = (1..6).map { sat("GPS", it, 35f, eph = true, az = it * 60f) }
        assertTrue(Sky.explain(sats, false).first().contains("a fix should come within seconds"))
    }

    @Test fun weakSkyIsCalledWeak() {
        val sats = (1..7).map { sat("GLONASS", it, 18f, az = it * 50f) } + sat("GPS", 3, 28f)
        assertTrue(Sky.explain(sats, false).first().contains("only 1 strong"))
    }

    @Test fun withAFixStrongUnusedBeiDouIsExplained() {
        val sats = (1..5).map { sat("GPS", it, 34f, used = true, eph = true, az = it * 70f) } +
            (1..4).map { sat("BeiDou", 20 + it, 30f, used = false, eph = false, az = it * 80f) }
        val why = Sky.explain(sats, hasFix = true)
        assertTrue(why.toString(), why[0].startsWith("Fix from 5 satellites: 5 GPS"))
        assertTrue(why.toString(), why[1].contains("BeiDou: 4 strong but none used — the chip hasn't got their orbit data yet"))
    }

    @Test fun chipThatNeverSetsOrbitFlagsIsNotBlamedOnOrbits() {
        val sats = (1..5).map { sat("GPS", it, 34f, used = true, eph = false, az = it * 70f) } +
            (1..4).map { sat("BeiDou", 20 + it, 30f, az = it * 80f) }
        assertTrue(!Sky.orbitFlagsBelievable(sats))
        assertTrue(Sky.explain(sats, true)[1].contains("leaves them out"))
    }

    @Test fun allOnOneSide() {
        val strong = listOf(sat("GPS", 1, 30f, az = 100f), sat("GPS", 2, 30f, az = 140f), sat("GPS", 3, 30f, az = 170f))
        val t = Sky.oneSide(strong)!!
        assertTrue(t, t.contains("E (100°) to S (170°)"))
        assertNull(Sky.oneSide(listOf(sat("GPS", 1, 30f, az = 0f), sat("GPS", 2, 30f, az = 120f), sat("GPS", 3, 30f, az = 240f))))
    }

    @Test fun tableAndUsedList() {
        val sats = listOf(sat("BeiDou", 22, 31f, used = true, eph = true), sat("GPS", 12, 40f, used = true, eph = true),
            sat("GPS", 5, 33f, used = true, eph = true), sat("GLONASS", 7, 15f))
        assertEquals("GPS 12 5 · BeiDou 22", Sky.usedList(sats))
        val t = Sky.table(sats)
        assertTrue(t, t.lines()[1].startsWith("GPS"))
        assertTrue(t, t.lines().last().startsWith("TOTAL"))
    }

    @Test fun skyPositions() {
        val (x, y) = Sky.toXY(90f, 0f, 100f) // east on the horizon
        assertEquals(100f, x, 0.01f)
        assertEquals(0f, y, 0.01f)
        val (x2, y2) = Sky.toXY(0f, 90f, 100f) // straight up
        assertEquals(0f, x2, 0.01f)
        assertEquals(0f, y2, 0.01f)
    }
}
