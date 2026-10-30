package io.github.esko997.sortie.ui

import android.view.KeyEvent
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Controller grammar (docs/SPEC.md): A select · B back · X swap screens · Y favourite (game page) / search (Home; system and
 * folder lists search only there) · Start search · Select context menu ·
 * L1/R1 previous/next folder · R2 Want to play · L2 leave a note (only while the post-session bookmark invitation shows). A and B
 * are handled by the framework (focus click / back); the rest arrive here as events that the current screen interprets. Keeping
 * it an event bus avoids every screen owning a key handler. R2 on a game tile or row never gets here: the focused tile consumes
 * it (`Modifier.wantToPlayKey`); the event serves the game detail screen.
 *
 * One instance per activity window, provided to its screens through [LocalHotkeys]: Sortie's navigation and a built-in app can
 * be visible on two screens at once, and a key that reaches one window must never also act in the other.
 */
class Hotkeys {
    enum class Key { SEARCH, FAVOURITE, CONTEXT, SWAP_SCREENS, PREV_FOLDER, NEXT_FOLDER, WANT_TO_PLAY, NOTE }

    private val _events = MutableSharedFlow<Key>(extraBufferCapacity = 8)
    val events: SharedFlow<Key> = _events

    private val _handled = MutableStateFlow<Set<Key>>(emptySet())

    /** Keys some screen of this window currently handles; everything else falls through to the framework. */
    val handled: StateFlow<Set<Key>> = _handled

    // Reference counts: during a navigation transition the entering screen handles a key before the leaving screen releases it,
    // so a plain set would drop the key the new screen just claimed.
    private val owners = HashMap<Key, Int>()

    fun handle(keys: Set<Key>) = synchronized(owners) {
        keys.forEach { owners[it] = (owners[it] ?: 0) + 1 }
        _handled.value = owners.keys.toSet()
    }

    fun release(keys: Set<Key>) = synchronized(owners) {
        for (k in keys) {
            val n = (owners[k] ?: 0) - 1
            if (n <= 0) owners.remove(k) else owners[k] = n
        }
        _handled.value = owners.keys.toSet()
    }

    /** Returns true when the key was a hotkey that a screen of this window consumes. */
    fun onKeyDown(code: Int): Boolean {
        val key = keyFor(code) ?: return false
        return key in _handled.value && _events.tryEmit(key)
    }

    private var shoulderHeld = false

    /**
     * The window's `Activity.onKeyDown`, for keys nothing on screen consumed: true when this window took the key. Hotkeys fire
     * on the first key-down, except L1/R1, which fire on key-up ([keyUp]): a held R1 is "favourite" on the focused tile
     * (`Modifier.keyLongPress` swallows the hold and its release), and must not also page sorts/systems.
     */
    fun keyDown(code: Int, event: KeyEvent?): Boolean {
        if (code == KeyEvent.KEYCODE_BUTTON_L1 || code == KeyEvent.KEYCODE_BUTTON_R1) {
            // a repeat means the button is being held: whatever is focused may act on it, and the release must not page
            if ((event?.repeatCount ?: 0) > 0) shoulderHeld = true
            return true
        }
        return event?.repeatCount == 0 && onKeyDown(code)
    }

    /** The window's `Activity.onKeyUp`: L1/R1 fire here unless they were held (see [keyDown]). */
    fun keyUp(code: Int): Boolean {
        if (code != KeyEvent.KEYCODE_BUTTON_L1 && code != KeyEvent.KEYCODE_BUTTON_R1) return false
        val held = shoulderHeld
        shoulderHeld = false
        return held || onKeyDown(code)
    }

    companion object {
        /** The hotkey a key code maps to, or null for keys the framework handles (A/B/D-pad). */
        fun keyFor(code: Int): Key? = when (code) {
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> Key.SEARCH
            KeyEvent.KEYCODE_BUTTON_Y -> Key.FAVOURITE
            KeyEvent.KEYCODE_BUTTON_SELECT -> Key.CONTEXT
            KeyEvent.KEYCODE_BUTTON_X -> Key.SWAP_SCREENS
            KeyEvent.KEYCODE_BUTTON_L1 -> Key.PREV_FOLDER
            KeyEvent.KEYCODE_BUTTON_R1 -> Key.NEXT_FOLDER
            KeyEvent.KEYCODE_BUTTON_R2 -> Key.WANT_TO_PLAY
            KeyEvent.KEYCODE_BUTTON_L2 -> Key.NOTE
            else -> null
        }
    }
}

/** The [Hotkeys] of the activity window a screen is composed in. Each activity (and each overlay window) provides its own. */
val LocalHotkeys = staticCompositionLocalOf<Hotkeys> { error("No Hotkeys provided: the hosting window must provide LocalHotkeys") }
