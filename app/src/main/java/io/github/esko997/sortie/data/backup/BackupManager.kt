package io.github.esko997.sortie.data.backup

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import androidx.room.withTransaction
import io.github.esko997.sortie.BuildConfig
import io.github.esko997.sortie.data.apps.LibraryItem
import io.github.esko997.sortie.data.db.SessionEntity
import io.github.esko997.sortie.data.db.SortieDb
import io.github.esko997.sortie.data.db.deleteSessions
import io.github.esko997.sortie.data.manifest.UserData
import io.github.esko997.sortie.data.user.PlayLog
import io.github.esko997.sortie.data.user.UserStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface BackupState {
    data object Idle : BackupState

    data class Working(val what: String) : BackupState

    /** A parsed, valid backup awaiting the user's explicit confirmation; nothing has been written yet. */
    data class Preview(
        val source: String,
        val backup: BackupFile,
        val openIgnored: Int,
        val merge: SessionMerge,
        val current: UserData,
        /** App and shortcut ids the backup's favourites/layout/dock refer to that this device does not have (Android owns those). */
        val missingLaunchables: Int
    ) : BackupState

    data class Result(val ok: Boolean, val title: String, val lines: List<String>) : BackupState
}

/**
 * Export to and restore from a file the user picks through the Storage Access Framework (format: [BackupFile]).
 *
 * Restore replaces user.json with the backup's preferences and merges its sessions into the database (see [planSessionMerge]);
 * playlog.jsonl is never touched (it is this device's append-only log for the desktop, not the stats source). Before anything
 * is replaced, the current state is written to `<card>/sortie/backups/pre-restore-<time>.json` in the same format. Restoring
 * that file brings the previous preferences back; it cannot remove imported sessions (restore only ever merges history).
 * Work runs on this object's own scope so leaving the screen cannot cut it short.
 */
