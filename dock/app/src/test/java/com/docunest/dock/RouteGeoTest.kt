package com.docunest.dock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteGeoTest {
    // A1 near Pombal, roughly north: three points ~5.5 km apart.
    private val lat = doubleArrayOf(39.90, 39.95, 40.00)
    private val lng = doubleArrayOf(-8.64, -8.63, -8.62)
    private val cum = RouteGeo.cumulative(lat, lng)

    @Test fun cumulativeAndProjection() {
        assertTrue(cum[2] in 11_000.0..11_600.0)
        // A Galp 300 m east of the middle point: ~5.6 km along, ~300 m off, on the right going north.
        val p = RouteGeo.project(lat, lng, cum, 39.95, -8.6265)
        assertTrue("along ${p.along}", p.along in 5_300.0..6_000.0)
        assertTrue("off ${p.off}", p.off in 200.0..330.0)
        assertTrue(!p.left)
        assertTrue(RouteGeo.project(lat, lng, cum, 39.95, -8.6335).left)
    }

    @Test fun pointAtAndStraight() {
        val (la, ln) = RouteGeo.pointAt(lat, lng, cum, cum[1])
        assertEquals(39.95, la, 1e-6); assertEquals(-8.63, ln, 1e-6)
        val (sl, sg) = RouteGeo.straight(39.0, -8.0, 40.0, -8.0, 10_000.0)
        assertEquals(12, sl.size) // 111 km / 10 km → 11 steps + start
        assertEquals(40.0, sl.last(), 1e-9); assertEquals(-8.0, sg.last(), 1e-9)
    }

    @Test fun autoZoom() {
        val T = AutoZoom.Phase.TOWN; val R = AutoZoom.Phase.ROAD; val O = AutoZoom.Phase.OPEN
        // Trip: town close-up near the start and the end, whatever the speed.
        assertEquals(T, AutoZoom.phaseFor(1_000.0, 250_000.0, 100.0, O))
        assertEquals(T, AutoZoom.phaseFor(250_000.0, 3_000.0, 100.0, O))
        // Middle of a long drive: far out; a town on the way (average dropped to 60) stays ROAD, never TOWN.
        assertEquals(O, AutoZoom.phaseFor(80_000.0, 170_000.0, 100.0, R))
        assertEquals(R, AutoZoom.phaseFor(80_000.0, 170_000.0, 60.0, O))
        assertEquals(R, AutoZoom.phaseFor(80_000.0, 170_000.0, 5.0, R))
        // Hysteresis: 72 km/h average keeps OPEN, needs 84+ to enter it.
        assertEquals(O, AutoZoom.phaseFor(80_000.0, 170_000.0, 72.0, O))
        assertEquals(R, AutoZoom.phaseFor(80_000.0, 170_000.0, 80.0, R))
        // No trip: by the averaged speed.
        assertEquals(T, AutoZoom.phaseFor(null, null, 20.0, T))
        assertEquals(R, AutoZoom.phaseFor(null, null, 50.0, T))
        assertEquals(O, AutoZoom.phaseFor(null, null, 110.0, R))
        assertEquals(25.0, MapViews.autoSpan(R), 0.0)
        // ＋ / － steps.
        assertEquals(8.0, MapViews.zoomStep(25.0, closer = true), 0.0)
        assertEquals(80.0, MapViews.zoomStep(25.0, closer = false), 0.0)
        assertEquals(3.0, MapViews.zoomStep(5.0, closer = true), 0.0)
        assertEquals(1.0, MapViews.zoomStep(1.0, closer = true), 0.0)
    }
}
