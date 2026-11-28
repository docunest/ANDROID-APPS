package com.docunest.dock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WazeLinkCheckTest {
    // Lines as seen in Waze's log on the unit, 2026-11-17 / 11-20.
    @Test fun recognisesWazeTakingAndRunningTheLink() {
        assertTrue(WazeLinkCheck.tookLink("[17:08:31.559 7393 D] Executing query: MRF - Manuel Rodrigues Ferreira [urlscheme.cc:2080 (execute_query)]"))
        assertTrue(WazeLinkCheck.startedNavigation("[17:08:34.194 7344 I] NavigationServiceImpl: startNavigationFlow is called: Params(caller=DeepLink, destination=x)"))
    }
    @Test fun theEngineApplyingTheLinkIsReceipt() {
        assertTrue(WazeLinkCheck.reachedEngine("[13:11:04.157 7393 W] Url is not valid: ?q=Av. Manuel Remígio 55&navigate=yes [urlscheme.cc:2379 (urlscheme_apply)]"))
        assertTrue(WazeLinkCheck.reachedEngine("[13:11:04.157 7393 W] Falling back to init without prefix: ?q=Av. Manuel Remígio 55&navigate=yes [urlscheme.cc:2356 (urlscheme_apply)]"))
        assertTrue(WazeLinkCheck.reachedEngine("[17:08:31.559 7393 D] Executing query: MRF [urlscheme.cc:2080 (execute_query)]"))
    }
    @Test fun javaSideHandoverIsNotReceipt() {
        // A stuck Waze gets this far and stops: Android delivered it, the engine never applied it.
        assertFalse(WazeLinkCheck.reachedEngine("[17:08:28.917 7393 W] URL to handle: ?q=MRF&navigate=yes [NativeManager_JNI.cc:950]"))
        assertFalse(WazeLinkCheck.reachedEngine("IntentManager: Sending to native: ?q=MRF&navigate=yes"))
        assertFalse(WazeLinkCheck.reachedEngine("RouteInstructionsPlayer: Route instructions state is null. Not playing turn by turn instructions [:0 ()]"))
    }
    @Test fun verdict() {
        assertEquals(WazeLinkCheck.Verdict.GOT_IT, WazeLinkCheck.verdict(true, 40))
        // Waze busy planning / showing the route after it got the link: still GOT_IT, never a restart.
        assertEquals(WazeLinkCheck.Verdict.GOT_IT, WazeLinkCheck.verdict(true, 0))
        assertEquals(WazeLinkCheck.Verdict.STUCK, WazeLinkCheck.verdict(false, 12))
        // Waze's log silent (can't read it): leave Waze alone.
        assertEquals(WazeLinkCheck.Verdict.CANT_TELL, WazeLinkCheck.verdict(false, 0))
    }
}
