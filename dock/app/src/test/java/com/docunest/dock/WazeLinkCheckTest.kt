package com.docunest.dock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WazeLinkCheckTest {
    // Lines as seen in Waze's log on the unit, 2026-11-17.
    @Test fun recognisesWazeTakingAndRunningTheLink() {
        assertTrue(WazeLinkCheck.tookLink("[17:08:31.559 7393 D] Executing query: MRF - Manuel Rodrigues Ferreira [urlscheme.cc:2080 (execute_query)]"))
        assertTrue(WazeLinkCheck.startedNavigation("[17:08:34.194 7344 I] NavigationServiceImpl: startNavigationFlow is called: Params(caller=DeepLink, destination=x)"))
    }
    @Test fun acceptingTheLinkIsNotTakingIt() {
        assertFalse(WazeLinkCheck.tookLink("[17:08:28.917 7393 W] URL to handle: ?q=MRF&navigate=yes [NativeManager_JNI.cc:950]"))
        assertFalse(WazeLinkCheck.tookLink("IntentManager: Sending to native: ?q=MRF&navigate=yes"))
        assertFalse(WazeLinkCheck.startedNavigation("[17:08:31.560 7393 D] Auto nav to address 'MRF' [single_search.cc:293 (single_search_pos)]"))
    }
}
