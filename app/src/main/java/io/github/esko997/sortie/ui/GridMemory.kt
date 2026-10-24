package io.github.esko997.sortie.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where a browse grid or list was: the item that last had the D-pad focus and the first visible item, both by key. Saved
 * with its navigation destination, so it comes back with Back from a detail screen and across the X screen swap
 * (MainActivity hands its saved state to the instance it starts on the other screen). Restored by key, never by pixel
 * offset: the other screen may fit a different number of rows and columns.
 */
@Stable
class GridMemory internal constructor(focused: String?, internal var anchor: String?, internal var keyboard: Boolean = false) {
    /** Key of the item that last had the focus. */
    var focused by mutableStateOf(focused)
        internal set

    /** Whether the [focused] item holds the focus right now. */
    internal var holding = false

    /** Set until [RememberGridPosition] has acted on the saved keys; once per composition of the destination. */
    internal var pending = focused != null || anchor != null

    /**
     * [keyboard] as saved when the destination was left: only then does [RememberGridPosition] focus [focused] again. Kept
     * apart because [keyboard] follows the live input mode from the first frame, which may differ on the way back.
     */
    internal val restoreFocus = keyboard
    internal val requester = FocusRequester()

    companion object {
        val saver: Saver<GridMemory, Any> = listSaver(
            save = { listOf(it.focused, it.anchor, it.keyboard) },
            restore = { GridMemory(it[0] as String?, it[1] as String?, it[2] as Boolean) }
        )
    }
}

@Composable
fun rememberGridMemory(): GridMemory = rememberSaveable(saver = GridMemory.saver) { GridMemory(null, null) }

/** One grid item: records itself as [GridMemory.focused] when it takes the focus, and is where the focus goes back to. */
@Composable
fun RememberedItem(memory: GridMemory, key: String, content: @Composable () -> Unit) {
    // derived, so a focus move recomposes the two items involved rather than every visible one
    val target by remember(memory, key) { derivedStateOf { memory.focused == key } }
    Box(
        Modifier
            .onFocusChanged {
                if (it.hasFocus) {
                    memory.focused = key
                    memory.holding = true
                } else if (memory.focused == key) {
                    memory.holding = false
                }
            }
            .then(if (target) Modifier.focusRequester(memory.requester) else Modifier),
        propagateMinConstraints = true
    ) { content() }
}

/**
 * Brings [GridMemory.focused] on screen and focuses it again, retrying while it composes (the item composes on the next layout
 * pass); focusing it also brings a partly visible tile fully into view. Stops once it holds the focus. [keys] lists every
 * item of the grid in order (headers too); a key that no longer resolves is skipped.
 */
internal suspend fun GridMemory.focusRemembered(state: LazyGridState, keys: List<Any>) {
    val key = focused ?: return
    val index = keys.indexOf(key)
    if (index < 0) return
    if (state.layoutInfo.visibleItemsInfo.none { it.key == key }) state.scrollToItem(index)
    repeat(25) {
        runCatching { requester.requestFocus() }
        delay(40)
        if (holding) return
    }
}

/**
 * Tracks the first visible item of [state] and the input mode and, once per composition, brings [memory] back when the grid
 * has its items: scrolls to the remembered first item unless the lazy state already restored it (same screen), and when the
 * controller was in use on leaving makes sure the focused item is on screen and focuses it again. A key that no longer
 * resolves (item removed, filtered out) is skipped, leaving the lazy state's own clamped position. [keys] lists every item of
 * the grid in order (headers too).
 *
 * The focus has to be back before the screen we return from leaves the composition: when its focused item is removed while
 * nothing else holds the focus, Compose clears the view's focus and, outside touch mode, Android hands it straight back to
 * the first focusable item on screen (Home's Apps button).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RememberGridPosition(memory: GridMemory, state: LazyGridState, keys: List<Any>) {
    val inputModes = LocalInputModeManager.current
    // always the live mode: a restored screen entered with the controller already in use sees no further change to record
    LaunchedEffect(memory, inputModes) {
        snapshotFlow { inputModes.inputMode == InputMode.Keyboard }.collect { memory.keyboard = it }
    }
    LaunchedEffect(memory, state) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.firstOrNull()?.key }.collect { key ->
            if (!memory.pending && key is String) memory.anchor = key
        }
    }
    LaunchedEffect(memory, state, keys) {
        if (!memory.pending || keys.isEmpty()) return@LaunchedEffect // nothing to restore, or still loading
        withTimeoutOrNull(1_000) { snapshotFlow { state.layoutInfo.totalItemsCount }.first { it == keys.size } }
        memory.pending = false
        val anchor = memory.anchor
        val anchorIndex = anchor?.let { keys.indexOf(it) } ?: -1
        if (anchorIndex >= 0 && state.layoutInfo.visibleItemsInfo.firstOrNull()?.key != anchor) state.scrollToItem(anchorIndex)
        // Touch: the anchor is the position. A focus remembered from earlier D-pad use may be far from where the user tapped.
        if (!memory.restoreFocus) return@LaunchedEffect
        inputModes.requestInputMode(InputMode.Keyboard)
        memory.focusRemembered(state, keys)
    }
}
