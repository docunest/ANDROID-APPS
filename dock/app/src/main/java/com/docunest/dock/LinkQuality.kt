package com.docunest.dock

/**
 * Wi-Fi link quality from a rolling window of pings to the hotspot (every
 * 2 s, last [WINDOW] = ~30 s) — average round trip + share lost. Getting
 * worse is the hint that the connection is about to drop. Pure, unit-tested.
 *
 *   great ≤ 50 ms and < 10 % lost · ok ≤ 150 ms and < 20 % · poor ≤ 400 ms
 *   and < 40 % · bad otherwise · unknown with fewer than 3 pings.
 */
class LinkQuality(private val window: Int = WINDOW) {
    enum class Level(val label: String) { UNKNOWN("measuring"), GREAT("great"), OK("ok"), POOR("poor — may drop"), BAD("bad — about to drop") }

    private val samples = ArrayDeque<Int?>()

    /** One ping: round trip in ms, or null = no answer. */
    @Synchronized
    fun add(ms: Int?) {
        samples.addLast(ms)
        while (samples.size > window) samples.removeFirst()
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
        return when {
            avg <= 50 && loss < 0.10f -> Level.GREAT
            avg <= 150 && loss < 0.20f -> Level.OK
            avg <= 400 && loss < 0.40f -> Level.POOR
            else -> Level.BAD
        }
    }

    /** "34 ms avg · 0 % lost · great" */
    fun text(): String {
        if (count() == 0) return "—"
        val avg = avgMs()?.let { "$it ms avg" } ?: "no answers"
        return "$avg · ${(loss() * 100).toInt()} % lost · ${level().label}"
    }

    companion object {
        const val WINDOW = 15
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
