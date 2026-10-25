package io.github.esko997.sortie.data.cocoon

import android.system.Os
import android.util.Log
import androidx.room.withTransaction
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.data.apps.LibraryItem
import io.github.esko997.sortie.data.backup.BackupSession
import io.github.esko997.sortie.data.backup.planSessionMerge
import io.github.esko997.sortie.data.manifest.FolderDef
import io.github.esko997.sortie.data.manifest.SystemDef
import io.github.esko997.sortie.data.setup.LibraryConfiguration
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Applies a confirmed [CocoonImportPlan]. Writes only Sortie's own files: manifests through
 * [io.github.esko997.sortie.data.setup.LibrarySetupStore] (atomic commits), user.json once through
 * [io.github.esko997.sortie.data.user.UserStore.commit], sessions inside [io.github.esko997.sortie.data.user.PlayLog.paused] and artwork
 * copies into Sortie's media folders. Nothing under the Cocoon directory or the chosen backup is ever opened for writing, and
 * [CocoonWriteGuard] refuses the whole import before the first write when any destination resolves into either of them or
 * a file written in place is a link that could lead there.
 *
 * Every step is a union or an insert-if-absent, so running the same import again changes nothing: systems that exist are left
 * as they are, ids already in favourites/hidden stay once, user-chosen emulators win, sessions are deduplicated by
 * (game id, start time) and copied covers are skipped. Steps up to the folders are not cancellable once started; only the
 * artwork copy stops on request, keeping the files already copied.
 */
object CocoonImporter {
    private const val TAG = "SortieCocoon"

    /** The Working step shown while artwork is copied; the screen offers "Stop copying" only then. */
    const val ARTWORK_STEP = "Copying artwork…"

    private const val LIST_CAP = 20

