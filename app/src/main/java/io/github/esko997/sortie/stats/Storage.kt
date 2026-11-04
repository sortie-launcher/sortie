package io.github.esko997.sortie.stats

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager

/** What a storage reading is: the device's own storage, or a removable volume by what Android says it is. */
enum class StorageKind(
    /** The name on the switcher's storage line. */
    val word: String
) {
    INTERNAL("Internal"),
    SD("SD"),
    USB("USB"),
    OTHER("External")
}

/**
 * Free space on one volume. [freeBytes] is what apps may still write (`StatFs.availableBytes`, without the blocks the file
 * system keeps in reserve); [freeBytes] and [totalBytes] are null when the volume is mounted but its size could not be read,
 * which surfaces show as unavailable rather than as an empty volume. [label] is Android's own description of the volume
 * (`SD card`, `SanDisk SD card`, `USB drive`), or `Internal storage`.
 */
data class StorageSpace(val kind: StorageKind, val label: String, val freeBytes: Long?, val totalBytes: Long?)

/** One volume as StorageManager lists it, reduced to what the choice needs ([Storage.spaces]). [path] is null when unmounted. */
data class VolumeFacts(
    val path: String?,
    val state: String,
    val primary: Boolean,
    val emulated: Boolean,
    val removable: Boolean,
    val description: String?
)

/** A volume's available and total bytes, as StatFs reads them. */
data class SpaceSize(val availableBytes: Long, val totalBytes: Long)

object Storage {
    /**
     * The readings to show: [internal] (the data partition) first, then each removable volume that is mounted, SD cards
     * before USB drives. The primary shared storage (`/sdcard`, emulated on the data partition) is not listed again, nor is
     * any other emulated volume; a volume that is not mounted (removed, ejected, being checked) is left out. [size] reads a
     * mounted volume's path; null keeps the volume, unreadable.
     */
    fun spaces(internal: SpaceSize?, volumes: List<VolumeFacts>, size: (String) -> SpaceSize?): List<StorageSpace> {
        val removable = volumes
            .filter { it.removable && !it.primary && !it.emulated && it.path != null && it.state in MOUNTED }
            .distinctBy { it.path }
            .map { v ->
                val kind = kindOf(v.description)
                val read = size(v.path!!)?.takeIf { it.totalBytes > 0 }
                StorageSpace(kind, v.description?.takeIf { it.isNotBlank() } ?: kind.fallbackLabel(), read?.availableBytes, read?.totalBytes)
            }
            .sortedBy { it.kind.ordinal }
        val own = internal?.takeIf { it.totalBytes > 0 }
        return listOf(StorageSpace(StorageKind.INTERNAL, INTERNAL_LABEL, own?.availableBytes, own?.totalBytes)) + removable
    }

    /**
     * SD or USB by the volume's description, the only public sign of the disk's bus: Android words it from the disk's flags
     * (`SD card`, `USB drive`, with the maker's name before it when known, translated). USB is checked first, so a USB card
     * reader is a USB drive; anything else is [StorageKind.OTHER] under its own description.
     */
    fun kindOf(description: String?): StorageKind = when {
        description == null -> StorageKind.OTHER
        USB_WORD.containsMatchIn(description) -> StorageKind.USB
        SD_WORD.containsMatchIn(description) -> StorageKind.SD
        else -> StorageKind.OTHER
    }

    /** Reads every reading now; blocking (a binder call and a statfs per volume), so only from the monitor's IO poll. */
    fun read(context: Context): List<StorageSpace> {
        val manager = context.getSystemService(StorageManager::class.java)
        val volumes = manager?.storageVolumes.orEmpty().map {
            VolumeFacts(it.directory?.path, it.state, it.isPrimary, it.isEmulated, it.isRemovable, it.getDescription(context))
        }
        return spaces(sizeOf(Environment.getDataDirectory().path), volumes, ::sizeOf)
    }

    /** StatFs throws when the path is gone or unreadable (a card pulled between listing and reading): null then. */
    private fun sizeOf(path: String): SpaceSize? = runCatching { StatFs(path) }.getOrNull()?.let { SpaceSize(it.availableBytes, it.totalBytes) }

    private fun StorageKind.fallbackLabel(): String = when (this) {
        StorageKind.INTERNAL -> INTERNAL_LABEL
        StorageKind.SD -> "SD card"
        StorageKind.USB -> "USB drive"
        StorageKind.OTHER -> "External storage"
    }

    private const val INTERNAL_LABEL = "Internal storage"

    private val MOUNTED = setOf(Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY)

    /** The bus names as words of their own (`SD card`, `SD-Karte`, `Carte SD`, `SDカード`), not inside other words. */
    private val SD_WORD = Regex("(?<![A-Za-z])SD(?![A-Za-z])")
    private val USB_WORD = Regex("(?<![A-Za-z])USB(?![A-Za-z])")
}
