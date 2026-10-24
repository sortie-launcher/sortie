package io.github.esko997.sortie.ui

import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.data.db.GameEntity
import io.github.esko997.sortie.data.index.Indexer
import io.github.esko997.sortie.data.manifest.LaunchProfile
import io.github.esko997.sortie.data.manifest.SystemDef
import io.github.esko997.sortie.data.setup.LibraryConfiguration
import io.github.esko997.sortie.data.setup.LibraryConflictException
import io.github.esko997.sortie.data.setup.LibrarySetupStore
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The library configuration as Library setup last read it from the card. */
internal sealed interface SetupConfig {
    data object Loading : SetupConfig

    data class Loaded(val config: LibraryConfiguration) : SetupConfig

    data class Failed(val message: String) : SetupConfig
}

/**
 * Where a library change stands. [token] names the screen draft that asked for it, so the screen that shows the outcome (possibly a
 * new instance after the X swap or a return from a game page) knows whether to close that draft or keep it with the error.
 */
internal sealed interface SetupWork {
    data object Idle : SetupWork

    data class Working(val token: String, val step: String) : SetupWork

    data class Failed(val token: String, val message: String, val conflict: Boolean) : SetupWork

    data class Done(val token: String, val result: SetupResult) : SetupWork
}

internal enum class FolderState { OK, MISSING, UNREADABLE }

/**
 * What one game folder holds as the indexer sees it: [matching] files of the system's types directly inside it, [subfolders]
 * beneath it and how many of those ([subfoldersWithGames]) hold matching files themselves, which the index never reads.
 */
internal data class FolderCheck(val path: String, val state: FolderState, val matching: Int, val subfolders: Int, val subfoldersWithGames: Int)

/** The outcome of a save, removal or re-index, with the saved system's indexed games to open for a test launch. */
internal data class SetupResult(
    val title: String,
    val report: Indexer.Report?,
    val problems: List<String>,
    val systemId: String?,
    val folders: List<FolderCheck>,
    val games: List<GameEntity>,
    val gameCount: Int
)

/**
 * Library setup's writes and the incremental re-index that follows them. Process-wide (one per [SortieApp.Graph]), so leaving the
 * screen, opening a game page or the X screen swap never cancels a half-done multi-file save or loses its outcome; the screen
 * only shows [work] and [config]. One change at a time.
 */
internal class LibrarySetupModel private constructor(private val graph: SortieApp.Graph) {
    private val store = LibrarySetupStore(graph.sortieDir, graph.manifests)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _config = MutableStateFlow<SetupConfig>(SetupConfig.Loading)
    val config: StateFlow<SetupConfig> = _config
    private val _work = MutableStateFlow<SetupWork>(SetupWork.Idle)
    val work: StateFlow<SetupWork> = _work
    private var loading: Job? = null

    private val busy: Boolean get() = _work.value is SetupWork.Working

    /** Re-reads the card's configuration (a failed read keeps nothing stale: it says why). */
    fun refresh() {
        if (busy) return
        loading?.cancel()
        loading = scope.launch {
            _config.value = try {
                SetupConfig.Loaded(readConfiguration())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SetupConfig.Failed(describe(e))
            }
        }
    }

    /** Reads the card now, for a draft that must be re-based after a conflicting change; throws what the store's load throws. */
    suspend fun reload(): LibraryConfiguration {
        loading?.cancel()
        val fresh = readConfiguration()
        _config.value = SetupConfig.Loaded(fresh)
        return fresh
    }

    private suspend fun readConfiguration(): LibraryConfiguration = withContext(Dispatchers.IO) {
        val dir = graph.sortieDir
        val remembered = SortieApp.pinnedSortieDir(graph.app) != null
        if ((remembered && !dir.isDirectory) || dir.parentFile?.isDirectory != true) {
            throw IOException(
                "The library folder ${dir.path} is unavailable. Reconnect its storage or restore the settings folder, then try again. " +
                    "Your library has not been reset."
            )
        }
        if (dir.isDirectory && !dir.canRead()) {
            throw IOException("The library folder ${dir.path} can't be read. Check the storage and Sortie's All files access, then try again.")
        }
        store.load()
    }

    /** False when another change is still running (nothing started). */
    fun saveSystem(token: String, expected: LibraryConfiguration, system: SystemDef, profiles: List<LaunchProfile>): Boolean =
        commit(token, "Saving ${system.label}…", "Saved ${system.label}", system) { store.saveSystem(expected, system, profiles) }

    fun removeSystem(token: String, expected: LibraryConfiguration, system: SystemDef): Boolean =
        commit(token, "Removing ${system.label}…", "Removed ${system.label} from the library", null) { store.removeSystem(expected, system.id) }

    fun saveSupportPaths(token: String, expected: LibraryConfiguration, biosDir: String?, saves: List<String>): Boolean =
        commit(token, "Saving BIOS and save folders…", "Saved BIOS and save folders", null) { store.saveSupportPaths(expected, biosDir, saves) }

    fun reindex(token: String): Boolean {
        if (busy) return false
        _work.value = SetupWork.Working(token, "Indexing every system…")
        scope.launch { _work.value = SetupWork.Done(token, index("Re-indexed the library", null, emptyList(), force = true)) }
        return true
    }

    /** The screen took over [token]'s failure (it now shows it with the draft). */
    fun acknowledge(token: String) {
        val w = _work.value
        if (w is SetupWork.Failed && w.token == token) _work.value = SetupWork.Idle
    }

