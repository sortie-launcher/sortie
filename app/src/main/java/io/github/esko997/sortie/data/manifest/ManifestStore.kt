package io.github.esko997.sortie.data.manifest

import android.os.FileObserver
import android.util.Log
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

/** Loads the JSON manifests in `<root>/sortie`, hot-reloading when a file changes. The card is the source of truth. */
class ManifestStore(private val sortieDir: File) {
    data class Snapshot(
        val device: DeviceManifest,
        val systems: List<SystemDef>,
        val profiles: Map<String, LaunchProfile>,
        val folders: List<FolderDef>,
        val guide: Map<String, GuideEntry>,
        val loadedAt: Long = System.currentTimeMillis(),
        val errors: List<String> = emptyList()
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val _snapshot = MutableStateFlow(load())
    val snapshot: StateFlow<Snapshot> = _snapshot

    @Suppress("DEPRECATION") // the path-based constructor is the one that works below API 29 too
    private val observer = object : FileObserver(sortieDir.path, CLOSE_WRITE or MOVED_TO or DELETE) {
        override fun onEvent(event: Int, path: String?) {
            if (path != null && path.endsWith(".json")) reload()
        }
    }

    init {
        if (sortieDir.isDirectory) observer.startWatching()
    }

    fun reload() {
        _snapshot.value = load()
    }

    private fun load(): Snapshot {
        val errors = mutableListOf<String>()
        fun <T> read(name: String, default: T, parse: (String) -> T): T {
            val f = File(sortieDir, name)
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
        val device =
            read("device.json", DeviceManifest(root = sortieDir.parentFile?.path ?: "/")) {
                json.decodeFromString(DeviceManifest.serializer(), it)
            }
        val systems = read("systems.json", SystemsManifest()) {
            json.decodeFromString(SystemsManifest.serializer(), it)
        }.systems.sortedBy { it.order }
        val launchers =
            read("launchers.json", LaunchersManifest()) { json.decodeFromString(LaunchersManifest.serializer(), it) }
        val folders = read("folders.json", FoldersManifest()) {
            json.decodeFromString(FoldersManifest.serializer(), it)
        }.folders
        val guide = read("guide.json", GuideFile()) { json.decodeFromString(GuideFile.serializer(), it) }.games
        return Snapshot(device, systems, launchers.profiles.associateBy { it.id }, folders, guide, errors = errors)
    }

    companion object {
        private const val TAG = "ManifestStore"
    }
}
