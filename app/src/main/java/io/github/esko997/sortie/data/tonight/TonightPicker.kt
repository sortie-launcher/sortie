package io.github.esko997.sortie.data.tonight

import io.github.esko997.sortie.data.apps.LibraryItem
import io.github.esko997.sortie.data.db.GameEntity
import io.github.esko997.sortie.data.db.PlayStat
import io.github.esko997.sortie.data.index.cleanTitle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.serialization.Serializable

/*
 * Tonight: one small set of up to five games per local date, one per role, chosen by fixed rules from what Sortie already
 * knows — the index's metadata, play history, Want to play, bookmarks and the magazines' game references. No questions, no
 * network, no model: every reason states the rule that picked the game and the data it used, nothing it would have to guess
 * (how long a session takes, whether the player will like it). A role whose rule has nothing to work with (no history yet, no
 * Field Notes article about a playable game) becomes a labelled discovery pick instead of pretending. The set is chosen once
 * per date and stored (TonightStore); this file only chooses.
 */

/** The five roles in display order. [key] is what the store keeps. */
enum class TonightRole(val key: String, val label: String) {
    /** The game to return to: bookmarked games first, then the most recently played. */
    RESUME("resume", "Pick up again"),

    /** Unplayed, made by the same developer as (or else sharing a genre with) one of the most-played games. */
    FAMILIAR("familiar", "Familiar ground"),

    /** Unplayed, on a system and in a genre the play history has never touched. */
    DIFFERENT("different", "Something different"),

    /** A playable game a Field Notes article discusses, with that exact article. */
    FIELD_NOTES("field-notes", "From Field Notes"),

    /** A random draw, seeded by the date. */
    RANDOM("random", "Wild card");

    companion object {
        fun of(key: String): TonightRole? = entries.firstOrNull { it.key == key }
    }
}

/** The exact article a Field Notes pick opens. */
@Serializable
data class TonightArticle(val issueId: String, val articleId: String, val articleTitle: String, val issueTitle: String)

/**
 * One saved pick. [title] is the title when it was picked, so a pick whose game has since left the library can still be named.
 * [fallback] is set when the role's own rule had nothing to offer and this is a discovery pick instead: it says why.
 * [reason] names the rule and the data it used, as of the day it was picked.
 */
@Serializable
data class TonightPick(
    val role: String,
    val gameId: String,
    val title: String,
    val reason: String,
    val fallback: String? = null,
    val article: TonightArticle? = null
) {
    val roleOf: TonightRole? get() = TonightRole.of(role)
}

/** The set chosen for one local [date] (ISO `yyyy-MM-dd`), in [TonightRole] order. */
@Serializable
data class TonightDay(val date: String, val picks: List<TonightPick>)

/** A Field Notes article that discusses exactly one available local file ([gameId]); [finished]: read to its end. */
data class ZineLead(val gameId: String, val article: TonightArticle, val finished: Boolean)

/**
 * What a pick is chosen from. [games]: copied games plus indexed games with play history, including shed files and variants
 * so they still inform the rules; [stats]: play history by game id; [shelved]: games on the Finished or Shelved shelf;
 * [bookmarks]: game id → bookmark text (blank entries absent); [leads]: Field Notes leads newest issue first;
 * [present]: whether a game's file can be opened now (checked only for games about to be picked).
 */
class TonightInput(
    val games: List<GameEntity>,
    val stats: Map<String, PlayStat>,
    val hidden: Set<String>,
    val shelved: Set<String>,
    val wantToPlay: Set<String>,
    val bookmarks: Map<String, String>,
    val leads: List<ZineLead>,
    val systemLabels: Map<String, String>,
    val zone: ZoneId = ZoneId.systemDefault(),
    val present: (GameEntity) -> Boolean
)

/**
 * Chooses [date]'s set: at most one game per role, never the same id or the same cleaned title twice. Hidden, shelved,
 * placeholder, still-arriving and missing files are never picked; a small library gets fewer picks. Deterministic for the same
 * date and input; ties are broken by an order seeded with the date, so a different day can surface different games.
 */
fun pickTonight(date: LocalDate, input: TonightInput): List<TonightPick> = TonightPicker(date, input).pick()

private class TonightPicker(private val date: LocalDate, private val input: TonightInput) {
    private val byId = input.games.associateBy { it.id }
    private val eligible = input.games
        .filter { LibraryItem.isGameId(it.id) && it.id !in input.hidden && it.id !in input.shelved && !it.placeholder && !it.arriving && it.variantOf == null }
        .sortedBy { it.id }
    private val eligibleById = eligible.associateBy { it.id }

