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

    @Test fun realLiveLineFormat() {
        // Exactly as uploaded from the head unit on 2026-11-13.
        val s = GocState()
        s.feed(0, "1794583687.824 20738 20748 E goc     : send_ind: [IB84D328D8B613] serial")
        s.feed(1, "1794583690.100 20738 20748 E goc     : send_ind: [SALukas’s iPhone] serial")
        s.feed(2, "1794583695.391 20738 20748 E goc     : send_ind: [MU2] serial")
        assertEquals("Lukas’s iPhone", s.name)
        assertEquals("84D328D8B613", s.mac)
        assertTrue(s.phoneConnected)
        assertEquals(true, s.a2dp)
        s.feed(3, "1794583700.000 20738 20748 E goc     : send_ind: [MX184D328D8B613Lukas’s iPhone] serial")
        assertEquals("Lukas’s iPhone", s.name)
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

    /** Owner 2026-11-15: "Starlink signal wins — it's unmetered". Log: on Starlink at −60 dBm with internet, pulled to a −85 dBm iPhone. */
    @Test fun workingStarlinkWinsOverTheIPhone() {
        val visible = mapOf("StarlinkVilnius" to -60, "Lukas’s iPhone" to -75)
        assertNull(WifiPrefer.switchTo("StarlinkVilnius", visible, currentDbm = -60, currentWorks = true)) // stay
        assertEquals("StarlinkVilnius", WifiPrefer.switchTo("Lukas’s iPhone", visible)) // go to Starlink
        // Starlink weak (car 4 m away, through the body) or without internet: the iPhone.
        assertEquals("Lukas’s iPhone", WifiPrefer.switchTo("StarlinkVilnius", mapOf("StarlinkVilnius" to -84, "Lukas’s iPhone" to -60), currentDbm = -84))
        assertEquals("Lukas’s iPhone", WifiPrefer.switchTo("StarlinkVilnius", visible, currentDbm = -60, currentWorks = false))
        // Hysteresis: a −76 dBm Starlink isn't worth leaving the iPhone for, but isn't left either.
        assertNull(WifiPrefer.switchTo("Lukas’s iPhone", mapOf("StarlinkVilnius" to -76, "Lukas’s iPhone" to -60)))
        assertNull(WifiPrefer.switchTo("StarlinkVilnius", mapOf("StarlinkVilnius" to -76, "Lukas’s iPhone" to -60), currentDbm = -76, currentWorks = true))
        // A Starlink that just had no internet isn't switched back to.
        assertNull(WifiPrefer.switchTo("Lukas’s iPhone", visible, avoid = setOf("StarlinkVilnius")))
    }

    @Test fun amongTheMeteredOnesTheOwnersIPhoneFirst() {
        assertNull(WifiPrefer.switchTo("Lukas’s iPhone", mapOf("Lukas’s iPhone" to -62))) // already there
        assertEquals(3, WifiPrefer.rank("Lukas's iPhone")) // straight apostrophe still matches
        assertNull(WifiPrefer.switchTo("Lukas’s iPhone", mapOf("Ona’s iPhone" to -50)))
        assertEquals("Lukas’s iPhone", WifiPrefer.switchTo("Ona’s iPhone", mapOf("Lukas’s iPhone" to -70)))
        assertEquals("Ona’s iPhone", WifiPrefer.switchTo("Cafe", mapOf("Ona’s iPhone" to -70)))
        assertNull(WifiPrefer.switchTo("Cafe", mapOf("Lukas’s iPhone" to -90))) // too weak to join
    }
}
