package io.github.esko997.sortie.data.apps

import io.github.esko997.sortie.data.db.GameEntity

/**
 * One launchable thing a favourites list or smart folder can hold: a game from the index, an installed app (`app:<package>`)
 * or a launcher shortcut published by another app (`shortcut:<package>/<shortcutId>`). Game-only screens keep using GameEntity.
 */
sealed interface LibraryItem {
    val id: String
    val label: String

    /** Case-folded key for title sorting; games use their article-stripped sort title. */
    val sortKey: String

    data class Game(val game: GameEntity) : LibraryItem {
        override val id: String get() = game.id
        override val label: String get() = game.title
        override val sortKey: String get() = game.sortTitle
    }

    data class App(val app: AppEntry) : LibraryItem {
        override val id: String get() = app.id
        override val label: String get() = app.label
        override val sortKey: String get() = app.label.lowercase()
    }

    data class Shortcut(val shortcut: ShortcutEntry) : LibraryItem {
        override val id: String get() = shortcut.id
        override val label: String get() = shortcut.label
        override val sortKey: String get() = shortcut.label.lowercase()
    }

    companion object {
        const val APP_PREFIX = "app:"
        const val SHORTCUT_PREFIX = "shortcut:"

        /** Games keep their index id (`<system>/<file>`); everything else carries a prefix. */
        fun isGameId(id: String): Boolean = !id.startsWith(APP_PREFIX) && !id.startsWith(SHORTCUT_PREFIX)
    }
}
