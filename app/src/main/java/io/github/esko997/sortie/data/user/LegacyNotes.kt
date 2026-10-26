package io.github.esko997.sortie.data.user

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** One game's personal text from before bookmarks: the "where was I?" note and the mapping notes, exactly as stored. */
data class LegacyGameNotes(val returnNote: String = "", val mappingNotes: String = "")

/**
 * Personal writing that older Sortie versions kept in `user.json` and this one no longer has a screen for: the weekly
 * journal's `journalEntries` (kept as the raw JSON, so fields this build never knew survive too) and each game's
 * `returnNote` / `mappingNotes`. Journal screenshots are files under `sortie/journal/media/`; only their metadata is here.
 */
data class LegacyNotes(
    /** The `journalEntries` value as found (normally an array of entries); null when absent or an empty array. */
    val journal: JsonElement? = null,
    /** Games with any non-blank legacy text, by game id. */
    val games: Map<String, LegacyGameNotes> = emptyMap()
) {
    val journalEntries: Int get() = (journal as? JsonArray)?.size ?: if (journal != null) 1 else 0
    val returnNotes: Int get() = games.values.count { it.returnNote.isNotBlank() }
    val mappingNotes: Int get() = games.values.count { it.mappingNotes.isNotBlank() }

    /** "3 journal entries, 2 "where was I?" notes and 1 mapping note", for previews and results. */
    fun describe(): String = listOfNotNull(
        journalEntries.takeIf { it > 0 }?.let { if (it == 1) "1 journal entry" else "$it journal entries" },
        returnNotes.takeIf { it > 0 }?.let { "$it \"where was I?\" note${if (it == 1) "" else "s"}" },
        mappingNotes.takeIf { it > 0 }?.let { "$it mapping note${if (it == 1) "" else "s"}" }
    ).let { parts -> if (parts.size <= 1) parts.joinToString("") else parts.dropLast(1).joinToString(", ") + " and " + parts.last() }
}

/** A `user` object with the legacy fields taken out ([user]) and what they held ([legacy], null when there was nothing). */
data class MigratedUser(val user: JsonObject, val legacy: LegacyNotes?)

/**
 * Turns a `user.json` object (or a backup's `user` section) written by an older Sortie into the bookmarks format, without
 * decoding it: unknown fields are left exactly as they are. `returnNote` becomes the game's bookmark (an existing bookmark
 * wins), `mappingNotes` and `journalEntries` are removed, and an annotation left without a controller value is dropped.
 * Everything removed or moved is returned as [LegacyNotes] so the caller can archive it ([LegacyNotesArchive]) before
 * anything without it is written.
 */
object LegacyUserMigration {
    private const val JOURNAL = "journalEntries"
    private const val ANNOTATIONS = "gameAnnotations"
    private const val BOOKMARKS = "bookmarks"
    private const val RETURN_NOTE = "returnNote"
    private const val MAPPING_NOTES = "mappingNotes"

    /** Whether [user] still has any field this migration rewrites. */
    fun needed(user: JsonObject): Boolean = JOURNAL in user ||
        (user[ANNOTATIONS] as? JsonObject)?.values?.any { it is JsonObject && (RETURN_NOTE in it || MAPPING_NOTES in it) } == true

