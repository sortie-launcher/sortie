package io.github.esko997.sortie.keyboard

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import io.github.esko997.sortie.shell.ScreenMover
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Types what Sortie's own keyboards produce into the focused app on [targetDisplay], the other screen (a Moonlight stream,
 * say), as a hardware keyboard would: Shizuku's shell service injects real key presses with that display set on each. Keys
 * only go out while the input dispatcher has a focused window on that display; stock Android gives one only to the top-focused
 * display unless the build enables per-display focus, so a focused window on another screen means nothing is typed and
 * [error] says the app there lacks input focus. Text becomes key presses through Android's virtual keyboard map (shift wraps
 * capitals and shifted symbols); [key] presses one key such as Delete, Enter or an arrow.
 *
 * Submissions are typed in order, one at a time, off the calling thread; at most [QUEUE_LIMIT] wait. A submission that cannot
 * be typed whole (a character with no key, a lost Shizuku connection, nothing focused there) is not typed at all or stops where
 * it failed, and [error] says why; whatever was waiting behind a failed send is discarded, never typed later on a new
 * connection. Nothing typed is logged or kept. [close] stops typing at once; the owner makes a new output for another display.
 */
class RemoteKeyboardOutput internal constructor(
    private val mover: ScreenMover,
    private val targetDisplay: Int,
    scope: CoroutineScope,
    /** The balanced key presses (every press released) that type [CharArray], or null if one of its characters has no key. */
    private val keystrokes: (CharArray) -> Array<KeyEvent>?
) {
    constructor(mover: ScreenMover, targetDisplay: Int, scope: CoroutineScope) : this(mover, targetDisplay, scope, ::virtualKeystrokes)

    private val _error = MutableStateFlow<String?>(null)

    /** Why the latest submission was not (completely) typed; null again once one is. */
    val error: StateFlow<String?> = _error.asStateFlow()

    private sealed interface Submission
    private class Text(val value: String) : Submission
    private class Key(val code: Int) : Submission

    private val queue = Channel<Submission>(QUEUE_LIMIT)

    private val worker = scope.launch(Dispatchers.IO) {
        for (submission in queue) {
            val events = try {
                events(submission)
            } catch (e: RuntimeException) {
                // no key for a character, an unknown key code, or no virtual keyboard map: nothing of it is typed
                _error.value = e.message ?: UNSUPPORTED
                continue
            }
            val failure = mover.injectKeyEvents(targetDisplay, events)
            // discard before reporting, so whatever the user types once the error shows is kept
            if (failure != null) {
                while (queue.tryReceive().isSuccess) Unit
            }
            _error.value = failure
        }
    }.apply { invokeOnCompletion { queue.cancel() } }

    /** Types [value] (characters on a US keyboard, `\n` and `\t` included). */
    fun text(value: String) {
        if (value.isNotEmpty()) submit(Text(value))
    }

    /** Presses and releases [keyCode] (a [KeyEvent] key code) with no modifiers. */
    fun key(keyCode: Int) = submit(Key(keyCode))

    /** Stops typing: the batch already handed to the shell service finishes, nothing after it is sent. */
    fun close() {
        queue.cancel()
        worker.cancel()
    }

    private fun submit(submission: Submission) {
        val sent = queue.trySend(submission)
        if (sent.isFailure && !sent.isClosed) _error.value = BUSY
    }

    /** One submission is a complete set of key presses and releases; closing cannot strand a modifier halfway through. */
    private fun events(submission: Submission): Array<KeyEvent> = when (submission) {
        is Text -> requireNotNull(keystrokes(submission.value.toCharArray())) { UNSUPPORTED }
        is Key -> {
            require(submission.code in 1..KeyEvent.getMaxKeyCode()) { NOT_A_KEY }
            val now = SystemClock.uptimeMillis()
            arrayOf(press(now, KeyEvent.ACTION_DOWN, submission.code), press(now, KeyEvent.ACTION_UP, submission.code))
        }
    }

    private fun press(time: Long, action: Int, code: Int) =
        KeyEvent(time, time, action, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)

    internal companion object {
        const val QUEUE_LIMIT = 64
        const val UNSUPPORTED = "Only characters on a standard keyboard can be typed"
        const val NOT_A_KEY = "Not a key that can be typed"
        const val BUSY = "Typing faster than the other screen takes keys; that key was not sent"
    }
}

/** Android's virtual keyboard map (the generic US layout), loaded once; it types each ASCII character with the modifiers it needs. */
private val virtualKeyboard: KeyCharacterMap by lazy { KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD) }

private fun virtualKeystrokes(chars: CharArray): Array<KeyEvent>? = virtualKeyboard.getEvents(chars)