    /** Games with at least one session, most played first: the history the rules compare with. */
    private val played: List<Pair<GameEntity, PlayStat>> = input.stats.values
        .filter { it.sessions > 0 }
        .mapNotNull { s -> byId[s.gameId]?.let { it to s } }
        .sortedWith(compareByDescending<Pair<GameEntity, PlayStat>> { it.second.minutes }.thenByDescending { it.second.sessions }.thenBy { it.first.id })
    private val unplayed = eligible.filter { (input.stats[it.id]?.sessions ?: 0) == 0 }

    private val picks = LinkedHashMap<TonightRole, TonightPick>()
    private val takenIds = HashSet<String>()
    private val takenTitles = HashSet<String>()
    private val checked = HashMap<String, Boolean>()

    fun pick(): List<TonightPick> {
        resume()
        fieldNotes()
        familiar()
        different()
        for (role in listOf(TonightRole.RESUME, TonightRole.FAMILIAR, TonightRole.DIFFERENT, TonightRole.FIELD_NOTES)) {
            if (role !in picks) discovery(role)
        }
        random()
        return TonightRole.entries.mapNotNull { picks[it] }
    }

    private fun usable(g: GameEntity): Boolean = g.id !in takenIds && titleKey(g) !in takenTitles && checked.getOrPut(g.id) { input.present(g) }

    private fun take(role: TonightRole, g: GameEntity, reason: String, fallback: String? = null, article: TonightArticle? = null) {
        takenIds += g.id
        takenTitles += titleKey(g)
        picks[role] = TonightPick(role.key, g.id, cleanTitle(g.title), reason, fallback, article)
    }

    /** [games] sorted by id then shuffled with the date and [role]: the tie order of every rule. */
    private fun seeded(games: List<GameEntity>, role: TonightRole, salt: Int = 0): List<GameEntity> =
        games.sortedBy { it.id }.shuffled(Random(date.toEpochDay() * 64 + role.ordinal * 8 + salt))

    private fun resume() {
        val candidates = eligible.filter { input.stats[it.id]?.lastPlayed != null && (input.stats[it.id]?.sessions ?: 0) > 0 }
            .sortedWith(compareBy<GameEntity> { it.id !in input.bookmarks }.thenByDescending { input.stats.getValue(it.id).lastPlayed })
        val g = candidates.firstOrNull(::usable) ?: return
        val stat = input.stats.getValue(g.id)
        val history = "Last played ${day(stat.lastPlayed!!)}; ${playedFor(stat)}."
        take(TonightRole.RESUME, g, if (g.id in input.bookmarks) "You left a bookmark in it. $history" else history)
    }

    private fun fieldNotes() {
        // unread articles first, then read ones; within each, a date-seeded order so the pick moves between days (stable sort)
        val ordered = input.leads.shuffled(Random(date.toEpochDay() * 64 + TonightRole.FIELD_NOTES.ordinal * 8)).sortedBy { it.finished }
        for (lead in ordered) {
            val g = eligibleById[lead.gameId] ?: continue
            if (!usable(g)) continue
            val read = if (lead.finished) "" else " You haven't read it to the end."
            take(TonightRole.FIELD_NOTES, g, "“${lead.article.articleTitle}” in ${lead.article.issueTitle} discusses it.$read", article = lead.article)
            return
        }
    }

    private fun familiar() {
        val basis = played.take(FAMILIAR_BASIS)
        if (basis.isEmpty()) return
        data class Match(val game: GameEntity, val developer: Boolean, val basis: Int)
        val matches = seeded(unplayed, TonightRole.FAMILIAR).mapNotNull { g ->
            val dev = norm(g.developer)
            val genre = norm(g.genre)
            val byDev = if (dev == null) -1 else basis.indexOfFirst { norm(it.first.developer) == dev }
            val byGenre = if (genre == null) -1 else basis.indexOfFirst { norm(it.first.genre) == genre }
            when {
                byDev >= 0 -> Match(g, true, byDev)
                byGenre >= 0 -> Match(g, false, byGenre)
                else -> null
            }
        }.sortedWith(compareBy<Match>({ !it.developer }, { it.basis }).thenComparator { a, b -> appeal.compare(a.game, b.game) })
        val m = matches.firstOrNull { usable(it.game) } ?: return
        val (b, stat) = basis[m.basis]
        val like = "${cleanTitle(b.title)}, which you've played ${playedFor(stat)}"
        val reason = if (m.developer) "Made by ${m.game.developer!!.trim()}, like $like." else "${m.game.genre!!.trim()}, like $like."
        take(TonightRole.FAMILIAR, m.game, "$reason You haven't played it yet.")
    }

