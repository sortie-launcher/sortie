package io.github.esko997.sortie.shell

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Sortie's built-in apps in the switcher. The task list is the AYN Thor capture `thor/sortie-top` (Sortie HOME on top, its
 * secondary home on the bottom) with root tasks added in the same format for what has no recording yet: the Magazines app on
 * the bottom screen, the Performance app on top, and an anchor caught mid-flight.
 */
class BuiltinTasksReplayTest {
    private val stackList = DeviceCaptures.stackList("thor/sortie-top") + """

RootTask id=9300 bounds=[0,0][1240,1080] displayId=4 userId=0
  taskId=9301: $SORTIE/$SORTIE.MagazinesActivity bounds=[0,0][1240,1080] userId=0 visible=true topActivity=ComponentInfo{$SORTIE/$SORTIE.MagazinesActivity}

RootTask id=9310 bounds=[0,0][1920,1080] displayId=0 userId=0
  taskId=9311: $SORTIE/$SORTIE.PerformanceActivity bounds=[0,0][1920,1080] userId=0 visible=false topActivity=ComponentInfo{$SORTIE/$SORTIE.PerformanceActivity}

RootTask id=9320 bounds=[0,0][1240,1080] displayId=4 userId=0
  taskId=9321: $SORTIE/$SORTIE.AnchorActivity bounds=[0,0][1240,1080] userId=0 visible=false topActivity=ComponentInfo{$SORTIE/$SORTIE.AnchorActivity}
"""

    private fun switcher(): Pair<TaskSwitcher, ReplayShell> {
        val (mover, shell) = replayMover("thor", stackList)
        return TaskSwitcher(SortieContext(), mover) { 0 }.also { runBlocking { it.refresh().join() } } to shell
    }

    @Test
    fun builtInAppsAreSwitchableUnderTheirOwnNamesAndSortiesOtherTasksAreNot() {
        val entries = switcher().first.state.value.entries
        assertEquals(
            // navigation (9101), secondary home (9103) and the anchor (9321) stay out; launchers too
            listOf(Triple(0, 9082, "example.browser"), Triple(0, 9311, "Performance"), Triple(4, 9052, "example.tv"), Triple(4, 9301, "Magazines")),
            entries.map { Triple(it.task.displayId, it.task.id, it.label) }.sortedWith(compareBy({ it.first }, { it.second }))
        )
    }

    @Test
    fun closingABuiltInRemovesOnlyItsTask() {
        val (switcher, shell) = switcher()
        val magazines = switcher.state.value.entries.single { it.label == "Magazines" }
        runBlocking { switcher.closeApp(magazines).join() }
        assertEquals(listOf("am stack remove 9301"), shell.actions)
    }
}