    /**
     * Runs the plan in the documented order and returns the outcome. [onProgress] receives every step; [onArtworkJob] receives
     * the artwork copy's own job (cancelling it stops the copy only) and null once the copy has ended.
     */
    suspend fun apply(
        plan: CocoonImportPlan,
        options: ImportOptions,
        graph: SortieApp.Graph,
        onProgress: (CocoonImportState.Working) -> Unit,
        onArtworkJob: (Job?) -> Unit = {}
    ): CocoonImportState.Result {
        val done = mutableListOf<String>()

        // 1. Overlap guard, before anything is written.
        onProgress(CocoonImportState.Working("Checking where the import writes…"))
        val overlaps = withContext(Dispatchers.IO) { overlaps(plan, options, graph) }
        if (overlaps.isNotEmpty()) {
            return CocoonImportState.Result(
                false,
                "Import refused",
                listOf(
                    "These destinations are inside the Cocoon folder ${plan.cocoonDir.path}${plan.backup?.let { " or the backup ${it.dir.path}" } ?: ""}, " +
                        "are the files being copied, or are links Sortie would write through. The import never writes there, so nothing was changed:"
                ) + capped(overlaps) + "Move Sortie's settings or media folder elsewhere in Library setup, replace the linked files with plain " +
                    "ones, or choose another Cocoon folder."
            )
        }

        // 2. Safety copy of the current preferences and history.
        onProgress(CocoonImportState.Working("Saving a safety copy…"))
        val safety = try {
            withContext(NonCancellable) { graph.backup.writeSafetyCopy(CocoonWriteGuard.SAFETY_PREFIX) }
        } catch (e: Exception) {
            Log.w(TAG, "safety copy", e)
            return CocoonImportState.Result(
                false,
                "Import not started",
                listOf("Couldn't save a safety copy of the current preferences to ${graph.backup.safetyDir.path}: ${describe(e)}", "Nothing was changed.")
            )
        }
        val safetyLine = "Your previous preferences and play history were saved to ${safety.path}; Settings → Backup and restore → " +
            "Restore from backup… brings the preferences back."

        // Steps 3–8 change manifests, user.json and the database together: once begun they finish even if nobody watches.
        val outcome = withContext(NonCancellable) {
            try {
                applyLibrary(plan, options, graph, onProgress, done)
            } catch (e: Exception) {
                Log.w(TAG, "apply", e)
                return@withContext describe(e)
            }
            null
        }
        if (outcome != null) {
            return CocoonImportState.Result(false, "Import stopped", done + "Stopped: $outcome" + safetyLine)
        }

        // 9. Artwork: the only cancellable step.
        var copy = CopyOutcome()
        val ids = withContext(NonCancellable) { graph.db.games().allIds().toHashSet() }
        val artwork = if (options.artwork) plan.artwork.filter { it.gameId in ids } else emptyList()
        if (artwork.isNotEmpty()) {
            copy = copyArtwork(artwork, onProgress, onArtworkJob)
            done += buildString {
                append("Artwork: copied ${copy.copied} files (${mb(copy.bytes)})")
                if (copy.existing > 0) append(", ${copy.existing} already in place")
                if (copy.unavailable > 0) append(", ${copy.unavailable} no longer in Cocoon's folder")
                append('.')
                if (copy.stopped) append(" Copying was stopped; run the import again to copy the rest.")
            }
            copy.failures.takeIf { it.isNotEmpty() }?.let {
                done += "Artwork that couldn't be copied (${it.size}):"
                done += capped(it)
            }
        }
        val skippedArt = plan.artwork.size - artwork.size
        if (options.artwork && skippedArt > 0) done += "$skippedArt artwork files belong to games Sortie didn't index and were not copied."

        // 10. Re-index so the media index sees the new files (folder mtimes alone don't change when files land in covers/).
        if (copy.copied > 0) {
            onProgress(CocoonImportState.Working("Indexing artwork…"))
            withContext(NonCancellable) {
                runCatching { graph.indexer.run(force = true) }.onFailure {
                    done += "Re-indexing after the copy failed: ${describe(it)}. Settings → Library and emulators → Re-index finishes it."
                }
            }
        }

        val lines = buildList {
            addAll(done)
            add(safetyLine)
            if (plan.playStats > 0 && !(options.sessions && plan.backup != null)) {
                add(
                    "${plan.playStats} games have Cocoon play stats; they were shown only, not imported" +
                        if (plan.backup == null) " — pick a backup to import real sessions." else "."
                )
            }
            if (plan.droidDeck.isNotEmpty()) {
                add(
                    "Re-pin these DroidDeck games from DroidDeck while Sortie is the home app (Cocoon's pins can't move): " +
                        plan.droidDeck.take(LIST_CAP).joinToString() + more(plan.droidDeck)
                )
            }
            add("Nothing in ${plan.cocoonDir.path}${plan.backup?.let { " or ${it.dir.path}" } ?: ""} was changed: Cocoon and its files are still there.")
            val artworkComplete = plan.artwork.isEmpty() || (options.artwork && !copy.stopped && copy.failures.isEmpty())
            if (!artworkComplete) add("Artwork was not fully copied: keep Cocoon's folder and run the import again with artwork on to copy the rest.")
            val sessionsLeft = if (plan.backup != null && options.sessions) "anything played after the backup was made" else "play sessions"
            add(
                "Before uninstalling Cocoon or deleting its folder, check in Sortie that your games, favourites, folders and play history " +
                    "came over. Games that weren't matched, custom titles, themes and $sessionsLeft stay only in Cocoon."
            )
        }
        return CocoonImportState.Result(true, if (copy.stopped) "Import finished, artwork incomplete" else "Import complete", lines)
    }

