package io.github.esko997.sortie.data.cocoon

import io.github.esko997.sortie.data.setup.CommitIo
import io.github.esko997.sortie.data.setup.LibraryConflictException
import io.github.esko997.sortie.data.setup.LibraryPresets
import io.github.esko997.sortie.data.setup.LibrarySetupStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The import's library step against real manifest commits on a temporary card. */
class CocoonImporterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * A card with a computer-prepared Game Boy Color system that has no emulators yet (Library setup can't save it as a
     * whole) and a SNES system whose media folder is already chosen.
     */
    private fun card(): File {
        val dir = File(tmp.newFolder("card"), "sortie").apply { mkdirs() }
        File(dir, "device.json").writeText("""{"device": "thor", "root": "${dir.parentFile!!.path}", "futureKey": 1}""")
        File(dir, "systems.json").writeText(
            """{"systems": [
              |{"id": "gbc", "label": "Game Boy Color", "roots": ["roms/gbc"], "profiles": [], "order": 1, "desktopNote": {"keep": true}},
              |{"id": "snes", "label": "SNES", "roots": ["roms/snes"], "media": "media/snes", "profiles": ["my-snes"], "order": 2}
              |], "desktopVersion": 7}
            """.trimMargin()
        )
        File(dir, "launchers.json").writeText("""{"profiles": [{"id": "my-snes", "label": "My Snes9x", "package": "com.retroarch"}]}""")
        File(dir, "folders.json").writeText("""{"folders": []}""")
        File(dir, "guide.json").writeText("""{"built": "", "games": {}}""")
        return dir
    }

    private fun newSystem(id: String): NewSystem {
        val preset = LibraryPresets.preset(id)!!
        return NewSystem(preset.system.copy(roots = listOf("roms/$id")), preset.profiles, fromPreset = true)
    }

    private fun systemIds(dir: File): List<String> =
        Json.parseToJsonElement(File(dir, "systems.json").readText()).jsonObject["systems"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    private fun systemEntry(dir: File, id: String): JsonObject =
        Json.parseToJsonElement(File(dir, "systems.json").readText()).jsonObject["systems"]!!.jsonArray.map { it.jsonObject }
            .single { it["id"]!!.jsonPrimitive.content == id }

    private fun bytes(dir: File): Map<String, String> = dir.listFiles()!!.filter { it.isFile }.associate { it.name to it.readText() }

    @Test
    fun aFailedSaveAfterACommittedSystemStillReportsItAndARerunFinishesTheRest() = runBlocking {
        val dir = card()
        var failing = false
        val io = object : CommitIo() {
            override fun write(file: File, bytes: ByteArray) {
                if (failing) throw IOException("card removed")
                super.write(file, bytes)
            }
        }
        val store = LibrarySetupStore(dir, {}, io, "test")
        val gba = newSystem("gba")
        val psx = newSystem("psx")
        val progress = CocoonImporter.SystemsProgress()
        try {
            // the storage goes away once GBA is committed, as the PSX save starts
            CocoonImporter.applySystems(
                store,
                store.load(),
                listOf(gba, psx),
                mapOf("gbc" to "media/gbc"),
                { if (it.step == "Adding ${psx.system.label}…") failing = true },
                progress
            )
            fail("the failed PSX save was not reported")
        } catch (e: IOException) {
            assertFalse(e is LibraryConflictException)
        }
        assertEquals(1, progress.created)
        assertEquals(0, progress.mediaSet)
        assertEquals(listOf("gbc", "snes", "gba"), systemIds(dir))
        assertFalse(systemEntry(dir, "gbc").containsKey("media"))

        failing = false
        val again = CocoonImporter.SystemsProgress()
        val after = CocoonImporter.applySystems(store, store.load(), listOf(gba, psx), mapOf("gbc" to "media/gbc"), {}, again)
        assertEquals(1, again.created)
        assertEquals(1, again.existed)
        assertEquals(1, again.mediaSet)
        assertEquals(listOf("gbc", "snes", "gba", "psx"), systemIds(dir))
        assertEquals("media/gbc", after.systems.single { it.id == "gbc" }.media)
        assertEquals(after, store.load())
    }

    @Test
    fun anEditMadeElsewhereBetweenSavesStopsTheImportAndIsKept() = runBlocking {
        val dir = card()
        val store = LibrarySetupStore(dir, {}, CommitIo(), "test")
        val gba = newSystem("gba")
        val psx = newSystem("psx")
        val progress = CocoonImporter.SystemsProgress()
        try {
            CocoonImporter.applySystems(
                store,
                store.load(),
                listOf(gba, psx),
                emptyMap(),
                { step ->
                    // Library setup (or a computer) renames SNES while the import is between its saves
                    if (step.step == "Adding ${psx.system.label}…") {
                        val f = File(dir, "systems.json")
                        f.writeText(f.readText().replace("\"SNES\"", "\"Super Famicom\""))
                    }
                },
                progress
            )
            fail("the import saved over an edit it never read")
        } catch (_: LibraryConflictException) {
        }
        assertEquals(1, progress.created)
        assertEquals(listOf("gbc", "snes", "gba"), systemIds(dir))
        assertEquals("Super Famicom", systemEntry(dir, "snes")["label"]!!.jsonPrimitive.content)
    }

    @Test
    fun mediaFoldersAreSetOnlyWhereMissingAndChangeNothingElse() = runBlocking {
        val dir = card()
        val store = LibrarySetupStore(dir, {}, CommitIo(), "test")
        val gbcBefore = systemEntry(dir, "gbc")
        val snesBefore = systemEntry(dir, "snes")
        val others = bytes(dir) - "systems.json"
        val progress = CocoonImporter.SystemsProgress()

        val after = CocoonImporter.applySystems(store, store.load(), emptyList(), mapOf("gbc" to "media/gbc", "snes" to "elsewhere/snes"), {}, progress)

        assertEquals(1, progress.mediaSet)
        val gbcAfter = systemEntry(dir, "gbc")
        assertEquals("media/gbc", gbcAfter["media"]!!.jsonPrimitive.content)
        assertEquals(gbcBefore, JsonObject(gbcAfter - "media"))
        assertTrue(gbcAfter["profiles"]!!.jsonArray.isEmpty())
        assertEquals(snesBefore, systemEntry(dir, "snes"))
        assertEquals(others, bytes(dir) - "systems.json")
        assertEquals("media/snes", after.systems.single { it.id == "snes" }.media)
        assertNull(after.errors.firstOrNull())
    }
}
