package com.docunest.dock

import com.docunest.dock.GpsDot.Level
import org.junit.Assert.assertEquals
import org.junit.Test

class GpsDotTest {
    private fun lvl(age: Long? = 1000, acc: Float? = 5f, ok: Boolean = true, mock: Boolean = false, noSky: Boolean = false, asked: Boolean = true) =
        GpsDot.level(ok, mock, age, acc, noSky, asked)

    @Test fun fixQuality() {
        assertEquals(Level.GREAT, lvl(acc = 4f))
        assertEquals(Level.GREAT, lvl(acc = 10f))
        assertEquals(Level.GOOD, lvl(acc = 18f))
        assertEquals(Level.WEAK, lvl(acc = 60f))
        assertEquals(Level.GOOD, lvl(acc = null)) // chip gives no accuracy
    }

    @Test fun lateFixesAreBarelyWorking() {
        assertEquals(Level.WEAK, lvl(age = 12_000, acc = 4f))
        assertEquals(Level.GONE, lvl(age = 45_000))
    }

    @Test fun parkedPositionIsPinkEvenWithoutAFix() {
        assertEquals(Level.EMULATED, lvl(age = null, mock = true))
    }

    @Test fun tunnelIsBlackNotRed() {
        assertEquals(Level.NO_SKY, lvl(age = 90_000, noSky = true))
        assertEquals(Level.NO_SKY, lvl(age = null, noSky = true))
    }

    @Test fun offIsRedNobodyAskingIsGrey() {
        assertEquals(Level.GONE, lvl(ok = false))
        assertEquals(Level.UNKNOWN, lvl(age = null, asked = false))
        assertEquals(Level.GONE, lvl(age = null, asked = true))
    }

    @Test fun aFreshFixWinsOverAStaleTunnelCall() {
        assertEquals(Level.GREAT, lvl(acc = 6f, noSky = true))
    }
}