    fun migrate(user: JsonObject): MigratedUser {
        if (!needed(user)) return MigratedUser(user, null)
        val journal = user[JOURNAL]?.takeUnless { it is JsonNull || (it is JsonArray && it.isEmpty()) }
        val games = LinkedHashMap<String, LegacyGameNotes>()
        val annotations = LinkedHashMap<String, JsonElement>()
        val bookmarks = LinkedHashMap<String, JsonElement>((user[BOOKMARKS] as? JsonObject).orEmpty())
        val sourceAnnotations = user[ANNOTATIONS] as? JsonObject
        sourceAnnotations?.forEach { (id, value) ->
            if (value !is JsonObject) {
                annotations[id] = value
                return@forEach
            }
            val returnNote = value.text(RETURN_NOTE)
            val mapping = value.text(MAPPING_NOTES)
            if (returnNote.isNotBlank() || mapping.isNotBlank()) games[id] = LegacyGameNotes(returnNote, mapping)
            val existing = (bookmarks[id] as? JsonObject)?.text("text").orEmpty()
            if (returnNote.isNotBlank() && existing.isBlank()) {
                bookmarks[id] = buildJsonObject { put("text", JsonPrimitive(returnNote.trim())) }
            }
            val rest = JsonObject(value - RETURN_NOTE - MAPPING_NOTES)
            val controller = (rest["controller"] as? JsonPrimitive)?.contentOrNull
            // nothing but an unknown controller left: the entry would read as empty, which Sortie never stores
            if (rest.keys.any { it != "controller" } || (controller != null && controller != "unknown")) annotations[id] = rest
        }
        val next = LinkedHashMap<String, JsonElement>(user)
        next.remove(JOURNAL)
        if (sourceAnnotations != null) next[ANNOTATIONS] = JsonObject(annotations)
        if (bookmarks.isNotEmpty()) next[BOOKMARKS] = JsonObject(bookmarks)
        val legacy = LegacyNotes(journal, games).takeIf { it.journal != null || it.games.isNotEmpty() }
        return MigratedUser(JsonObject(next), legacy)
    }

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
}

/**
 * Readable, durable copies of [LegacyNotes] in `<card>/sortie/archive/`: `personal-notes-<hash>.md` for people and
 * `personal-notes-<hash>.json` holding the exact removed fields. The hash is of the content, so archiving the same writing again
 * (a migration interrupted before user.json was rewritten, the same backup restored twice) finds the existing copy instead of
 * piling up duplicates. Both files are fsynced before [save] returns; nothing is ever deleted here, and journal screenshot
 * files are left where they are.
 */
class LegacyNotesArchive(val dir: File, private val zone: ZoneId = ZoneId.systemDefault()) {
    private val json = Json { prettyPrint = true }

    /**
     * Archives [legacy] (found in [source], e.g. "user.json" or "backup sortie-backup-thor.json") and returns the readable
     * file. Throws [IOException] when it cannot be written and verified; the caller must then keep the writing where it is.
     */
    fun save(legacy: LegacyNotes, source: String, now: Long): File {
        val payload = payload(legacy)
        val id = sha256(json.encodeToString(JsonObject.serializer(), JsonObject(payload))).take(16)
        val data = File(dir, "personal-notes-$id.json")
        val readable = File(dir, "personal-notes-$id.md")
        if (holds(data, payload) && readable.isFile && readable.length() > 0) return readable
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create ${dir.path}")
        val record = JsonObject(
            mapOf(
                "format" to JsonPrimitive(FORMAT),
                "version" to JsonPrimitive(1),
                "source" to JsonPrimitive(source),
                "archivedAt" to JsonPrimitive(now)
            ) + payload
        )
        writeDurably(data, json.encodeToString(JsonObject.serializer(), record))
        writeDurably(readable, render(legacy, source, now))
        if (!holds(data, payload)) throw IOException("${data.path} did not read back as written")
        return readable
    }

    private fun payload(legacy: LegacyNotes): Map<String, JsonElement> = mapOf(
        "gameNotes" to JsonObject(
            legacy.games.toSortedMap().mapValues { (_, n) ->
                JsonObject(mapOf("returnNote" to JsonPrimitive(n.returnNote), "mappingNotes" to JsonPrimitive(n.mappingNotes)))
            }
        ),
        "journalEntries" to (legacy.journal ?: JsonArray(emptyList()))
    )

    private fun holds(file: File, payload: Map<String, JsonElement>): Boolean = runCatching {
        val root = Json.parseToJsonElement(file.readText()) as JsonObject
        payload.all { (k, v) -> root[k] == v }
    }.getOrDefault(false)

