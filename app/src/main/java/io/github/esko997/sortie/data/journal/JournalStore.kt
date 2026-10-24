package io.github.esko997.sortie.data.journal

import io.github.esko997.sortie.data.manifest.UserData
import io.github.esko997.sortie.data.user.UserStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Journal edits. Entries live in user.json ([UserData.journalEntries]) and every change goes through [UserStore.commit], so it is
 * fsynced before anything shows it and concurrent edits elsewhere are kept. Screenshot files are deleted only after the entry
 * that dropped them is safely written, and never while any saved entry still refers to them.
 */
class JournalStore(private val user: UserStore, sortieDir: File) {
    val media = JournalMedia(sortieDir)
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Saves [entry] (replacing the entry with its id, else adding it) and then deletes [discard] — screenshots removed from it or
     * imported and dropped during the edit. Returns null once user.json holds the entry, else why it is not on the card.
     */
    suspend fun save(entry: JournalEntry, discard: Collection<String>): String? {
        val saved = attempt {
            user.commit { u ->
                val i = u.journalEntries.indexOfFirst { it.id == entry.id }
                u.copy(journalEntries = if (i < 0) u.journalEntries + entry else u.journalEntries.toMutableList().also { it[i] = entry })
            }
        }
        return saved.fold({
            deleteUnreferenced(discard, it)
            null
        }, { reason(it) })
    }

    /** Removes [entry] and then its screenshots. Returns null once user.json no longer holds it, else why. */
    suspend fun delete(entry: JournalEntry): String? {
        val saved = attempt { user.commit { u -> u.copy(journalEntries = u.journalEntries.filterNot { it.id == entry.id }) } }
        return saved.fold({
            deleteUnreferenced(entry.attachments.map { it.path }, it)
            null
        }, { reason(it) })
    }

    /**
     * A cancelled edit: deletes the screenshots it imported that no saved entry refers to. Runs on the store's own scope, so it
     * finishes after the editor that asked for it has closed.
     */
    fun discardImports(imported: Collection<String>) {
        if (imported.isEmpty()) return
        cleanup.launch { deleteUnreferenced(imported, user.data.value) }
    }

    private suspend fun deleteUnreferenced(paths: Collection<String>, state: UserData) {
        if (paths.isEmpty()) return
        val kept = state.journalEntries.flatMapTo(HashSet()) { e -> e.attachments.map { it.path } }
        media.delete(paths.filterNot { it in kept })
    }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun reason(e: Throwable): String = e.message ?: e.javaClass.simpleName
}
