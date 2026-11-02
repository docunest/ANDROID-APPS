package com.docunest.camper

import com.docunest.camper.victron.VictronParser
import org.junit.Assert.assertEquals
import org.junit.Test

class VictronParserTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun publishedSolarAdvert() {
        // victron_ble's published test advert: SmartSolar, Absorption, 13.88 V, 1.4 A, 19 W
        val v = VictronParser.parse(hex("100242a0016207adceb37b605d7e0ee21b24df5c"), hex("adeccb947395801a4dd45a2eaa44bf17"))
        assertEquals("solar_charger", v.getString("type"))
        assertEquals("Absorption", v.getString("state_name"))
        assertEquals(13.88, v.getDouble("battery_voltage"), 1e-9)
        assertEquals(1.4, v.getDouble("battery_current"), 1e-9)
        assertEquals(19.0, v.getDouble("pv_power"), 1e-9)
    }

    @Test(expected = VictronParser.KeyMismatch::class)
    fun wrongKeyIsReported() {
        VictronParser.parse(hex("100242a0016207adceb37b605d7e0ee21b24df5c"), hex("00eccb947395801a4dd45a2eaa44bf17"))
    }
}
