package com.docunest.camper

import com.docunest.camper.heater.HeaterProtocol as P
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same cases as docunest/BatteryPro diesel-heater/bridge/tests/test_protocol.py. */
class HeaterProtocolTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.hexs() = joinToString("") { "%02x".format(it) }

    @Test fun knownCommandPackets() {
        assertEquals("aa550c220100002f", P.command(P.STATUS).hexs())
        assertEquals("aa550c2203010032", P.command(P.POWER, 1).hexs())
        assertEquals("aa550c2203000031", P.command(P.POWER, 0).hexs())
    }

    @Test fun levelModeAndPasskey() {
        assertEquals("aa550c22040700" + "%02x".format((12 + 34 + 4 + 7) % 256), P.level(7).hexs())
        val m = P.command(P.SET_MODE, P.MODE_LEVEL)
        assertEquals(2, m[4].toInt()); assertEquals(1, m[5].toInt())
        val pk = P.command(P.STATUS, 0, 9876)
        assertEquals(98, pk[2].toInt()); assertEquals(76, pk[3].toInt()); assertEquals((98 + 76 + 1) % 256, pk[7].toInt() and 0xFF)
    }

    @Test(expected = IllegalArgumentException::class) fun levelOutOfRange() { P.level(11) }

    @Test fun aa55LevelMode() {
        val s = P.parse(hex("aa5500010303e8030108007e00c800190000"))!!
        assertEquals("AA55", s.protocol); assertTrue(s.running); assertEquals("running", s.step); assertTrue(s.heating)
        assertEquals("level", s.mode); assertEquals(8, s.level)
        assertEquals(12.6, s.voltage!!, 0.001); assertEquals(200.0, s.caseTemp!!, 0.001); assertEquals(25.0, s.roomTemp!!, 0.001)
        assertEquals(3, s.errorCode); assertTrue(s.error!!.startsWith("E3"))
    }

    @Test fun aa55TemperatureModeNegativeTemps() {
        val s = P.parse(hex("aa5500010003d204021704f0009cfffbff00"))!!
        assertEquals("temperature", s.mode); assertEquals(23, s.setTemp); assertEquals(5, s.level)
        assertEquals(-100.0, s.caseTemp!!, 0.001); assertEquals(-5.0, s.roomTemp!!, 0.001); assertEquals(24.0, s.voltage!!, 0.001)
    }

    @Test fun aa66Plain() {
        val d = ByteArray(20)
        d[0] = 0xAA.toByte(); d[1] = 0x66; d[3] = 1; d[5] = 3; d[8] = 1; d[9] = 4
        d[11] = 0x7E; d[12] = 0; d[13] = 0xE8.toByte(); d[14] = 0x03; d[15] = 19; d[16] = 0; d[17] = 0
        val s = P.parse(d)!!
        assertEquals("AA66", s.protocol); assertEquals(4, s.level); assertEquals(100.0, s.caseTemp!!, 0.001); assertEquals(19.0, s.roomTemp!!, 0.001)
        assertNull(s.error)
    }

    @Test fun encryptedAa55() {
        val d = ByteArray(48)
        d[0] = 0xAA.toByte(); d[1] = 0x55; d[3] = 1; d[5] = 3; d[8] = 1; d[10] = 6
        d[11] = 0; d[12] = 0x7C; d[13] = 0; d[14] = 0x96.toByte(); d[32] = 0; d[33] = 0xD7.toByte()
        val s = P.parse(P.xor(d))!!
        assertEquals("AA55enc", s.protocol); assertEquals(6, s.level); assertEquals(12.4, s.voltage!!, 0.001)
        assertEquals(150.0, s.caseTemp!!, 0.001); assertEquals(21.5, s.roomTemp!!, 0.001)
    }

    @Test fun encryptedAa66ErrorPosition() {
        val d = ByteArray(48)
        d[0] = 0xAA.toByte(); d[1] = 0x66; d[10] = 1; d[35] = 2
        val s = P.parse(P.xor(d))!!
        assertEquals("AA66enc", s.protocol); assertEquals(2, s.errorCode); assertFalse(s.running)
    }

    @Test fun garbageIgnored() {
        assertNull(P.parse(byteArrayOf(1, 2)))
        assertNull(P.parse(ByteArray(20)))
    }
}
