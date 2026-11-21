package com.docunest.dock

import com.docunest.dock.BubblePlace.Area
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A 1280×720 head-unit screen: 40 px status bar on top, 50 px button. */
class BubblePlaceTest {
    private val safe = Area(4, 48, 1276, 712)

    @Test fun pushedUnderTheStatusBarComesBack() {
        // The owner's case: dragged up under the status bar (y = 5) — and off the right edge.
        assertEquals(1226 to 48, BubblePlace.clamp(1300, 5, 50, safe))
        assertEquals(4 to 662, BubblePlace.clamp(-80, 900, 50, safe))
        assertEquals(600 to 300, BubblePlace.clamp(600, 300, 50, safe))
    }

    @Test fun resetGoesLeftMiddle() = assertEquals(4 to 355, BubblePlace.default(50, safe))

    @Test fun keyboardPushesItUpOrHidesIt() {
        val kb = Area(0, 420, 1280, 720)
        assertEquals(600 to 100, BubblePlace.avoidKeyboard(600, 100, 50, kb, safe, 8)) // well above: stays
        assertEquals(600 to 362, BubblePlace.avoidKeyboard(600, 500, 50, kb, safe, 8)) // on the keys: just above
        assertNull(BubblePlace.avoidKeyboard(600, 500, 50, Area(0, 80, 1280, 720), safe, 8)) // no room: hide
        assertEquals(600 to 500, BubblePlace.avoidKeyboard(600, 500, 50, null, safe, 8)) // no keyboard
        assertEquals(10 to 500, BubblePlace.avoidKeyboard(10, 500, 50, Area(640, 420, 1280, 720), safe, 8)) // half-width keyboard elsewhere
    }
}