class BackupManager(
    private val context: Context,
    private val db: SortieDb,
    private val user: UserStore,
    private val playLog: PlayLog,
    sortieDir: File,
    private val deviceName: () -> String,
    /** Ids (`app:` / `shortcut:`) Android currently offers; used only to warn about entries a restore cannot bring back. */
    private val availableLaunchables: () -> Set<String>
) {
    private val dao = db.games()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<BackupState>(BackupState.Idle)
    val state: StateFlow<BackupState> = _state

    /** Where the pre-restore (and other pre-change) safety copies go; user-visible on the card, outside the files the desktop deploys. */
    val safetyDir = File(sortieDir, "backups")

    /**
     * Writes the current preferences and all recorded sessions to `<safetyDir>/<prefix>-<time>.json` in the backup format and
     * returns the file, so any bulk change (a restore, an import) can be undone by restoring it. Throws when it cannot be
     * written; callers must then not make their change.
     */
    suspend fun writeSafetyCopy(prefix: String): File = writeSafetyCopy(prefix, user.data.value, dao.allSessions())

    /** Writes exactly [prefs] and [sessions], for callers that must know what the copy holds (restore compares it with user.json). */
    private suspend fun writeSafetyCopy(prefix: String, prefs: UserData, sessions: List<SessionEntity>): File = withContext(Dispatchers.IO) {
        File(safetyDir, "$prefix-${stamp()}.json").also { writeSafety(it, prefs, sessions) }
    }

    fun suggestedName(): String {
        val device = deviceName().replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "device" }
        return "sortie-backup-$device-${stamp()}.json"
    }

    /** [uri] null = the picker was cancelled. */
    fun export(uri: Uri?) = launchJob("Exporting…") {
        if (uri == null) BackupState.Result(true, "Export cancelled", listOf("No file was chosen, so nothing was written.")) else exportTo(uri)
    }

    fun preview(uri: Uri?) = launchJob("Reading backup…") {
        if (uri == null) BackupState.Result(true, "Restore cancelled", listOf("No file was chosen; nothing was changed.")) else previewFrom(uri)
    }

    fun confirmRestore() {
        val preview = _state.value as? BackupState.Preview ?: return
        launchJob("Restoring…") { playLog.paused { open -> restoreLocked(preview, open) } }
    }

    fun dismiss() {
        if (_state.value !is BackupState.Working) _state.value = BackupState.Idle
    }

    /** No app answered the Storage Access Framework request (stripped-down firmware). */
    fun pickerUnavailable(e: Throwable) {
        Log.w(TAG, "file picker", e)
        if (_state.value !is BackupState.Working) {
            _state.value = BackupState.Result(false, "No file picker", listOf("Android has no app to choose files with (${describe(e)}). Nothing was changed."))
        }
    }

    private fun launchJob(what: String, block: suspend () -> BackupState) {
        synchronized(this) {
            if (_state.value is BackupState.Working) return
            _state.value = BackupState.Working(what)
        }
        scope.launch {
            _state.value = try {
                block()
            } catch (e: Exception) {
                Log.w(TAG, what, e)
                BackupState.Result(false, "Something went wrong", listOf(describe(e)))
            }
        }
    }

    private suspend fun exportTo(uri: Uri): BackupState {
        val name = displayName(uri)
        // One consistent snapshot: no session can open or close while the preferences and the table are read.
        val backup = playLog.paused { buildBackup(user.data.value, dao.allSessions(), System.currentTimeMillis(), deviceName(), BuildConfig.VERSION_NAME) }
        val bytes = encodeBackup(backup).toByteArray()
        try {
            writeUri(uri, bytes)
        } catch (e: Exception) {
            Log.w(TAG, "export write", e)
            return BackupState.Result(
                false,
                "Export failed",
                listOf(
                    "Couldn't write $name: ${describe(e)}",
                    "The file may be incomplete — delete it and export again, e.g. to internal storage or the SD card."
                )
            )
        }
        val readBack = runCatching { readUri(uri) }.getOrNull()
        if (readBack == null || !readBack.contentEquals(bytes)) {
            return BackupState.Result(
                false,
                "Export not verified",
                listOf("$name was written but reading it back did not return the same backup.", "Don't rely on this file; export again to another location.")
            )
        }
        val lines = buildList {
            add(
                "Saved $name: all preferences and personal records, including favourites, Want to play, Finished/Shelved status, game notes, " +
                    "reference-card text, magazine clippings and journal entries. Completed play sessions saved: ${backup.sessions.size}."
            )
            backup.openSessionExcluded?.let {
                add("${it.gameId} is being played right now; its session is still open, so it is not in this backup. Export again after it ends.")
            }
            add(SCOPE)
        }
        return BackupState.Result(true, "Backup saved", lines)
    }

    private suspend fun previewFrom(uri: Uri): BackupState {
        val name = displayName(uri)
        val bytes = try {
            readUri(uri)
        } catch (e: Exception) {
            Log.w(TAG, "restore read", e)
            return BackupState.Result(false, "Can't read $name", listOf(describe(e), "Check the file still exists and is readable. Nothing was changed."))
        }
        return when (val parsed = parseBackup(bytes.toString(Charsets.UTF_8))) {
            is ParsedBackup.Invalid -> BackupState.Result(false, "Can't restore $name", listOf(parsed.reason, "Nothing was changed."))
            is ParsedBackup.Valid -> {
                val available = availableLaunchables()
                val missing = parsed.backup.user.launchableIds().count { it !in available }
                val merge = planSessionMerge(parsed.backup.sessions, dao.allSessions())
                BackupState.Preview(name, parsed.backup, parsed.openIgnored, merge, user.data.value, missing)
            }
        }
    }

    /** Runs inside [PlayLog.paused]: no session can start or end until this returns. */
    private suspend fun restoreLocked(preview: BackupState.Preview, open: SessionEntity?): BackupState {
        if (open != null) {
            return BackupState.Result(
                false,
                "Restore not started",
                listOf(
                    "${open.gameId} is still being played (its session is open).",
                    "Quit the game and come back to Sortie so the session is recorded, then restore again. Nothing was changed."
                )
            )
        }
        val existing = dao.allSessions()
        val merge = planSessionMerge(preview.backup.sessions, existing)
        val next = preview.backup.user
        var before = user.data.value
        val safety = try {
            writeSafetyCopy("pre-restore", before, existing)
        } catch (e: Exception) {
            Log.w(TAG, "safety copy", e)
            return BackupState.Result(
                false,
                "Restore not started",
                listOf("Couldn't save a safety copy of the current state to ${safetyDir.path}: ${describe(e)}", "Nothing was changed.")
            )
        }
        val inserted = try {
            db.withTransaction { dao.insertSessions(merge.toInsert) }
        } catch (e: Exception) {
            Log.w(TAG, "restore sessions", e)
            return BackupState.Result(
                false,
                "Restore failed",
                listOf("Couldn't add the play history: ${describe(e)}", "The database write was rolled back; preferences and history are unchanged.")
            )
        }
        val failure: Exception? = try {
            var replaced = user.replace(before, next)
            var tries = 0
            while (!replaced && tries++ < 3) {
                // an edit landed after the safety copy was taken: copy again so it holds exactly the preferences being replaced
                before = user.data.value
                writeSafety(safety, before, existing)
                replaced = user.replace(before, next)
            }
            if (replaced) null else IOException("preferences kept changing during the restore")
        } catch (e: Exception) {
            e
        }
        if (failure != null) {
            Log.w(TAG, "restore preferences", failure)
            val rolledBack = runCatching { db.withTransaction { dao.deleteSessions(inserted) } }.onFailure { Log.w(TAG, "compensate", it) }.isSuccess
            val history = if (rolledBack) {
                "The ${inserted.size} sessions just added were removed again, so history is unchanged too."
            } else {
                "${inserted.size} imported sessions could not be removed again and remain in the play history."
            }
            return BackupState.Result(false, "Restore failed", listOf("Couldn't write user.json: ${describe(failure)}", "Preferences are unchanged.", history))
        }
        val lines = buildList {
            add(
                "Replaced all preferences and personal records from ${preview.source}, including layout, dock, favourites, Want to play, " +
                    "Finished/Shelved status, game notes, reference-card text, magazine clippings and journal entries."
            )
            add(
                "Play history merged: ${inserted.size} sessions added (${merge.addedMinutes} min); ${merge.alreadyPresent} already on this device were kept. " +
                    "Sessions recorded here since the backup are untouched. playlog.jsonl was not modified."
            )
            add(
                "Previous preferences saved to ${safety.path}; restoring that file brings them back. It cannot remove the sessions " +
                    "this restore added (history is only ever merged)."
            )
            if (preview.missingLaunchables > 0) {
                add(
                    "${preview.missingLaunchables} apps/shortcuts the backup refers to aren't on this device. Their ids are kept; they reappear once " +
                        "the app is installed or the shortcut is pinned again from its app (Android owns shortcut pins)."
                )
            }
            add(NOT_RESTORED)
        }
        return BackupState.Result(true, "Restore complete", lines)
    }

    private fun writeSafety(target: File, prefs: UserData, sessions: List<SessionEntity>) {
        val backup = buildBackup(prefs, sessions, System.currentTimeMillis(), deviceName(), BuildConfig.VERSION_NAME)
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(encodeBackup(backup).toByteArray())
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) throw IOException("could not create ${target.path}")
    }

    /** Truncating write, flushed to the provider; providers that cannot fsync (cloud pipes) are verified by the read-back instead. */
    private fun writeUri(uri: Uri, bytes: ByteArray) {
        val resolver = context.contentResolver
        val pfd = runCatching { resolver.openFileDescriptor(uri, "wt") }.getOrNull()
            ?: resolver.openFileDescriptor(uri, "w")
            ?: throw IOException("the storage app returned no file")
        ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { out ->
            out.write(bytes)
            out.flush()
            runCatching { out.fd.sync() }.onFailure { Log.i(TAG, "fsync unsupported by provider: $it") }
        }
    }

    private fun readUri(uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("the storage app returned no file")
        return input.use { it.readCapped(MAX_BYTES) }
    }

    private fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "the file"

    companion object {
        private const val TAG = "SortieBackup"
        private const val MAX_BYTES = 64 * 1024 * 1024

        const val SCOPE = "A backup holds Sortie's own state: every preference in user.json (layout, favourites, hidden entries, launcher choices, dock, " +
            "Want to play, game notes, controller entries, Finished/Shelved verdicts, magazine clippings, reference-card text and journal entries) " +
            "and the play history from the database. It does not hold reference images or journal screenshots: copy their folders alongside " +
            "the backup. ROMs, covers, emulator saves, the game index (rebuilt from the card), desktop manifests and playlog.jsonl are also excluded."

        const val NOT_RESTORED = "Not restored, because Android owns them: launcher shortcut pins, Shizuku and all-files/draw-over-apps permissions, " +
            "and the default home app. Re-grant those in Android settings if they were lost."

        private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

        fun describe(e: Throwable): String = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

        fun formatTime(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
    }
}

/** App and shortcut ids referenced anywhere a restore puts them back. */
fun UserData.launchableIds(): Set<String> = (favourites + layout + dock + shortcuts)
    .filterTo(HashSet()) { it.startsWith(LibraryItem.APP_PREFIX) || it.startsWith(LibraryItem.SHORTCUT_PREFIX) }

private fun InputStream.readCapped(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    while (true) {
        val n = read(buf)
        if (n < 0) break
        if (out.size() + n > limit) throw IOException("the file is larger than ${limit / (1024 * 1024)} MB, too big to be a Sortie backup")
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
