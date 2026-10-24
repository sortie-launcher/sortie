package io.github.esko997.sortie.data.setup

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * File operations of a commit, one method per durable step, so tests can fail or "crash" at any exact step. A crash is
 * simulated by throwing an [Error]: commit code only cleans up after [Exception]s, so an Error leaves the files exactly as a
 * power loss at that point would.
 */
internal open class CommitIo {
    /** Writes [bytes] to [file] and fsyncs it. */
    open fun write(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
    }

    /** Atomically replaces [to] with [from] (rename(2)). */
    open fun rename(from: File, to: File) {
        if (!from.renameTo(to)) throw IOException("could not replace ${to.name}")
    }

    open fun delete(file: File) {
        if (file.exists() && !file.delete()) throw IOException("could not delete ${file.name}")
    }

    /** Makes completed renames durable; best effort, since not every filesystem lets a directory be opened for fsync. */
    open fun syncDirectory(dir: File) {
        runCatching { FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) } }
    }
}

/**
 * Crash-safe replacement of several manifest files in `<card>/sortie` at once. Atomic per-file renames alone would let a
 * crash (or a concurrent [io.github.esko997.sortie.data.manifest.ManifestStore] load) observe a new `systems.json` next to an
 * old `launchers.json`, so a commit is a small redo journal:
 *
 * 1. every new file is written and fsynced as `<name>.setup-new` (readers ignore it);
 * 2. the journal [JOURNAL] listing each name with the SHA-256 of its new content and of the target it replaces ("absent"
 *    when there is none) is written to a temp file, fsynced and renamed into place. That rename is the decision point:
 *    before it the commit never happened, after it it must finish;
 * 3. each staged file is renamed over its target, then the journal is deleted.
 *
 * [recover] runs under the same lock before every read or commit. Without a journal it discards staged leftovers (the
 * attempt never decided, targets untouched). With one it first checks every entry and only then renames anything:
 * - staged copy present, its hash matching and the target still as recorded (or already the new content): rename pending;
 * - staged copy gone and the target no longer the recorded old content: already applied (anything there now is at least as
 *   new and is kept);
 * - anything else (staged copy damaged or unreadable, target edited by someone else while the change was pending, staged
 *   copy lost while the target is still old): the journal is inconsistent with the card. Nothing is renamed or deleted,
 *   [Recovery.consistent] is false and readers must not publish these files as a library (some may be old, some new).
 * When the renames themselves fail (read-only card, no grant yet) [Recovery.pending] lets readers see the decided set.
 *
 * The lock is per directory and process-wide: [withLock] serialises [LibrarySetupStore] commits with ManifestStore loads, so a
 * snapshot is never published from a half-renamed set. Another process (a desktop editing over USB) cannot take it; that case
 * is detected with fingerprints by the store and with the recorded old hashes here.
 */
internal object ManifestCommit {
    const val JOURNAL = ".sortie-setup-commit"
    const val JOURNAL_TMP = "$JOURNAL.tmp"
    const val STAGED_SUFFIX = ".setup-new"
    private const val TAG = "ManifestCommit"
    private const val VERSION = 2
    private const val ABSENT = "absent"
    private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.json")

    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    fun <T> withLock(dir: File, block: () -> T): T = locks.computeIfAbsent(dir.absoluteFile.normalize().path) { ReentrantLock() }.withLock(block)

    /**
     * [consistent] false: the files cannot be read as one library (an inconsistent interrupted change); readers keep their last
     * complete state and writers refuse. [pending] maps manifest names to staged copies readers must use because roll-forward
     * could not finish; [error] says why either happened.
     */
    class Recovery(val pending: Map<String, File>, val error: String?, val consistent: Boolean = true) {
        val clean: Boolean get() = consistent && pending.isEmpty() && error == null
    }

    /** Thrown when the commit decided (journal published) but could not finish; the change completes on the next [recover]. */
    class PartiallyApplied(message: String, cause: Throwable) : IOException(message, cause)

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class Entry(val name: String, val sha256: String, val before: String)

    private fun journalBytes(entries: List<Entry>): ByteArray = JsonObject(
        mapOf(
            "version" to JsonPrimitive(VERSION),
            "files" to JsonArray(
                entries.map {
                    JsonObject(mapOf("name" to JsonPrimitive(it.name), "sha256" to JsonPrimitive(it.sha256), "before" to JsonPrimitive(it.before)))
                }
            )
        )
    ).toString().toByteArray(Charsets.UTF_8)

    private fun parseJournal(bytes: ByteArray): List<Entry> {
        val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        val version = root["version"]?.jsonPrimitive?.int
        if (version != VERSION) throw IOException("unsupported journal version $version")
        return root.getValue("files").jsonArray.map {
            val o = it.jsonObject
            val name = o.getValue("name").jsonPrimitive.content
            if (!NAME.matches(name)) throw IOException("unexpected file name in journal: $name")
            Entry(name, o.getValue("sha256").jsonPrimitive.content, o.getValue("before").jsonPrimitive.content)
        }
    }

    /** SHA-256 of [file], or [ABSENT]; throws when it exists but cannot be read. */
    private fun stateOf(file: File): String = if (file.exists()) sha256(file.readBytes()) else ABSENT

