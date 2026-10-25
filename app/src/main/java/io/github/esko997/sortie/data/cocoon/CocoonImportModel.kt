package io.github.esko997.sortie.data.cocoon

import android.net.Uri
import android.util.Log
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.data.manifest.FAVOURITES_FOLDER_ID
import io.github.esko997.sortie.data.manifest.WANT_TO_PLAY_FOLDER_ID
import io.github.esko997.sortie.data.user.FINISHED_FOLDER_ID
import io.github.esko997.sortie.data.user.SHELVED_FOLDER_ID
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Progress of an import. */
sealed interface CocoonImportState {
    /** Nothing located yet (the screen starts [CocoonImportModel.locate]). */
    object Idle : CocoonImportState

    /** [done]/[total] are bytes while artwork is copied, 0/0 for steps without a measurable size. */
    data class Working(val step: String, val done: Long = 0, val total: Long = 0) : CocoonImportState

    /**
     * Detected Cocoon directories, the one [chosen] (null = none found yet) and the backups on offer: those the user typed or
     * picked (latest pick first), then the ones found in [chosen] (newest first). [backupProblem] says why the last typed or
     * picked backup location wasn't usable (null = no problem).
     */
    data class Located(
        val candidates: List<File>,
        val chosen: File?,
        val backups: List<BackupChoice>,
        val cocoonInstalled: Boolean,
        val backupProblem: String? = null
    ) : CocoonImportState

    /**
     * A complete plan awaiting the user's confirmation; nothing has been written yet. [fingerprint] is the library configuration
     * the plan was made from ([io.github.esko997.sortie.data.setup.LibraryConfiguration.fingerprint]): Import is refused once it
     * differs. [delta] compares the plan with what Sortie already has. [changed] marks a plan made again because the library
     * changed after the previous preview.
     */
    data class Preview(
        val plan: CocoonImportPlan,
        val options: ImportOptions,
        val fingerprint: String,
        val delta: ImportDelta,
        val changed: Boolean = false
    ) : CocoonImportState

    data class Result(val ok: Boolean, val title: String, val lines: List<String>) : CocoonImportState
}

/** A Cocoon backup on offer: its folder and the manifest's creation time (the folder's mtime when the manifest has none). */
data class BackupChoice(val dir: File, val createdAt: Long)

/** Which parts of a [CocoonImportPlan] the user keeps; the backup-only parts are ignored when no backup was read. */
data class ImportOptions(
    val systems: Boolean = true,
    /** Adds the plan's favourites and removes its [CocoonImportPlan.legacyFavourites] folders.json entries. */
    val favourites: Boolean = true,
    val hidden: Boolean = true,
    val artwork: Boolean = true,
    val folders: Boolean = true,
    val sessions: Boolean = true,
    val overrides: Boolean = true
)

/**
 * Locate → preview → confirm for "Import from Cocoon…". Process-wide (one per [SortieApp.Graph]) like the Library setup model,
 * so leaving the screen, the X screen swap or opening a game never cancels a half-done import or loses its outcome; the screen
 * only shows [state]. One import at a time. Reading never writes anything; [confirm] hands the plan to [CocoonImporter].
 */
