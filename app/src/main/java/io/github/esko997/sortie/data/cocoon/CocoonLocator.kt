package io.github.esko997.sortie.data.cocoon

import java.io.File

/**
 * Where a Cocoon directory may be. Cocoon's own default is `<internal>/Cocoon`, but the owner decided not to assume it: every
 * mounted volume is searched, and the user can still type or pick another directory. Only reads directory listings.
 */
object CocoonLocator {
    private val PRIMARY = File("/storage/emulated/0")
    private val STORAGE = File("/storage")

    /** /storage/emulated/0 first, then every /storage/<uuid> that is a readable directory (skip "self", "emulated"). */
    fun volumes(): List<File> {
        val out = ArrayList<File>()
        if (PRIMARY.isDirectory) out += PRIMARY
        STORAGE.listFiles()
            ?.filter { it.name != "self" && it.name != "emulated" && !it.name.startsWith(".") && it.isDirectory && it.canRead() }
            ?.sortedBy { it.name }
            ?.let { out += it }
        return out
    }

    /**
     * <volume>/Cocoon for each volume, then any dir ≤2 levels deep holding gamelists/<x>/gamelist.xml (skip Android/, dot dirs);
     * only dirs passing CocoonGamelistReader.looksLikeCocoonDir.
     */
    fun candidates(volumes: List<File>): List<File> {
        val found = LinkedHashMap<String, File>()
        fun offer(dir: File) {
            if (CocoonGamelistReader.looksLikeCocoonDir(dir)) found.putIfAbsent(CocoonPaths.canonical(dir).path, dir)
        }
        volumes.forEach { offer(File(it, "Cocoon")) }
        for (volume in volumes) {
            for (first in subdirs(volume, skipAndroid = true)) {
                offer(first)
                subdirs(first, skipAndroid = false).forEach(::offer)
            }
        }
        return found.values.toList()
    }

    private fun subdirs(dir: File, skipAndroid: Boolean): List<File> = dir.listFiles()
        ?.filter { !it.name.startsWith(".") && !(skipAndroid && it.name == "Android") && it.isDirectory }
        ?.sortedBy { it.name }
        .orEmpty()
}
