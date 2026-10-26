package io.github.esko997.sortie.shell

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TaskSwitcher's choices against `cmd activity stack list` recorded on an AYN Thor (top display 0, bottom display 4) and an
 * AYANEO DMG. Activity starts (the controller anchor, Sortie's own tasks) are outside the shell and not replayed here.
 */
class ReplayTaskSwitcherTest {
    // Relevant WindowManager fields from a DMG overlay hidden by Android Settings; no task history or device IDs.
    private val protectedOverlay = """
        WINDOW MANAGER WINDOWS (dumpsys window windows)
          Window #0 Window{abc u0 Sortie switcher}:
            mDisplayId=0 mSession=Session{abc}
            mOwnerUid=10000 showForAllUsers=false package=io.github.esko997.sortie appop=SYSTEM_ALERT_WINDOW
            mViewVisibility=0x0 mHaveFrame=true mObscured=false
            mPolicyVisibility=false mAppOpVisibility=true mForceHideNonSystemOverlayWindow=true
            mHasSurface=true isReadyForDisplay()=false
    """.trimIndent()

    @Test
    fun protectedOverlayRequiresAnOwnedWindowOnTheInvokingDisplay() {
        assertTrue(TaskSwitcher.overlayHiddenByPolicy(protectedOverlay, 0, "io.github.esko997.sortie"))
        assertFalse(TaskSwitcher.overlayHiddenByPolicy(protectedOverlay, 4, "io.github.esko997.sortie"))
        assertFalse(TaskSwitcher.overlayHiddenByPolicy(protectedOverlay, 0, "another.package"))
        assertFalse(TaskSwitcher.overlayHiddenByPolicy(protectedOverlay.replace("Sortie switcher", "Another overlay"), 0, "io.github.esko997.sortie"))
    }

    @Test
    fun visibleOrUnknownOverlayPolicyDoesNotDismissTheSwitcher() {
        assertFalse(
            TaskSwitcher.overlayHiddenByPolicy(
                protectedOverlay.replace("mForceHideNonSystemOverlayWindow=true", "mForceHideNonSystemOverlayWindow=false"),
                0,
                "io.github.esko997.sortie"
            )
        )
        assertFalse(TaskSwitcher.overlayHiddenByPolicy("Bad window command, or no windows match: Sortie switcher", 0, "io.github.esko997.sortie"))
        assertFalse(TaskSwitcher.overlayHiddenByPolicy(protectedOverlay.substringBefore("mPolicyVisibility="), 0, "io.github.esko997.sortie"))
    }

    @Test
    fun overlayPolicyNeverLeaksAcrossDisplayWindowBlocks() {
        val visible = protectedOverlay.replace("mForceHideNonSystemOverlayWindow=true", "mForceHideNonSystemOverlayWindow=false")
        val hiddenOtherDisplay = protectedOverlay.replace("mDisplayId=0", "mDisplayId=4")
        assertFalse(TaskSwitcher.overlayHiddenByPolicy(visible + "\n" + hiddenOtherDisplay, 0, "io.github.esko997.sortie"))
        assertTrue(TaskSwitcher.overlayHiddenByPolicy(visible + "\n" + hiddenOtherDisplay, 4, "io.github.esko997.sortie"))
    }

    private fun switcher(capture: String): Pair<TaskSwitcher, ReplayShell> {
        val (mover, shell) = replayMover(capture.substringBefore('/'), DeviceCaptures.stackList(capture))
        return TaskSwitcher(SortieContext(), mover) { 0 } to shell
    }

    private fun TaskSwitcher.loaded(): TaskSwitcher = also { runBlocking { refresh().join() } }

    private fun List<TaskSwitcher.Entry>.ids() = map { it.task.id }

    @Test
    fun revealWithoutASortieSurfaceRaisesTheAppHomeCoveredOnThatDisplay() {
        // Home on the top screen over a playing stream: the overlay goes, the stream (not the PSP game behind it) comes back
        val (overlay, overlayShell) = switcher("thor/switcher-overlay")
        runBlocking { overlay.reveal(0, null).join() }
        assertEquals(listOf("am task focus 10975"), overlayShell.actions)
        // Sortie on the bottom screen and an emulator on top: Home on top brings back the emulator
        val (bottom, bottomShell) = switcher("thor/sortie-bottom")
        runBlocking { bottom.reveal(0, null).join() }
        assertEquals(listOf("am task focus 6737"), bottomShell.actions)
    }

    @Test
    fun revealNeverRaisesAnotherDisplaysApp() {
        // recents on top, no app there: the bottom screen's hidden app must not be pulled into view
        val (switcher, shell) = switcher("thor/recents")
        runBlocking { switcher.reveal(0, null).join() }
        assertEquals(emptyList<String>(), shell.actions)
    }

    @Test
    fun revealOfACoveredSortieSurfaceTouchesNoExternalTask() {
        // Home on the bottom screen covered Sortie's own navigation (6768): that comes back, not the app hidden behind it
        val (switcher, shell) = switcher("thor/sortie-bottom")
        runBlocking { switcher.reveal(4, 6768).join() }
        assertEquals(emptyList<String>(), shell.actions)
    }

    @Test
    fun goHomeRaisesSortiesNavigationWhereverItRuns() {
        val navigation = listOf(
            // navigation is HOME on the top screen
            "thor/sortie-top" to 9101,
            // navigation was swapped to the bottom screen
            "thor/sortie-bottom" to 6768,
            // hidden on the bottom screen under the secondary home; companion on top
            "thor/switcher-overlay" to 10820,
            "thor/panel-stuck" to 5502,
            // single screen: in the home root task, under the game
            "dmg/home-app-under-game" to 332
        )
        for ((capture, nav) in navigation) {
            val (switcher, shell) = switcher(capture)
            runBlocking { switcher.goHome(0).join() }
            assertEquals(capture, listOf("am task focus $nav"), shell.actions)
        }
    }

    @Test
    fun goHomeWithoutANavigationTaskRaisesNoCompanionOrHome() {
        // only Sortie's secondary home runs (bottom) and recents is on top: Sortie must be started, not some other task raised
        val (switcher, shell) = switcher("thor/recents")
        runBlocking { switcher.goHome(0).join() }
        assertEquals(emptyList<String>(), shell.actions)
    }

    @Test
    fun carouselsOfferEachScreensOwnAppsAndTheOtherScreensWhenEmpty() {
        val games = switcher("thor/games-both-screens").first.loaded().state.value
        assertEquals(listOf(6684, 6667, 6626, 6594, 6679) to false, games.offers(0).let { it.first.ids() to it.second })
        assertEquals(listOf(6627, 6648, 6599, 6562) to false, games.offers(4).let { it.first.ids() to it.second })
        // freed by a move: the display offers every running app to bring in
        assertEquals(games.entries.ids() to true, games.copy(freed = 4).offers(4).let { it.first.ids() to it.second })
        // Sortie is HOME on top, yet the app hidden behind it still belongs to the top carousel
        val sortieTop = switcher("thor/sortie-top").first.loaded().state.value
        assertEquals(listOf(9082) to false, sortieTop.offers(0).let { it.first.ids() to it.second })
        // recents on top, no app there: the top carousel offers the bottom screen's app to bring up
        val recents = switcher("thor/recents").first.loaded().state.value
        assertEquals(listOf(11564) to true, recents.offers(0).let { it.first.ids() to it.second })
    }

    @Test
    fun quickSwapExchangesTheTopAppOfEachScreen() {
        val (switcher, shell) = switcher("thor/games-both-screens")
        switcher.loaded()
        runBlocking { switcher.quickSwap(0, 4).join() }
        assertEquals(listOf("cmd activity display move-stack 6684 4", "cmd activity display move-stack 6627 0"), shell.actions)
    }

    @Test
    fun quickSwapWithOneEmptyScreenMovesTheOtherAppAcross() {
        // recents on top is not an app: only the bottom screen's app moves up
        val (switcher, shell) = switcher("thor/recents")
        switcher.loaded()
        runBlocking { switcher.quickSwap(0, 4).join() }
        assertEquals(listOf("cmd activity display move-stack 11564 0"), shell.actions)
    }
}
