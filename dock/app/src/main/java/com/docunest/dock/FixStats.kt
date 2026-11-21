package com.docunest.dock

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * What the GPS doctor has learned: every fix it tried, whether a real GPS
 * fix followed and how long that took. Pure Kotlin (no Android classes) so
 * the maths is unit-tested — persistence is [FixHistory].
 *
 * Counting rules (keep them honest):
 *  - A step counts as tried only when it actually did something (skipped
 *    steps — "already on", "needs root" — are not stored).
 *  - FIXED = a fresh GPS fix (±100 m or better) arrived during that step's
 *    wait. The credit goes to the step during whose wait the fix came.
 *  - VOID = the try was spoiled: the sky was suddenly blocked during it
 *    (tunnel, car park), or the person pressed Stop. Stored, never counted.
 */
object FixStats {
    /** Tries per fix before its success rate is trusted (≈ ±30 % at 95 %). */
    const val MIN_TRIES = 10

    enum class Outcome { FIXED, NO_FIX, VOID }

    /** One fix step, tried once. [secs] = until the fix (FIXED) or time spent. */
    data class Attempt(
        val runId: Long,
        val at: Long,
        val step: String,
        val symptom: String,
        val auto: Boolean,
        val outcome: Outcome,
        val secs: Int,
        val waitSecs: Int,
        val order: Int,
    ) {
        fun encode() = listOf("a1", runId, at, step, symptom, if (auto) "auto" else "manual", outcome.name, secs, waitSecs, order)
            .joinToString("|")

        companion object {
            fun decode(line: String): Attempt? = runCatching {
                val p = line.split('|')
                if (p.size < 10 || p[0] != "a1") return null
                Attempt(p[1].toLong(), p[2].toLong(), p[3], p[4], p[5] == "auto", Outcome.valueOf(p[6]), p[7].toInt(), p[8].toInt(), p[9].toInt())
            }.getOrNull()
        }
    }

    /** One press of Fix GPS (or one auto run). [result]: fixed, failed, stopped, paused, blocked. */
    data class Run(
        val id: Long,
        val auto: Boolean,
        val symptom: String,
        val result: String,
        val fixedBy: String?,
        val secs: Int,
        val tried: Int,
    ) {
        fun encode() = listOf("r1", id, if (auto) "auto" else "manual", symptom, result, fixedBy ?: "-", secs, tried).joinToString("|")

        companion object {
            fun decode(line: String): Run? = runCatching {
                val p = line.split('|')
                if (p.size < 8 || p[0] != "r1") return null
                Run(p[1].toLong(), p[2] == "auto", p[3], p[4], p[5].takeIf { it != "-" }, p[6].toInt(), p[7].toInt())
            }.getOrNull()
        }
    }

    data class StepStats(
        val step: String,
        val tries: Int,
        val wins: Int,
        val voids: Int,
        /** Seconds from doing the step to the fix, over the wins. */
        val fixSecs: List<Int>,
        /** Seconds spent on the tries that didn't work. */
        val failSecs: List<Int>,
        /** Tries where this step went first (no earlier step could have helped). */
        val firstTries: Int,
        val firstWins: Int,
    ) {
        val rate: Double get() = if (tries == 0) 0.0 else wins.toDouble() / tries
        val learning: Boolean get() = tries < MIN_TRIES
        val medianFixSecs: Int? get() = percentile(fixSecs, 0.5)
        val p90FixSecs: Int? get() = percentile(fixSecs, 0.9)
        fun interval(): Pair<Double, Double> = wilson(wins, tries)

        /** Expected seconds spent on one try, with [p] as the chance it works. */
        fun expectedSecs(p: Double, waitSecs: Int): Double {
            val win = medianFixSecs?.toDouble() ?: (waitSecs / 2.0)
            return p * win + (1 - p) * (waitSecs + STEP_OVERHEAD_S)
        }
    }

    /** Doing a step (pausing GPS, root, downloads) costs a few seconds by itself. */
    const val STEP_OVERHEAD_S = 5

    fun summarize(attempts: List<Attempt>, symptom: String? = null): Map<String, StepStats> =
        attempts.filter { symptom == null || it.symptom == symptom }.groupBy { it.step }.mapValues { (step, list) ->
            val counted = list.filter { it.outcome != Outcome.VOID }
            val wins = counted.filter { it.outcome == Outcome.FIXED }
            val first = counted.filter { it.order == 1 }
            StepStats(
                step = step,
                tries = counted.size,
                wins = wins.size,
                voids = list.size - counted.size,
                fixSecs = wins.map { it.secs },
                failSecs = counted.filter { it.outcome == Outcome.NO_FIX }.map { it.secs },
                firstTries = first.size,
                firstWins = first.count { it.outcome == Outcome.FIXED },
            )
        }

