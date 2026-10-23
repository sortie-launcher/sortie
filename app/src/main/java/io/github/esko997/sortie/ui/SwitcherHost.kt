package io.github.esko997.sortie.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.shell.SwitcherOverlay
import io.github.esko997.sortie.shell.TaskSwitcher
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Presents the app switcher as overlay windows: the controller-driven [SwitcherScreen] on the display that had the controller,
 * the touch [TouchSwitcher] on the other, both over a dark scrim with the games still running underneath. Installed into
 * [TaskSwitcher.presenter]/[TaskSwitcher.dismisser] by the application; returns false (so the activity route is used) without
 * the draw-over-apps grant.
 */
class SwitcherHost(private val graph: SortieApp.Graph) {
    private val overlay = SwitcherOverlay(graph.app)

    /** Y / Select from the overlay's own key listener; kept apart from [Hotkeys.events] so Sortie's screens underneath never react. */
    private val keys = MutableSharedFlow<Hotkeys.Key>(extraBufferCapacity = 8)

    fun install() {
        graph.switcher.presenter = ::present
        graph.switcher.dismisser = overlay::hide
        graph.switcher.textInput = overlay::allowTextInput
    }

    private fun present(focusDisplay: Int): Boolean {
        if (!overlay.available) return false
        val switcher = graph.switcher
        val displays = listOfNotNull(TaskSwitcher.TOP, switcher.secondaryDisplay()).distinct()
        switcher.open(overlay = true, focusDisplay = focusDisplay)
        overlay.show(displays, focusDisplay, onClose = switcher::close, onHotkey = { keys.tryEmit(it) }) { displayId, controller ->
            SortieTheme {
                Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = Color(0xDD0B0D10), contentColor = MaterialTheme.colorScheme.onSurface) {
                    if (controller) {
                        SwitcherScreen(keys = keys, claimHotkeys = false, owner = false)
                    } else {
                        val state by switcher.state.collectAsState()
                        TouchSwitcher(switcher, state, displayId)
                    }
                }
            }
        }
        // Home on the top screen also resumed Sortie's HOME activity over the game: raise the game again so it keeps running
        // under the overlay, and give the controller to the overlay's display (a focusable window alone does not do that).
        if (overlay.shown) switcher.reveal(focusDisplay)
        return overlay.shown
    }
}
