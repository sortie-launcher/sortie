package io.github.esko997.sortie.shell

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ScreenMover's task decisions against `cmd activity stack list` recorded on an AYN Thor (two displays) and an AYANEO DMG. */
class ReplayScreenMoverTest {
    private fun mover(capture: String): Pair<ScreenMover, ReplayShell> = replayMover(capture.substringBefore('/'), DeviceCaptures.stackList(capture))

    private fun appTaskIds(capture: String): List<Pair<Int, Int>> = runBlocking { mover(capture).first.appTasks() }.map { it.displayId to it.id }

    @Test
    fun appTasksAreEveryRunningAppTopMostFirstPerDisplayButNeverSortieOrALauncher() {
        // retroarch playing on top, a PSX emulator on the bottom; Sortie's navigation (6672), companion (6682) and secondary
        // home (6628) are running, as are Launcher3's secondary-display home (2626) and its recents (2026)
        assertEquals(
            listOf(0 to 6684, 0 to 6667, 0 to 6626, 0 to 6594, 0 to 6679, 4 to 6627, 4 to 6648, 4 to 6599, 4 to 6562),
            appTaskIds("thor/games-both-screens")
        )
        // Sortie on top as HOME; the bottom shows its secondary home over Launcher3's in the same root task
        assertEquals(listOf(0 to 9082, 4 to 9052), appTaskIds("thor/sortie-top"))
        // recents open on top: nothing but the app hidden behind the bottom screen's home is switchable
        assertEquals(listOf(4 to 11564), appTaskIds("thor/recents"))
        // single-screen DMG: Sortie and another HOME app share the home root task under the game
        assertEquals(listOf(0 to 333, 0 to 334, 0 to 316, 0 to 324, 0 to 331), appTaskIds("dmg/home-app-under-game"))
    }

    @Test
    fun appTasksKeepTheLiteralComponentOfEveryRecordedApp() {
        val tasks = runBlocking { mover("thor/switcher-overlay").first.appTasks() }
        // `$` in an activity name (a nested class) must survive parsing, or the task would vanish from the switcher
        assertTrue(tasks.any { it.component == "example.video/example.video.Shell\$HomeActivity" && it.displayId == 4 })
        assertTrue(tasks.none { it.pkg == SORTIE || it.pkg == "com.android.launcher3" })
    }

    @Test
    fun moveTopAppMovesTheVisibleAppsRootTaskToTheOtherDisplay() = runBlocking {
        val (mover, shell) = mover("thor/games-both-screens")
        assertEquals("moved com.retroarch (task 6684) to display 4", mover.moveTopApp(0, 4))
        assertEquals("moved example.psx (task 6627) to display 0", mover.moveTopApp(4, 0))
        assertEquals(listOf("cmd activity display move-stack 6684 4", "cmd activity display move-stack 6627 0"), shell.actions)
    }

    @Test
    fun moveTopAppNeverMovesSortieAHomeOrAnAppHiddenBehindThem() = runBlocking {
        // Sortie's navigation is the visible task on the bottom screen; the apps below it are hidden
        val (bottom, bottomShell) = mover("thor/sortie-bottom")
        assertEquals("no movable app on display 4", bottom.moveTopApp(4, 0))
        // the bottom screen shows Sortie's secondary home and Launcher3's; the top screen shows recents
        val (recents, recentsShell) = mover("thor/recents")
        assertEquals("no movable app on display 4", recents.moveTopApp(4, 0))
        assertEquals("no movable app on display 0", recents.moveTopApp(0, 4))
        assertEquals(emptyList<String>(), bottomShell.actions + recentsShell.actions)
    }

    @Test
    fun moveTopAppWithTheSwitcherOverlayUpMovesTheAppUnderIt() = runBlocking {
        // the overlay is a window, not a task: the stream on top is still the visible app there
        val (mover, shell) = mover("thor/switcher-overlay")
        assertEquals("moved example.stream (task 10975) to display 4", mover.moveTopApp(0, 4))
        assertEquals(listOf("cmd activity display move-stack 10975 4"), shell.actions)
    }

    @Test
    fun sweepRemovesOnlyAnotherLaunchersLeftoverTask() = runBlocking {
        // DMG: the other HOME app's task (6) sits in the home root task with Sortie's (332), under the running game
        val (mover, shell) = mover("dmg/home-app-under-game")
        assertEquals(1, mover.sweepStaleLaunchers())
        assertEquals(listOf("am stack remove 6"), shell.actions)
    }

    @Test
    fun sweepNeverTouchesLauncher3OrSortieOnTheThor() = runBlocking {
        // every Thor capture holds Launcher3 tasks (secondary-display home, recents) and Sortie's own homes
        for (capture in listOf("games-both-screens", "sortie-top", "sortie-bottom", "switcher-overlay", "recents", "panel-stuck")) {
            val (mover, shell) = mover("thor/$capture")
            assertEquals(capture, 0, mover.sweepStaleLaunchers())
            assertEquals(capture, emptyList<String>(), shell.actions)
        }
    }
}