class CocoonImportModel private constructor(private val graph: SortieApp.Graph) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<CocoonImportState>(CocoonImportState.Idle)
    val state: StateFlow<CocoonImportState> = _state

    /** The running artwork copy, the only step [cancel] stops (everything before it must finish once begun). */
    @Volatile private var artworkJob: Job? = null

    private val busy: Boolean get() = _state.value is CocoonImportState.Working

    /** Backups the user typed or picked, latest first; offered with whichever Cocoon folder is chosen. Touched only inside [launchJob]. */
    @Volatile private var addedBackups: List<BackupChoice> = emptyList()

    /** Detected Cocoon directories (the remembered one first), backups of the first usable one and whether Cocoon is installed. */
    fun locate() = launchJob("Looking for Cocoon…") {
        withContext(Dispatchers.IO) {
            val remembered = graph.user.data.value.cocoonDir?.let(::File)?.takeIf { CocoonGamelistReader.looksLikeCocoonDir(it) }
            val candidates = distinctDirs(listOfNotNull(remembered) + CocoonLocator.candidates(CocoonLocator.volumes()))
            located(candidates, candidates.firstOrNull())
        }
    }

    /** Uses [dir] when it holds a Cocoon library (`gamelists/` with at least one gamelist.xml). */
    fun choose(dir: File) {
        // read before launchJob replaces the Located state with Working
        val known = (_state.value as? CocoonImportState.Located)?.candidates.orEmpty()
        launchJob("Checking ${dir.path}…") { withContext(Dispatchers.IO) { chosenState(dir, known) } }
    }

    private fun chosenState(dir: File, known: List<File>): CocoonImportState {
        if (!CocoonGamelistReader.looksLikeCocoonDir(dir)) {
            return CocoonImportState.Result(
                false,
                "No Cocoon library there",
                listOf(
                    "${dir.path} has no gamelists folder with a gamelist.xml in it. Choose the folder Cocoon keeps its gamelists, " +
                        "downloaded_media and backups in (usually Cocoon at the top of internal storage).",
                    "Nothing was changed."
                )
            )
        }
        // an alias of a listed folder (/sdcard/… vs /storage/emulated/0/…) selects that row instead of adding a twin
        val candidates = distinctDirs(known + dir)
        val target = CocoonPaths.canonical(dir)
        return located(candidates, candidates.firstOrNull { CocoonPaths.canonical(it) == target } ?: dir)
    }

    /** A directory picked with the system file browser; read with plain file access (the URI grant is never kept). */
    fun chooseTree(uri: Uri) {
        val dir = CocoonPaths.fromTreeUri(uri)
        if (dir == null) {
            if (!busy) {
                _state.value = CocoonImportState.Result(
                    false,
                    "Can't read that folder directly",
                    listOf(
                        "The file browser returned a location that isn't a folder on internal storage or an SD card ($uri).",
                        "Pick the Cocoon folder on the device's own storage, or type its path. Nothing was changed."
                    )
                )
            }
            return
        }
        choose(dir)
    }

    /**
     * Offers the backups at [dir] — one backup, a folder holding backups, or another Cocoon folder; on any storage — above the
     * chosen folder's own, so a backup kept elsewhere (SD card, Download, a copy from another device) can be used.
     */
    fun chooseBackup(dir: File) {
        // read before launchJob replaces the Located state with Working
        val current = _state.value as? CocoonImportState.Located ?: return
        launchJob("Checking ${dir.path}…") {
            withContext(Dispatchers.IO) {
                val found = backupsIn(dir)
                when {
                    found.isNotEmpty() -> {
                        addedBackups = distinctBackups(found + addedBackups)
                        located(current.candidates, current.chosen)
                    }
                    !dir.isDirectory -> located(current.candidates, current.chosen, "${dir.path} doesn't exist or isn't a readable folder.")
                    else -> located(
                        current.candidates,
                        current.chosen,
                        "${dir.path} holds no Cocoon backup. Choose a cocoon_backup_… folder (it contains manifest.json and categories) " +
                            "or the folder that holds such backups."
                    )
                }
            }
        }
    }

    /** A backup location picked with the system file browser; read with plain file access (the URI grant is never kept). */
    fun chooseBackupTree(uri: Uri) {
        val dir = CocoonPaths.fromTreeUri(uri)
        if (dir != null) return chooseBackup(dir)
        val current = _state.value as? CocoonImportState.Located ?: return
        _state.value = current.copy(
            backupProblem = "The file browser returned a location that isn't a folder on internal storage or an SD card ($uri). " +
                "Pick the backup on the device's own storage, or type its path."
        )
    }

    /** Reads the chosen library (and [backup] when given), finds ROM folders and plans; nothing is written. */
    fun preview(backup: File?) {
        val located = _state.value as? CocoonImportState.Located ?: return
        val cocoonDir = located.chosen ?: return
        launchJob("Reading Cocoon's library…") {
            withContext(Dispatchers.IO) { plan(cocoonDir, backup, ImportOptions(), changed = false) }
        }
    }

    fun setOptions(options: ImportOptions) {
        val p = _state.value as? CocoonImportState.Preview ?: return
        _state.value = p.copy(options = options)
    }

    /**
     * Applies the previewed plan if the library configuration is still the one it was made from. A configuration that became
     * unwritable refuses the import; a changed one (a system added in Library setup meanwhile, a desktop edit) gets a fresh
     * preview to review instead, since the old plan could add a second system on the same folder.
     */
    fun confirm() {
        val p = _state.value as? CocoonImportState.Preview ?: return
        launchJob("Starting the import…") {
            val current = graph.librarySetup.load()
            when (val check = ConfirmCheck.of(p.fingerprint, current)) {
                is ConfirmCheck.Blocked -> return@launchJob blocked(check.reasons)
                ConfirmCheck.Changed -> return@launchJob withContext(Dispatchers.IO) {
                    plan(p.plan.cocoonDir, p.plan.backup?.dir, p.options, changed = true)
                }
                ConfirmCheck.Ready -> Unit
            }
            try {
                CocoonImporter.apply(
                    p.plan,
                    p.options,
                    graph,
                    expectedFingerprint = p.fingerprint,
                    onProgress = { _state.value = it },
                    onArtworkJob = { artworkJob = it }
                )
            } finally {
                artworkJob = null
            }
        }
    }

    /** Stops an artwork copy in progress; files already copied stay, and the import still finishes its remaining steps. */
    fun cancel() {
        artworkJob?.cancel()
    }

    fun dismiss() {
        if (!busy) _state.value = CocoonImportState.Idle
    }

    private fun launchJob(step: String, block: suspend () -> CocoonImportState) {
        synchronized(this) {
            if (busy) return
            _state.value = CocoonImportState.Working(step)
        }
        scope.launch {
            _state.value = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, step, e)
                CocoonImportState.Result(false, "Something went wrong", listOf(describe(e), "Nothing in your Cocoon folder was changed."))
            }
        }
    }

    private fun located(candidates: List<File>, chosen: File?, backupProblem: String? = null): CocoonImportState.Located {
        val backups = distinctBackups(addedBackups + chosen?.let(::backupsIn).orEmpty())
        return CocoonImportState.Located(candidates, chosen, backups, installed(COCOON_PACKAGE), backupProblem)
    }

    private fun backupsIn(dir: File): List<BackupChoice> = CocoonBackupReader.findDated(dir).map { (d, at) -> BackupChoice(d, at) }

    private suspend fun plan(cocoonDir: File, backupDir: File?, options: ImportOptions, changed: Boolean): CocoonImportState {
        // a configuration Sortie can't write would stop the import after its safety copy: refuse before planning anything
        val config = graph.librarySetup.load()
        if (config.blocking.isNotEmpty()) return blocked(config.blocking)
        val library = CocoonGamelistReader.readLibrary(cocoonDir)
        if (library.platforms.isEmpty()) {
            return CocoonImportState.Result(
                false,
                "Nothing to import",
                listOf("No gamelist in ${cocoonDir.path}/gamelists could be read.") + library.problems + "Nothing was changed."
            )
        }
        val backup = backupDir?.let { dir ->
            _state.value = CocoonImportState.Working("Reading the backup ${dir.name}…")
            try {
                CocoonBackupReader.read(dir)
            } catch (e: Exception) {
                Log.w(TAG, "backup ${dir.path}", e)
                return CocoonImportState.Result(
                    false,
                    "Can't read that backup",
                    listOf("${dir.path}: ${describe(e)}", "Choose another backup or continue without one. Nothing was changed.")
                )
            }
        }

        _state.value = CocoonImportState.Working("Looking for ROM folders…")
        val volumes = CocoonLocator.volumes()
        val probe = RootProbe(volumes, exclude = listOfNotNull(cocoonDir, backupDir, graph.sortieDir))
        val romPlatforms = library.platforms.filter { it.id !in NOT_ROMS }
        val names = romPlatforms.associate { p -> p.id to p.games.map { it.file } }
        // Hints (backup roots, configured systems' roots, Cocoon's folder grants filtered per alias group) are scored first.
        val hints = RootProbe.hints(names.keys, RootProbe.grantHints(cocoonDir), backup, config.systems, cardRoot())
        val roots = probe.discover(names, hints)

        _state.value = CocoonImportState.Working("Planning…")
        val media = existingMediaLookup()
        val installedCache = HashMap<String, Boolean>()
        val planner = CocoonPlanner(
            cardRoot = cardRoot(),
            sortieDir = graph.sortieDir,
            config = config,
            installed = { pkg -> installedCache.getOrPut(pkg) { installed(pkg) } },
            existingMedia = { systemId, kind, stem -> media[mediaKey(systemId, kind, stem)] == true }
        )
        val plan = planner.plan(library, backup, roots, probe.listings())
        // Problems found while reading and the card configuration's non-blocking problems belong in the preview, not only in the log.
        val notes = (plan.notes + library.problems + config.errors + listOfNotNull(customisedReservedNote(plan))).distinct()
        _state.value = CocoonImportState.Working("Comparing with your library…")
        val sessions = if (plan.sessions.isEmpty()) emptyList() else graph.db.games().allSessions()
        val delta = ImportDelta.of(plan, graph.user.data.value, graph.manifests.snapshot.value.folders, sessions)
        return CocoonImportState.Preview(plan.copy(notes = notes), options, config.fingerprint, delta, changed)
    }

    /** The refusal for a library configuration Sortie can't write ([io.github.esko997.sortie.data.setup.LibraryConfiguration.blocking]). */
    private fun blocked(reasons: List<String>) = CocoonImportState.Result(
        false,
        "Fix the library settings first",
        reasons + listOf(
            "The import adds systems and folders to these files, so it can't start while Sortie can't read or safely rewrite them. " +
                "Library setup shows the same problem: fix or replace the file named above, then start the import again.",
            "Nothing was changed."
        )
    )

    /**
     * Entries using a built-in folder's id that are not the old default Favourites the import may remove: the import never
     * touches them, so the preview says they stay ignored instead of letting the cleanup look like the whole fix.
     */
    private fun customisedReservedNote(plan: CocoonImportPlan): String? {
        val claimed = graph.manifests.snapshot.value.folders.count { it.id in RESERVED_FOLDER_IDS }
        val customised = (claimed - plan.legacyFavourites).coerceAtLeast(0)
        if (customised == 0) return null
        val them = if (customised == 1) "it" else "them"
        return "folders.json has ${if (customised == 1) "1 folder" else "$customised folders"} using a built-in folder's id " +
            "(${RESERVED_FOLDER_IDS.joinToString()}) other than Sortie's old default Favourites. The import leaves $them as written; " +
            "Sortie keeps ignoring such entries and reports each on that built-in folder. Give $them another id in folders.json to use $them."
    }

    /** (system, kind folder, file stem) of every art file Sortie's index already shows, so the plan never copies over or beside it. */
    private suspend fun existingMediaLookup(): Map<String, Boolean> {
        val map = HashMap<String, Boolean>()
        for (row in graph.db.games().mediaRows()) {
            val stem = row.fileName.substringBeforeLast('.')
            if (row.cover != null) map[mediaKey(row.systemId, "covers", stem)] = true
            if (row.screenshot != null) map[mediaKey(row.systemId, "screenshots", stem)] = true
            if (row.marquee != null) map[mediaKey(row.systemId, "marquees", stem)] = true
        }
        return map
    }

    private fun cardRoot(): File =
        graph.manifests.snapshot.value.device.root.takeIf { it.isNotBlank() }?.let(::File) ?: graph.sortieDir.parentFile ?: graph.sortieDir

    private fun installed(pkg: String): Boolean = runCatching { graph.app.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    companion object {
        private const val TAG = "SortieCocoon"

        /** Cocoon's package; only asked whether it is installed, never started, stopped or sent anything. */
        const val COCOON_PACKAGE = "rip.moth.cocoonshell"

        /** Cocoon platforms that are not ROM folders: its own screens and DroidDeck pins (listed for re-pinning instead). */
        private val NOT_ROMS = setOf("pod", "shortcut")

        /** The built-in folders' ids, which folders.json entries cannot use. */
        private val RESERVED_FOLDER_IDS = listOf(FAVOURITES_FOLDER_ID, WANT_TO_PLAY_FOLDER_ID, FINISHED_FOLDER_ID, SHELVED_FOLDER_ID)

        private var instance: CocoonImportModel? = null

        /** Main thread only (composition). */
        fun of(graph: SortieApp.Graph): CocoonImportModel = instance?.takeIf { it.graph === graph } ?: CocoonImportModel(graph).also { instance = it }

        private fun mediaKey(systemId: String, kind: String, stem: String) = "$systemId\u0000$kind\u0000$stem"

        /** Keeps the first of directories that name the same place (`/sdcard/Cocoon` and `/storage/emulated/0/Cocoon`). */
        private fun distinctDirs(dirs: List<File>): List<File> {
            val seen = HashSet<String>()
            return dirs.filter { seen.add(CocoonPaths.canonical(it).path) }
        }

        /** [distinctDirs] for backups: one row per backup folder however it was reached. */
        private fun distinctBackups(backups: List<BackupChoice>): List<BackupChoice> {
            val seen = HashSet<String>()
            return backups.filter { seen.add(CocoonPaths.canonical(it.dir).path) }
        }

        internal fun describe(e: Throwable): String = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    }
}
