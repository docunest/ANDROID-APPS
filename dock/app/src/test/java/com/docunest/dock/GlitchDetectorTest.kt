package com.docunest.dock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Drives near Noja (Cantabria), 1 fix per second, like a real GPS chip. */
class GlitchDetectorTest {
    private val lat0 = 43.48
    private val lng0 = -3.526
    private val mPerDegLat = 111_195.0

    /** Straight north: [moving] m/s really, chip says [reported] m/s; ±[noise] m position jitter. */
    private fun drive(seconds: Int, moving: (Int) -> Double, reported: (Int) -> Float, noise: Double = 0.0, seed: Int = 1): List<String> {
        val d = GlitchDetector()
        val rnd = Random(seed)
        var north = 0.0
        val out = mutableListOf<String>()
        for (i in 0 until seconds) {
            if (i > 0) north += moving(i)
            val jitter = if (noise > 0) (rnd.nextDouble() * 2 - 1) * noise else 0.0
            d.feed(GlitchDetector.Fix(i.toDouble(), lat0 + (north + jitter) / mPerDegLat, lng0, reported(i), 5f))?.let { out += it }
        }
        return out
    }

    @Test fun cleanDriveAt80WithNormalJitterRaisesNothing() {
        assertEquals(emptyList<String>(), drive(120, { 22.2 }, { 22.2f }, noise = 4.0))
    }

    @Test fun reports110WhileMovingAt80() {
        val g = drive(30, { 22.2 }, { 30.6f })
        assertTrue(g.toString(), g.isNotEmpty())
        assertTrue(g.first(), g.first().contains("Reported ~110 km/h but moved at 79 km/h"))
    }

    @Test fun suddenSpeedJump80To110() {
        val g = drive(20, { 22.2 }, { if (it < 10) 22.2f else 30.6f })
        assertTrue(g.toString(), g.first().startsWith("Speed jumped 79 → 110 km/h"))
    }

    @Test fun positionLeap() {
        val g = drive(20, { if (it == 10) 300.0 else 22.2 }, { 22.2f })
        assertTrue(g.toString(), g.any { it.startsWith("Position jumped") })
    }

    @Test fun hardBrakingAndTowingAccelerationAreFine() {
        // Brake 90 → 0 km/h at 7 m/s² (very hard), then pull away at 1.5 m/s².
        val speed = { i: Int -> if (i < 10) 25.0 else if (i < 14) maxOf(0.0, 25.0 - 7 * (i - 9)) else minOf(25.0, 1.5 * (i - 14)) }
        assertEquals(emptyList<String>(), drive(60, speed, { speed(it).toFloat() }))
    }

    @Test fun hairpinBendIsFine() {
        // 30 m radius at 8 m/s: straight-line distance is shorter than the arc, but not by enough to look wrong.
        val d = GlitchDetector()
        val r = 30.0
        val w = 8.0 / r
        val out = (0 until 40).mapNotNull { i ->
            val a = w * i
            val north = r * sin(a)
            val east = r * (1 - cos(a))
            d.feed(GlitchDetector.Fix(i.toDouble(), lat0 + north / mPerDegLat, lng0 + east / (mPerDegLat * cos(Math.toRadians(lat0))), 8f, 5f))
        }
        assertEquals(emptyList<String>(), out)
    }
}
