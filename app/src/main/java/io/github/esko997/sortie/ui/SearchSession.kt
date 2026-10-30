package io.github.esko997.sortie.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.esko997.sortie.data.db.SearchFilters
import io.github.esko997.sortie.data.db.SearchScope

/**
 * Search as it was last left: the text and filters, how many results were loaded, and the scope. Kept in memory for the rest of
 * the process, so leaving Search (Back, Home, a game, the X swap) and opening it again finds it as it was; never written to
 * user.json. One navigation window exists at a time, so one session serves it. Snapshot state, used on the main thread.
 */
internal object SearchSession {
    var filters by mutableStateOf(SearchFilters())

    /** Results loaded so far; reset to one step when the question changes. */
    var limit by mutableIntStateOf(SEARCH_PAGE)

    /** Where Search looks; null is the whole library. Set by [open], removed with its "In:" chip. */
    var scope by mutableStateOf<SearchScope?>(null)

    /**
     * Search is being opened [from] a system or folder list (Y there, or Start), or from anywhere else (null: Home, its header
     * button) to search the whole library. The scope follows the place; the text and filters stay.
     */
    fun open(from: SearchScope?) {
        if (from == scope) return
        scope = from
        limit = SEARCH_PAGE
    }
}
