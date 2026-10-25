package io.github.esko997.sortie.ui

import android.view.KeyEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Each activity window owns its [Hotkeys]: with Sortie's navigation on one screen and a built-in app on the other, a key the
 * focused window gets must act there only, and a key one window claims must not be swallowed for the other.
 */
@RunWith(RobolectricTestRunner::class)
class HotkeysTest {
    private fun down(code: Int, repeat: Int = 0) = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, code, repeat)

    /** Runs [block] while collecting what [navigation] and [builtin] each emit. */
    private fun record(navigation: Hotkeys, builtin: Hotkeys, block: () -> Unit): Pair<List<Hotkeys.Key>, List<Hotkeys.Key>> = runBlocking {
        val a = mutableListOf<Hotkeys.Key>()
        val b = mutableListOf<Hotkeys.Key>()
        val ja = launch(Dispatchers.Unconfined, CoroutineStart.UNDISPATCHED) { navigation.events.collect { a += it } }
        val jb = launch(Dispatchers.Unconfined, CoroutineStart.UNDISPATCHED) { builtin.events.collect { b += it } }
        block()
        ja.cancel()
        jb.cancel()
        a to b
    }

    @Test
    fun aKeyActsOnlyInTheWindowThatReceivedIt() {
        val navigation = Hotkeys().apply { handle(setOf(Hotkeys.Key.SWAP_SCREENS, Hotkeys.Key.FAVOURITE)) }
        val builtin = Hotkeys().apply { handle(setOf(Hotkeys.Key.SWAP_SCREENS)) }
        val (nav, own) = record(navigation, builtin) {
            assertTrue(builtin.keyDown(KeyEvent.KEYCODE_BUTTON_X, down(KeyEvent.KEYCODE_BUTTON_X)))
            // Y is claimed by the navigation only: in the built-in's window it falls through to the framework
            assertFalse(builtin.keyDown(KeyEvent.KEYCODE_BUTTON_Y, down(KeyEvent.KEYCODE_BUTTON_Y)))
        }
        assertEquals(emptyList<Hotkeys.Key>(), nav)
        assertEquals(listOf(Hotkeys.Key.SWAP_SCREENS), own)
        assertEquals(setOf(Hotkeys.Key.SWAP_SCREENS, Hotkeys.Key.FAVOURITE), navigation.handled.value)
    }

    @Test
    fun claimsAreCountedPerWindowAcrossOverlappingScreens() {
        val window = Hotkeys()
        val other = Hotkeys()
        // a navigation transition: the entering screen claims before the leaving one releases
        window.handle(setOf(Hotkeys.Key.CONTEXT))
        window.handle(setOf(Hotkeys.Key.CONTEXT))
        window.release(setOf(Hotkeys.Key.CONTEXT))
        assertEquals(setOf(Hotkeys.Key.CONTEXT), window.handled.value)
        other.release(setOf(Hotkeys.Key.CONTEXT)) // another window's release never touches this one's claims
        assertEquals(setOf(Hotkeys.Key.CONTEXT), window.handled.value)
        window.release(setOf(Hotkeys.Key.CONTEXT))
        assertEquals(emptySet<Hotkeys.Key>(), window.handled.value)
    }

    @Test
    fun shouldersFireOnReleaseUnlessHeld() {
        val window = Hotkeys().apply { handle(setOf(Hotkeys.Key.NEXT_FOLDER)) }
        val (events, _) = record(window, Hotkeys()) {
            // a press: swallowed on the way down, fires on release
            assertTrue(window.keyDown(KeyEvent.KEYCODE_BUTTON_R1, down(KeyEvent.KEYCODE_BUTTON_R1)))
            assertTrue(window.keyUp(KeyEvent.KEYCODE_BUTTON_R1))
            // a hold (key repeats): whatever is focused acts on it, and the release does not page
            window.keyDown(KeyEvent.KEYCODE_BUTTON_R1, down(KeyEvent.KEYCODE_BUTTON_R1))
            window.keyDown(KeyEvent.KEYCODE_BUTTON_R1, down(KeyEvent.KEYCODE_BUTTON_R1, repeat = 1))
            assertTrue(window.keyUp(KeyEvent.KEYCODE_BUTTON_R1))
        }
        assertEquals(listOf(Hotkeys.Key.NEXT_FOLDER), events)
        // a repeated non-shoulder key never re-fires
        assertFalse(window.keyDown(KeyEvent.KEYCODE_BUTTON_Y, down(KeyEvent.KEYCODE_BUTTON_Y, repeat = 1)))
    }
}
