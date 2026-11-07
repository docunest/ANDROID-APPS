package com.docunest.dock

import com.docunest.dock.ParkedLogic.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cold start at every ignition: when to show the parked position, and when to hand back. One tick per second. */
class ParkedLogicTest {
    private class Sim(hasSaved: Boolean = true) {
        val l = ParkedLogic(hasSaved)
        var t = 0L
        fun run(seconds: Int, strong: Int, fix: Boolean = false, driving: Boolean? = false): List<State> =
            List(seconds) { l.tick(t, fix, strong, driving).also { t += 1000 } }
    }

    @Test fun garageStartShowsParkedPositionAfter12s() {
        val s = Sim()
        val states = s.run(20, strong = 0)
        assertEquals(State.WATCHING, states[11])
        assertEquals(State.RESTORED, states[12])
    }

    @Test fun openSkyStartNeverMocks() {
        val s = Sim()
        val states = s.run(50, strong = 8) + s.run(5, strong = 8, fix = true)
        assertTrue(states.none { it == State.RESTORED })
        assertEquals(State.DONE, states.last())
    }

    @Test fun drivingOutOfTheGarageHandsBackAfter8s() {
        val s = Sim()
        s.run(60, strong = 0)
        val out = s.run(10, strong = 0, driving = true)
        assertEquals(State.RESTORED, out[7])
        assertEquals(State.DONE, out[8])
    }

    @Test fun aDoorSlamIsNotDriving() {
        val s = Sim()
        s.run(60, strong = 0)
        s.run(3, strong = 0, driving = true)
        assertEquals(State.RESTORED, s.run(30, strong = 0).last())
    }

    @Test fun realGpsIsCheckedEvery4MinFor30s() {
        val s = Sim()
        val states = s.run(12, strong = 0) + s.run(5 * 60, strong = 0)
        val firstProbe = states.indexOf(State.PROBING)
        assertEquals(12 + 240, firstProbe)
        assertEquals(State.RESTORED, states[firstProbe + 30]) // still no sky: back to the parked position
    }

    @Test fun probeThatSeesTheSkyHandsBack() {
        val s = Sim()
        s.run(12 + 240, strong = 0)
        val probe = s.run(3, strong = 0) + s.run(2, strong = 6)
        assertEquals(State.PROBING, probe[2])
        assertEquals(State.DONE, probe[3])
    }

    @Test fun probeWithARealFixHandsBack() {
        val s = Sim()
        s.run(12 + 241, strong = 0)
        assertEquals(State.DONE, s.run(1, strong = 2, fix = true).last())
    }

    @Test fun noSavedPositionNeverMocks() {
        val s = Sim(hasSaved = false)
        assertTrue(s.run(20 * 60, strong = 0).none { it == State.RESTORED })
    }

    @Test fun movingAtStartNeverMocks() {
        val s = Sim()
        assertTrue(s.run(60, strong = 0, driving = true).none { it == State.RESTORED })
    }

    @Test fun afterARealFixATunnelIsNotOurs() {
        val s = Sim()
        s.run(30, strong = 8, fix = true)
        assertTrue(s.run(10 * 60, strong = 0).all { it == State.DONE })
    }

    @Test fun onlyInTheFirst15Minutes() {
        val s = Sim()
        s.run(16 * 60, strong = 6, driving = null) // open sky but no fix (chip trouble — auto doctor's job)
        assertEquals(State.DONE, s.l.state)
    }

    @Test fun usingItNowWorksEvenAfterDone() {
        val s = Sim()
        s.run(5, strong = 8, fix = true)
        s.l.force(s.t)
        assertEquals(State.RESTORED, s.run(1, strong = 0).last())
    }
}
