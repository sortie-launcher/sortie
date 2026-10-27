package io.github.esko997.sortie.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.shell.SwitcherOverlay
import io.github.esko997.sortie.shell.TaskSwitcher
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Presents the app switcher as overlay windows: the controller-driven [SwitcherScreen] on the display that had the controller,
 * the touch [TouchSwitcher] on the other, both over a dark scrim with the games still running underneath. Installed into
 * [TaskSwitcher.presenter]/[TaskSwitcher.dismisser] by the application; returns false (so the activity route is used) without
 * the draw-over-apps grant.
 *
 * Each window can show the Perf page instead ([OverlayPerf]): the "Perf" chip opens it by touch, L1/R1 on the controller
 * window; B (or L1/R1, or the "Apps" chip) goes back to the app list with the focus where it was ([SwitcherPages]).
 */
class SwitcherHost(private val graph: SortieApp.Graph) {
    private val overlay = SwitcherOverlay(graph.app)

    /** Y / Select from the overlay's own key listener; kept apart from [Hotkeys.events] so Sortie's screens underneath never react. */
    private val keys = MutableSharedFlow<Hotkeys.Key>(extraBufferCapacity = 8)

    private val pages = SwitcherPages()

    fun install() {
        graph.switcher.presenter = ::present
        graph.switcher.dismisser = overlay::hide
        graph.switcher.textInput = overlay::allowTextInput
    }

    private fun present(focusDisplay: Int, homeTask: Int?): Boolean {
        if (!overlay.available || !graph.user.data.value.overlaySwitcher) return false
        val switcher = graph.switcher
        val displays = listOfNotNull(TaskSwitcher.TOP, switcher.secondaryDisplay()).distinct()
        switcher.open(overlay = true, focusDisplay = focusDisplay)
        pages.reset() // every summons starts on the app list, kept windows included
        overlay.show(
            displays,
            focusDisplay,
            onClose = { if (!pages.back(focusDisplay)) switcher.close() },
            onHotkey = { key ->
                when (key) {
                    Hotkeys.Key.PREV_FOLDER, Hotkeys.Key.NEXT_FOLDER -> if (switcher.state.value.picking == null) pages.toggle(focusDisplay)
                    else -> keys.tryEmit(key)
                }
            }
        ) { displayId, controller ->
            SelectedSortieTheme {
                Surface(
                    Modifier.fillMaxSize().safeDrawingPadding(),
                    color = BarColour.copy(alpha = 0xDD / 255f),
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    val page by pages.state.collectAsState()
                    val state by switcher.state.collectAsState()
                    when {
                        page[displayId] == SwitcherPages.Page.PERF -> OverlayPerf(controller, onApps = { pages.back(displayId) })
                        controller -> SwitcherScreen(
                            onHome = { switcher.goHome(it) },
                            keys = keys,
                            claimHotkeys = false,
                            owner = false,
                            header = {
                                PerfChip("Perf", { pages.openPerf(displayId) })
                                DeviceStatsHeader()
                            },
                            extraHints = listOf("R1" to "perf"),
                            restoreFocus = pages.returnSpot(displayId),
                            onCardFocused = { pages.focused(displayId, it) }
                        )
                        else -> Box(Modifier.fillMaxSize()) {
                            TouchSwitcher(switcher, state, displayId)
                            if (state.picking != displayId) {
                                PerfChip("Perf", { pages.openPerf(displayId) }, Modifier.align(Alignment.TopEnd).padding(12.dp))
                            }
                        }
                    }
                }
            }
        }
        // Home can cover navigation or a companion after an X swap, not just a game. Restore that exact surface rather
        // than raising an unrelated background app; the overlay keeps the controller on the invoking display.
        if (overlay.shown) switcher.reveal(focusDisplay, homeTask)
        return overlay.shown
    }
}
