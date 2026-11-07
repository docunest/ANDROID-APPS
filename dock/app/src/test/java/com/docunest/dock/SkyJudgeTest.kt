package com.docunest.dock

import com.docunest.dock.SkyJudge.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The auto doctor's tunnel / car-park filter, one reading per second, on
 * real Iberian places: Túnel de Vielha (N-230, 5.2 km), Túnel do Marão
 * (A4, 5.7 km), the underground car park at Marquês de Pombal (Lisbon),
 * the beach at Noja (Cantabria).
 */
class SkyJudgeTest {
    private class Sim(val j: SkyJudge = SkyJudge()) {
        var t = 0L
        val calls = mutableListOf<SkyJudge.Call>()
        var lat = 0.0
        var lng = 0.0

        fun run(
            seconds: Int,
            strong: Int,
            fix: Boolean,
            speed: Float = 0f,
            driving: Boolean? = speed > 1f,
            talks: Boolean = true,
            settings: Boolean = true,
        ): List<SkyJudge.Call> {
            val out = mutableListOf<SkyJudge.Call>()
            repeat(seconds) {
                if (fix) lat += speed / 111_195.0
                val c = j.judge(SkyJudge.Reading(t, settings, talks, if (fix) SkyJudge.Fix(lat, lng, speed) else null, strong, driving))
                out += c
                t += 1000
            }
            calls += out
            return out
        }
    }

    private fun sim(lat: Double, lng: Double, zones: List<SkyJudge.Spot> = emptyList()) =
        Sim(SkyJudge { zones }).apply { this.lat = lat; this.lng = lng }

    @Test fun vielhaTunnelAt60IsWaitedOutNotFixed() {
        val s = sim(42.6235, 0.7600) // south portal, N-230
        s.run(60, strong = 9, fix = true, speed = 16.7f)
        val inside = s.run(5 * 60 + 15, strong = 0, fix = false, driving = true) // 5.2 km at 60 km/h
        assertTrue(inside.none { it.fixNow })
        assertEquals(Mode.TUNNEL, inside.last().mode)
        val out = s.run(10, strong = 3, fix = false, driving = true) + s.run(20, strong = 8, fix = false, driving = true) +
            s.run(30, strong = 9, fix = true, speed = 16.7f)
        assertTrue(out.none { it.fixNow })
        assertEquals(Mode.OK, out.last().mode)
    }

    @Test fun afterTheTunnelClearSkyButNoFixIsFixedWithin45s() {
        val s = sim(41.2830, -7.8930) // Túnel do Marão, A4
        s.run(30, strong = 8, fix = true, speed = 25f)
        s.run(4 * 60, strong = 0, fix = false, driving = true)
        val after = s.run(60, strong = 8, fix = false, driving = true)
        val first = after.indexOfFirst { it.fixNow }
        assertTrue("fix should start ~45 s after the sky is clear, was $first", first in 44..50)
        assertEquals("nofix", after[first].symptom)
    }

    @Test fun tunnelLongerThanAnyRealOneGetsFixed() {
        val s = sim(42.6235, 0.7600)
        s.run(30, strong = 9, fix = true, speed = 16.7f)
        val calls = s.run(13 * 60, strong = 0, fix = false, driving = true)
        val first = calls.indexOfFirst { it.fixNow }
        assertTrue(first > 11 * 60)
        assertEquals("nosky", calls[first].symptom)
    }

    @Test fun underCoverAtLowSpeedWaitsWhileParkedAndLearnsTheSpot() {
        val s = sim(38.7253, -9.1500) // Marquês de Pombal car park entrance
        s.run(60, strong = 7, fix = true, speed = 3f)
        val entrance = s.lat // where the GPS dropped (the van rolled 180 m down the street first)
        val parked = s.run(2 * 3600, strong = 0, fix = false, driving = false)
        assertTrue(parked.none { it.fixNow })
        assertEquals(Mode.COVERED, parked.last().mode)
        // Drive up the ramp for a minute, then the sky.
        s.run(60, strong = 0, fix = false, driving = true)
        s.run(20, strong = 7, fix = false, driving = true)
        assertTrue(s.calls.none { it.fixNow })
        s.run(5, strong = 7, fix = true, speed = 4f)
        val spot = s.j.selfRecoveredAt
        assertNotNull("the car park should be remembered", spot)
        assertEquals(entrance, spot!!.lat, 1e-6)

        // Next visit: known no-GPS spot straight away.
        val again = sim(entrance, -9.1500, listOf(spot))
        again.run(30, strong = 7, fix = true, speed = 2f)
        assertEquals(Mode.DEAD_ZONE, again.run(10, strong = 0, fix = false, driving = false).last().mode)
    }

