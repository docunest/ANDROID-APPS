package com.docunest.dock

import com.docunest.dock.WifiDot.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiDotTest {
    private fun lvl(
        on: Boolean = true, conn: Boolean = false, net: Boolean? = null, visible: Boolean = false, kick: Boolean = false,
        link: LinkQuality.Level = LinkQuality.Level.UNKNOWN,
    ) = WifiDot.level(on, conn, net, visible, kick, link)

    @Test fun connected() {
        assertEquals(Level.ONLINE, lvl(conn = true, net = true))
        assertEquals(Level.ONLINE, lvl(conn = true, net = null)) // still checking
        assertEquals(Level.NO_INTERNET, lvl(conn = true, net = false)) // tunnel: iPhone has no data
    }

    @Test fun linkQualityWarnsBeforeTheDrop() {
        assertEquals(Level.ONLINE, lvl(conn = true, net = true, link = LinkQuality.Level.GREAT))
        assertEquals(Level.ONLINE, lvl(conn = true, net = true, link = LinkQuality.Level.OK))
        assertEquals(Level.POOR, lvl(conn = true, net = true, link = LinkQuality.Level.POOR))
        assertEquals(Level.NO_INTERNET, lvl(conn = true, net = true, link = LinkQuality.Level.BAD))
        assertTrue(WifiDot.arc(lvl(conn = true, net = true, link = LinkQuality.Level.GREAT)).fine)
        assertFalse(WifiDot.arc(lvl(conn = true, net = true, link = LinkQuality.Level.POOR)).fine)
    }

    @Test fun down() {
        assertEquals(Level.JOINING, lvl(visible = true))
        assertEquals(Level.JOINING, lvl(visible = true, kick = true)) // the kick worked: hotspot is there
        assertEquals(Level.KICKED, lvl(kick = true))
        assertEquals(Level.LOST, lvl())
    }

    @Test fun off() {
        assertEquals(Level.OFF, lvl(on = false, conn = true, net = true))
    }
}
