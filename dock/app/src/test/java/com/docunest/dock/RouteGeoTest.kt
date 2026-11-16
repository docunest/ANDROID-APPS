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
        assertEquals(3.0, MapViews.autoSpan(20f, 50_000.0), 0.0)     // town speed
        assertEquals(3.0, MapViews.autoSpan(110f, 2_000.0), 0.0)     // services right ahead
        assertEquals(25.0, MapViews.autoSpan(110f, 18_000.0), 0.0)
        assertEquals(80.0, MapViews.autoSpan(110f, 60_000.0), 0.0)   // empty motorway
        assertEquals(80.0, MapViews.autoSpan(110f, null), 0.0)
    }
}
