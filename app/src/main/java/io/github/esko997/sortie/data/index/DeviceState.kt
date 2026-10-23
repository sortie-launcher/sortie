package io.github.esko997.sortie.data.index

import io.github.esko997.sortie.data.manifest.ManifestStore
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** Cheap device facts the lists decorate rows with: which BIOS files are missing per system, which game stems have a save. */
class DeviceState(private val manifests: ManifestStore) {
    private val _biosMissing = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val biosMissing: StateFlow<Map<String, List<String>>> = _biosMissing

    private val _saveStems = MutableStateFlow<Set<String>>(emptySet())
    val saveStems: StateFlow<Set<String>> = _saveStems

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val snap = manifests.snapshot.value
        val root = File(snap.device.root)
        val biosDir = snap.device.biosDir?.let { if (it.startsWith("/")) File(it) else File(root, it) }
        _biosMissing.value = if (biosDir == null) {
            emptyMap()
        } else {
            snap.systems.associate { sys ->
                sys.id to sys.bios.filter {
                    !File(biosDir, it).exists()
                }
            }.filterValues { it.isNotEmpty() }
        }
        val stems = HashSet<String>()
        for (r in snap.device.saves) {
            val dir = if (r.startsWith("/")) File(r) else File(root, r)
            dir.listFiles()?.forEach { entry ->
                if (entry.name.startsWith(".") || entry.name.equals("states", ignoreCase = true)) return@forEach
                if (entry.isFile) {
                    stems += entry.nameWithoutExtension.lowercase(Locale.ROOT)
                } else {
                    entry.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }?.forEach { stems += it.nameWithoutExtension.lowercase(Locale.ROOT) }
                }
            }
        }
        _saveStems.value = stems
    }
}
