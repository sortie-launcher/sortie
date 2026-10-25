package io.github.esko997.sortie.shell

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The AYN panel guard end to end: the real probe script runs in `sh` against `dumpsys` output recorded on the Thor whose panel
 * locked the bottom screen, and ScreenMover decides from its reply. Variants change one recorded fact each.
 */
class ReplayPanelGuardTest {
    private val crash = "am crash ${ScreenMover.VENDOR_PANEL}"

    private fun guard(vararg probes: PanelDumps): ReplayShell {
        val (mover, shell) = replayMover("thor", DeviceCaptures.stackList("thor/panel-stuck"), probes.map { dumps -> { dumps.probe() } })
        runBlocking { mover.guardVendorPanel() }
        return shell
    }

    /** The stuck dumps with the panel's window drawn: the user has the panel open. */
    private fun PanelDumps.panelDrawn(): PanelDumps {
        val lines = windows.lines().toMutableList()
        val panel = lines.indexOfFirst { "u0 ${ScreenMover.VENDOR_PANEL}}:" in it }
        val surface = (panel + 1 until lines.size).first { "mHasSurface=" in lines[it] }
        lines[surface] = lines[surface].replace("mHasSurface=false", "mHasSurface=true")
        return copy(windows = lines.joinToString("\n"))
    }

    /** Panel closed: a Thor window dump with no panel window (release 1033) and display 4's focus back on Sortie. */
    private fun PanelDumps.panelClosed(): PanelDumps = copy(
        windows = DeviceCaptures.text("thor/release-1033/windows.txt"),
        displays = displays.replace(
            "mCurrentFocus=Window{4dbb146 u0 ${ScreenMover.VENDOR_PANEL}}",
            "mCurrentFocus=Window{600abe0 u0 $SORTIE/$SORTIE.MainActivity}"
        )
    )

    /** Only the panel's first [n] network requests: before the leak reached the cap. */
    private fun PanelDumps.panelRequests(n: Int): PanelDumps {
        var kept = 0
        val panel = "uid/pid:1000/$pid "
        return copy(connectivity = connectivity.lines().filter { panel !in it || kept++ < n }.joinToString("\n"))
    }

    @Test
    fun probeReadsTheRecordedStuckState() {
        // 213 requests held by the panel's pid (not system_server's 36), its one window focused on display 4 and never drawn
        assertEquals("0\npid=2841\nrequests=213\nfocused=1\nwindows=1\nmHasSurface=false\nend\n", PanelDumps.stuck().probe())
        assertEquals(
            "0\npid=2841\nrequests=213\nfocused=0\nwindows=0\nend\n",
            PanelDumps.stuck().panelClosed().probe()
        )
    }

    @Test
    fun stuckPanelIsRestartedOnceConfirmedAndOnlyOnceAMinute() {
        val (mover, shell) = replayMover("thor", DeviceCaptures.stackList("thor/panel-stuck"), listOf { PanelDumps.stuck().probe() })
        runBlocking {
            mover.guardVendorPanel()
            mover.guardVendorPanel() // Sortie returns again straight away
        }
        assertEquals(2, shell.probeCount)
        assertEquals(listOf(crash), shell.actions)
    }

    @Test
    fun aPanelThatDrewBeforeTheConfirmationIsLeftAlone() {
        val shell = guard(PanelDumps.stuck(), PanelDumps.stuck().panelDrawn())
        assertEquals(2, shell.probeCount)
        assertEquals(emptyList<String>(), shell.actions)
    }

    @Test
    fun anOpenPanelIsNeverRestartedEvenPastTheCap() {
        val shell = guard(PanelDumps.stuck().panelDrawn())
        assertEquals(1, shell.probeCount)
        assertEquals(emptyList<String>(), shell.actions)
    }

    @Test
    fun aClosedPanelPastTheCapIsRestartedWithoutConfirmation() {
        val shell = guard(PanelDumps.stuck().panelClosed())
        assertEquals(1, shell.probeCount)
        assertEquals(listOf(crash), shell.actions)
    }

    @Test
    fun aHealthyClosedPanelIsLeftAlone() {
        val belowCap = PanelDumps.stuck().panelClosed().panelRequests(ScreenMover.PANEL_REQUEST_LIMIT - 1)
        assertEquals(emptyList<String>(), guard(belowCap).actions)
        // the cap itself restarts
        assertEquals(listOf(crash), guard(PanelDumps.stuck().panelClosed().panelRequests(ScreenMover.PANEL_REQUEST_LIMIT)).actions)
    }

    @Test
    fun partialDumpsNeverRestartThePanel() {
        val stuck = PanelDumps.stuck()
        val lines = stuck.windows.lines()
        // the window dump cut off right after the panel's window line: its surface and the dump's tail are missing
        val cut = stuck.copy(windows = lines.take(lines.indexOfFirst { "u0 ${ScreenMover.VENDOR_PANEL}}:" in it } + 1).joinToString("\n"))
        assertEquals("4\n", cut.probe())
        // a connectivity dump that printed nothing, or a displays dump without focus lines
        assertEquals("2\n", stuck.copy(connectivity = "").probe())
        assertEquals("3\n", stuck.copy(displays = "WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)\n").probe())
        for (partial in listOf(cut, stuck.copy(connectivity = ""), stuck.panelClosed().copy(windows = ""))) {
            assertEquals(emptyList<String>(), guard(partial).actions)
        }
    }

    @Test
    fun devicesWithoutThePanelProcessAreLeftAlone() {
        val noPanel = PanelDumps.stuck().copy(pid = null)
        assertEquals("0\npid=\nend\n", noPanel.probe())
        assertEquals(emptyList<String>(), guard(noPanel).actions)
    }
}
