package com.docunest.dock

import com.docunest.dock.WifiPlan.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** One tick per second unless said otherwise. */
class WifiPlanTest {
    private class Sim {
        val p = WifiPlan()
        var t = 100_000L
        fun run(
            seconds: Int,
            visible: Boolean = false,
            connected: Boolean = false,
            joining: Boolean = false,
            bt: Boolean? = true,
            btSince: Long = 0L,
            call: Boolean = false,
            wifiOn: Boolean = true,
        ): List<Pair<Long, Action>> = List(seconds) {
            val s = p.next(WifiPlan.Input(t, connected, joining, visible, bt, btSince, call, wifiOn))
            (t to s.action).also { t += 1000 }
        }.filter { it.second != Action.NONE }
    }

    @Test fun hotspotVisibleIsJoinedWithin2sThenRetriedFast() {
        val s = Sim()
        val start = s.t
        val acts = s.run(80, visible = true)
        assertEquals(Action.JOIN, acts[0].second)
        assertEquals(2, ((acts[0].first - start) / 1000).toInt())
        val secs = acts.map { ((it.first - start) / 1000).toInt() }
        assertEquals(listOf(2, 8, 18, 38, 68), secs)
        assertEquals(Action.RESTART_AND_JOIN, acts[2].second) // every third try
    }

    @Test fun midHandshakeIsLeftAlone() {
        val s = Sim()
        s.run(3, visible = true) // first join at 2 s
        assertTrue(s.run(6, visible = true, joining = true).isEmpty())
    }

    @Test fun connectedResetsEverything() {
        val s = Sim()
        s.run(20, visible = true)
        s.run(1, connected = true)
        assertEquals(1, s.run(3, visible = true).size)
        assertEquals(1, s.p.joinTries)
    }

    @Test fun noHotspotButIPhoneOnBluetoothKicksAfter10s() {
        val s = Sim()
        val start = s.t
        val acts = s.run(260, btSince = 0L)
        assertEquals(Action.BT_KICK, acts[0].second)
        assertEquals(listOf(10, 55, 115, 235), acts.map { ((it.first - start) / 1000).toInt() })
    }

    @Test fun freshBluetoothConnectionGetsTimeToFireTheAutomation() {
        val s = Sim()
        val acts = s.run(40, btSince = s.t + 5_000)
        assertEquals(1, acts.size)
        assertTrue("kick only 25 s after BT came up", acts[0].first - (100_000L + 5_000) >= WifiPlan.BT_SETTLE_MS)
    }

    @Test fun neverDuringACall() {
        assertTrue(Sim().run(300, call = true).isEmpty())
    }

    @Test fun noPhoneOnBluetoothNoKick() {
        assertTrue(Sim().run(300, bt = false).isEmpty())
        assertTrue(Sim().run(300, bt = null).isEmpty())
    }

    @Test fun hotspotAppearsAfterKickAndGetsJoined() {
        val s = Sim()
        s.run(12)
        val acts = s.run(5, visible = true)
        assertEquals(Action.JOIN, acts.single().second)
    }

    @Test fun wifiOffDoesNothingHere() {
        assertTrue(Sim().run(60, visible = true, wifiOn = false).isEmpty())
    }
}
