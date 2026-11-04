package io.github.esko997.sortie.shell

import android.app.Application
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The shell service's side of typing: what reaches the input dispatcher, on which display, and what is never left held. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class KeyInjectorTest {
    private data class Press(val down: Boolean, val code: Int, val meta: Int, val display: Int)

    private val dispatched = mutableListOf<KeyEvent>()

    private fun injector(focused: Set<Int>? = setOf(TARGET), accept: (KeyEvent) -> Boolean = { true }) = KeyInjector({ focused }) { event ->
        dispatched += event
        accept(event)
    }

    private fun key(action: Int, code: Int, meta: Int = 0) =
        KeyEvent(1, 1, action, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)

    /** `%a` as the virtual keyboard map types it: shift held around the 5, released before the a. */
    private val percentA = arrayOf(
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, SHIFT),
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_5, SHIFT),
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_5, SHIFT),
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT),
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A),
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A)
    )

    private fun displayOf(event: KeyEvent) = KeyEvent::class.java.getMethod("getDisplayId").invoke(event) as Int

    private fun presses() = dispatched.map { Press(it.action == KeyEvent.ACTION_DOWN, it.keyCode, it.metaState, displayOf(it)) }

    private fun pressesOf(vararg events: KeyEvent) = events.map { Press(it.action == KeyEvent.ACTION_DOWN, it.keyCode, it.metaState, TARGET) }

    @Test
    fun everyEventGoesToTheTargetDisplayInOrderWithItsModifiersAndPairedDownTimes() {
        assertEquals("0\n", injector().inject(TARGET, percentA))
        assertEquals(pressesOf(*percentA), presses())
        assertTrue(dispatched.all { it.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD && it.source == InputDevice.SOURCE_KEYBOARD })
        // a release carries the down time of its own press, so the target pairs them
        assertEquals(dispatched[1].downTime, dispatched[2].downTime)
        assertEquals(dispatched[0].downTime, dispatched[3].downTime)
    }

    @Test
    fun aDisplayWithoutAFocusedWindowOrAnUnknownFocusTypesNothing() {
        // stock Android: the keyboard's own display holds focus, the game's display has none
        val unfocused = injector(focused = setOf(OTHER)).inject(TARGET, percentA)
        assertTrue(unfocused, unfocused.startsWith("-1\n") && "input focus" in unfocused)
        assertTrue(injector(focused = null).inject(TARGET, percentA).startsWith("-1\n"))
        assertTrue(injector(focused = setOf(-1, TARGET)).inject(-1, percentA).startsWith("-1\n"))
        assertEquals(emptyList<Press>(), presses())
    }

    @Test
    fun onlyPressesAndReleasesAreTyped() {
        val multiple = KeyEvent(1, "%", KeyCharacterMap.VIRTUAL_KEYBOARD, 0)
        assertTrue(injector().inject(TARGET, arrayOf(multiple)).startsWith("-1\n"))
        assertEquals(emptyList<Press>(), presses())
    }

    @Test
    fun aRefusedKeyStopsTheBatchButShiftIsStillReleased() {
        val reply = injector { it.keyCode != KeyEvent.KEYCODE_5 }.inject(TARGET, percentA)
        assertTrue(reply.startsWith("-1\n"))
        // shift pressed, 5 refused, then the batch's own shift release; the a is never typed
        assertEquals(pressesOf(percentA[0], percentA[1], percentA[3]), presses())
    }

    @Test
    fun aKeyWhoseReleaseWasRefusedIsReleasedAgainBeforeTheModifier() {
        var refusals = 0
        val reply = injector { !(it.keyCode == KeyEvent.KEYCODE_5 && it.action == KeyEvent.ACTION_UP && refusals++ == 0) }
            .inject(TARGET, percentA)
        assertTrue(reply.startsWith("-1\n"))
        val tail = presses().drop(3)
        // most recent press first: the 5, then shift with its own (meta-free) release from the batch
        assertEquals(listOf(Press(false, KeyEvent.KEYCODE_5, 0, TARGET), Press(false, KeyEvent.KEYCODE_SHIFT_LEFT, 0, TARGET)), tail)
    }

    @Test
    fun aFailingInputServiceReportsItsOwnReasonAndReleasesWhatWasPressed() {
        var calls = 0
        val reply = injector {
            if (calls++ == 1) throw InvocationTargetException(SecurityException("Injecting input events requires INJECT_EVENTS"))
            true
        }.inject(TARGET, percentA)
        assertEquals("-1\nInjecting input events requires INJECT_EVENTS", reply)
        assertEquals(Press(false, KeyEvent.KEYCODE_SHIFT_LEFT, 0, TARGET), presses().last())
    }

    @Test
    fun aPressWithoutAReleaseIsReleasedAtTheEndOfTheBatch() {
        assertEquals("0\n", injector().inject(TARGET, arrayOf(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))))
        assertEquals(
            listOf(Press(true, KeyEvent.KEYCODE_ENTER, 0, TARGET), Press(false, KeyEvent.KEYCODE_ENTER, 0, TARGET)),
            presses()
        )
    }

    private companion object {
        const val TARGET = 0
        const val OTHER = 4
        const val SHIFT = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
    }
}
