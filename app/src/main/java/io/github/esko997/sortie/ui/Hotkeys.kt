package io.github.esko997.sortie.ui

import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Controller grammar (docs/SPEC.md): A select · B back · X swap screens · Y favourite · Start search · Select context menu ·
 * L1/R1 previous/next folder. A and B are handled by the framework (focus click / back); the rest arrive here as events that
 * the current screen interprets. Keeping it an event bus avoids every screen owning a key handler.
 */
object Hotkeys {
    enum class Key { SEARCH, FAVOURITE, CONTEXT, SWAP_SCREENS, PREV_FOLDER, NEXT_FOLDER }

    val events = MutableSharedFlow<Key>(extraBufferCapacity = 8)

    /** Keys some visible screen currently handles; everything else falls through to the framework. */
    val handled = kotlinx.coroutines.flow.MutableStateFlow<Set<Key>>(emptySet())

    // Reference counts: during a navigation transition the entering screen handles a key before the leaving screen releases it,
    // so a plain set would drop the key the new screen just claimed.
    private val owners = HashMap<Key, Int>()

    fun handle(keys: Set<Key>) = synchronized(owners) {
        keys.forEach { owners[it] = (owners[it] ?: 0) + 1 }
        handled.value = owners.keys.toSet()
    }

    fun release(keys: Set<Key>) = synchronized(owners) {
        for (k in keys) {
            val n = (owners[k] ?: 0) - 1
            if (n <= 0) owners.remove(k) else owners[k] = n
        }
        handled.value = owners.keys.toSet()
    }

    /** The hotkey a key code maps to, or null for keys the framework handles (A/B/D-pad). */
    fun keyFor(code: Int): Key? = when (code) {
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> Key.SEARCH
        KeyEvent.KEYCODE_BUTTON_Y -> Key.FAVOURITE
        KeyEvent.KEYCODE_BUTTON_SELECT -> Key.CONTEXT
        KeyEvent.KEYCODE_BUTTON_X -> Key.SWAP_SCREENS
        KeyEvent.KEYCODE_BUTTON_L1 -> Key.PREV_FOLDER
        KeyEvent.KEYCODE_BUTTON_R1 -> Key.NEXT_FOLDER
        else -> null
    }

    /** Returns true when the key was a hotkey that a screen consumes. */
    fun onKeyDown(code: Int): Boolean {
        val key = keyFor(code) ?: return false
        return key in handled.value && events.tryEmit(key)
    }
}
