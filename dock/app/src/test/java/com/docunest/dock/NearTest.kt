package com.docunest.dock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which places are up front, and how they're told apart — real Iberian addresses. */
class NearTest {
    private val day = 24 * 60 * 60_000L

    @Test fun nearOrFreshIsUpFront() {
        assertTrue("250 km, a month old", Near.keep(250_000f, 30 * day, false))
        assertTrue("1200 km ahead, sent yesterday (next destination)", Near.keep(1_200_000f, day, false))
        assertFalse("1200 km behind, 5 days old", Near.keep(1_200_000f, 5 * day, false))
        assertFalse("310 km, 4 days old", Near.keep(310_000f, 4 * day, false))
        assertTrue("pinned always", Near.keep(1_500_000f, 90 * day, true))
    }

    @Test fun unknownDistanceCountsAsFar() {
        assertFalse(Near.keep(null, 10 * day, false))
        assertTrue(Near.keep(null, day, false))
    }

    @Test fun townFromSpanishAndPortugueseAddresses() {
        assertEquals("Noja", Near.town("Av. de Ris, 39180 Noja, Cantabria, España"))
        assertEquals("Nazaré", Near.town("Av. da República 12, 2450-101 Nazaré, Portugal"))
        assertEquals("Vielha", Near.town("Carretera N-230, km 160, 25530 Vielha, Lleida, España"))
        assertEquals("Lisboa", Near.town("Praça Marquês de Pombal, 1250-160 Lisboa, Portugal"))
        assertEquals("Sintra", Near.town("Parque da Pena, Sintra, Portugal"))
    }

    @Test fun noTownWhenItIsAlreadyInTheNameOrMissing() {
        assertNull(Near.town("Av. de Ris, 39180 Noja, Cantabria, España", "Playa de Noja"))
        assertNull(Near.town(null))
        assertNull(Near.town("España"))
    }

    @Test fun arrows() {
        assertEquals("↑", Near.arrow(0f))
        assertEquals("↗", Near.arrow(40f))
        assertEquals("→", Near.arrow(91f))
        assertEquals("↓", Near.arrow(180f))
        assertEquals("↓", Near.arrow(-179f))
        assertEquals("↖", Near.arrow(-30f))
        assertEquals("↑", Near.arrow(350f))
    }

    @Test fun mapTileMaths() {
        // Nazaré at zoom 13 is tile 3889 / 3113 (standard slippy-map formula; tile checked by eye).
        val (gx, gy) = TileMath.pixel(39.6050, -9.0850, 13)
        assertEquals(3889, (gx / 256).toInt())
        assertEquals(3113, (gy / 256).toInt())
        assertTrue(TileMath.tiles(gx, gy, 256, 13).size in 1..4)
        // A point exactly in a tile's middle needs 1 tile; near a corner 4.
        assertEquals(1, TileMath.tiles(128.0 + 256 * 10, 128.0 + 256 * 20, 256, 13).size)
        assertEquals(4, TileMath.tiles(256.0 * 10 + 3, 256.0 * 20 + 3, 256, 13).size)
    }
}