    /** Steps 3–8. Runs non-cancellable; throws on the first failure, with [done] holding what already happened. */
    private suspend fun applyLibrary(
        plan: CocoonImportPlan,
        options: ImportOptions,
        graph: SortieApp.Graph,
        onProgress: (CocoonImportState.Working) -> Unit,
        done: MutableList<String>
    ) {
        val store = graph.librarySetup

        // 3. Systems, then media folders for systems that have none (the artwork copy needs one to land in).
        var created = 0
        var existed = 0
        if (options.systems) {
            for (ns in plan.newSystems) {
                onProgress(CocoonImportState.Working("Adding ${ns.system.label}…"))
                val expected = store.load()
                if (expected.systems.any { it.id == ns.system.id }) {
                    existed++
                    continue
                }
                // a profile already on the card keeps its (possibly user-edited) definition
                val known = expected.profiles.mapTo(HashSet()) { it.id }
                store.saveSystem(expected, ns.system, ns.profiles.filter { it.id !in known })
                created++
            }
        }
        var mediaSet = 0
        if (options.artwork) {
            for ((systemId, media) in plan.mediaDirs) {
                val expected = store.load()
                val existing: SystemDef = expected.systems.firstOrNull { it.id == systemId } ?: continue
                if (existing.media != null) continue
                onProgress(CocoonImportState.Working("Setting ${existing.label}'s media folder…"))
                store.saveSystem(expected, existing.copy(media = media), emptyList())
                mediaSet++
            }
        }
        if (created > 0 || existed > 0) {
            done += "Systems: added $created${if (existed > 0) ", $existed were already in the library and were left as they are" else ""}."
        }
        if (mediaSet > 0) done += "Media folders set for $mediaSet existing systems (for the copied artwork)."

        // 4. Index what the new systems hold, so the remaining steps only reference games that exist.
        onProgress(CocoonImportState.Working("Indexing the library…"))
        graph.manifests.reloadNow()
        graph.indexer.run(force = false)

        // 5. Ids that exist after indexing; app ids are kept as they are (Android owns those, they show once installed).
        val ids = graph.db.games().allIds().toHashSet()
        fun known(id: String) = id in ids || id.startsWith(LibraryItem.APP_PREFIX)
        val config: LibraryConfiguration = store.load()
        val systems = config.systems.mapTo(HashSet()) { it.id }
        val profiles = graph.manifests.snapshot.value.profiles

        // 6. One user.json commit: unions, and launcher choices only where the user has made none.
        onProgress(CocoonImportState.Working("Saving favourites and emulator choices…"))
        val favourites = if (options.favourites) plan.favourites.filter(::known) else emptyList()
        val hidden = if (options.hidden) plan.hidden.filter(::known) else emptyList()
        val choices = if (options.emulators) plan.systemProfiles.filter { it.systemId in systems && it.profileId in profiles } else emptyList()
        val overrides = if (options.overrides) plan.profileOverrides.filter { (id, profile) -> known(id) && profile in profiles } else emptyMap()
        var before = graph.user.data.value
        val after = graph.user.commit { u ->
            before = u
            u.copy(
                favourites = u.favourites + favourites,
                hidden = u.hidden + hidden,
                systemProfiles = u.systemProfiles + choices.filter { it.systemId !in u.systemProfiles }.associate { it.systemId to it.profileId },
                profileOverrides = u.profileOverrides + overrides.filterKeys { it !in u.profileOverrides },
                cocoonDir = plan.cocoonDir.path
            )
        }
        if (options.favourites) done += countLine("Favourites", after.favourites.size - before.favourites.size, plan.favourites.size - favourites.size)
        if (options.hidden && plan.hidden.isNotEmpty()) {
            done += countLine("Hidden games", after.hidden.size - before.hidden.size, plan.hidden.size - hidden.size)
        }
        if (options.emulators && plan.systemProfiles.isNotEmpty()) {
            val set = after.systemProfiles.size - before.systemProfiles.size
            done += "Emulators: chosen for $set systems; ${plan.systemProfiles.size - set} kept your existing choice or weren't added."
        }
        if (options.overrides && plan.profileOverrides.isNotEmpty()) {
            done += countLine("Per-game emulators", after.profileOverrides.size - before.profileOverrides.size, plan.profileOverrides.size - overrides.size)
        }

        // 7. Real sessions from the backup, deduplicated by (game id, start time) while no session can open or close.
        if (options.sessions && plan.sessions.isNotEmpty()) {
            onProgress(CocoonImportState.Working("Adding play history…"))
            // app: sessions are kept like app favourites — Android owns those ids, Sortie's play log already records them
            val incoming = plan.sessions.filter { known(it.gameId) }
                .map { BackupSession(it.gameId, it.profileId?.takeIf { p -> p in profiles }, it.startedAt, it.endedAt) }
            val merge = graph.playLog.paused {
                val m = planSessionMerge(incoming, graph.db.games().allSessions())
                if (m.toInsert.isNotEmpty()) graph.db.withTransaction { graph.db.games().insertSessions(m.toInsert) }
                m
            }
            done += "Play history: ${merge.toInsert.size} sessions added (${merge.addedMinutes} min), ${merge.alreadyPresent} already here" +
                "${if (plan.sessions.size > incoming.size) ", ${plan.sessions.size - incoming.size} for games Sortie didn't index" else ""}."
        }

        // 8. User-made folders; an existing folder of the same id keeps its name and rules and gains the members.
        if (options.folders && plan.folders.isNotEmpty()) {
            onProgress(CocoonImportState.Working("Adding folders…"))
            val existing = graph.manifests.snapshot.value.folders.associateBy { it.id }
            val defs: List<FolderDef> = plan.folders.mapNotNull { fp ->
                val members = fp.def.include.filter(::known)
                if (members.isEmpty()) return@mapNotNull null
                existing[fp.def.id]?.let { it.copy(include = (it.include + members).distinct()) } ?: fp.def.copy(include = members)
            }
            if (defs.isNotEmpty()) store.saveFolders(store.load(), defs)
            done += "Folders: ${defs.size} saved${if (defs.size < plan.folders.size) ", ${plan.folders.size - defs.size} had no games Sortie indexed" else ""}."
        }
    }

