package com.docunest.dock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiAskTest {
    @Test fun askingIsRateLimited() {
        val t = 10_000_000L
        assertTrue(WifiAsk.mayAsk("stall", t, null, null, showing = false))
        assertFalse("one card at a time", WifiAsk.mayAsk("stall", t, null, null, showing = true))
        assertFalse("not again within 5 min", WifiAsk.mayAsk("stall", t, t - 60_000, null, showing = false))
        assertTrue(WifiAsk.mayAsk("stall", t, t - WifiAsk.MIN_GAP_MS, null, showing = false))
        assertFalse("'Leave it' = quiet for 30 min", WifiAsk.mayAsk("stall", t, t - WifiAsk.MIN_GAP_MS, t + 1, showing = false))
    }
}
