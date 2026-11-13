package com.docunest.dock

import com.docunest.dock.NetVerdict.Checks
import com.docunest.dock.NetVerdict.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetVerdictTest {
    private fun v(hot: Boolean?, dns: Boolean?, code: Int?, ms: Int = 250) =
        NetVerdict.classify(Checks(if (hot == true) 18 else null, hot, if (dns == true) 40 else null, dns, if (code != null) ms else null, code))

    @Test fun allGood() = assertEquals(Level.WORKS, v(true, true, 204).level)

    @Test fun pingLossAloneDoesNotMeanNoInternet() {
        // The live log: 13–40 % pings lost to the iPhone while the internet kept working.
        assertEquals(Level.WORKS, v(false, true, 204).level)
    }

    @Test fun iPhoneWithoutMobileData() {
        val r = v(true, false, null)
        assertEquals(Level.NO_DATA, r.level)
        assertEquals(false, r.internet)
    }

    @Test fun hotspotStalled() {
        val r = v(false, false, null)
        assertEquals(Level.STALLED, r.level)
        assertTrue(r.text.startsWith("Hotspot stalled"))
    }

    @Test fun slowButWorkingCountsAsWorking() {
        assertEquals(Level.WORKS, v(true, true, 204, ms = 2_800).level) // 1–3 s replies still count
        val slow = v(true, true, 204, ms = 4_200)
        assertEquals(Level.SLOW, slow.level)
        assertEquals(true, slow.internet)
    }

    @Test fun dnsOkWebFails() = assertEquals(Level.BLOCKED, v(true, true, null).level)

    @Test fun dnsPacketRoundTrip() {
        val q = NetVerdict.dnsQuery(0x1234, "x7k2q9.connectivitycheck.gstatic.com")
        assertEquals(0x12, q[0].toInt())
        assertEquals(6, q[12].toInt()) // first label length
        // A reply: same id, response bit, NXDOMAIN (3) — still proves the internet round trip.
        val reply = q.copyOf().also { it[2] = 0x81.toByte(); it[3] = 0x83.toByte() }
        assertTrue(NetVerdict.dnsAnswered(0x1234, reply))
        val servfail = q.copyOf().also { it[2] = 0x81.toByte(); it[3] = 0x82.toByte() }
        assertFalse(NetVerdict.dnsAnswered(0x1234, servfail))
        assertFalse(NetVerdict.dnsAnswered(0x9999, reply))
    }
}
