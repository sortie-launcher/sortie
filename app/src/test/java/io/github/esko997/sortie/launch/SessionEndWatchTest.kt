package io.github.esko997.sortie.launch

import io.github.esko997.sortie.shell.DeviceCaptures
import io.github.esko997.sortie.shell.ScreenMover
import io.github.esko997.sortie.shell.ShellExec
import io.github.esko997.sortie.shell.SortieContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the post-session bookmark invitation may be offered. The task lists are a real Thor capture: RetroArch's game task
 * 6684 (visible) next to its menu task 6679, Sortie's navigation task 6672 on the other screen.
 */
class SessionEndWatchTest {
    private val capture = ScreenMover.parseRootTasks(DeviceCaptures.stackList("thor/games-both-screens"))
    private val ownTask = 6672
    private val gameTask = 6684
    private val start = 1_000_000L
    private var now = start
    private val watch = SessionEndWatch { now }

    private fun launched() {
        watch.started("snes/Game.sfc", start, "com.retroarch")
        assertTrue(watch.identify("snes/Game.sfc", start, capture))
        now = start + 30 * 60_000
    }

    private val gameClosed get() = capture.filterNot { it.id == gameTask }

    @Test
    fun `the launched game's own visible task is identified, not the emulator's other tasks`() {
        launched()
        assertEquals(gameTask, watch.watching()?.taskId)
    }

    @Test
    fun `Home, the switcher or a return to the game never end it, and the real exit ends it exactly once`() {
        launched()
        assertNull(watch.observe(capture, ownTask)) // Home over the running game
        assertNull(watch.observe(null, ownTask)) // Shizuku unavailable or the read failed
        assertNull(watch.observe(emptyList(), ownTask)) // an empty list proves nothing
        assertNull(watch.observe(gameClosed.filterNot { it.id == ownTask }, ownTask)) // a list that does not even show Sortie
        assertNull(watch.observe(capture, ownTask)) // back into the game and out again
        assertNotNull(watch.watching())
        // RetroArch's menu task stays behind; the game's own task is gone
        val ended = watch.observe(gameClosed, ownTask)
        assertEquals("snes/Game.sfc", ended?.gameId)
        assertNull(watch.observe(gameClosed, ownTask))
        assertNull(watch.watching())
    }

    @Test
    fun `a session whose task was never seen is never reported`() {
        watch.started("snes/Game.sfc", start, "com.retroarch")
        assertFalse(watch.identify("snes/Game.sfc", start, null))
        assertFalse(watch.identify("snes/Game.sfc", start, capture.filterNot { it.pkg == "com.retroarch" && it.visible }))
        now = start + 30 * 60_000
        assertNull(watch.observe(gameClosed, ownTask))
        assertNull(watch.observe(emptyList(), ownTask))
    }

    @Test
    fun `a quick exit offers nothing`() {
        watch.started("snes/Game.sfc", start, "com.retroarch")
        watch.identify("snes/Game.sfc", start, capture)
        now = start + QUICK_EXIT_MS
        assertNull(watch.observe(gameClosed, ownTask))
        assertNull(watch.watching())

        launched()
        watch.forget("snes/Game.sfc", start) // the navigation decided QUICK_EXIT first
        assertNull(watch.observe(gameClosed, ownTask))
    }

    /**
     * Game A is watched, then game B starts through Android's chooser (no emulator package to watch): A's later exit must not
     * prompt over B, and an invitation A had waiting goes too.
     */
    @Test
    fun `an unwatchable new session ends the earlier game's watch and invitation`() {
        launched()
        watch.stop() // B started without a known package
        assertNull(watch.watching())
        assertNull(watch.observe(gameClosed, ownTask))
        assertNull(watch.invite.value)

        launched()
        watch.offer(watch.observe(gameClosed, ownTask)!!)
        watch.stop()
        assertNull(watch.invite.value)
    }

    @Test
    fun `a new launch replaces the watch and drops an unanswered invitation`() {
        launched()
        watch.offer(watch.observe(gameClosed, ownTask)!!)
        assertNotNull(watch.invite.value)
        watch.started("psx/Other.chd", start + 40 * 60_000, "com.duckstation")
        assertNull(watch.invite.value)
        assertEquals("psx/Other.chd", watch.watching()?.gameId)
        // identifying for a session that is no longer watched changes nothing
        assertTrue(watch.identify("snes/Game.sfc", start, capture))
        assertNull(watch.watching()?.taskId)
    }

    @Test
    fun `an invitation is answered once and stale answers do nothing`() {
        launched()
        watch.offer(watch.observe(gameClosed, ownTask)!!)
        val invite = watch.invite.value!!
        watch.offer(SessionEndWatch.Watched("other", 1, "x", 1)) // never replaces one being answered
        assertEquals(invite, watch.invite.value)
        watch.accept(invite)
        assertNull(watch.invite.value)
        assertEquals("snes/Game.sfc", watch.editorRequest.value)
        watch.dismiss(invite) // already answered
        watch.accept(invite)
        assertEquals("snes/Game.sfc", watch.editorRequest.value)
        watch.editorOpened("snes/Game.sfc")
        assertNull(watch.editorRequest.value)
    }

    @Test
    fun `it shows only on the focused, resumed navigation without a switcher, while fresh and enabled`() {
        val invite = SessionEndWatch.Invite("snes/Game.sfc", start, start + 60_000)
        val at = start + 61_000
        fun show(
            i: SessionEndWatch.Invite? = invite,
            enabled: Boolean = true,
            time: Long = at,
            resumed: Boolean = true,
            focused: Boolean = true,
            switcher: Boolean = false,
            route: String? = "game/{id}"
        ) = SessionEndWatch.mayPresent(i, enabled, time, resumed, focused, switcher, route)
        assertTrue(show())
        assertFalse(show(i = null))
        assertFalse(show(enabled = false))
        assertFalse(show(time = invite.endedAt + SessionEndWatch.UNSHOWN_TTL_MS + 1))
        assertFalse(show(resumed = false)) // stopped behind a game, or a temporary Home
        assertFalse(show(focused = false)) // the companion or a built-in app has the controller
        assertFalse(show(switcher = true))
        assertFalse(show(route = "switcher"))
        assertFalse(show(route = null))
    }

    /** A failed, unbound or empty read is "unknown", never "no tasks". */
    @Test
    fun `task snapshots are null unless the list was really read`() = runBlocking {
        fun mover(reply: String) = ScreenMover(
            SortieContext(),
            object : ShellExec {
                override fun exec(cmd: String) = reply
                override fun restorePinnedTask(rootTaskId: Int) = "0\n"
            },
            DeviceCaptures.homes("thor")
        )
        assertNull(mover("-1\nshell service not bound").taskSnapshot())
        assertNull(mover("255\nSecurityException").taskSnapshot())
        assertNull(mover("0\n").taskSnapshot())
        assertEquals(capture, mover("0\n" + DeviceCaptures.stackList("thor/games-both-screens")).taskSnapshot())
    }
}
