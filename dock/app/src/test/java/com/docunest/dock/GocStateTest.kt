package com.docunest.dock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Goodocom module's log, as seen live on the KSW unit (2026-11-13 handoff), and the Wi-Fi rule. */
class GocStateTest {
    @Test fun liveDisconnectReconnectSequence() {
        val s = GocState()
        s.feed(0, "goc: send_ind: [MG3]")
        s.feed(1, "GocsdkCallbackImp: onHfpStatus: 3")
        s.feed(2, "goc: send_ind: [SALukas’s iPhone]")
        assertTrue(s.phoneConnected)
        assertEquals("Lukas’s iPhone", s.name)
        // AT#CD
        s.feed(10, "goc: send_ind: [IA84D328D8B6130]")
        s.feed(11, "GocsdkCallbackImp: onHfpStatus: 1")
        s.feed(11, "goc: send_ind: [MU1]")
        assertFalse(s.phoneConnected)
        assertEquals(false, s.a2dp)
        assertEquals(-1L, s.connectedAt)
        // AT#CC84D328D8B613
        s.feed(12, "goc: send_ind: [IV84D328D8B613]")
        assertFalse(s.phoneConnected)
        assertEquals("84D328D8B613", s.mac)
        s.feed(19, "goc: send_ind: [IB84D328D8B613]")
        s.feed(19, "GocsdkCallbackImp: onHfpConnected")
        assertTrue(s.phoneConnected)
        assertEquals(19L, s.connectedAt)
        s.feed(26, "goc: send_ind: [MU2]")
        assertEquals(true, s.a2dp)
    }

    @Test fun callStates() {
        val s = GocState()
        s.feed(0, "goc: send_ind: [MG3]")
        assertFalse(s.inCall)
        s.feed(1, "goc: send_ind: [MG5]") // incoming
        assertTrue(s.inCall)
        assertTrue(s.phoneConnected)
        s.feed(2, "GocsdkCallbackImp: onHfpStatus: 6") // talking
        assertTrue(s.inCall)
        s.feed(3, "goc: send_ind: [MG3]")
        assertFalse(s.inCall)
    }

    @Test fun pairedListGivesTheMac() {
        val s = GocState()
        s.feed(0, "goc: send_ind: [MX184D328D8B613Lukas’s iPhone]")
        assertEquals("84D328D8B613", s.mac)
        assertEquals("Lukas’s iPhone", s.name)
        assertEquals("84D328D8B613", GocState.moduleMac("84:d3:28:d8:b6:13"))
    }

    @Test fun iPhoneHotspotAlwaysWinsOverStarlink() {
        val visible = mapOf("STARLINK" to -50, "Lukas’s iPhone" to -62)
        assertEquals("Lukas’s iPhone", WifiPrefer.switchTo("STARLINK", visible))
        assertNull(WifiPrefer.switchTo("Lukas’s iPhone", visible)) // already there
        assertEquals(3, WifiPrefer.rank("Lukas's iPhone")) // straight apostrophe still matches
        assertNull(WifiPrefer.switchTo("STARLINK", mapOf("STARLINK" to -50))) // iPhone not visible: stay
        assertNull(WifiPrefer.switchTo("STARLINK", mapOf("Lukas’s iPhone" to -90))) // too weak to join
        assertEquals("Ona’s iPhone", WifiPrefer.switchTo("STARLINK", mapOf("Ona’s iPhone" to -70)))
        assertEquals("Lukas’s iPhone", WifiPrefer.switchTo("Ona’s iPhone", mapOf("Lukas’s iPhone" to -70)))
    }
}
