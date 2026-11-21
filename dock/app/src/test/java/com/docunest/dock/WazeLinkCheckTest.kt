package com.docunest.dock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WazeLinkCheckTest {
    private val u = "https://waze.com/ul?q=MRF&navigate=yes"
    private val t0 = 1_000_000L

    @Test fun onlyTheSamePlaceTwiceRestarts() {
        // One send: never (slow route planning, or looking at the route, is fine).
        assertFalse(WazeLinkCheck.shouldRestart(null, 0, u, t0, 0, false))
        // Same place again within 5 min, Waze never started navigating: restart.
        assertTrue(WazeLinkCheck.shouldRestart(u, t0, u, t0 + 40_000, 0, false))
        // A different place: no.
        assertFalse(WazeLinkCheck.shouldRestart("https://waze.com/ul?q=Lidl&navigate=yes", t0, u, t0 + 40_000, 0, false))
        // Same place but much later (a new leg after a stop): no.
        assertFalse(WazeLinkCheck.shouldRestart(u, t0, u, t0 + 6 * 60_000, 0, false))
        // Waze's log showed the first link started navigation: no.
        assertFalse(WazeLinkCheck.shouldRestart(u, t0, u, t0 + 40_000, t0 + 5_000, false))
        // A restart already waiting: no second one.
        assertFalse(WazeLinkCheck.shouldRestart(u, t0, u, t0 + 40_000, 0, true))
    }

    // Lines as seen in Waze's log on the unit, 2026-11-17.
    @Test fun navigationStartedLines() {
        assertTrue(WazeLinkCheck.startedNavigation("[17:08:34.194 7344 I] NavigationServiceImpl: startNavigationFlow is called: Params(caller=DeepLink, destination=x)"))
        assertFalse(WazeLinkCheck.startedNavigation("[17:08:31.560 7393 D] Auto nav to address 'MRF' [single_search.cc:293 (single_search_pos)]"))
        // The search alone isn't navigation (the owner may be looking at the route).
        assertFalse(WazeLinkCheck.startedNavigation("[17:08:31.559 7393 D] Executing query: MRF - Manuel Rodrigues Ferreira [urlscheme.cc:2080 (execute_query)]"))
        assertFalse(WazeLinkCheck.startedNavigation("IntentManager: Sending to native: ?q=MRF&navigate=yes"))
    }
}
