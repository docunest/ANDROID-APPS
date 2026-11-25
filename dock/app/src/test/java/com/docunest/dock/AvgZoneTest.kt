package com.docunest.dock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvgZoneTest {
    // A 5 km section due north, limit 100.
    private val z = AvgZone.Zone("r1", 40.0, -8.0, 40.0 + 5000 / 111_320.0, -8.0, 100, "A1")

    private fun drive(kmh: Double, startT: Long = 0, northBearing: Float = 0f): List<AvgZone.State?> {
        val tr = AvgZone.Tracker(listOf(z))
        val out = ArrayList<AvgZone.State?>()
        val ms = kmh / 3.6
        var lat = 40.0 - 50 / 111_320.0 // 50 m before the start
        var t = startT
        repeat(400) {
            out += tr.feed(lat, -8.0, t, ms.toFloat(), northBearing)
            lat += ms / 111_320.0; t += 1000
        }
        return out
    }

    @Test fun averagesAndLeavesAtTheEnd() {
        val s = drive(110.0)
        val inside = s.filterNotNull()
        assertTrue("inside for ~160 s", inside.size in 150..175)
        val mid = inside[inside.size / 2]
        assertEquals(110.0, mid.avgKmh!!, 3.0)
        assertEquals(2, mid.level()) // over the limit
        assertNull(s.last()) // left at the end
    }

    @Test fun maxForTheRestKeepsTheAverage() {
        val mid = drive(90.0).filterNotNull().let { it[it.size / 2] }
        // Halfway at 90: the rest can be faster (~112) and still average 100.
        assertTrue(mid.maxRestKmh!! in 105.0..120.0)
        assertEquals(0, mid.level())
    }

    @Test fun wrongDirectionIsNotEntered() {
        assertTrue(drive(110.0, northBearing = 180f).all { it == null })
    }

    @Test fun parkedNearTheStartIsNotEntered() {
        val tr = AvgZone.Tracker(listOf(z))
        assertNull(tr.feed(40.0, -8.0, 0, 0f, null))
        assertNotNull(AvgZone.Tracker(listOf(z)).feed(40.0, -8.0, 0, 30f, 0f))
    }
}
