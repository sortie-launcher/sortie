package io.github.esko997.sortie.ui

import io.github.esko997.sortie.data.db.GameEntity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The game the user is looking at, for the companion panel on the other screen. */
object Selection {
    private val _current = MutableStateFlow<GameEntity?>(null)
    val current: StateFlow<GameEntity?> = _current

    /** Emitted when the companion Activity on the default display should go away (Sortie swapped back to that screen). */
    val closeCompanion = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Debug navigation requests from adb (`--es open diagnostics`). */
    val openRoute = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 1) // replay: emitted before the NavHost exists on cold start

    /** Mirrors `user.heroRotation`; false shows the plain Sortie panel when nothing is selected. Set by SortieViewModel. */
    val heroRotation = MutableStateFlow(true)

    /** Palette of the game shown on the companion screen (set by its hero panel); tints the navigation screen's background. */
    val heroPalette = MutableStateFlow<GamePalette?>(null)

    /** Candidates for the idle hero rotation (set by the view model). */
    val heroes = MutableStateFlow<List<GameEntity>>(emptyList())

    /**
     * Sortie's navigation activity is on screen (started). While it is, the companion shows the game being browsed even with
     * a play session open; once it stops (the game is back in front), the companion returns to Now playing. Set by MainActivity.
     */
    val navigationVisible = MutableStateFlow(false)

    /** Reader navigation for the other display; cleared when its owning magazine screen leaves composition. */
    internal val magazine = MutableStateFlow<MagazineCompanionState?>(null)
    internal val magazineActions = MutableSharedFlow<MagazineAction>(extraBufferCapacity = 16)

    fun set(game: GameEntity?) {
        _current.value = game
    }

    /** What the companion screen shows: the selected game, else a hero that changes every 20 s. */
    fun panel(): kotlinx.coroutines.flow.Flow<GameEntity?> {
        val ticker = kotlinx.coroutines.flow.flow {
            var i = 0
            while (true) {
                emit(i++)
                kotlinx.coroutines.delay(20_000) // slow enough that the ambient background has settled long before the next change
            }
        }
        return kotlinx.coroutines.flow.combine(current, heroes, ticker, heroRotation) { cur, hs, i, rotate ->
            cur ?: if (rotate) hs.getOrNull(if (hs.isEmpty()) 0 else i % hs.size) else null
        }
    }
}
