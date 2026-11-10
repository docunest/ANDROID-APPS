package com.docunest.dock

import com.docunest.dock.FixStats.Attempt
import com.docunest.dock.FixStats.Outcome
import org.junit.Assert.assertTrue
import org.junit.Test

class FixReportTest {
    private val fixes = listOf("listen" to "Just wait", "assist" to "Fresh satellite data", "cold" to "Cold start").map { FixReport.Fix(it.first, it.second) }

    @Test fun emptyHistorySaysSoAndPlansLearning() {
        val t = FixReport.text(fixes, emptyList(), emptyList()) { it }
        assertTrue(t, t.contains("Nothing measured yet"))
        assertTrue(t, t.contains("Measured enough: 0 of 3"))
        assertTrue(t, t.contains("Next run tries, in order:"))
    }

    @Test fun measuredFixesGetAVerdict() {
        val a = List(12) { Attempt(it.toLong(), 0, "listen", "nofix", true, if (it < 2) Outcome.FIXED else Outcome.NO_FIX, 60, 60, 1) } +
            List(12) { Attempt(it.toLong(), 0, "assist", "nofix", true, if (it < 11) Outcome.FIXED else Outcome.NO_FIX, 40, 120, 1) } +
            List(3) { Attempt(it.toLong(), 0, "cold", "nofix", true, Outcome.VOID, 10, 180, 1) }
        val runs = List(12) { FixStats.Run(1_700_000_000_000 + it, true, "nofix", "fixed", "assist", 57, 2) }
        val t = FixReport.text(fixes, a, runs) { if (it == "nofix") "satellites, no fix" else it }
        assertTrue(t, t.contains("Fresh satellite data: helps"))
        assertTrue(t, t.contains("spoiled (tunnel / Stop, not counted): 3"))
        assertTrue(t, t.contains("Measured enough: 2 of 3"))
        assertTrue(t, t.contains("LAST RUNS"))
    }
}
