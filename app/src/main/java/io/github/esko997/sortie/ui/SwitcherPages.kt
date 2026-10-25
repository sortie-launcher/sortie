package io.github.esko997.sortie.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** A card in the switcher's app list: the screen row it sits in and its task. */
data class CardSpot(val row: Int, val task: Int)

/**
 * Which page each switcher overlay window shows (by display), and the card that last had the controller focus in each
 * window's app list, so leaving the Perf page puts the focus back where it was. Every summons starts on the app list
 * ([reset]). Main thread only.
 */
class SwitcherPages {
    enum class Page { APPS, PERF }

    private val pages = MutableStateFlow<Map<Int, Page>>(emptyMap())
    val state: StateFlow<Map<Int, Page>> = pages
    private val focus = HashMap<Int, CardSpot>()

    fun page(display: Int): Page = pages.value[display] ?: Page.APPS

    fun openPerf(display: Int) = pages.update { it + (display to Page.PERF) }

    /** Back to the app list; false when [display] already shows it (so B closes the switcher instead). */
    fun back(display: Int): Boolean {
        if (page(display) != Page.PERF) return false
        pages.update { it - display }
        return true
    }

    /** L1 / R1: the other page. */
    fun toggle(display: Int) {
        if (!back(display)) openPerf(display)
    }

    /** A card took the focus. Losing it is not recorded: the list leaving composition must not forget where it was. */
    fun focused(display: Int, spot: CardSpot) {
        focus[display] = spot
    }

    /** The card to give the focus back to when [display]'s app list returns; null before any card had it. */
    fun returnSpot(display: Int): CardSpot? = focus[display]

    fun reset() {
        pages.value = emptyMap()
        focus.clear()
    }
}
