package io.github.esko997.sortie.shell

import io.github.esko997.sortie.shell.ScreenMover.PanelAction
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenMoverTest {
    @Test
    fun pinnedModeBelongsToTheRootNotTheDisplayOrNextTask() {
        val tasks = ScreenMover.parseRootTasks(
            """
            RootTask id=41 bounds=[600,479][1204,819] displayId=4 userId=0
             configuration={winConfig={mWindowingMode=pinned mDisplayWindowingMode=fullscreen}}
              taskId=41: example.player/example.player.Playback bounds=[600,479][1204,819] userId=0 visible=true
            RootTask id=50 bounds=[0,0][1920,1080] displayId=0 userId=0
             configuration={winConfig={mWindowingMode=fullscreen mDisplayWindowingMode=pinned}}
              taskId=51: example.browser/example.browser.Main bounds=[0,0][1920,1080] userId=0 visible=false
              taskId=52: example.browser/example.browser.Other bounds=[0,0][1920,1080] userId=0 visible=true
            RootTask id=60 bounds=[0,0][1240,1080] displayId=4 userId=0
              taskId=60: example.app/example.app.Main bounds=[0,0][1240,1080] userId=0 visible=true
              taskId=61: unknown bounds=[0,0][1240,1080] userId=0 visible=false
            """.trimIndent()
        )
        assertEquals(
            listOf(
                ScreenMover.RootTask(41, 41, 4, "example.player/example.player.Playback", true, true),
                ScreenMover.RootTask(50, 51, 0, "example.browser/example.browser.Main", false, false),
                ScreenMover.RootTask(50, 52, 0, "example.browser/example.browser.Other", true, false),
                ScreenMover.RootTask(60, 60, 4, "example.app/example.app.Main", true, false)
            ),
            tasks
        )
    }

    /** Decision for a probe reply: pid, leaked request count, whether a panel window has focus, each window's surface. */
    private fun action(requests: Int = 0, focused: Int = 0, vararg surfaces: Boolean, pid: String = "2841") =
        ScreenMover.vendorPanelAction("0\npid=$pid\nrequests=$requests\nfocused=$focused\n" + surfaces.joinToString("") { "mHasSurface=$it\n" })

    @Test
    fun vendorPanelIsRestartedBeforeTheCapOnlyWhileClosed() {
        assertEquals(PanelAction.NONE, action(requests = ScreenMover.PANEL_REQUEST_LIMIT - 1))
        assertEquals(PanelAction.RESTART_LEAK, action(requests = ScreenMover.PANEL_REQUEST_LIMIT))
        // the user is looking at the open panel: wait for it to close
        assertEquals(PanelAction.NONE, action(213, 1, true))
    }

    @Test
    fun focusedPanelWindowThatNeverDrewIsAZombieToConfirm() {
        // the state left on the Thor after TooManyRequestsException: 213 leaked requests, undrawn window holding focus
        assertEquals(PanelAction.CONFIRM_ZOMBIE, action(213, 1, false))
        // a panel window that has drawn is not a zombie, nor is an unfocused undrawn one below the cap
        assertEquals(PanelAction.NONE, action(3, 1, false, true))
        assertEquals(PanelAction.NONE, action(3, 0, false))
    }

    @Test
    fun devicesWithoutThePanelOrAFailedProbeDoNothing() {
        assertEquals(PanelAction.NONE, action(requests = 400, pid = ""))
        assertEquals(PanelAction.NONE, ScreenMover.vendorPanelAction("-1\nshell service not bound"))
    }
}
