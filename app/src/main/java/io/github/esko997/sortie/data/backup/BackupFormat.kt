package io.github.esko997.sortie.data.backup

import io.github.esko997.sortie.data.db.SessionEntity
import io.github.esko997.sortie.data.manifest.UserData
import io.github.esko997.sortie.stats.statsInterval
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * A Sortie backup (docs/formats/user.md): every device-side preference (user.json, including layout, dock and per-game
 * annotations) plus the closed play sessions from the database. Not included: ROMs, art, emulator saves, the game index
 * (rebuilt from the card), manifests, playlog.jsonl, and anything Android owns (shortcut pins, permissions, default home).
 */
@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val version: Int = VERSION,
    /** Epoch millis of the snapshot. */
    val createdAt: Long,
    val device: String = "",
    val appVersion: String = "",
    /** Required (no default): a file without it must be rejected, never read as "reset every preference". */
    val user: UserData,
    /** Required (no default), may be empty. */
    val sessions: List<BackupSession>,
    /** The session that was still being played when the snapshot was taken; deliberately not in [sessions] and never imported. */
    val openSessionExcluded: BackupSession? = null
) {
    companion object {
        const val FORMAT = "sortie-backup"
        const val VERSION = 1
    }
}

/** A play session. Its identity is ([gameId], [startedAt]) — database row ids differ between installs and are not exported. */
@Serializable
data class BackupSession(
    val gameId: String,
    val profileId: String? = null,
    val startedAt: Long,
    val endedAt: Long? = null,
    val abandoned: Boolean = false
)

fun SessionEntity.toBackup() = BackupSession(gameId, profileId, startedAt, endedAt, abandoned)

val backupJson = Json {
    ignoreUnknownKeys = true // later minor additions within one version stay readable
    encodeDefaults = true
    prettyPrint = true
}

/** The snapshot as written: closed sessions only; the open one (if any) is recorded as excluded. */
fun buildBackup(user: UserData, sessions: List<SessionEntity>, createdAt: Long, device: String, appVersion: String): BackupFile {
    val (open, closed) = sessions.partition { it.endedAt == null }
    return BackupFile(
        createdAt = createdAt,
        device = device,
        appVersion = appVersion,
        user = user,
        sessions = closed.map { it.toBackup() },
        openSessionExcluded = open.maxByOrNull { it.startedAt }?.toBackup()
    )
}

fun encodeBackup(backup: BackupFile): String = backupJson.encodeToString(BackupFile.serializer(), backup)

sealed interface ParsedBackup {
    /** [openIgnored]: sessions without an end in the file; they are dropped, never imported as "currently playing". */
    data class Valid(val backup: BackupFile, val openIgnored: Int) : ParsedBackup

    data class Invalid(val reason: String) : ParsedBackup
}

/**
 * Parses and checks a backup without touching any state. Anything wrong with structure, version or a session rejects the whole
 * file: a restore either applies a coherent backup or nothing. Preferences are clamped to the ranges the settings allow.
 */
fun parseBackup(text: String): ParsedBackup {
    val root = try {
        backupJson.parseToJsonElement(text)
    } catch (e: SerializationException) {
        return ParsedBackup.Invalid("The file is not valid JSON — it may be cut short (an interrupted copy) or not a Sortie backup.")
    }
    if (root !is JsonObject || (root["format"] as? JsonPrimitive)?.content != BackupFile.FORMAT) {
        return ParsedBackup.Invalid("This is not a Sortie backup (no \"format\": \"${BackupFile.FORMAT}\"). Pick a file made by Export backup.")
    }
    val version = (root["version"] as? JsonPrimitive)?.intOrNull
        ?: return ParsedBackup.Invalid("The backup has no format version; it is damaged or hand-edited.")
    if (version > BackupFile.VERSION) {
        return ParsedBackup.Invalid(
            "This backup was made by a newer Sortie (format $version; this build reads up to ${BackupFile.VERSION}). Update Sortie first."
        )
    }
    if (version < 1) return ParsedBackup.Invalid("Unsupported backup format version $version.")
    // a restore replaces every preference, so a file missing its sections must not pass as an all-defaults backup
    if (root["user"] !is JsonObject || root["sessions"] !is JsonArray) {
        return ParsedBackup.Invalid("The backup is incomplete (no preferences or no play history section); it is damaged or hand-edited.")
    }
    val backup = try {
        backupJson.decodeFromJsonElement(BackupFile.serializer(), root)
    } catch (e: SerializationException) {
        return ParsedBackup.Invalid("The backup is damaged: ${e.message?.lineSequence()?.firstOrNull() ?: "unreadable field"}.")
    } catch (e: IllegalArgumentException) {
        return ParsedBackup.Invalid("The backup is damaged: ${e.message ?: "invalid value"}.")
    }
    backup.sessions.forEachIndexed { i, s ->
        val problem = when {
            s.gameId.isBlank() -> "has no game id"
            s.startedAt <= 0 -> "has no start time"
            s.endedAt != null && s.endedAt < s.startedAt -> "ends before it starts"
            else -> null
        }
        if (problem != null) return ParsedBackup.Invalid("Session ${i + 1} in the backup $problem; the file is damaged. Nothing was changed.")
    }
    val openIgnored = backup.sessions.count { it.endedAt == null }
    return ParsedBackup.Valid(backup.copy(user = backup.user.clamped(), sessions = backup.sessions.filter { it.endedAt != null }), openIgnored)
}

/** The same bounds the UserStore setters enforce, so a hand-edited backup cannot store a layout the UI cannot show. */
private fun UserData.clamped() = copy(
    gridColumns = gridColumns.coerceIn(2, 10),
    gridRows = gridRows.coerceIn(2, 6),
    appColumns = appColumns.coerceIn(4, 14),
    deviceStatsInterval = statsInterval(deviceStatsInterval),
    layout = layout.distinct()
)

/** What a restore would add to the sessions table. [addedMinutes] counts only the completed, non-abandoned sessions it adds. */
data class SessionMerge(val toInsert: List<SessionEntity>, val alreadyPresent: Int, val addedMinutes: Long)

/**
 * Merges by natural identity (game id + start time): sessions already on the device — including every one newer than the
 * backup — are kept as they are (the database is the authority), duplicates inside the file count once, and only closed
 * sessions are added. Running the same restore twice therefore adds nothing the second time.
 */
fun planSessionMerge(incoming: List<BackupSession>, existing: List<SessionEntity>): SessionMerge {
    val seen = existing.mapTo(HashSet()) { it.gameId to it.startedAt }
    val toInsert = ArrayList<SessionEntity>()
    var present = 0
    for (s in incoming) {
        val ended = s.endedAt ?: continue
        if (!seen.add(s.gameId to s.startedAt)) {
            present++
            continue
        }
        toInsert += SessionEntity(gameId = s.gameId, profileId = s.profileId, startedAt = s.startedAt, endedAt = ended, abandoned = s.abandoned)
    }
    val minutes = toInsert.filter { !it.abandoned }.sumOf { (it.endedAt ?: it.startedAt) - it.startedAt } / 60000
    return SessionMerge(toInsert, present, minutes)
}
