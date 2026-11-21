package com.docunest.dock

import kotlin.math.abs

/**
 * The satellites the GPS chip reports (GnssStatus), as plain data: which are
 * heard, strong, have orbit data, are used in the fix — and, when there's no
 * fix although satellites are heard well ("8 strong BeiDou, no fix"), why.
 * Pure (unit-tested); drawn by [SkyPlotView] and on the dock map.
 */
object Sky {
    data class Sat(
        val system: String,
        val svid: Int,
        /** Signal, dB-Hz (≥ 25 strong, < 20 weak). */
        val cn0: Float,
        val used: Boolean,
        /** Orbit data (ephemeris) for this satellite is known — needed to use it. */
        val eph: Boolean,
        /** Degrees from north, clockwise. */
        val az: Float,
        /** Degrees above the horizon (0–90). */
        val el: Float,
    ) {
        val strong get() = cn0 >= STRONG
    }

    const val STRONG = 25f

    /** Fixed order for tables and legends. */
    val SYSTEMS = listOf("GPS", "Galileo", "GLONASS", "BeiDou", "QZSS", "SBAS", "other")

    data class Row(val system: String, val heard: Int, val strong: Int, val withOrbits: Int, val used: Int, val bestCn0: Float)

    fun rows(sats: List<Sat>): List<Row> = sats.groupBy { it.system }
        .map { (sys, l) -> Row(sys, l.size, l.count { it.strong }, l.count { it.eph }, l.count { it.used }, l.maxOf { it.cn0 }) }
        .sortedBy { SYSTEMS.indexOf(it.system).let { i -> if (i < 0) 99 else i } }

    /** Monospace table for the doctor screen. */
    fun table(sats: List<Sat>): String {
        if (sats.isEmpty()) return "No satellites reported yet."
        val rows = rows(sats)
        val head = "SYSTEM    HEARD STRONG ORBITS USED  BEST"
        val lines = rows.map { r ->
            "${r.system.padEnd(9)} ${r.heard.toString().padStart(5)} ${r.strong.toString().padStart(6)} ${r.withOrbits.toString().padStart(6)} ${r.used.toString().padStart(4)}  ${r.bestCn0.toInt()} dB-Hz"
        }
        val total = "TOTAL     ${sats.size.toString().padStart(5)} ${sats.count { it.strong }.toString().padStart(6)} ${sats.count { it.eph }.toString().padStart(6)} ${sats.count { it.used }.toString().padStart(4)}"
        return (listOf(head) + lines + total).joinToString("\n")
    }

    /** "GPS 5 12 25 · BeiDou 19 22" — the concrete satellites in the fix (by number), strongest first. */
    fun usedList(sats: List<Sat>): String = rows(sats).filter { it.used > 0 }.joinToString(" · ") { r ->
        r.system + " " + sats.filter { it.system == r.system && it.used }.sortedByDescending { it.cn0 }.joinToString(" ") { "${it.svid}" }
    }.ifEmpty { "none" }

    /**
     * Whether this chip's "has orbit data" flags can be believed: chips that never set them would make every
     * used satellite look orbit-less. Believable when a used satellite has the flag, or nothing is used yet.
     */
    fun orbitFlagsBelievable(sats: List<Sat>): Boolean {
        val used = sats.filter { it.used }
        return if (used.isEmpty()) sats.any { it.eph } else used.any { it.eph }
    }