    @Test fun drivingForMinutesWithoutSkyIsNotACarPark() {
        val s = sim(43.4800, -3.5260) // Noja, slow through town, then the antenna dies
        s.run(60, strong = 7, fix = true, speed = 4f)
        val calls = s.run(6 * 60, strong = 0, fix = false, driving = true)
        val first = calls.indexOfFirst { it.fixNow }
        assertTrue("fix after ~4 min of driving, was $first s", first in 235..250)
    }

    @Test fun silentChipIsFixedEvenInATunnel() {
        val s = sim(42.6235, 0.7600)
        s.run(30, strong = 9, fix = true, speed = 16.7f)
        val calls = s.run(60, strong = 0, fix = false, driving = true, talks = false)
        val first = calls.indexOfFirst { it.fixNow }
        assertTrue(first in 29..31)
        assertEquals("silent", calls[first].symptom)
    }

    @Test fun locationSwitchedOffIsFixedAfter5s() {
        val s = sim(43.4800, -3.5260)
        s.run(30, strong = 8, fix = true)
        val calls = s.run(10, strong = 8, fix = false, settings = false)
        assertEquals(5, calls.indexOfFirst { it.fixNow })
        assertEquals("off", calls.first { it.fixNow }.symptom)
    }

    @Test fun startUpUnderOpenSkyGets75sForAFirstFix() {
        val s = Sim(SkyJudge().apply { seed(SkyJudge.Fix(43.4800, -3.5260, 0f)) })
        val calls = s.run(90, strong = 8, fix = false, driving = false)
        assertEquals(75, calls.indexOfFirst { it.fixNow })
    }

    @Test fun startUpInTheGarageWaitsUntilWeDriveOut() {
        val s = Sim(SkyJudge().apply { seed(SkyJudge.Fix(38.7253, -9.1500, 0f)) })
        val calls = s.run(20 * 60, strong = 0, fix = false, driving = false)
        assertTrue(calls.none { it.fixNow })
        assertEquals(Mode.COVERED, calls.last().mode)
    }

    @Test fun bridgeDipsDontResetTheClearSkyClock() {
        val s = sim(43.4800, -3.5260)
        s.run(30, strong = 8, fix = true, speed = 20f)
        // No fix, clear sky, but a bridge every 20 s blocks it for 3 s.
        val calls = mutableListOf<SkyJudge.Call>()
        repeat(4) {
            calls += s.run(17, strong = 8, fix = false, driving = true)
            calls += s.run(3, strong = 0, fix = false, driving = true)
        }
        val first = calls.indexOfFirst { it.fixNow }
        assertTrue("should still fix within ~45–50 s, was $first", first in 44..52)
    }

    @Test fun wakingUpAfterANightParkedStartsAfresh() {
        val s = sim(42.6235, 0.7600)
        s.run(60, strong = 9, fix = true, speed = 16.7f)
        s.t += 9 * 3600_000L // ignition off overnight, head unit asleep
        val morning = s.run(60, strong = 8, fix = false, driving = false, talks = true)
        assertTrue("no instant fix on wake-up", morning.none { it.fixNow })
        assertEquals(75, (morning + s.run(30, strong = 8, fix = false, driving = false)).indexOfFirst { it.fixNow })
    }

    @Test fun workingGpsNeverCallsForAFix() {
        val s = sim(43.4800, -3.5260)
        val calls = s.run(3600, strong = 8, fix = true, speed = 22f)
        assertFalse(calls.any { it.fixNow })
        assertEquals(Mode.OK, calls.last().mode)
    }
}
