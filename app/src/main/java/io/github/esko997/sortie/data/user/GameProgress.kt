package io.github.esko997.sortie.data.user

import io.github.esko997.sortie.data.apps.LibraryItem
import java.time.LocalDate
import kotlinx.serialization.Serializable

/** Reserved id of the built-in Finished folder (tile `folder:finished`); a `folders.json` entry with this id is ignored. */
const val FINISHED_FOLDER_ID = "finished"

/** Reserved id of the built-in Shelved folder (tile `folder:shelved`); a `folders.json` entry with this id is ignored. */
const val SHELVED_FOLDER_ID = "shelved"

/** Longest verdict the editor accepts, in characters: a line or two, not a review. */
const val VERDICT_MAX_CHARS = 280

/** A personal shelf for a game the user has stopped playing. Stored by [key] (stable, lower-case); each has a built-in folder. */
enum class Shelf(val key: String, val label: String, val folderId: String) {
    /** Played through, or as far as the user means to. */
    FINISHED("finished", "Finished", FINISHED_FOLDER_ID),

    /** Put aside without finishing. */
    SHELVED("shelved", "Shelved", SHELVED_FOLDER_ID);

    companion object {
        fun of(key: String): Shelf? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Where a game ended up: one [status], so a game is on at most one shelf, an optional personal [verdict] that belongs to the
 * shelving (removed with it), and the local date ([since], `yyyy-MM-dd`) the game went onto its current shelf. Independent of
 * favourites, Want to play and the game's notes. A [status] this build does not know (a newer build's) is kept verbatim and
 * puts the game on no shelf; every field has a default so an incomplete entry cannot fail the whole user.json.
 */
@Serializable
data class GameProgress(
    val status: String = "",
    val since: String = "",
    val verdict: String = ""
) {
    /** The shelf [status] names, or null when this build does not know it. */
    val shelf: Shelf? get() = Shelf.of(status)

    /** [since] as a date, or null when absent or malformed. */
    val sinceDate: LocalDate? get() = runCatching { LocalDate.parse(since) }.getOrNull()
}

/**
 * The progress map with game [id] on [shelf] and [verdict] (trimmed; blank = none); a null [shelf] removes the entry, verdict
 * included. Staying on the same shelf keeps its date (a verdict edit is not a new finish); a different shelf is dated [today].
 * Throws [IllegalArgumentException] for an app or shortcut id and for a verdict over [VERDICT_MAX_CHARS], so a commit fails
 * visibly instead of saving something else.
 */
fun Map<String, GameProgress>.withShelf(id: String, shelf: Shelf?, verdict: String, today: LocalDate): Map<String, GameProgress> {
    require(LibraryItem.isGameId(id)) { "only games go on a shelf" }
    if (shelf == null) return this - id
    val text = verdict.trim()
    require(text.length <= VERDICT_MAX_CHARS) { "the verdict is ${text.length} characters; the limit is $VERDICT_MAX_CHARS" }
    val previous = this[id]
    val since = previous?.takeIf { it.shelf == shelf && it.sinceDate != null }?.since ?: today.toString()
    return this + (id to GameProgress(shelf.key, since, text))
}

/** Game ids on [shelf], in no particular order; entries with an unknown status belong to none. Ids not in the index are kept. */
fun Map<String, GameProgress>.idsOn(shelf: Shelf): Set<String> = buildSet {
    for ((id, progress) in this@idsOn) if (progress.shelf == shelf) add(id)
}