    /** Plain sentences: what the sky looks like, and why there's no fix if there isn't one. */
    /** [orbitFlagsKnownGood]: this unit has been seen to report orbit data for used satellites before. */
    fun explain(sats: List<Sat>, hasFix: Boolean, orbitFlagsKnownGood: Boolean = false): List<String> {
        val out = mutableListOf<String>()
        val strong = sats.filter { it.strong }
        val orbitsOk = orbitFlagsKnownGood || orbitFlagsBelievable(sats)
        val rows = rows(sats)
        if (sats.isEmpty()) return listOf("No satellites heard: covered (car park, tunnel), or the antenna isn't connected.")

        if (hasFix) {
            out += "Fix from ${sats.count { it.used }} satellites: ${rows.filter { it.used > 0 }.joinToString { "${it.used} ${it.system}" }}."
            // Strong but unused systems — the "8 good BeiDou" question when a fix exists.
            for (r in rows.filter { it.strong >= 2 && it.used == 0 }) {
                val noOrbit = sats.count { it.system == r.system && it.strong && !it.eph }
                out += if (orbitsOk && noOrbit > 0) "${r.system}: ${r.strong} strong but none used — the chip hasn't got their orbit data yet ($noOrbit without). They join the fix once it has (~30 s of clean signal each)."
                else "${r.system}: ${r.strong} strong but none used — the chip has enough others and leaves them out (low in the sky, or not trusted yet)."
            }
            return out
        }

        // No fix.
        val withOrbit = strong.filter { it.eph }
        when {
            strong.size < 4 && sats.size >= 4 ->
                out += "Heard ${sats.size}, but only ${strong.size} strong (≥ 25 dB-Hz): too weak to read — sky partly blocked (roof, trees, buildings) or the antenna."
            strong.size < 4 ->
                out += "Only ${sats.size} satellite(s) heard — a fix needs at least 4 strong ones. Open sky helps."
            orbitsOk && withOrbit.size < 4 -> {
                val best = rows.maxByOrNull { it.strong }!!
                out += "${strong.size} strong satellites (${best.strong} ${best.system}) but only ${withOrbit.size} with orbit data. " +
                    "The chip must download each satellite's orbit (it's broadcast every 30 s) before it can use it — after a cold start " +
                    "that takes 30–60 s of clean signal, and bridges or tunnels restart it. \"Fresh satellite data\" (XTRA) skips the wait."
            }
            else ->
                out += "${strong.size} strong satellites" + (if (orbitsOk) " with orbit data — a fix should come within seconds. " +
                    "If it doesn't: the clock or the saved position may be wrong (\"Fix the clock\", \"Warm restart\")."
                else " — this chip doesn't say whether it has their orbit data yet; after a cold start that download takes 30–60 s of clean signal.")
        }
        // One system only: mixing needs an extra satellite per system, a single system needs 4 of its own.
        val strongSystems = strong.map { it.system }.distinct()
        if (strongSystems.size == 1 && strong.size >= 4) out += "All strong satellites are ${strongSystems[0]}: a fix needs 4 of them with orbit data."
        // Geometry: all strong satellites on one side of the sky.
        oneSide(strong)?.let { out += it }
        return out
    }

    /** "All strong satellites are between 120° and 200°…" when the strong ones cover less than a half of the sky's directions. */
    fun oneSide(strong: List<Sat>): String? {
        if (strong.size < 3) return null
        val az = strong.map { ((it.az % 360f) + 360f) % 360f }.sorted()
        // Largest empty gap between neighbouring directions (wrapping round).
        var gap = 0f
        var gapEnd = 0f
        for (i in az.indices) {
            val next = if (i == az.size - 1) az[0] + 360f else az[i + 1]
            val g = next - az[i]
            if (g > gap) { gap = g; gapEnd = next % 360f }
        }
        if (gap < 180f) return null
        val from = gapEnd
        val to = (gapEnd + (360f - gap)) % 360f
        return "All strong satellites are on one side of the sky (${dir(from)} to ${dir(to)}): the other side is blocked — positions come out poor or late."
    }

    private fun dir(a: Float): String {
        val names = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return names[(((a + 22.5f) % 360f) / 45f).toInt() % 8] + " (${a.toInt()}°)"
    }

    /** Colour per system for the sky plot and map (ARGB). */
    fun color(system: String): Int = when (system) {
        "GPS" -> 0xFF3B82F6.toInt()
        "Galileo" -> 0xFF22C55E.toInt()
        "GLONASS" -> 0xFFEF4444.toInt()
        "BeiDou" -> 0xFFF59E0B.toInt()
        "QZSS" -> 0xFFA855F7.toInt()
        "SBAS" -> 0xFF94A3B8.toInt()
        else -> 0xFF9CA3AF.toInt()
    }

    /** Sky position → x, y in a circle of radius [r] around (0,0); north up, [rotationDeg] = map rotation. */
    fun toXY(az: Float, el: Float, r: Float, rotationDeg: Float = 0f): Pair<Float, Float> {
        val dist = r * (90f - el.coerceIn(0f, 90f)) / 90f
        val a = Math.toRadians((az + rotationDeg).toDouble())
        return (dist * kotlin.math.sin(a)).toFloat() to (-dist * kotlin.math.cos(a)).toFloat()
    }

    fun close(a: Float, b: Float) = abs(a - b) < 0.5f
}