    private fun different() {
        if (played.isEmpty()) return
        val systems = played.mapTo(HashSet()) { it.first.systemId }
        val genres = played.mapNotNullTo(HashSet()) { norm(it.first.genre) }
        fun tier(g: GameEntity): Int? {
            val newSystem = g.systemId !in systems
            val newGenre = norm(g.genre)?.let { it !in genres } == true
            return when {
                newSystem && newGenre -> 0
                newSystem -> 1
                newGenre -> 2
                else -> null
            }
        }
        val ranked = seeded(unplayed, TonightRole.DIFFERENT).mapNotNull { g -> tier(g)?.let { g to it } }
            .sortedWith(compareBy<Pair<GameEntity, Int>> { it.second }.thenComparator { a, b -> appeal.compare(a.first, b.first) })
        val (g, t) = ranked.firstOrNull { usable(it.first) } ?: return
        val system = input.systemLabels[g.systemId]?.takeIf { it.isNotBlank() } ?: g.systemId
        val genre = g.genre?.trim()
        val reason = when (t) {
            0 -> "Outside your indexed play history: $system and $genre."
            1 -> "A system outside your indexed play history: $system."
            else -> "A genre not recorded in your indexed play history: $genre."
        }
        take(TonightRole.DIFFERENT, g, "$reason You haven't played it yet.")
    }

    /** The labelled stand-in for [role] when its rule found nothing: Want to play first, then curated, rated, unplayed games. */
    private fun discovery(role: TonightRole) {
        val why = when (role) {
            TonightRole.RESUME -> if (input.stats.values.none { it.sessions > 0 && LibraryItem.isGameId(it.gameId) }) {
                "No play history yet, so nothing to pick up again."
            } else {
                "No previously played game is available for this slot."
            }
            TonightRole.FAMILIAR -> if (played.isEmpty()) {
                "No indexed play history to find familiar ground from."
            } else {
                "No additional playable match for your most-played games remains for this slot."
            }
            TonightRole.DIFFERENT -> if (played.isEmpty()) {
                "No indexed play history to contrast with."
            } else {
                "No additional playable contrast could be found from the recorded system and genre metadata."
            }
            TonightRole.FIELD_NOTES -> "No additional playable Field Notes match remains for this slot."
            TonightRole.RANDOM -> return
        }
        val order = seeded(eligible, role, salt = 1)
        val unplayedIds = unplayed.mapTo(HashSet()) { it.id }
        order.firstOrNull { it.id in input.wantToPlay && usable(it) }?.let { return take(role, it, "It's on your Want to play list.", why) }
        order.filter { it.id in unplayedIds && it.handWritten && it.hook != null }.sortedWith(appeal).firstOrNull(::usable)?.let {
            return take(role, it, "It has a hand-written guide note, and you haven't played it yet.", why)
        }
        order.filter { it.id in unplayedIds && (it.rating ?: 0f) > 0f }.sortedByDescending { it.rating }.firstOrNull(::usable)?.let {
            return take(role, it, "Rated ${rating(it.rating!!)} in your library's metadata, and you haven't played it yet.", why)
        }
        order.firstOrNull { it.id in unplayedIds && usable(it) }?.let { return take(role, it, "You haven't played it yet.", why) }
    }

    private fun random() {
        val g = seeded(eligible, TonightRole.RANDOM).firstOrNull(::usable) ?: return
        take(TonightRole.RANDOM, g, "Today's random draw from the rest of your library.")
    }

    private fun day(epochMs: Long): String = Instant.ofEpochMilli(epochMs).atZone(input.zone).toLocalDate().format(DATE)

    companion object {
        /** How many of the most-played games familiar ground compares with. */
        const val FAMILIAR_BASIS = 5

        private val DATE: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

        /** Within a rule's tier: a hand-written hook first (something to show), then a higher metadata rating. Stable sort keeps the seeded order. */
        private val appeal = compareBy<GameEntity> { !(it.handWritten && it.hook != null) }.thenByDescending { it.rating ?: 0f }

        private fun norm(s: String?): String? = s?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

        private fun titleKey(g: GameEntity): String = cleanTitle(g.title).lowercase(Locale.ROOT)
    }
}

/** "for 3 h 10 min over 5 sessions", or "in 2 sessions" when no completed session counted any time. */
internal fun playedFor(stat: PlayStat): String {
    val sessions = "${stat.sessions} session" + if (stat.sessions == 1) "" else "s"
    if (stat.minutes <= 0) return "in $sessions"
    val h = stat.minutes / 60
    val m = stat.minutes % 60
    val time = when {
        h == 0 -> "$m min"
        m == 0 -> "$h h"
        else -> "$h h $m min"
    }
    return "for $time over $sessions"
}

/** A gamelist rating as written: 0–1 as a percentage, anything larger as the number itself. */
internal fun rating(r: Float): String = if (r <= 1f) "${(r * 100).roundToInt()}%" else "%.1f".format(Locale.ROOT, r)