    private class CopyOutcome(
        val copied: Int = 0,
        val bytes: Long = 0,
        val existing: Int = 0,
        val unavailable: Int = 0,
        val failures: List<String> = emptyList(),
        val stopped: Boolean = false
    )

    /**
     * Copies each source to `<target>.tmp` and renames it into place, so a stopped or failed copy never leaves a partial cover the
     * index would pick up. Existing targets are kept (re-runs resume). Runs in its own job: cancelling it stops between files.
     */
    private suspend fun copyArtwork(artwork: List<ArtworkCopy>, onProgress: (CocoonImportState.Working) -> Unit, onArtworkJob: (Job?) -> Unit): CopyOutcome {
        val total = artwork.sumOf { it.bytes }
        var copied = 0
        var bytes = 0L
        var existing = 0
        var unavailable = 0
        var processed = 0L
        val failures = mutableListOf<String>()
        onProgress(CocoonImportState.Working(ARTWORK_STEP, 0, total))
        val job = Job(currentCoroutineContext()[Job])
        onArtworkJob(job)
        val stopped = try {
            withContext(job + Dispatchers.IO) {
                val buffer = ByteArray(256 * 1024)
                for (a in artwork) {
                    ensureActive()
                    when {
                        a.target.exists() -> existing++
                        !a.source.isFile -> unavailable++
                        else -> try {
                            copyFile(a.source, a.target, buffer)
                            copied++
                            bytes += a.bytes
                        } catch (e: Exception) {
                            // a file Android refuses (IOException, SecurityException) is reported; the rest still copy
                            if (e is CancellationException) throw e
                            Log.w(TAG, "copy ${a.source.path}", e)
                            failures += "${a.target.path}: ${describe(e)}"
                        }
                    }
                    processed += a.bytes
                    onProgress(CocoonImportState.Working(ARTWORK_STEP, processed, total))
                }
            }
            false
        } catch (e: CancellationException) {
            // only the copy's own job was cancelled when the caller is still active; anything else is a real cancellation
            if (!currentCoroutineContext().isActive) throw e
            true
        } finally {
            job.complete()
            onArtworkJob(null)
        }
        return CopyOutcome(copied, bytes, existing, unavailable, failures, stopped)
    }

    /** Opens [source] read-only; writes only [target]'s temp sibling, then renames it. */
    private fun copyFile(source: File, target: File, buffer: ByteArray) {
        val dir = target.parentFile ?: throw IOException("no folder for ${target.path}")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create ${dir.path}")
        val tmp = CocoonWriteGuard.tempOf(target)
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(tmp).use { out ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                }
            }
            if (!tmp.renameTo(target)) throw IOException("could not rename ${tmp.name}")
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
    }

    private fun overlaps(plan: CocoonImportPlan, options: ImportOptions, graph: SortieApp.Graph): List<String> {
        val root = graph.manifests.snapshot.value.device.root.takeIf { it.isNotBlank() }?.let(::File) ?: graph.sortieDir.parentFile
        return CocoonWriteGuard.hazards(
            protectedTrees = listOfNotNull(plan.cocoonDir, plan.backup?.dir),
            sortieDir = graph.sortieDir,
            safetyDir = graph.backup.safetyDir,
            cardRoot = root,
            plan = plan,
            options = options,
            // lstat is always there on Android; NIO's "unix" view is what plain JVM tests use
            linkCount = { f -> runCatching { Os.lstat(f.path).st_nlink.toInt() }.getOrNull() }
        )
    }

    private fun countLine(what: String, added: Int, notIndexed: Int): String =
        "$what: $added added${if (notIndexed > 0) ", $notIndexed for games Sortie didn't index" else ""} (already present ones stay once)."

    private fun capped(items: List<String>): List<String> = items.take(LIST_CAP) + listOfNotNull(more(items).takeIf { it.isNotEmpty() }?.trim())

    private fun more(items: List<String>): String = if (items.size > LIST_CAP) " +${items.size - LIST_CAP} more" else ""

    private fun mb(bytes: Long): String = "%.1f MB".format(java.util.Locale.ROOT, bytes / 1_048_576.0)

    private fun describe(e: Throwable): String = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
}
