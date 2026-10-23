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

    fun handle(keys: Set<Key>) {
        handled.value = handled.value + keys
    }

    fun release(keys: Set<Key>) {
        handled.value = handled.value - keys
    }

    /** Returns true when the key was a hotkey that a screen consumes. */
    fun onKeyDown(code: Int): Boolean {
        val key = when (code) {
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> Key.SEARCH
            KeyEvent.KEYCODE_BUTTON_Y -> Key.FAVOURITE
            KeyEvent.KEYCODE_BUTTON_SELECT -> Key.CONTEXT
            KeyEvent.KEYCODE_BUTTON_X -> Key.SWAP_SCREENS
            KeyEvent.KEYCODE_BUTTON_L1 -> Key.PREV_FOLDER
            KeyEvent.KEYCODE_BUTTON_R1 -> Key.NEXT_FOLDER
            else -> return false
        }
        return key in handled.value && events.tryEmit(key)
    }
}
