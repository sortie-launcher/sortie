package io.github.esko997.sortie.data.journal

import java.time.LocalDate
import kotlinx.serialization.Serializable

/**
 * One dated personal note in the weekly journal (`user.json` → `journalEntries`). Recorded play sessions are not copied in:
 * they stay in the play-history database and the journal reads them live. Every field has a default so an older or hand-edited
 * entry never fails the whole user.json.
 */
@Serializable
data class JournalEntry(
    /** Random UUID; also names the entry's screenshot folder (`journal/media/<id>/`). */
    val id: String = "",
    /** The local calendar day the note is about, ISO `yyyy-MM-dd` (chosen by the user, not the day it was written). */
    val date: String = "",
    val text: String = "",
    /** Optional library item the note is about (game id, `app:` or `shortcut:` id). */
    val gameId: String? = null,
    /**
     * Optional recorded session the note is about: that session's `startedAt` (epoch ms) together with [gameId]. Row ids are not
     * used because a backup restore re-inserts sessions under new ones; game + start time identifies the same session anywhere.
     */
    val sessionStartedAt: Long? = null,
    /** Screenshots the user picked, copied under the card's `sortie/` folder; shown in the order added. */
    val attachments: List<JournalAttachment> = emptyList(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0
) {
    /** [date] as a day, or null when it is not a valid ISO date (the journal then lists the entry as undated). */
    fun localDate(): LocalDate? = runCatching { LocalDate.parse(date) }.getOrNull()

    /** Nothing written, linked or attached: such a draft is not saved. */
    fun isBlank(): Boolean = text.isBlank() && gameId == null && attachments.isEmpty()
}

/**
 * A screenshot copied into the journal. [path] is relative to the card's `sortie/` folder and always of the form
 * `journal/media/<entry id>/<file id>.<png|jpg|webp>` (see [JournalMedia.resolve]); [name] is the picked file's display name.
 */
@Serializable
data class JournalAttachment(
    val path: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val bytes: Long = 0,
    val name: String = ""
)

/** Most screenshots one entry holds; the picker's surplus is reported, not silently dropped. */
const val MAX_ATTACHMENTS = 12