    /** 95 % Wilson score interval: honest even for 0/3 or 3/3. */
    fun wilson(k: Int, n: Int, z: Double = 1.96): Pair<Double, Double> {
        if (n == 0) return 0.0 to 1.0
        val p = k.toDouble() / n
        val z2 = z * z
        val centre = (p + z2 / (2 * n)) / (1 + z2 / n)
        val half = z * sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / (1 + z2 / n)
        return max(0.0, centre - half) to min(1.0, centre + half)
    }

    fun percentile(xs: List<Int>, q: Double): Int? {
        if (xs.isEmpty()) return null
        val s = xs.sorted()
        return s[((s.size - 1) * q).toInt().coerceIn(0, s.size - 1)]
    }
}

/**
 * Picks the order of the GPS fixes for the next run.
 *
 *  1. Learning: any fix with fewer than [FixStats.MIN_TRIES] counted tries
 *     goes first, least-tried first (ties at random) — so every fix keeps
 *     getting tried, in every position, until its numbers mean something.
 *     Without this the ladder would stop at the same early winner forever
 *     and never learn whether a later fix is better.
 *  2. Once everything has enough tries: fastest expected fix first —
 *     chance it works ÷ seconds it costs (the classic best order for
 *     "try until one works"). The chance is drawn from its Beta posterior
 *     (Thompson sampling), so a close second still goes first now and then
 *     and the numbers stay current.
 *
 * Per-symptom numbers ("no satellites", "chip silent"…) are used once that
 * symptom has enough tries for the fix; until then the overall numbers.
 */
object Planner {
    fun order(
        remedies: List<Pair<String, Int>>,
        attempts: List<FixStats.Attempt>,
        symptom: String,
        rnd: Random = Random.Default,
    ): List<String> {
        val overall = FixStats.summarize(attempts)
        val bySymptom = FixStats.summarize(attempts, symptom)
        val tries = { id: String -> overall[id]?.tries ?: 0 }
        val (learning, known) = remedies.partition { tries(it.first) < FixStats.MIN_TRIES }
        val first = learning.map { it to rnd.nextDouble() }.sortedWith(compareBy({ tries(it.first.first) }, { it.second })).map { it.first.first }
        val rest = known.map { (id, defaultWait) ->
            val s = bySymptom[id]?.takeIf { !it.learning } ?: overall.getValue(id)
            val p = beta(s.wins + 1.0, s.tries - s.wins + 1.0, rnd)
            val cost = s.expectedSecs(p, waitSecs(s, defaultWait))
            id to p / max(1.0, cost)
        }.sortedByDescending { it.second }.map { it.first }
        return first + rest
    }

    /**
     * How long to wait for a fix after a step. While learning: the full
     * default, so the numbers stay comparable. After that: long enough for
     * nine in ten of the fixes it has brought (×1.5 + 15 s), never below
     * 45 s and never above the default.
     */
    fun waitSecs(s: FixStats.StepStats?, defaultSecs: Int): Int {
        if (s == null || s.learning || s.fixSecs.size < 5) return defaultSecs
        val p90 = s.p90FixSecs ?: return defaultSecs
        return (p90 * 1.5 + 15).toInt().coerceIn(min(45, defaultSecs), defaultSecs)
    }

    /** Beta(a, b) sample via two Gamma samples. */
    fun beta(a: Double, b: Double, rnd: Random): Double {
        val x = gamma(a, rnd)
        val y = gamma(b, rnd)
        return if (x + y <= 0) 0.5 else x / (x + y)
    }

    /** Marsaglia–Tsang; shape ≥ 1 here (counts + 1). */
    private fun gamma(shape: Double, rnd: Random): Double {
        if (shape < 1) return gamma(shape + 1, rnd) * rnd.nextDouble().pow(1 / shape)
        val d = shape - 1.0 / 3
        val c = 1 / sqrt(9 * d)
        while (true) {
            var x: Double
            var v: Double
            do {
                x = normal(rnd)
                v = 1 + c * x
            } while (v <= 0)
            v = v * v * v
            val u = rnd.nextDouble()
            if (u < 1 - 0.0331 * x * x * x * x) return d * v
            if (ln(u) < 0.5 * x * x + d * (1 - v + ln(v))) return d * v
        }
    }

    private fun normal(rnd: Random): Double {
        val u1 = rnd.nextDouble().coerceAtLeast(1e-12)
        val u2 = rnd.nextDouble()
        return sqrt(-2 * ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)
    }
}
