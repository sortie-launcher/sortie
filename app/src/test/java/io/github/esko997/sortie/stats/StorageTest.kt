package io.github.esko997.sortie.stats

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageTest {
    private val data = SpaceSize(41_200_000_000, 128_000_000_000)

    /** `/sdcard`: the primary shared storage, emulated on the data partition. */
    private val sdcard = VolumeFacts(
        "/storage/emulated/0",
        "mounted",
        primary = true,
        emulated = true,
        removable = false,
        description = "Internal shared storage"
    )

    private fun card(path: String?, state: String = "mounted", description: String? = "SD card") =
        VolumeFacts(path, state, primary = false, emulated = false, removable = true, description = description)

    private val sizes = mapOf(
        "/storage/1234-ABCD" to SpaceSize(212_400_000_000, 256_000_000_000),
        "/storage/USB1-0000" to SpaceSize(30_000_000_000, 64_000_000_000)
    )

    @Test
    fun internalOnceThenMountedRemovableVolumes() {
        val spaces = Storage.spaces(
            data,
            listOf(sdcard, card("/storage/USB1-0000", description = "USB drive"), card("/storage/1234-ABCD", description = "SanDisk SD card")),
            sizes::get
        )
        assertEquals(
            listOf(
                StorageSpace(StorageKind.INTERNAL, "Internal storage", 41_200_000_000, 128_000_000_000),
                StorageSpace(StorageKind.SD, "SanDisk SD card", 212_400_000_000, 256_000_000_000),
                StorageSpace(StorageKind.USB, "USB drive", 30_000_000_000, 64_000_000_000)
            ),
            spaces
        )
    }

    @Test
    fun aCardThatIsNotMountedIsLeftOut() {
        for (state in listOf("unmounted", "removed", "bad_removal", "checking", "ejecting", "unmountable")) {
            val spaces = Storage.spaces(data, listOf(sdcard, card("/storage/1234-ABCD", state)), sizes::get)
            assertEquals(listOf(StorageKind.INTERNAL), spaces.map { it.kind })
        }
        assertEquals(listOf(StorageKind.INTERNAL), Storage.spaces(data, listOf(card(null)), sizes::get).map { it.kind })
        // read-only still has free space to report
        assertEquals(2, Storage.spaces(data, listOf(card("/storage/1234-ABCD", "mounted_ro")), sizes::get).size)
    }

    @Test
    fun aMountedVolumeThatCannotBeReadIsUnreadableNotEmpty() {
        val spaces = Storage.spaces(null, listOf(card("/storage/GONE-0000"), card("/storage/ZERO-0000")), mapOf("/storage/ZERO-0000" to SpaceSize(0, 0))::get)
        assertEquals(
            listOf(
                StorageSpace(StorageKind.INTERNAL, "Internal storage", null, null),
                StorageSpace(StorageKind.SD, "SD card", null, null),
                StorageSpace(StorageKind.SD, "SD card", null, null)
            ),
            spaces
        )
    }

    @Test
    fun emulatedAndRepeatedVolumesCountOnce() {
        val secondUser = sdcard.copy(path = "/storage/emulated/10", primary = false)
        val spaces = Storage.spaces(data, listOf(sdcard, secondUser, card("/storage/1234-ABCD"), card("/storage/1234-ABCD")), sizes::get)
        assertEquals(listOf(StorageKind.INTERNAL, StorageKind.SD), spaces.map { it.kind })
    }

    @Test
    fun usbIsNeverCalledSd() {
        assertEquals(StorageKind.SD, Storage.kindOf("SD card"))
        assertEquals(StorageKind.SD, Storage.kindOf("SanDisk SD card"))
        assertEquals(StorageKind.SD, Storage.kindOf("SD-Karte"))
        assertEquals(StorageKind.SD, Storage.kindOf("Carte SD"))
        assertEquals(StorageKind.SD, Storage.kindOf("SDカード"))
        assertEquals(StorageKind.USB, Storage.kindOf("USB drive"))
        assertEquals(StorageKind.USB, Storage.kindOf("SD USB drive"))
        assertEquals(StorageKind.USB, Storage.kindOf("USB-Speicher"))
        assertEquals(StorageKind.OTHER, Storage.kindOf("Portable disk"))
        assertEquals(StorageKind.OTHER, Storage.kindOf("ASDF"))
        assertEquals(StorageKind.OTHER, Storage.kindOf(null))
        val unnamed = Storage.spaces(data, listOf(card("/storage/1234-ABCD", description = " ")), sizes::get).last()
        assertEquals(StorageSpace(StorageKind.OTHER, "External storage", 212_400_000_000, 256_000_000_000), unnamed)
    }
}
