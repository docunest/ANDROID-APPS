package com.docunest.dock

import com.docunest.dock.KeyGrid.Dir
import com.docunest.dock.KeyGrid.Pos
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyGridTest {
    private val L = KeyGrid.LETTERS
    private fun label(p: Pos) = L[p.row][p.col].label

    @Test fun everyRowIsTenUnitsWide() {
        for (page in listOf(KeyGrid.LETTERS, KeyGrid.SYMBOLS)) for (row in page) {
            assertEquals(10f, row.sumOf { it.w.toDouble() }.toFloat(), 0.001f)
            row.zipWithNext().forEach { (a, b) -> assertEquals(a.x + a.w, b.x, 0.001f) }
        }
    }

    @Test fun pushingMovesToTheNeighbour() {
        val g = KeyGrid.home(L)
        assertEquals("g", label(g))
        assertEquals("t", label(KeyGrid.move(L, g, Dir.UP)))
        assertEquals("v", label(KeyGrid.move(L, g, Dir.DOWN))) // the key drawn right below "g" (row starts with ⇧)
        assertEquals("f", label(KeyGrid.move(L, g, Dir.LEFT)))
        assertEquals("h", label(KeyGrid.move(L, g, Dir.RIGHT)))
    }

    @Test fun edgesWrapToTheNextRowSideways_butNotPastTopOrBottom() {
        assertEquals("1", label(KeyGrid.move(L, Pos(0, 0), Dir.UP)))
        assertEquals("q", label(KeyGrid.move(L, Pos(0, 9), Dir.RIGHT)))
        assertEquals("p", label(KeyGrid.move(L, Pos(2, 0), Dir.LEFT))) // "a" ← wraps to the end of the row above
        assertEquals("space", label(KeyGrid.move(L, Pos(3, 4), Dir.DOWN))) // "v" → space bar
    }

    @Test fun turningStepsThroughAllKeysAndWraps() {
        assertEquals("2", label(KeyGrid.step(L, Pos(0, 0), true)))
        assertEquals("q", label(KeyGrid.step(L, Pos(0, 9), true)))
        val last = Pos(L.lastIndex, L.last().lastIndex)
        assertEquals("1", label(KeyGrid.step(L, last, true)))
        assertEquals("▾", label(KeyGrid.step(L, Pos(0, 0), false)))
    }
}
