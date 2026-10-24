package io.github.esko997.sortie.data.manifest

import android.os.FileObserver
import android.util.Log
import io.github.esko997.sortie.data.setup.ManifestCommit
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Loads the JSON manifests in `<root>/sortie`, hot-reloading (debounced, off the main thread) when one changes. */
class ManifestStore(private val sortieDir: File, private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
    data class Snapshot(
        val device: DeviceManifest,
        val systems: List<SystemDef>,
        val library: LibraryRules,
        val profiles: Map<String, LaunchProfile>,
        val folders: List<FolderDef>,
        val guide: Map<String, GuideEntry>,
        val guideStamp: String,
        val loadedAt: Long = System.currentTimeMillis(),
        val errors: List<String> = emptyList()
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val _snapshot = MutableStateFlow(load(previous = null))
    val snapshot: StateFlow<Snapshot> = _snapshot
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    private var observer: FileObserver? = null

    init {
        @OptIn(FlowPreview::class)
        scope.launch { changes.debounce(400).collect { _snapshot.value = load(_snapshot.value) } }
        watch()
    }

    /** Starts the directory watcher if the directory exists; safe to call again later (e.g. after a permission grant). */
    fun watch() {
        if (observer != null || !sortieDir.isDirectory) return
        observer = object : FileObserver(sortieDir, CLOSE_WRITE or MOVED_TO or DELETE or CREATE) {
            override fun onEvent(event: Int, path: String?) {
                // user.json and playlog.jsonl are device-owned outputs, not inputs
                if (path != null && path.endsWith(".json") && path !in DEVICE_OWNED) changes.tryEmit(Unit)
            }
        }.also { it.startWatching() }
    }

    fun reload() {
        changes.tryEmit(Unit)
        watch()
    }

    suspend fun reloadNow() {
        _snapshot.value = withContext(Dispatchers.IO) { load(_snapshot.value) }
        watch()
    }

    /**
     * Reads the set under the [ManifestCommit] lock, after finishing any interrupted on-device setup commit, so a snapshot never
     * mixes files from before and after a library change. If the commit cannot be finished (no write access yet) its staged
     * copies are read instead and the reason is listed in [Snapshot.errors]. If the interrupted change no longer matches the
     * files (damaged copy, edited meanwhile) the files are not published at all: [previous] (the last complete set this
     * process read) is kept, or an empty library on a first load, with the reason in [Snapshot.errors].
     */
    private fun load(previous: Snapshot?): Snapshot = ManifestCommit.withLock(sortieDir) { loadLocked(previous) }

    private fun loadLocked(previous: Snapshot?): Snapshot {
        val errors = mutableListOf<String>()
        val recovery = ManifestCommit.recover(sortieDir)
        recovery.error?.let { errors += it }
        if (!recovery.consistent) {
            Log.w(TAG, "not publishing an inconsistent manifest set: ${recovery.error}")
            val kept = previous ?: Snapshot(
                device = DeviceManifest(root = sortieDir.parentFile?.path ?: "/"),
                systems = emptyList(),
                library = LibraryRules(),
                profiles = emptyMap(),
                folders = emptyList(),
                guide = emptyMap(),
                guideStamp = ""
            )
            return kept.copy(loadedAt = System.currentTimeMillis(), errors = errors)
        }
        fun file(name: String): File = recovery.pending[name] ?: File(sortieDir, name)
        fun <T> read(name: String, default: T, parse: (String) -> T): T {
            val f = file(name)
            if (!f.isFile) {
                errors += "$name missing"
                return default
            }
            return runCatching { parse(f.readText()) }.getOrElse {
                errors += "$name: ${it.message}"
                Log.w(TAG, "parse $name", it)
                default
            }
        }
        val device = read("device.json", DeviceManifest(root = sortieDir.parentFile?.path ?: "/")) { json.decodeFromString(DeviceManifest.serializer(), it) }
        val systemsFile = read("systems.json", SystemsManifest()) { json.decodeFromString(SystemsManifest.serializer(), it) }
        val launchers = read("launchers.json", LaunchersManifest()) { json.decodeFromString(LaunchersManifest.serializer(), it) }
        val folders = read("folders.json", FoldersManifest()) { json.decodeFromString(FoldersManifest.serializer(), it) }
        val guideFile = file("guide.json")
        val guide = read("guide.json", GuideFile()) { json.decodeFromString(GuideFile.serializer(), it) }
        val guideStamp = if (guideFile.isFile) "${guideFile.length()}:${guideFile.lastModified()}:${guide.built}" else ""
        // the card we actually found wins over a stale root in device.json
        return Snapshot(
            device = device.copy(root = sortieDir.parentFile?.path ?: device.root),
            systems = systemsFile.systems.sortedBy { it.order },
            library = systemsFile.library,
            profiles = launchers.profiles.associateBy { it.id },
            folders = folders.folders,
            guide = guide.games,
            guideStamp = guideStamp,
            errors = errors
        )
    }

    companion object {
        private const val TAG = "ManifestStore"
        private val DEVICE_OWNED = setOf("user.json", "user.json.tmp")
    }
}
