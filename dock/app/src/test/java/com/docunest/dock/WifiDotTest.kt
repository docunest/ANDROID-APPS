package com.docunest.dock

import com.docunest.dock.WifiDot.Level
import org.junit.Assert.assertEquals
import org.junit.Test

class WifiDotTest {
    private fun lvl(on: Boolean = true, conn: Boolean = false, net: Boolean? = null, visible: Boolean = false, kick: Boolean = false) =
        WifiDot.level(on, conn, net, visible, kick)

    @Test fun connected() {
        assertEquals(Level.ONLINE, lvl(conn = true, net = true))
        assertEquals(Level.ONLINE, lvl(conn = true, net = null)) // still checking
        assertEquals(Level.NO_INTERNET, lvl(conn = true, net = false)) // tunnel: iPhone has no data
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