    private fun writeDurably(target: File, text: String) {
        val tmp = File(target.path + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray())
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) throw IOException("could not create ${target.path}")
    }

    /** Markdown a person can read without Sortie: every text in full, journal entries with their dates and attachments. */
    internal fun render(legacy: LegacyNotes, source: String, now: Long): String = buildString {
        appendLine("# Sortie personal notes archive")
        appendLine()
        appendLine("Saved ${time(now)} from $source, when Sortie replaced its journal and general game notes with one bookmark per game.")
        appendLine("Nothing here was deleted. \"Where was I?\" notes also became each game's bookmark. Journal screenshots stay as files")
        appendLine("under `sortie/` at the paths listed. The exact original fields are in the `.json` file next to this one.")
        val returns = legacy.games.toSortedMap().filterValues { it.returnNote.isNotBlank() }
        if (returns.isNotEmpty()) {
            appendLine()
            appendLine("## \"Where was I?\" notes")
            returns.forEach { (id, n) -> section(id, n.returnNote) }
        }
        val mappings = legacy.games.toSortedMap().filterValues { it.mappingNotes.isNotBlank() }
        if (mappings.isNotEmpty()) {
            appendLine()
            appendLine("## Mapping notes")
            mappings.forEach { (id, n) -> section(id, n.mappingNotes) }
        }
        val journal = legacy.journal
        if (journal != null) {
            appendLine()
            appendLine("## Journal entries")
            val entries = (journal as? JsonArray)?.toList() ?: listOf(journal)
            entries.forEachIndexed { i, e -> journalEntry(i, e) }
        }
    }

    private fun StringBuilder.section(heading: String, text: String) {
        appendLine()
        appendLine("### $heading")
        appendLine()
        appendLine(text.trimEnd())
    }

    private fun StringBuilder.journalEntry(index: Int, e: JsonElement) {
        appendLine()
        if (e !is JsonObject) {
            appendLine("### Entry ${index + 1}")
            appendLine()
            appendLine("```")
            appendLine(e.toString())
            appendLine("```")
            return
        }
        fun str(k: String) = (e[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        fun millis(k: String) = (e[k] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
        appendLine("### ${str("date") ?: "Undated"}${str("gameId")?.let { " — $it" }.orEmpty()}")
        appendLine()
        millis("sessionStartedAt")?.let { appendLine("- About the session started ${time(it)}") }
        millis("createdAt")?.let { appendLine("- Written ${time(it)}") }
        millis("updatedAt")?.takeIf { it != millis("createdAt") }?.let { appendLine("- Last edited ${time(it)}") }
        str("id")?.let { appendLine("- Entry id `$it`") }
        val known = setOf("id", "date", "text", "gameId", "sessionStartedAt", "attachments", "createdAt", "updatedAt")
        e.filterKeys { it !in known }.forEach { (k, v) -> appendLine("- $k: $v") }
        str("text")?.let {
            appendLine()
            appendLine(it.trimEnd())
        }
        val attachments = (e["attachments"] as? JsonArray).orEmpty()
        if (attachments.isNotEmpty()) {
            appendLine()
            appendLine("Screenshots:")
            attachments.forEach { a ->
                val o = a as? JsonObject
                if (o == null) {
                    appendLine("- $a")
                } else {
                    val path = (o["path"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    val details = listOfNotNull(
                        (o["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
                        dims(o),
                        (o["bytes"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }?.let { "$it bytes" }
                    )
                    appendLine("- `sortie/$path`" + if (details.isEmpty()) "" else " (${details.joinToString(", ")})")
                }
            }
        }
    }

    private fun dims(o: JsonObject): String? {
        val w = (o["width"] as? JsonPrimitive)?.longOrNull ?: return null
        val h = (o["height"] as? JsonPrimitive)?.longOrNull ?: return null
        return if (w > 0 && h > 0) "$w×$h" else null
    }

    private fun time(millis: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochMilli(millis).atZone(zone))

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val FORMAT = "sortie-personal-notes-archive"
        const val DIR = "archive"
    }
}