    fun dismissResult() {
        if (_work.value is SetupWork.Done) _work.value = SetupWork.Idle
    }

    private fun commit(token: String, step: String, title: String, system: SystemDef?, write: suspend () -> LibraryConfiguration): Boolean {
        if (busy) return false
        loading?.cancel()
        _work.value = SetupWork.Working(token, step)
        scope.launch {
            // the multi-file write finishes even if nothing is watching any more
            val saved = try {
                withContext(NonCancellable + Dispatchers.IO) { write() }
            } catch (e: LibraryConflictException) {
                _work.value = SetupWork.Failed(token, describe(e), conflict = true)
                return@launch
            } catch (e: Exception) {
                _work.value = SetupWork.Failed(token, describe(e), conflict = false)
                // Keep the failed draft while refreshing the configuration shown underneath it.
                refresh()
                return@launch
            }
            _config.value = SetupConfig.Loaded(saved)
            val notes = mutableListOf<String>()
            try {
                withContext(Dispatchers.IO) { SortieApp.pinSortieDir(graph.app, graph.sortieDir) }
            } catch (e: Exception) {
                notes += "Saved, but Sortie could not record ${graph.sortieDir.path} as its settings folder for the next start: ${describe(e)}"
            }
            notes += saved.errors
            _work.value = SetupWork.Working(token, "Indexing ${system?.label ?: "the library"}…")
            _work.value = SetupWork.Done(token, index(title, system, notes))
        }
        return true
    }

    private suspend fun index(title: String, system: SystemDef?, notes: List<String>, force: Boolean = false): SetupResult {
        val problems = notes.toMutableList()
        val report = try {
            graph.manifests.reloadNow() // the indexer reads the published snapshot; make sure it holds what was just written
            graph.indexer.run(force = force)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problems += "Indexing failed: ${describe(e)}"
            null
        }
        report?.problems?.let { problems += it }
        if (system == null) return SetupResult(title, report, problems, null, emptyList(), emptyList(), 0)
        val extensions = parseExtensions(system.extensions.joinToString(",")).first.toSet()
        val folders = withContext(Dispatchers.IO) { system.roots.map { checkFolder(resolveFolder(graph, it), extensions) } }
        val games = runCatching { graph.db.games().bySystem(system.id).first() }.getOrDefault(emptyList()).filter { !it.placeholder }
        return SetupResult(title, report, problems, system.id, folders, games.take(TEST_GAMES), games.size)
    }

    companion object {
        private const val TEST_GAMES = 6
        private var instance: LibrarySetupModel? = null

        /** Main thread only (composition). */
        fun of(graph: SortieApp.Graph): LibrarySetupModel = instance?.takeIf { it.graph === graph } ?: LibrarySetupModel(graph).also { instance = it }
    }
}

internal fun describe(e: Throwable): String = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

/** A configured folder as a file: absolute paths as written, relative ones under the card root (as the indexer reads them). */
internal fun resolveFolder(graph: SortieApp.Graph, path: String): File {
    if (path.startsWith("/")) return File(path)
    val root = graph.manifests.snapshot.value.device.root.takeIf { it.isNotBlank() }?.let(::File) ?: graph.sortieDir.parentFile ?: graph.sortieDir
    return File(root, path)
}

/**
 * "iso, .CHD *.cso" → [".iso", ".chd", ".cso"] (the form systems.json and the indexer use). Second: the first entry that is not a
 * plain file type, if any; such input is never saved.
 */
internal fun parseExtensions(text: String): Pair<List<String>, String?> {
    val out = LinkedHashSet<String>()
    var bad: String? = null
    for (raw in text.split(',', ' ', ';', '\n', '\t')) {
        val t = raw.trim().removePrefix("*").removePrefix(".").lowercase(Locale.ROOT)
        if (t.isEmpty()) continue
        if (!EXT_RX.matches(t)) {
            if (bad == null) bad = raw.trim()
            continue
        }
        out += ".$t"
    }
    return out.toList() to bad
}

private val EXT_RX = Regex("[a-z0-9][a-z0-9_+-]{0,15}")

private const val SUBFOLDER_PROBE_LIMIT = 200

/** What the indexer would find in [dir] for these [extensions] (empty = every file), and what it would miss in subfolders. */
internal fun checkFolder(dir: File, extensions: Set<String>): FolderCheck {
    if (!dir.exists()) return FolderCheck(dir.path, FolderState.MISSING, 0, 0, 0)
    val listing = dir.listFiles() ?: return FolderCheck(dir.path, FolderState.UNREADABLE, 0, 0, 0)
    var matching = 0
    val subs = ArrayList<File>()
    for (f in listing) {
        if (f.name.startsWith(".")) continue
        if (f.isDirectory) subs += f else if (matches(f.name, extensions)) matching++
    }
    val withGames = subs.take(SUBFOLDER_PROBE_LIMIT).count { sub ->
        sub.listFiles()?.any { it.isFile && !it.name.startsWith(".") && matches(it.name, extensions) } == true
    }
    return FolderCheck(dir.path, FolderState.OK, matching, subs.size, withGames)
}

internal fun matches(name: String, extensions: Set<String>): Boolean =
    extensions.isEmpty() || ".${name.substringAfterLast('.', "").lowercase(Locale.ROOT)}" in extensions

internal fun plural(n: Int, noun: String): String = if (n == 1) "1 $noun" else "$n ${noun}s"
