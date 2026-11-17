package com.docunest.dock

import com.docunest.dock.MiniModes.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniModesTest {
    @Test fun autoModeHasHysteresis() {
        assertEquals(Mode.CITY, MiniModes.detect(80.0, Mode.CITY))
        assertEquals(Mode.AUTOBAHN, MiniModes.detect(90.0, Mode.CITY))
        assertEquals(Mode.AUTOBAHN, MiniModes.detect(75.0, Mode.AUTOBAHN))
        assertEquals(Mode.CITY, MiniModes.detect(65.0, Mode.AUTOBAHN))
        // Explore is never picked by Auto.
        assertEquals(Mode.CITY, MiniModes.detect(10.0, Mode.EXPLORE))
    }

    @Test fun dieselInWholeKmThenHundreds() {
        assertEquals("⛽ 23 km", MiniModes.dieselText(23_400.0))
        assertEquals("⛽ 2 km", MiniModes.dieselText(2_400.0))
        assertEquals("⛽ 1900 m", MiniModes.dieselText(1_880.0))
        assertEquals("⛽ 100 m", MiniModes.dieselText(20.0))
    }

    @Test fun routeLineOnlyWhileOnCourse() {
        val c = MiniModes.OnCourse()
        assertFalse(c.feed(50.0, 0))
        assertFalse(c.feed(50.0, 7_000))
        assertTrue(c.feed(50.0, 8_000))
        assertTrue(c.feed(200.0, 9_000))      // between the limits: stays
        assertTrue(c.feed(400.0, 10_000))
        assertTrue(c.feed(400.0, 19_000))
        assertFalse(c.feed(400.0, 20_000))
        assertFalse(c.feed(null, 21_000))
    }

    @Test fun offCourseBlipDoesNotHide() {
        val c = MiniModes.OnCourse()
        c.feed(10.0, 0); c.feed(10.0, 8_000)
        c.feed(400.0, 9_000)
        c.feed(10.0, 12_000)                  // back on: timer resets
        assertTrue(c.feed(400.0, 15_000))
        assertTrue(c.feed(400.0, 24_000))
    }

    @Test fun predictMovesAlongCourseAndCapsAge() {
        val (la, ln) = MiniModes.predict(40.0, -8.0, 20f, 0f, 1.0)
        assertEquals(40.0 + 20 / 111_320.0, la, 1e-9)
        assertEquals(-8.0, ln, 1e-9)
        val (la2, _) = MiniModes.predict(40.0, -8.0, 20f, 0f, 10.0)
        assertEquals(40.0 + 50 / 111_320.0, la2, 1e-9)
        assertEquals(40.0 to -8.0, MiniModes.predict(40.0, -8.0, 0.5f, 90f, 1.0))
    }

    @Test fun shortestTurn() {
        assertEquals(20f, MiniModes.turn(350f, 10f), 1e-3f)
        assertEquals(-20f, MiniModes.turn(10f, 350f), 1e-3f)
    }
}
