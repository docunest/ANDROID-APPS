package com.docunest.dock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android 10 `dumpsys activity activities` (format of frameworks/base Q, trimmed). */
class SplitStateTest {
    private val split = """
        ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
        Display #0 (activities from top to bottom):

          Stack #45: type=standard mode=split-screen-secondary
          isSleeping=false
          mBounds=Rect(640, 0 - 1280, 720)
            Task id #312
            mBounds=Rect(640, 0 - 1280, 720)
            * TaskRecord{a1b2c3 #312 A=com.google.android.apps.maps U=0 StackId=45 sz=1}
              userId=0 effectiveUid=u0a120 mCallingUid=u0a99 mUserSetupComplete=true mCallingPackage=com.docunest.dock

          Stack #44: type=standard mode=split-screen-primary
            Task id #300
            * TaskRecord{d4e5f6 #300 A=com.waze U=0 StackId=44 sz=1}

          Stack #1: type=home mode=fullscreen
            * TaskRecord{778899 #2 A=com.wits.ksw U=0 StackId=1 sz=1}
    """.trimIndent()

    @Test fun splitIsRecognised() {
        val m = SplitState.parse(split)
        assertEquals("split-screen-secondary", m["com.google.android.apps.maps"])
        assertEquals("split-screen-primary", m["com.waze"])
        assertTrue(SplitState.mapsBesideWaze(m))
    }

    @Test fun mapsFullScreenOverWazeIsNotASplit() {
        val full = """
              Stack #46: type=standard mode=fullscreen
                * TaskRecord{a1 #313 A=com.google.android.apps.maps U=0 StackId=46 sz=1}
              Stack #44: type=standard mode=fullscreen
                * TaskRecord{d4 #300 A=com.waze U=0 StackId=44 sz=1}
        """.trimIndent()
        val m = SplitState.parse(full)
        assertFalse(SplitState.mapsBesideWaze(m))
        assertEquals("maps=fullscreen, waze=fullscreen", SplitState.describe(m))
    }

    @Test fun userIdPrefixedAffinity() {
        val m = SplitState.parse("  Stack #3: type=standard mode=split-screen-primary\n    * TaskRecord{1 #5 A=10120:com.waze U=0 StackId=3 sz=1}")
        assertEquals("split-screen-primary", m["com.waze"])
    }
}
