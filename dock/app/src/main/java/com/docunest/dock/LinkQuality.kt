package com.docunest.dock

/**
 * Wi-Fi link quality from a rolling window of pings to the hotspot (every
 * 2 s, last [WINDOW] = ~30 s) — average round trip + share lost. Getting
 * worse is the hint that the connection is about to drop. Pure, unit-tested.
 *
 *   great ≤ 50 ms and < 10 % lost · ok ≤ 150 ms and < 20 % · poor ≤ 400 ms
 *   and < 40 % · bad otherwise · unknown with fewer than 3 pings.
 *
 * Self-adjusting: the ms limits grow with this link's own normal round trip
 * ([baseline], median of the last ~10 min of answers) — great ≤ 2.5×, ok ≤ 5×,
 * poor ≤ 10× baseline, never below the fixed limits above. A link that is
 * always 80 ms is normal for it, not "ok"; a jump from 8 to 120 ms is.
 */
class LinkQuality(private val window: Int = WINDOW, private val longWindow: Int = LONG_WINDOW) {
    enum class Level(val label: String) { UNKNOWN("measuring"), GREAT("great"), OK("ok"), POOR("poor — may drop"), BAD("bad — about to drop") }

    private val samples = ArrayDeque<Int?>()
    /** Answers over a longer time, for this link's normal round trip. Kept across drops (same hotspot, same normal). */
    private val history = ArrayDeque<Int>()

    /** One ping: round trip in ms, or null = no answer. */
    @Synchronized
    fun add(ms: Int?) {
        samples.addLast(ms)
        while (samples.size > window) samples.removeFirst()
        if (ms != null) {
            history.addLast(ms)
            while (history.size > longWindow) history.removeFirst()
        }
    }

    /**
     * This link's normal round trip: the 30th percentile of the last ~10 min of answers (null until 20).
     * Not the median: in a slow spell the median drifts up with it (seen live: 29 → 54 ms) and hides the spell.
     */
    @Synchronized
    fun baseline(): Int? = if (history.size < 20) null else history.sorted()[history.size * 3 / 10]

    /** The ms limits in use now: great, ok, poor. */
    fun limits(): Triple<Int, Int, Int> {
        val b = baseline() ?: 0
        return Triple(maxOf(50, (b * 2.5).toInt()), maxOf(150, b * 5), maxOf(400, b * 10))
    }

    @Synchronized
    fun clear() = samples.clear()

    @Synchronized
    fun count() = samples.size

    @Synchronized
    fun avgMs(): Int? = samples.filterNotNull().takeIf { it.isNotEmpty() }?.average()?.toInt()

    /** Share of pings without an answer, 0..1. */
    @Synchronized
    fun loss(): Float = if (samples.isEmpty()) 0f else samples.count { it == null }.toFloat() / samples.size

    fun level(): Level {
        if (count() < 3) return Level.UNKNOWN
        val avg = avgMs() ?: return Level.BAD
        val loss = loss()
        val (great, ok, _) = limits()
        // Slow answers alone are only "poor": an iPhone hotspot in power save answers in 300–400 ms with the
        // internet working fine (seen live 2026-11-13). "Bad" needs real loss, or answers over 1 s.
        return when {
            avg <= great && loss < 0.10f -> Level.GREAT
            avg <= ok && loss < 0.20f -> Level.OK
            loss < 0.40f && avg <= 1000 -> Level.POOR
            else -> Level.BAD
        }
    }

    /** "34 ms avg · 0 % lost · great" */
    fun text(): String {
        if (count() == 0) return "—"
        val avg = avgMs()?.let { "$it ms avg" } ?: "no answers"
        val (great, ok, _) = limits()
        return "$avg · ${(loss() * 100).toInt()} % lost · ${level().label}" +
            (baseline()?.let { " (normal here $it ms → great ≤ $great, ok ≤ $ok)" } ?: "")
    }

    companion object {
        const val WINDOW = 15
        const val LONG_WINDOW = 300
        const val WEAK_RSSI = -75

        /**
         * Was the drop explained by bad reception? From the last ~30 s before it:
         * signal (dBm, 0 = unknown) and this window. Returns (justified, sentence).
         */
        fun beforeDrop(lastRssi: Int, bestRssi: Int, q: LinkQuality): Pair<Boolean, String> {
            val level = q.level()
            val weakSignal = lastRssi != 0 && lastRssi <= WEAK_RSSI
            val badLink = level == Level.POOR || level == Level.BAD
            val facts = listOfNotNull(
                if (lastRssi != 0) "$lastRssi dBm" + (if (bestRssi != 0 && bestRssi != lastRssi) " (best $bestRssi)" else "") else null,
                q.avgMs()?.let { "ping $it ms" },
                if (q.count() > 0) "${(q.loss() * 100).toInt()} % lost" else null,
            ).joinToString(", ")
            return if (weakSignal || badLink) {
                true to "Reception was bad before the drop ($facts) — explained by signal / distance."
            } else if (q.count() >= 3 || lastRssi != 0) {
                false to "Link was fine right up to the drop ($facts) — sudden: the iPhone ended it or Android hung up."
            } else {
                false to "No measurements before the drop."
            }
        }
    }
}