    private fun deleteBestEffort(io: CommitIo, file: File) {
        try {
            io.delete(file)
        } catch (e: IOException) {
            Log.w(TAG, "remove ${file.name}", e)
        }
    }

    private fun inconsistent(dir: File, problems: List<String>) = Recovery(
        emptyMap(),
        "An interrupted library change in ${dir.path} does not match the files there now (${problems.joinToString("; ")}). " +
            "Nothing was changed and these settings are not used. Restore a backup of the folder, or delete $JOURNAL and the " +
            "*$STAGED_SUFFIX files there to keep the files exactly as they are now, then reopen Sortie.",
        consistent = false
    )

    /** Finishes or discards an interrupted commit in [dir]. Caller holds [withLock]. Never throws. */
    fun recover(dir: File, io: CommitIo = CommitIo()): Recovery {
        if (!dir.isDirectory) return Recovery(emptyMap(), null)
        val journal = File(dir, JOURNAL)
        if (!journal.isFile) {
            // an attempt that never reached its decision point: its staged copies are garbage, the targets were never touched
            dir.listFiles()?.filter { it.name.endsWith(STAGED_SUFFIX) || it.name == JOURNAL_TMP }?.forEach { f ->
                deleteBestEffort(io, f)
            }
            return Recovery(emptyMap(), null)
        }
        val entries = try {
            parseJournal(journal.readBytes())
        } catch (e: Exception) {
            Log.w(TAG, "journal", e)
            return inconsistent(dir, listOf("its record could not be read: ${e.message}"))
        }
        // preflight: decide every entry before touching any file
        val toRename = ArrayList<Entry>()
        val problems = mutableListOf<String>()
        for (e in entries) {
            val staged = File(dir, e.name + STAGED_SUFFIX)
            try {
                val target = stateOf(File(dir, e.name))
                if (staged.exists()) {
                    when {
                        sha256(staged.readBytes()) != e.sha256 -> problems += "${e.name}: the saved new copy is damaged"
                        target != e.before && target != e.sha256 -> problems += "${e.name} was changed by something else meanwhile"
                        else -> toRename += e
                    }
                } else if (target == e.before && e.before != e.sha256) {
                    problems += "${e.name}: the saved new copy is missing"
                }
            } catch (ex: IOException) {
                problems += "${e.name}: ${ex.message}"
            }
        }
        if (problems.isNotEmpty()) return inconsistent(dir, problems)
        val pending = LinkedHashMap<String, File>()
        var failure: String? = null
        for (e in toRename) {
            val staged = File(dir, e.name + STAGED_SUFFIX)
            if (failure == null) {
                try {
                    io.rename(staged, File(dir, e.name))
                    continue
                } catch (ex: IOException) {
                    failure = "${e.name}: ${ex.message}"
                }
            }
            pending[e.name] = staged
        }
        if (failure != null) {
            return Recovery(
                pending,
                "An interrupted library change in ${dir.path} could not be finished ($failure). The saved change is used meanwhile; " +
                    "check that the storage is writable and reopen Sortie."
            )
        }
        io.syncDirectory(dir)
        // a journal that cannot be deleted is harmless: every entry is applied, so the next recovery is a no-op that retries this
        deleteBestEffort(io, journal)
        return Recovery(emptyMap(), null)
    }

    /**
     * Replaces every file of [files] (manifest name → complete new content) in [dir] as one unit. Caller holds [withLock] and
     * has made sure [dir] exists. Throws [IOException] with nothing changed when the commit could not decide, or
     * [PartiallyApplied] when it decided but could not finish (the next [recover] finishes it).
     */
    fun commit(dir: File, files: Map<String, ByteArray>, io: CommitIo = CommitIo()) {
        require(files.isNotEmpty()) { "nothing to commit" }
        files.keys.forEach { require(NAME.matches(it)) { "not a manifest name: $it" } }
        val before = recover(dir, io)
        if (!before.clean) throw IOException(before.error ?: "an earlier library change is still pending")
        val staged = files.keys.associateWith { File(dir, it + STAGED_SUFFIX) }
        val journalTmp = File(dir, JOURNAL_TMP)
        try {
            val entries = files.map { (name, bytes) -> Entry(name, sha256(bytes), stateOf(File(dir, name))) }
            for ((name, bytes) in files) io.write(staged.getValue(name), bytes)
            io.write(journalTmp, journalBytes(entries))
            io.rename(journalTmp, File(dir, JOURNAL))
        } catch (e: Exception) {
            (staged.values + journalTmp).forEach { f -> deleteBestEffort(io, f) }
            throw if (e is IOException) e else IOException(e.message ?: e.javaClass.simpleName, e)
        }
        try {
            io.syncDirectory(dir)
            for (name in files.keys) io.rename(staged.getValue(name), File(dir, name))
            io.syncDirectory(dir)
        } catch (e: Exception) {
            throw PartiallyApplied(
                "Library settings were saved but not fully applied yet (${e.message}). Sortie finishes the change the next time the library loads.",
                e
            )
        }
        deleteBestEffort(io, File(dir, JOURNAL))
    }
}
