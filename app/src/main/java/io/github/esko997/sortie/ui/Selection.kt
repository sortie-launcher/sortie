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

    fun set(game: GameEntity?) {
        _current.value = game
    }
}
