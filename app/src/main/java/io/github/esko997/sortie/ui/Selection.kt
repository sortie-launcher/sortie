package io.github.esko997.sortie.ui

import io.github.esko997.sortie.data.db.GameEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

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

    /**
     * What the companion screen shows: the selected game, else a hero that changes every [HERO_ROTATION_MS] while [shown].
     * Each change loads the hero's picture, works out its palette and blends the other screen's background, so a hidden or
     * dark companion must not rotate: its timers keep running while it draws nothing.
     */
    fun panel(shown: Flow<Boolean>): Flow<GameEntity?> = combine(current, heroes, heroTicker(shown), heroRotation) { cur, hs, i, rotate ->
        cur ?: if (rotate) hs.getOrNull(if (hs.isEmpty()) 0 else i % hs.size) else null
    }

    /** Slow enough that the ambient background has settled long before the next change. */
    const val HERO_ROTATION_MS = 20_000L
}

/**
 * The hero index: the current one each time [shown] turns true, then the next one every [HERO_ROTATION_MS] for as long as it
 * stays true; while false there is no timer at all. [sleep] is the wait between steps (tests drive it).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun heroTicker(shown: Flow<Boolean>, sleep: suspend (Long) -> Unit = { delay(it) }): Flow<Int> = channelFlow {
    var i = 0
    shown.distinctUntilChanged().collectLatest { on ->
        if (!on) return@collectLatest
        send(i)
        while (true) {
            sleep(Selection.HERO_ROTATION_MS)
            send(++i)
        }
    }
}
