package io.github.esko997.sortie.data.tonight

import android.content.Context
import io.github.esko997.sortie.data.db.GameDao
import io.github.esko997.sortie.data.db.GameEntity
import io.github.esko997.sortie.data.db.MatchRow
import io.github.esko997.sortie.data.db.byIds
import io.github.esko997.sortie.data.magazine.GamePresence
import io.github.esko997.sortie.data.magazine.MagazineGames
import io.github.esko997.sortie.data.magazine.MagazineReadingStore
import io.github.esko997.sortie.data.magazine.MagazineRepository
import io.github.esko997.sortie.data.magazine.presenceOf
import io.github.esko997.sortie.data.manifest.SystemDef
import io.github.esko997.sortie.data.manifest.UserData
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * A saved pick as it stands now. [game] is null once the file has left the index; [unavailable] says why the pick cannot be
 * played now (gone, hidden since, not copied, still syncing, missing) — the pick stays in the set, disabled, never replaced.
 * [bookmark] is the game's current bookmark text, if any.
 */
data class TonightCard(val pick: TonightPick, val game: GameEntity?, val unavailable: String?, val bookmark: String?) {
    val playable: Boolean get() = game != null && unavailable == null
}

/**
 * Gathers Tonight's inputs from what Sortie already holds — the index, play history, user.json and the magazines' game
 * references resolved by [MagazineGames] (the same matching as an article's library lookup) — and keeps each date's set in
 * [store]. Nothing runs on a timer: a set is chosen when a Tonight visit first asks for its date.
 */
class Tonight(
    private val context: Context,
    private val dao: GameDao,
    private val user: StateFlow<UserData>,
    private val systems: () -> List<SystemDef>,
    private val magazines: MagazineRepository,
    private val magazineGames: MagazineGames,
    private val store: TonightStore
) {
    /** [date]'s set: the saved one, or chosen now and saved (first request of that date). */
    suspend fun day(date: LocalDate): TonightDay = store.day(date) { choose(date) }

    /** [day]'s picks with their games as indexed now and whether each can still be played. Touches the files of at most five games. */
    suspend fun cards(day: TonightDay): List<TonightCard> = withContext(Dispatchers.IO) {
        val u = user.value
        val games = dao.byIds(day.picks.map { it.gameId }).associateBy { it.id }
        tonightCards(day, games, u.hidden, bookmarksOf(u), ::presence)
    }

    private suspend fun choose(date: LocalDate): List<TonightPick> = withContext(Dispatchers.IO) {
        val u = user.value
        val stats = dao.playStats().first().associateBy { it.gameId }
        val games = dao.allPlayable().toMutableList()
        val ids = games.mapTo(HashSet()) { it.id }
        // Shed files remain history: their metadata still informs familiar/different picks, but they cannot be picked.
        games += dao.byIds(stats.keys.filterNot(ids::contains))
        val input = TonightInput(
            games = games,
            stats = stats,
            hidden = u.hidden,
            shelved = u.gameProgress.filterValues { it.shelf != null }.keys,
            wantToPlay = u.wantToPlay,
            bookmarks = bookmarksOf(u),
            leads = leads(),
            systemLabels = systems().associate { it.id to it.label },
            present = { presence(it) == GamePresence.AVAILABLE }
        )
        pickTonight(date, input)
    }

    /**
     * Every article reference that resolves to exactly one available local file, newest issue first and in article order,
     * with whether the article was read to its end. A reference matching several files is the reader's choice, never picked.
     */
    private suspend fun leads(): List<ZineLead> {
        val shelf = magazines.shelf()
        val refs = shelf.issues.flatMap { it.issue.games }.distinctBy { it.id }
        if (refs.isEmpty()) return emptyList()
        val matches = magazineGames.resolve(refs)
        val reading = MagazineReadingStore.open(context)
        val out = ArrayList<ZineLead>()
        for (issue in shelf.issues) {
            for (article in issue.issue.articles) {
                for (ref in article.games) {
                    val local = matches[ref.id].orEmpty().filter { it.available }.singleOrNull() ?: continue
                    val target = TonightArticle(issue.issue.id, article.id, article.title, issue.issue.title)
                    out += ZineLead(local.gameId, target, reading.finished(issue.issue.id, article.id))
                }
            }
        }
        return out
    }

    /** The same file checks as a magazine reference's library lookup. Blocking. */
    private fun presence(g: GameEntity): GamePresence =
        presenceOf(MatchRow(g.id, g.systemId, g.fileName, g.path, g.title, g.size, g.placeholder, g.arriving, g.hack))

    /** Game id → bookmark text, blank ones absent. */
    private fun bookmarksOf(u: UserData): Map<String, String> = u.bookmarks.mapValues { it.value.text }.filterValues { it.isNotBlank() }
}

/**
 * Every pick of [day], in order, with its game from [games] (the index now) and why it cannot be played, if it cannot: a pick is
 * never dropped or replaced once saved. [presence] checks a game's file; it is asked only for games still indexed and shown.
 */
internal fun tonightCards(
    day: TonightDay,
    games: Map<String, GameEntity>,
    hidden: Set<String>,
    bookmarks: Map<String, String>,
    presence: (GameEntity) -> GamePresence
): List<TonightCard> = day.picks.map { p ->
    val g = games[p.gameId]
    val unavailable = when {
        g == null -> "No longer in this device's library."
        g.id in hidden -> "Hidden since it was picked."
        else -> when (presence(g)) {
            GamePresence.AVAILABLE -> null
            GamePresence.PLACEHOLDER -> "Not copied to this device."
            GamePresence.ARRIVING -> "Still arriving through sync."
            GamePresence.MISSING -> "Its file is missing."
        }
    }
    TonightCard(p, g, unavailable, bookmarks[p.gameId])
}
