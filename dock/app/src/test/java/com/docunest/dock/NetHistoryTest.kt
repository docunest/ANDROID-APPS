package com.docunest.dock

import org.junit.Assert.assertTrue
import org.junit.Test

class NetHistoryTest {
    private fun s(t: Long, rssi: Int, ping: Int) = NetHistory.Sample(t, 1, "iPhone", rssi, ping, 72, 0f, 0f, 80f, 0.0, 0.0)
    private val t = 1_000_000L

    @Test fun mobileDataFailingFirst() {
        val samples = (0 until 30).map { s(t - 60_000 + it * 2_000L, -52, 20) }
        val checks = listOf(NetHistory.Check(t - 45_000, 20, null, null, false, "NO_DATA"))
        val d = NetHistory.diagnose(samples, checks, t)
        assertTrue(d, d.contains("signal good") && d.contains("internet failing") && d.contains("mobile data"))
    }

    @Test fun weakSignal() {
        val d = NetHistory.diagnose((0 until 30).map { s(t - 60_000 + it * 2_000L, -84, 40) }, emptyList(), t)
        assertTrue(d, d.contains("reception"))
    }

    @Test fun hotspotStoppedAnswering() {
        val samples = (0 until 30).map { s(t - 60_000 + it * 2_000L, -50, if (it > 15) -1 else 15) }
        val d = NetHistory.diagnose(samples, emptyList(), t)
        assertTrue(d, d.contains("stopped answering") && d.contains("silent"))
    }

    @Test fun signalCollapsingIsTheHotspotGoingAway_andMinus127IsNotASignal() {
        // 2026-11-27 on the unit: -42 dBm for a minute, -92 in the last seconds, then Android's -127 "no reading".
        val samples = (0 until 30).map { s(t - 60_000 + it * 2_000L, if (it >= 28) -92 else -42, 20) } + s(t, -127, -1)
        val d = NetHistory.diagnose(samples, emptyList(), t)
        assertTrue(d, d.contains("fell from -42 to -92") && d.contains("went away suddenly") && !d.contains("-127"))
    }
}
