package io.github.esko997.sortie.launch

import io.github.esko997.sortie.data.manifest.LaunchProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** When Sortie coming back to the front means the emulator closed right after starting, and what the player is told. */
class QuickExitTest {
    private val start = 1_000_000L
    private val noHome = 0L

    @Test
    fun `emulator gone within the window without a Home press is a quick exit`() {
        assertEquals(SessionReturn.QUICK_EXIT, sessionReturn(start, start + 1_500, appRunning = false, homeKeyAt = noHome))
        assertEquals(SessionReturn.QUICK_EXIT, sessionReturn(start, start + QUICK_EXIT_MS, appRunning = false, homeKeyAt = noHome))
        // without Shizuku the task list is unknown: a quick return that no Home press explains is still a quick exit
        assertEquals(SessionReturn.QUICK_EXIT, sessionReturn(start, start + 1_500, appRunning = null, homeKeyAt = noHome))
    }

    @Test
    fun `a session past the window ends normally`() {
        assertEquals(SessionReturn.ENDED, sessionReturn(start, start + QUICK_EXIT_MS + 1, appRunning = false, homeKeyAt = noHome))
        assertEquals(SessionReturn.ENDED, sessionReturn(start, start + 45 * 60_000, appRunning = null, homeKeyAt = noHome))
    }

    @Test
    fun `Home pressed quickly over a running game is not a quick exit`() {
        // with Shizuku the emulator's task is still there: the session stays open
        assertEquals(SessionReturn.RUNNING, sessionReturn(start, start + 1_000, appRunning = true, homeKeyAt = start + 900))
        assertEquals(SessionReturn.RUNNING, sessionReturn(start, start + 1_000, appRunning = true, homeKeyAt = noHome))
        // without Shizuku the Home press after the start says the player came back on purpose
        assertEquals(SessionReturn.ENDED, sessionReturn(start, start + 1_000, appRunning = null, homeKeyAt = start + 900))
        // even if the app has gone by then (it quits when paused, say), Home means the player left it
        assertEquals(SessionReturn.ENDED, sessionReturn(start, start + 1_000, appRunning = false, homeKeyAt = start + 900))
    }

    @Test
    fun `a Home press before the launch does not hide a quick exit`() {
        assertEquals(SessionReturn.QUICK_EXIT, sessionReturn(start, start + 1_200, appRunning = null, homeKeyAt = start - 5_000))
    }

    private val snes9x = retroArch("snes9x")
    private val genesis = retroArch("genesis_plus_gx")

    private fun retroArch(core: String) = LaunchProfile(
        "ra-$core",
        `package` = "com.retroarch",
        extras = mapOf("ROM" to "\${file}", "LIBRETRO" to "/data/user/0/com.retroarch/cores/${core}_libretro_android.so")
    )

    @Test
    fun `content switches are told apart for RetroArch`() {
        val fzero = libretroContent(snes9x, "/roms/snes/F-Zero.sfc")
        assertEquals(ContentSwitch.CORE, contentSwitch(libretroContent(genesis, "/roms/genesis/Abbaye.zip"), fzero))
        assertEquals(ContentSwitch.GAME, contentSwitch(libretroContent(snes9x, "/roms/snes/Chrono Trigger.sfc"), fzero))
        assertEquals(ContentSwitch.NONE, contentSwitch(fzero, fzero))
        assertEquals(ContentSwitch.NONE, contentSwitch(null, fzero))
        assertNull(libretroContent(LaunchProfile("duckstation", `package` = "com.github.stenzek.duckstation"), "/roms/psx/a.chd"))
    }

    @Test
    fun `messages name the app and say what to do`() {
        assertEquals("RetroArch closed while switching cores — press Play again.", quickExitMessage("RetroArch", ContentSwitch.CORE))
        assertEquals("RetroArch closed while switching games — press Play again.", quickExitMessage("RetroArch", ContentSwitch.GAME))
        assertEquals("DuckStation closed right after starting.", quickExitMessage("DuckStation", ContentSwitch.NONE))
    }
}
