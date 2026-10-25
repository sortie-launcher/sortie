package io.github.esko997.sortie.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Display 0 is the top screen, 2 the bottom one, as on the emulator; times in ms. */
class AnchorGuardTest {
    private var clock = 0L
    private val guard = AnchorGuard({ clock })

    @Test
    fun aCompanionResumedByOurAnchorDoesNotHandBackOnce() {
        // two companions (no navigation shown): the top one hands off to the bottom, whose companion resumes under the anchor
        guard.anchored(2)
        clock += 100
        assertFalse("the anchor's own resume must not anchor the other screen again", guard.companionResumed(2))
        clock += 2_000
        assertTrue("a later resume (the user opened something there and left) hands off as usual", guard.companionResumed(2))
    }

    @Test
    fun theAnchorGuardsOnlyItsOwnResume() {
        guard.anchored(2)
        clock += 100
        assertFalse(guard.companionResumed(2))
        clock += 200
        assertTrue("the same anchor never suppresses a second resume", guard.companionResumed(2))
    }

    @Test
    fun aSecondSwapWithinTheWindowHandsTheControllerToNavigation() {
        // X: navigation moves to the bottom; the top companion hands the controller down and navigation resumes there
        guard.anchored(2)
        guard.navigationShown()
        // X again 0.3 s later: navigation moves up, the bottom companion resumes and must hand the controller up
        clock += 300
        assertTrue(guard.companionResumed(2))
    }

    @Test
    fun aCompanionOnTheOtherScreenStillHandsOff() {
        guard.anchored(2)
        clock += 100
        assertTrue("re-anchoring the same target cannot loop", guard.companionResumed(0))
        assertFalse("and the target's own resume is still recognised", guard.companionResumed(2))
    }

    @Test
    fun aSwapStartedBeforeTheAnchorLandedStillHandsOff() {
        // the top companion's hand-off anchors the bottom screen, where navigation is; X moves navigation up before that anchor
        // resumes anything, and the bottom companion that comes back must hand the controller up, not keep it
        guard.anchored(2)
        clock += 100
        guard.navigationShown()
        clock += 300
        assertTrue(guard.companionResumed(2))
    }

    @Test
    fun anAnchorThatNothingOfOursResumesUnderExpires() {
        guard.anchored(0) // the controller handed to a game on the top screen
        clock += 1_500
        assertTrue(guard.companionResumed(0))
    }
}
