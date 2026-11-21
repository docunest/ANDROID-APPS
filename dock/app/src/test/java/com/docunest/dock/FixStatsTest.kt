package com.docunest.dock

import com.docunest.dock.FixStats.Attempt
import com.docunest.dock.FixStats.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FixStatsTest {
    private val remedies = listOf(
        "listen" to 60, "clock" to 90, "assist" to 120, "warm" to 150, "toggle" to 120, "cold" to 180, "driver" to 180,
    )

    private fun att(step: String, outcome: Outcome, secs: Int = 30, order: Int = 1, symptom: String = "nofix", run: Long = 1) =
        Attempt(run, 0, step, symptom, false, outcome, secs, 120, order)

    @Test fun wilsonMatchesTextbookValues() {
        val (lo0, hi0) = FixStats.wilson(0, 10)
        assertEquals(0.0, lo0, 1e-3)
        assertEquals(0.278, hi0, 1e-3)
        val (lo5, hi5) = FixStats.wilson(5, 10)
        assertEquals(0.237, lo5, 1e-3)
        assertEquals(0.763, hi5, 1e-3)
        assertEquals(0.0 to 1.0, FixStats.wilson(0, 0))
    }

    @Test fun attemptsAndRunsSurviveStorage() {
        val a = Attempt(1730970000000, 1730970060000, "warm", "nosats", true, Outcome.FIXED, 52, 150, 2)
        assertEquals(a, Attempt.decode(a.encode()))
        val r = FixStats.Run(1730970000000, false, "silent", "fixed", "driver", 245, 3)
        assertEquals(r, FixStats.Run.decode(r.encode()))
        assertEquals(null, FixStats.Run.decode(FixStats.Run(1, true, "x", "failed", null, 1, 1).encode())?.fixedBy)
        assertNull(Attempt.decode("garbage|line"))
    }

    @Test fun spoiledTriesAreKeptButNotCounted() {
        val s = FixStats.summarize(listOf(att("warm", Outcome.FIXED, 40), att("warm", Outcome.VOID), att("warm", Outcome.NO_FIX, 150)))
            .getValue("warm")
        assertEquals(2, s.tries)
        assertEquals(1, s.wins)
        assertEquals(1, s.voids)
        assertEquals(40, s.medianFixSecs)
    }

    @Test fun withNoDataEveryFixIsLearningAndAllAreInTheOrder() {
        val order = Planner.order(remedies, emptyList(), "nofix", Random(3))
        assertEquals(remedies.map { it.first }.toSet(), order.toSet())
        assertEquals(7, order.size)
    }

    @Test fun leastTriedGoesFirstWhileLearning() {
        val history = List(9) { att("listen", Outcome.NO_FIX) } + List(4) { att("warm", Outcome.FIXED) } +
            remedies.map { it.first }.filter { it !in setOf("listen", "warm", "driver") }.flatMap { id -> List(2) { att(id, Outcome.NO_FIX) } }
        val order = Planner.order(remedies, history, "nofix", Random(1))
        assertEquals("driver", order.first()) // 0 tries
        assertEquals("listen", order.last()) // 9 tries, still learning but most tried
    }

    @Test fun waitShrinksOnlyOnceAFixIsWellMeasured() {
        val few = FixStats.summarize(List(4) { att("warm", Outcome.FIXED, 20) }).getValue("warm")
        assertEquals(150, Planner.waitSecs(few, 150))
        val many = FixStats.summarize(List(10) { att("warm", Outcome.FIXED, 20 + it * 2) }).getValue("warm")
        // p90 = 36 s → 36 × 1.5 + 15 = 69 s
        assertEquals(69, Planner.waitSecs(many, 150))
        val fast = FixStats.summarize(List(12) { att("listen", Outcome.FIXED, 5) }).getValue("listen")
        assertEquals(45, Planner.waitSecs(fast, 60))
    }

    /**
     * Simulated outages with made-up "true" odds: the warm restart is the
     * best (70 % in ~40 s), waiting alone works 20 %, the driver restart
     * never helps. Runs the real planner the way the ladder does.
     */
    @Test fun everyFixGetsEnoughTriesThenTheBestOneLeads() {
        val truth = mapOf(
            "listen" to (0.20 to 25), "clock" to (0.05 to 30), "assist" to (0.35 to 60), "warm" to (0.70 to 40),
            "toggle" to (0.30 to 50), "cold" to (0.50 to 100), "driver" to (0.0 to 0),
        )
        val rnd = Random(42)
        val history = mutableListOf<Attempt>()
        val firstChoice = mutableListOf<String>()
        var runsUntilAllMeasured = -1
        for (run in 1..250L) {
            val order = Planner.order(remedies, history, "nofix", rnd)
            firstChoice += order.first()
            for ((i, id) in order.withIndex()) {
                val (p, t) = truth.getValue(id)
                val wait = Planner.waitSecs(FixStats.summarize(history)[id], remedies.toMap().getValue(id))
                val ok = rnd.nextDouble() < p && t <= wait
                history += att(id, if (ok) Outcome.FIXED else Outcome.NO_FIX, if (ok) t else wait, i + 1, run = run)
                if (ok) break
            }
            val stats = FixStats.summarize(history)
            if (runsUntilAllMeasured < 0 && remedies.all { (stats[it.first]?.tries ?: 0) >= FixStats.MIN_TRIES }) {
                runsUntilAllMeasured = run.toInt()
            }
        }
        val stats = FixStats.summarize(history)
        for ((id, _) in remedies) assertTrue("$id: ${stats[id]?.tries} tries", (stats[id]?.tries ?: 0) >= FixStats.MIN_TRIES)
        assertTrue("all measured after $runsUntilAllMeasured runs", runsUntilAllMeasured in 1..60)
        println("all 7 fixes had ${FixStats.MIN_TRIES}+ tries after $runsUntilAllMeasured simulated outages; late first choices: ${firstChoice.takeLast(100).groupingBy { it }.eachCount()}")
        val late = firstChoice.takeLast(100)
        val warmShare = late.count { it == "warm" } / late.size.toDouble()
        assertTrue("warm restart first in ${(warmShare * 100).toInt()} % of late runs: ${late.groupingBy { it }.eachCount()}", warmShare > 0.6)
        assertTrue("driver restart (never works) should almost never go first", late.count { it == "driver" } <= 3)
    }
}
