package io.github.esko997.sortie.keyboard

import android.app.Application
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import io.github.esko997.sortie.shell.ScreenMover
import io.github.esko997.sortie.shell.ShellExec
import io.github.esko997.sortie.shell.SortieContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the keyboards' output hands the shell service: whole characters in order on the target display, nothing of a text
 * that cannot be typed, nothing after a failure or [RemoteKeyboardOutput.close], and a reason whenever something was not typed.
 * Characters become key presses through a US-layout model of Android's virtual keyboard map (the device's own map is
 * exercised on a device); the shell side is [ScreenMover] over a fake service that records each batch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class RemoteKeyboardOutputTest {
    private data class Press(val down: Boolean, val code: Int, val shift: Boolean)

    private class Batch(val display: Int, val presses: List<Press>)

    /** The shell service: records every batch, answers [reply], and holds a batch while [gate] is closed. */
    private class TypingShell : ShellExec {
        val batches = LinkedBlockingQueue<Batch>()
        val entered = Semaphore(0)

        @Volatile var reply = "0\n"

        @Volatile var gate: CountDownLatch? = null

        override fun injectKeyEvents(displayId: Int, events: Array<KeyEvent>): String {
            entered.release()
            gate?.await(5, TimeUnit.SECONDS)
            batches += Batch(displayId, events.map { Press(it.action == KeyEvent.ACTION_DOWN, it.keyCode, it.isShiftPressed) })
            return reply
        }

        override fun exec(cmd: String): String = error("typing runs no shell command")

        override fun restorePinnedTask(rootTaskId: Int): String = error("typing moves no task")
    }

    private val shell = TypingShell()
    private val mover = ScreenMover(SortieContext(), shell, emptySet())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val output = RemoteKeyboardOutput(mover, TARGET, scope, ::usKeystrokes)

    @After
    fun tearDown() {
        output.close()
        scope.cancel()
    }

    private fun nextBatch(): Batch = requireNotNull(shell.batches.poll(5, TimeUnit.SECONDS)) { "no batch reached the shell service" }

    private fun awaitError(predicate: (String?) -> Boolean): String? = runBlocking { withTimeout(5_000) { output.error.first(predicate) } }

    @Test
    fun textAndEditingCommandsKeepTheirOrderAndTarget() {
        output.text("a")
        output.key(KeyEvent.KEYCODE_DEL)
        output.text("b")
        val typed = nextBatch()
        val delete = nextBatch()
        val final = nextBatch()
        assertEquals(listOf(TARGET, TARGET, TARGET), listOf(typed.display, delete.display, final.display))
        assertEquals("a", decode(typed.presses))
        assertEquals(listOf(Press(true, KeyEvent.KEYCODE_DEL, false), Press(false, KeyEvent.KEYCODE_DEL, false)), delete.presses)
        assertEquals("b", decode(final.presses))
    }

    @Test
    fun textWithACharacterThatHasNoKeyTypesNothingOfItAndSaysSo() {
        output.text("café")
        assertEquals(RemoteKeyboardOutput.UNSUPPORTED, awaitError { it != null })
        output.key(0)
        output.text("ok")
        val next = nextBatch()
        assertEquals("ok", decode(next.presses)) // neither "caf" nor the invalid key were sent
        assertNull(awaitError { it == null })
    }

    @Test
    fun aKeyCodeThatIsNoKeyIsReported() {
        output.key(0)
        assertEquals(RemoteKeyboardOutput.NOT_A_KEY, awaitError { it != null })
        assertEquals(0, shell.batches.size)
    }

    @Test
    fun aFailedSendIsReportedAndWhatWaitedBehindItIsNeverTypedLater() {
        val gate = CountDownLatch(1)
        shell.gate = gate
        output.text("a")
        assertTrue(shell.entered.tryAcquire(5, TimeUnit.SECONDS))
        output.text("b")
        output.key(KeyEvent.KEYCODE_ENTER)
        shell.reply = "-1\nThe app on display 0 doesn't have input focus, so nothing was typed"
        gate.countDown()
        assertEquals("The app on display 0 doesn't have input focus, so nothing was typed", awaitError { it != null })
        assertEquals("a", decode(nextBatch().presses))
        // the shell service is back: only what is typed from now on goes out, never the b and Enter queued before
        shell.gate = null
        shell.reply = "0\n"
        output.text("c")
        assertEquals("c", decode(nextBatch().presses))
        assertNull(awaitError { it == null })
        assertEquals(0, shell.batches.size)
        assertEquals("", mover.lastResult.value) // typing never lands in the mover's log line
    }

    @Test
    fun closeFinishesOnlyTheSubmissionAlreadyInFlight() {
        val gate = CountDownLatch(1)
        shell.gate = gate
        output.text("x")
        assertTrue(shell.entered.tryAcquire(5, TimeUnit.SECONDS))
        output.key(KeyEvent.KEYCODE_ENTER)
        output.close()
        gate.countDown()
        assertEquals("x", decode(nextBatch().presses))
        output.text("late")
        assertNull(shell.batches.poll(500, TimeUnit.MILLISECONDS))
        assertNull(output.error.value)
    }

    @Test
    fun withoutShizukuNothingIsTypedAndTheReasonIsShown() {
        val app = RemoteKeyboardOutput(ScreenMover(SortieContext()), TARGET, scope) { usKeystrokes(it) }
        try {
            app.text("a")
            val error = runBlocking { withTimeout(5_000) { app.error.first { it != null } } }
            assertTrue(error, error!!.startsWith("Shizuku"))
            assertEquals(0, shell.batches.size)
        } finally {
            app.close()
        }
    }

    private fun decode(presses: List<Press>): String = buildString {
        for (p in presses) {
            if (!p.down || p.code == KeyEvent.KEYCODE_SHIFT_LEFT) continue
            append(US.entries.first { (_, stroke) -> stroke == (p.code to p.shift) }.key)
        }
    }

    private companion object {
        const val TARGET = 0

        /** US layout: each typable ASCII character's key and whether it needs shift. */
        val US: Map<Char, Pair<Int, Boolean>> = buildMap {
            for (c in 'a'..'z') put(c, KeyEvent.KEYCODE_A + (c - 'a') to false)
            for (c in 'A'..'Z') put(c, KeyEvent.KEYCODE_A + (c - 'A') to true)
            for (c in '0'..'9') put(c, KeyEvent.KEYCODE_0 + (c - '0') to false)
            ")!@#$%^&*(".forEachIndexed { digit, c -> put(c, KeyEvent.KEYCODE_0 + digit to true) }
            val plain = mapOf(
                ' ' to KeyEvent.KEYCODE_SPACE, '`' to KeyEvent.KEYCODE_GRAVE, '-' to KeyEvent.KEYCODE_MINUS,
                '=' to KeyEvent.KEYCODE_EQUALS, '[' to KeyEvent.KEYCODE_LEFT_BRACKET, ']' to KeyEvent.KEYCODE_RIGHT_BRACKET,
                '\\' to KeyEvent.KEYCODE_BACKSLASH, ';' to KeyEvent.KEYCODE_SEMICOLON, '\'' to KeyEvent.KEYCODE_APOSTROPHE,
                ',' to KeyEvent.KEYCODE_COMMA, '.' to KeyEvent.KEYCODE_PERIOD, '/' to KeyEvent.KEYCODE_SLASH
            )
            plain.forEach { (c, code) -> put(c, code to false) }
            "~_+{}|:\"<>?".zip("`-=[]\\;',./").forEach { (shifted, base) -> put(shifted, plain.getValue(base) to true) }
        }

        fun usKeystrokes(chars: CharArray): Array<KeyEvent>? {
            val events = ArrayList<KeyEvent>()
            for (c in chars) {
                val (code, shift) = US[c] ?: return null
                val meta = if (shift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
                if (shift) events += key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, meta)
                events += key(KeyEvent.ACTION_DOWN, code, meta)
                events += key(KeyEvent.ACTION_UP, code, meta)
                if (shift) events += key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0)
            }
            return events.toTypedArray()
        }

        fun key(action: Int, code: Int, meta: Int) = KeyEvent(1, 1, action, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
    }
}
