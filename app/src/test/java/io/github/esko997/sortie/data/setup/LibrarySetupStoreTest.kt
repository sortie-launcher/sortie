package io.github.esko997.sortie.data.setup

import io.github.esko997.sortie.data.manifest.DeviceManifest
import io.github.esko997.sortie.data.manifest.FAVOURITES_FOLDER_ID
import io.github.esko997.sortie.data.manifest.FolderDef
import io.github.esko997.sortie.data.manifest.FoldersManifest
import io.github.esko997.sortie.data.manifest.GuideFile
import io.github.esko997.sortie.data.manifest.LaunchProfile
import io.github.esko997.sortie.data.manifest.LaunchersManifest
import io.github.esko997.sortie.data.manifest.ManifestStore
import io.github.esko997.sortie.data.manifest.SystemDef
import io.github.esko997.sortie.data.manifest.SystemsManifest
import io.github.esko997.sortie.data.manifest.WANT_TO_PLAY_FOLDER_ID
import io.github.esko997.sortie.data.user.FINISHED_FOLDER_ID
import io.github.esko997.sortie.data.user.SHELVED_FOLDER_ID
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LibrarySetupStoreTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** A desktop-authored card: unknown keys everywhere, a custom profile, rules, folders, guide and personal data. */
    private fun desktopCard(): File {
        val root = Files.createTempDirectory("sortie-card").toFile()
        val dir = File(root, "sortie").apply { mkdirs() }
        File(dir, "device.json").writeText(
            """{"device": "thor", "root": "/storage/XXXX", "screens": {"count": 2, "navigation": "bottom"}, "shizuku": true,
              |"saves": ["/storage/emulated/0/saves"], "futureKey": {"keep": true}}
            """.trimMargin()
        )
        File(dir, "systems.json").writeText(
            """{"systems": [
              |{"id": "ps2", "label": "PS2", "generation": "128-bit", "roots": ["big-roms/ps2"], "gamelist": "g/ps2.xml",
              | "profiles": ["armsx2-uri", "open-with"], "colour": "#f59e0b", "count_hint": 97, "order": 0},
              |{"id": "snes", "label": "SNES", "generation": "16-bit", "roots": ["roms/snes"], "profiles": ["my-snes", "open-with"],
              | "colour": "#3b82f6", "count_hint": 15, "order": 31, "bios": []}
              |], "library": {"skipFiles": ["neogeo.zip"], "hackRegex": "(?i)\\(hack\\)"}, "desktopVersion": 7}
            """.trimMargin()
        )
        File(dir, "launchers.json").writeText(
            """{"profiles": [
              |{"id": "armsx2-uri", "label": "ARMSX2 (content URI)", "package": "come.nanodata.armsx2", "activity": "com.armsx2.MainActivity",
              | "action": "android.intent.action.VIEW", "data": "${'$'}{uri}", "flags": ["GRANT_READ_URI_PERMISSION"]},
              |{"id": "my-snes", "label": "My tuned Snes9x", "package": "com.retroarch", "extras": {"ROM": "${'$'}{file}"}, "comment": "desktop note"},
              |{"id": "open-with", "label": "Open with…", "generic": true}
              |]}
            """.trimMargin()
        )
        File(dir, "folders.json").writeText("""{"folders": [{"id": "shmups", "label": "Shmups", "include": ["snes/a.sfc"]}]}""")
        File(dir, "guide.json").writeText("""{"built": "2026-10-24", "games": {"snes/a.sfc": {"fit": 5, "hook": "great"}}}""")
        File(dir, "user.json").writeText("""{"favourites": ["snes/a.sfc"], "systemProfiles": {"snes": "my-snes"}}""")
        File(dir, "playlog.jsonl").writeText("{\"game\":\"snes/a.sfc\"}\n")
        return dir
    }

    private fun store(dir: File, io: CommitIo = CommitIo()) = LibrarySetupStore(dir, {}, io, "test-device")

    private fun obj(file: File): JsonObject = Json.parseToJsonElement(file.readText()).jsonObject

    private fun entry(file: File, key: String, id: String): JsonObject? =
        obj(file)[key]!!.jsonArray.map { it.jsonObject }.firstOrNull { it["id"]!!.jsonPrimitive.content == id }

    private fun bytes(dir: File): Map<String, String> = dir.listFiles()!!.filter { it.isFile }.associate { it.name to it.readText() }

    /** Loads every manifest exactly as the app does; fails on anything ManifestStore would report as broken. */
    private fun assertCompleteSet(dir: File) {
        json.decodeFromString(DeviceManifest.serializer(), File(dir, "device.json").readText())
        json.decodeFromString(SystemsManifest.serializer(), File(dir, "systems.json").readText())
        json.decodeFromString(LaunchersManifest.serializer(), File(dir, "launchers.json").readText())
        json.decodeFromString(FoldersManifest.serializer(), File(dir, "folders.json").readText())
        json.decodeFromString(GuideFile.serializer(), File(dir, "guide.json").readText())
        assertEquals(emptyList<String>(), dir.list()!!.filter { it.endsWith(ManifestCommit.STAGED_SUFFIX) || it.startsWith(ManifestCommit.JOURNAL) })
    }

    private val gba =
        SystemDef(
            id = "gba",
            label = "GBA",
            generation = "handheld",
            roots = listOf("/storage/emulated/0/ROMs/GBA"),
            extensions = listOf("GBA", ".zip"),
            profiles = listOf("ra-mgba")
        )
    private val mgba = LibraryPresets.preset("gba")!!.profiles.first { it.id == "ra-mgba" }

    @Test
    fun firstSetupOnAnEmptyInstallationCreatesACompleteSet() = runBlocking {
        val volume = Files.createTempDirectory("sortie-volume").toFile()
        val dir = File(volume, "sortie")
        val s = store(dir)
        val empty = s.load()
        assertFalse(empty.exists)
        assertEquals(emptyList<String>(), empty.errors)
        assertFalse(dir.exists()) // loading never creates anything

        val preset = LibraryPresets.preset("psx")!!
        val saved = s.saveSystem(empty, preset.system.copy(roots = listOf("${volume.path}/psx", "/storage/1234-5678/psx/")), preset.profiles)
        assertTrue(saved.exists)
        assertCompleteSet(dir)
        val device = json.decodeFromString(DeviceManifest.serializer(), File(dir, "device.json").readText())
        assertEquals(volume.path, device.root)
        val systems = json.decodeFromString(SystemsManifest.serializer(), File(dir, "systems.json").readText())
        assertEquals(listOf("${volume.path}/psx", "/storage/1234-5678/psx"), systems.systems.single().roots)
        assertEquals(preset.profiles, json.decodeFromString(LaunchersManifest.serializer(), File(dir, "launchers.json").readText()).profiles)
        assertNotEquals(empty.fingerprint, saved.fingerprint)
        assertEquals(saved, s.load())
    }

    @Test
    fun missingStorageIsNotSilentlyCreatedElsewhere() = runBlocking {
        val gone = File(Files.createTempDirectory("sortie").toFile(), "unmounted-volume/sortie")
        val s = store(gone)
        try {
            s.saveSystem(s.load(), gba, listOf(mgba))
            fail("saved onto a missing volume")
        } catch (e: IOException) {
        }
        assertFalse(gone.parentFile!!.exists())
    }

    @Test
    fun editingOneSystemPreservesEverythingElse() = runBlocking {
        val dir = desktopCard()
        val before = bytes(dir)
        val ps2Before = entry(File(dir, "systems.json"), "systems", "ps2")
        val launchersBefore = obj(File(dir, "launchers.json"))
        val s = store(dir)
        val config = s.load()
        assertTrue(config.exists)
        assertEquals(emptyList<String>(), config.errors)
        val snes = config.systems.first { it.id == "snes" }
        s.saveSystem(config, snes.copy(label = "Super Famicom", roots = snes.roots + "/storage/emulated/0/snes-hacks"), emptyList())

        val snesAfter = entry(File(dir, "systems.json"), "systems", "snes")!!
        assertEquals("Super Famicom", snesAfter["label"]!!.jsonPrimitive.content)
        assertEquals(listOf("roms/snes", "/storage/emulated/0/snes-hacks"), snesAfter["roots"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("15", snesAfter["count_hint"].toString()) // unknown per-system key kept
        assertEquals(ps2Before, entry(File(dir, "systems.json"), "systems", "ps2")) // unedited system untouched
        val systemsAfter = obj(File(dir, "systems.json"))
        assertEquals("7", systemsAfter["desktopVersion"].toString())
        assertEquals(obj(File(dir, "systems.json"))["library"], Json.parseToJsonElement(before.getValue("systems.json")).jsonObject["library"])
        assertEquals(launchersBefore, obj(File(dir, "launchers.json"))) // no profile passed: launchers.json not rewritten
        for (name in listOf("device.json", "launchers.json", "folders.json", "guide.json", "user.json", "playlog.jsonl")) {
            assertEquals(name, before.getValue(name), File(dir, name).readText())
        }
        assertCompleteSet(dir)
    }

    @Test
    fun repeatedAddEditRemoveKeepsCustomProfilesAndPersonalData() = runBlocking {
        val dir = desktopCard()
        val personal = listOf("user.json", "playlog.jsonl", "guide.json", "folders.json").associateWith { File(dir, it).readText() }
        val myProfileBefore = entry(File(dir, "launchers.json"), "profiles", "my-snes")
        val s = store(dir)
        var config = s.load()

        config = s.saveSystem(config, gba, listOf(mgba, LaunchProfile(id = "open-with", label = "Open with…", generic = true)))
        val added = config.systems.first { it.id == "gba" }
        assertEquals(listOf(".gba", ".zip"), added.extensions) // normalised
        assertEquals(32, added.order) // after the desktop's highest order (31)
        assertEquals(myProfileBefore, entry(File(dir, "launchers.json"), "profiles", "my-snes"))

        config = s.saveSystem(config, added.copy(roots = added.roots + "/storage/1234-5678/gba", profiles = listOf("my-snes", "ra-mgba")), emptyList())
        assertEquals(listOf("my-snes", "ra-mgba"), config.systems.first { it.id == "gba" }.profiles)
        assertEquals(2, config.systems.first { it.id == "gba" }.roots.size)

        // a custom profile edited on purpose replaces only that profile and keeps its unknown keys
        val mine = config.profiles.first { it.id == "my-snes" }
        config = s.saveSystem(config, config.systems.first { it.id == "snes" }, listOf(mine.copy(label = "Snes9x (tuned)")))
        val edited = entry(File(dir, "launchers.json"), "profiles", "my-snes")!!
        assertEquals("Snes9x (tuned)", edited["label"]!!.jsonPrimitive.content)
        assertEquals("\"desktop note\"", edited["comment"].toString())

        config = s.removeSystem(config, "snes")
        config = s.removeSystem(config, "gba")
        assertEquals(listOf("ps2"), config.systems.map { it.id })
        // removing systems never removes profiles or personal records
        assertTrue(config.profiles.map { it.id }.containsAll(listOf("my-snes", "ra-mgba", "armsx2-uri", "open-with")))
        personal.forEach { (name, text) -> assertEquals(name, text, File(dir, name).readText()) }

        // re-adding with the same id gets the same game ids back (snes/<file>) and the personal data still applies
        config = s.saveSystem(config, SystemDef(id = "snes", label = "SNES", roots = listOf("roms/snes"), profiles = listOf("my-snes")), emptyList())
        assertEquals(listOf("ps2", "snes"), config.systems.map { it.id })
        assertCompleteSet(dir)
    }

    @Test
    fun supportPathsChangeOnlyThoseDeviceKeys() = runBlocking {
        val dir = desktopCard()
        val deviceBefore = obj(File(dir, "device.json"))
        val s = store(dir)
        var config = s.saveSupportPaths(s.load(), "/storage/emulated/0/RetroArch/system/", listOf("/storage/emulated/0/saves", "saves2", "saves2"))
        assertEquals("/storage/emulated/0/RetroArch/system", config.biosDir)
        assertEquals(listOf("/storage/emulated/0/saves", "saves2"), config.saves)
        val after = obj(File(dir, "device.json"))
        for (key in listOf("device", "root", "screens", "shizuku", "futureKey")) assertEquals(key, deviceBefore[key], after[key])
        config = s.saveSupportPaths(config, null, emptyList())
        assertNull(config.biosDir)
        assertEquals(emptyList<String>(), config.saves)
        assertFalse("biosDir" in obj(File(dir, "device.json")))
    }

    @Test
    fun aNoOpSaveWritesNothing() = runBlocking {
        val dir = desktopCard()
        val before = dir.listFiles()!!.associate { it.name to it.lastModified() to it.readText() }
        val s = store(dir)
        val config = s.load()
        val after = s.saveSystem(config, config.systems.first { it.id == "ps2" }, config.profiles)
        assertEquals(config, after)
        assertEquals(before, dir.listFiles()!!.associate { it.name to it.lastModified() to it.readText() })
    }

    @Test
    fun corruptConfigurationIsReportedAndNeverOverwritten() = runBlocking {
        val dir = desktopCard()
        File(dir, "systems.json").writeText("{\"systems\": [ {\"id\": \"ps2\", ")
        val before = bytes(dir)
        val s = store(dir)
        val config = s.load()
        assertTrue(config.exists)
        for (attempt in listOf<suspend () -> Unit>(
            { s.saveSystem(config, gba, listOf(mgba)) },
            { s.removeSystem(config, "ps2") },
            { s.saveSupportPaths(config, "/bios", emptyList()) }
        )) {
            try {
                attempt()
                fail("saved over a corrupt systems.json")
            } catch (e: IOException) {
            }
        }
        assertEquals(before, bytes(dir))
    }

    @Test
    fun aMissingFileIsCreatedNotTreatedAsCorrupt() = runBlocking {
        val dir = desktopCard()
        File(dir, "launchers.json").delete()
        File(dir, "folders.json").delete()
        val s = store(dir)
        val config = s.load()
        assertEquals(emptyList<String>(), config.errors)
        s.saveSystem(config, gba, listOf(mgba))
        assertEquals(listOf("ra-mgba"), json.decodeFromString(LaunchersManifest.serializer(), File(dir, "launchers.json").readText()).profiles.map { it.id })
        assertCompleteSet(dir)
    }

    @Test
    fun savingFoldersUpsertsByIdAndKeepsEverythingElse() = runBlocking {
        val dir = desktopCard()
        File(dir, "folders.json").writeText(
            """{"folders": [{"id": "shmups", "label": "Shmups", "include": ["snes/a.sfc"], "note": "desktop"},
              |{"id": "rpgs", "label": "RPGs", "sort": "title:asc", "colour": "#ff0000"}], "version": 2}
            """.trimMargin()
        )
        val folders = File(dir, "folders.json")
        val shmupsBefore = entry(folders, "folders", "shmups")
        val others = bytes(dir) - "folders.json"
        val s = store(dir)
        val saved = s.saveFolders(
            s.load(),
            listOf(
                FolderDef("rpgs", "Role-playing", include = listOf("snes/b.sfc")),
                FolderDef("cocoon-1", "From Cocoon", include = listOf("ps2/x.iso"))
            )
        )
        assertEquals(shmupsBefore, entry(folders, "folders", "shmups")) // untouched, unknown key included
        val rpgs = entry(folders, "folders", "rpgs")!!
        assertEquals("Role-playing", rpgs["label"]!!.jsonPrimitive.content)
        assertEquals("#ff0000", rpgs["colour"]!!.jsonPrimitive.content) // unknown key of an edited entry kept
        assertEquals(listOf("shmups", "rpgs", "cocoon-1"), obj(folders)["folders"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals("2", obj(folders)["version"].toString())
        assertEquals(listOf("ps2/x.iso"), json.decodeFromString(FoldersManifest.serializer(), folders.readText()).folders.first { it.id == "cocoon-1" }.include)
        assertEquals(others, bytes(dir) - "folders.json")
        assertCompleteSet(dir)

        // the same folders again change nothing
        val again = bytes(dir)
        assertEquals(saved, s.saveFolders(saved, listOf(FolderDef("cocoon-1", "From Cocoon", include = listOf("ps2/x.iso")))))
        assertEquals(again, bytes(dir))
    }

    @Test
    fun reservedOrInvalidFolderIdsAreRejectedWithoutWriting() = runBlocking {
        val dir = desktopCard()
        val before = bytes(dir)
        val s = store(dir)
        val config = s.load()
        for (id in listOf(FAVOURITES_FOLDER_ID, WANT_TO_PLAY_FOLDER_ID, FINISHED_FOLDER_ID, SHELVED_FOLDER_ID, "Bad Id")) {
            try {
                s.saveFolders(config, listOf(FolderDef("cocoon-1", "Fine"), FolderDef(id, "Clash")))
                fail("accepted folder id $id")
            } catch (e: IllegalArgumentException) {
            }
        }
        assertEquals(before, bytes(dir))
    }

    @Test
    fun aMissingFoldersFileIsScaffoldedAndABrokenOneNeverOverwritten() = runBlocking {
        val dir = desktopCard()
        File(dir, "folders.json").delete()
        val s = store(dir)
        s.saveFolders(s.load(), listOf(FolderDef("cocoon-1", "From Cocoon", include = listOf("snes/a.sfc"))))
        assertEquals(
            listOf(FolderDef("cocoon-1", "From Cocoon", include = listOf("snes/a.sfc"))),
            json.decodeFromString(FoldersManifest.serializer(), File(dir, "folders.json").readText()).folders
        )
        assertCompleteSet(dir)

        File(dir, "folders.json").writeText("{\"folders\": [ {\"id\": ")
        val before = bytes(dir)
        val config = s.load()
        try {
            s.saveFolders(config, listOf(FolderDef("cocoon-2", "More")))
            fail("saved over a broken folders.json")
        } catch (e: IOException) {
        }
        s.saveSystem(config, gba, listOf(mgba)) // other edits still work and leave it alone
        assertEquals(before["folders.json"], File(dir, "folders.json").readText())
    }

    /** The default Favourites entry older builds wrote, as the example card had it and as the format docs showed it (no sort). */
    private val legacyFavourites = """{"id": "favourites", "label": "Favourites", "rule": {"favourite": true}, "apps": true, "sort": "title:asc"}"""
    private val legacyFavouritesDocs = """{"id": "favourites", "label": "Favourites", "rule": { "favourite": true }, "apps": true}"""
    private val shmups = """{"id": "shmups", "label": "Shmups", "include": ["snes/a.sfc"], "note": "desktop"}"""

    private fun foldersFile(dir: File, vararg entries: String) =
        File(dir, "folders.json").apply { writeText("""{"folders": [${entries.joinToString(",\n")}], "version": 2}""") }

    private fun ids(folders: File) = obj(folders)["folders"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    @Test
    fun legacyFavouritesEntriesAreCountedAndRemovedLosslessly() = runBlocking {
        val dir = desktopCard()
        val folders = foldersFile(
            dir,
            legacyFavourites,
            shmups,
            """{"id": "cocoon-7", "label": "Pixel Art", "include": ["snes/b.sfc"]}""",
            legacyFavouritesDocs,
            """{"id": "explicit", "label": "Favourites", "rule": {"favourite": true}, "apps": true}""",
            // the same default with every other key written out at its default value
            """{"id": "favourites", "label": "Favourites", "rule": {"favourite": true}, "include": [], "exclude": [], "apps": true, "limit": 0}"""
        )
        val kept = listOf("shmups", "cocoon-7", "explicit").associateWith { entry(folders, "folders", it) }
        val before = bytes(dir)
        val s = store(dir)
        val config = s.load()
        assertEquals(emptyList<String>(), config.errors)
        assertEquals(3, config.legacyFavourites)
        assertEquals(before, bytes(dir)) // loading never tidies
        val others = before - "folders.json"

        val tidied = s.removeLegacyFavourites(config)
        assertEquals(0, tidied.legacyFavourites)
        assertEquals(config.systems, tidied.systems)
        assertEquals(config.profiles, tidied.profiles)
        assertEquals(listOf("shmups", "cocoon-7", "explicit"), ids(folders)) // order kept, only the default entries gone
        kept.forEach { (id, before) -> assertEquals(id, before, entry(folders, "folders", id)) } // unknown keys included
        assertEquals("2", obj(folders)["version"].toString())
        assertEquals(others, bytes(dir) - "folders.json") // user.json (the favourites themselves), playlog, other manifests
        assertCompleteSet(dir)

        // nothing left to remove: no file changes
        val again = dir.listFiles()!!.associate { it.name to it.lastModified() to it.readText() }
        assertEquals(tidied, s.removeLegacyFavourites(tidied))
        assertEquals(again, dir.listFiles()!!.associate { it.name to it.lastModified() to it.readText() })
    }

    @Test
    fun customisedReservedFavouritesEntriesAreNeitherCountedNorRemoved() = runBlocking {
        val variants = listOf(
            """{"id": "favourites", "label": "My favourites", "rule": {"favourite": true}, "apps": true}""",
            """{"id": "favourites", "rule": {"favourite": true}, "apps": true}""",
            """{"id": "favourites", "label": "Favourites", "rule": {"favourite": true, "system": "snes"}, "apps": true}""",
            """{"id": "favourites", "label": "Favourites", "rule": {"favourite": "true"}, "apps": true}""",
            """{"id": "favourites", "label": "Favourites", "rule": {"favourite": false}, "apps": true}""",
            """{"id": "favourites", "label": "Favourites", "rule": {"favourite": true}}""",
            """{"id": "favourites", "label": "Favourites", "rule": {"favourite": true}, "apps": false}""",
            """{"id": "favourites", "label": "Favourites", "rule": {"favourite": true}, "apps": true, "sort": "fit:desc"}""",
            """{"id": "favourites", "label": "Favourites", "rule": {"favourite": true}, "apps": true, "include": ["snes/a.sfc"]}""",
            """{"id": "favourites", "label": "Favourites", "rule": {"favourite": true}, "apps": true, "exclude": ["snes/a.sfc"]}""",
            """{"id": "favourites", "label": "Favourites", "rule": {"favourite": true}, "apps": true, "limit": 10}""",
            """{"id": "favourites", "label": "Favourites", "rule": {"favourite": true}, "apps": true, "colour": "#ff0000"}""",
            """{"id": "favourites", "label": "Favourites", "include": ["snes/a.sfc"], "apps": true}"""
        )
        for (variant in variants) {
            val dir = desktopCard()
            val folders = foldersFile(dir, shmups, variant)
            val before = bytes(dir)
            val s = store(dir)
            val config = s.load()
            assertEquals(variant, emptyList<String>(), config.errors)
            assertEquals(variant, 0, config.legacyFavourites)
            s.removeLegacyFavourites(config)
            assertEquals(variant, before, bytes(dir))

            // beside a removable default entry, only that entry goes
            foldersFile(dir, variant, legacyFavourites, shmups)
            val custom = obj(folders)["folders"]!!.jsonArray[0]
            val mixed = s.load()
            assertEquals(variant, 1, mixed.legacyFavourites)
            assertEquals(variant, 0, s.removeLegacyFavourites(mixed).legacyFavourites)
            assertEquals(variant, listOf(custom, Json.parseToJsonElement(shmups)), obj(folders)["folders"]!!.jsonArray.toList())
        }
    }

    /** A partial configuration (systems.json only) is not completed by a tidy-up: only folders.json is ever written. */
    @Test
    fun legacyFavouritesCleanupOfAPartialConfigurationWritesOnlyFoldersJson() = runBlocking {
        val dir = desktopCard()
        for (name in listOf("device.json", "launchers.json", "guide.json", "folders.json")) File(dir, name).delete()
        val s = store(dir)

        // no folders.json and nothing to remove: no file is created
        val none = bytes(dir)
        val missing = s.load()
        assertTrue(missing.exists)
        assertEquals(0, missing.legacyFavourites)
        assertEquals(missing, s.removeLegacyFavourites(missing))
        assertEquals(none, bytes(dir))

        // a folders.json without the default entry: nothing changes
        val folders = foldersFile(dir, shmups)
        val clean = bytes(dir)
        val unchanged = s.load()
        assertEquals(0, unchanged.legacyFavourites)
        s.removeLegacyFavourites(unchanged)
        assertEquals(clean, bytes(dir))

        // with it: folders.json loses that entry and is the only file written; no missing manifest is added
        foldersFile(dir, legacyFavourites, shmups)
        val before = bytes(dir)
        val config = s.load()
        assertEquals(1, config.legacyFavourites)
        val tidied = s.removeLegacyFavourites(config)
        assertEquals(0, tidied.legacyFavourites)
        assertEquals(config.systems, tidied.systems)
        assertEquals(before.keys, bytes(dir).keys)
        assertEquals(before - "folders.json", bytes(dir) - "folders.json")
        assertEquals(listOf("shmups"), ids(folders))
        assertEquals(entry(folders, "folders", "shmups"), Json.parseToJsonElement(shmups))
        assertEquals("2", obj(folders)["version"].toString())
        json.decodeFromString(FoldersManifest.serializer(), folders.readText())
        assertEquals(emptyList<String>(), dir.list()!!.filter { it.endsWith(ManifestCommit.STAGED_SUFFIX) || it.startsWith(ManifestCommit.JOURNAL) })
    }

    @Test
    fun legacyFavouritesAreNeverRemovedFromAnUnreadableOrChangedFile() = runBlocking {
        // a folders.json the app cannot load (an entry without an id) is reported, not counted, and never rewritten
        val dir = desktopCard()
        foldersFile(dir, legacyFavourites, """{"label": "No id"}""")
        val broken = bytes(dir)
        val s = store(dir)
        val config = s.load()
        assertTrue(config.errors.toString(), config.errors.any { it.startsWith("folders.json") })
        assertEquals(0, config.legacyFavourites)
        try {
            s.removeLegacyFavourites(config)
            fail("rewrote an unreadable folders.json")
        } catch (e: IOException) {
        }
        assertEquals(broken, bytes(dir))

        // a desktop edit after loading wins
        foldersFile(dir, legacyFavourites, shmups)
        val loaded = s.load()
        assertEquals(1, loaded.legacyFavourites)
        File(dir, "folders.json").appendText("\n")
        val edited = bytes(dir)
        try {
            s.removeLegacyFavourites(loaded)
            fail("removed from a changed folders.json")
        } catch (e: LibraryConflictException) {
        }
        assertEquals(edited, bytes(dir))

        // a failed write changes nothing and the same snapshot can be retried
        val fresh = s.load()
        try {
            store(dir, FaultyIo(at = 1, crash = false)).removeLegacyFavourites(fresh)
            fail("write should fail")
        } catch (e: IOException) {
        }
        assertEquals(edited, bytes(dir))
        assertEquals(0, s.removeLegacyFavourites(fresh).legacyFavourites)
        assertEquals(listOf("shmups"), ids(File(dir, "folders.json")))

        // without a library configuration nothing is counted, and removing creates no files
        val bare = File(Files.createTempDirectory("sortie-card").toFile(), "sortie").apply { mkdirs() }
        foldersFile(bare, legacyFavourites)
        val bareBefore = bytes(bare)
        val none = store(bare).load()
        assertFalse(none.exists)
        assertEquals(0, none.legacyFavourites)
        try {
            store(bare).removeLegacyFavourites(none)
            fail("created a configuration while tidying")
        } catch (e: IOException) {
        }
        assertEquals(bareBefore, bytes(bare))
    }

    @Test
    fun aConcurrentDesktopEditIsDetected() = runBlocking {
        val dir = desktopCard()
        val s = store(dir)
        val config = s.load()
        File(dir, "launchers.json").appendText("\n") // any byte change, e.g. a desktop sync
        val before = bytes(dir)
        try {
            s.saveSystem(config, gba, listOf(mgba))
            fail("saved over a newer card")
        } catch (e: LibraryConflictException) {
        }
        assertEquals(before, bytes(dir))
        // the guide and folders count too: they are created when missing, so a racing creation must not be overwritten
        val fresh = s.load()
        File(dir, "guide.json").writeText("{\"built\": \"later\", \"games\": {}}")
        try {
            s.removeSystem(fresh, "ps2")
            fail("saved after guide.json changed")
        } catch (e: LibraryConflictException) {
        }
    }

    @Test
    fun concurrentSavesFromTheSameSnapshotLetExactlyOneWin() = runBlocking {
        val dir = desktopCard()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocking = object : CommitIo() {
            override fun write(file: File, bytes: ByteArray) {
                if (entered.count > 0L) {
                    entered.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
                super.write(file, bytes)
            }
        }
        val first = store(dir, blocking)
        val second = store(dir)
        val config = first.load()
        val a = async(Dispatchers.IO) { first.saveSystem(config, gba, listOf(mgba)) }
        assertTrue(entered.await(10, TimeUnit.SECONDS)) // the first save holds the commit lock mid-write
        val b = async(Dispatchers.IO) { runCatching { second.removeSystem(config, "ps2") } }
        release.countDown()
        val winner = a.await()
        val loser = b.await().exceptionOrNull()
        assertTrue("$loser", loser is LibraryConflictException)
        assertEquals(listOf("ps2", "snes", "gba"), winner.systems.map { it.id })
        assertEquals(winner, second.load())
    }

    @Test
    fun aFailedWriteChangesNothingAndCanBeRetried() = runBlocking {
        val dir = desktopCard()
        val before = bytes(dir)
        val s = store(dir, FaultyIo(at = 1, crash = false))
        val config = s.load()
        try {
            s.saveSystem(config, gba, listOf(mgba))
            fail("write should fail")
        } catch (e: IOException) {
        }
        assertEquals(before, bytes(dir))
        // the caller's draft and snapshot are still valid: a retry with working storage succeeds
        val retried = store(dir).saveSystem(config, gba, listOf(mgba))
        assertTrue(retried.systems.any { it.id == "gba" })
    }

    @Test
    fun anInterruptedAcceptedSaveReturnsTheCommittedLibrary() = runBlocking {
        val dir = desktopCard()
        val s = store(dir, FaultyIo(at = 1, crash = false, only = "rename"))
        val config = s.saveSystem(s.load(), gba, listOf(mgba))
        assertEquals(emptyList<String>(), config.errors)
        assertTrue(config.systems.any { it.id == "gba" })
        assertTrue(config.profiles.any { it.id == "ra-mgba" })
        assertCompleteSet(dir)
    }

    @Test
    fun aCrashAtAnyStepLeavesTheOldOrTheNewConfigurationWhole() = runBlocking {
        val reference = desktopCard()
        val oldSystems = obj(File(reference, "systems.json"))
        val oldLaunchers = obj(File(reference, "launchers.json"))
        var step = 0
        while (true) {
            val dir = desktopCard()
            val crashed = try {
                store(dir, FaultyIo(at = step)).saveSystem(store(dir).load(), gba, listOf(mgba))
                false
            } catch (e: SimulatedCrash) {
                true
            }
            if (!crashed) break
            val config = store(dir).load() // recovers
            assertEquals(emptyList<String>(), config.errors)
            val newSystems = obj(File(dir, "systems.json"))
            val newLaunchers = obj(File(dir, "launchers.json"))
            val hasGba = config.systems.any { it.id == "gba" }
            assertEquals("step $step: systems/launchers must change together", hasGba, config.profiles.any { it.id == "ra-mgba" })
            if (!hasGba) {
                assertEquals(oldSystems, newSystems)
                assertEquals(oldLaunchers, newLaunchers)
            }
            assertCompleteSet(dir)
            step++
        }
    }

    @Test
    fun cancellationBeforeTheCommitChangesNothing() = runBlocking {
        val dir = desktopCard()
        val before = bytes(dir)
        val s = store(dir)
        val config = s.load()
        val reached = AtomicBoolean(false)
        val job = launch(Dispatchers.IO) {
            currentCoroutineContext().job.cancel() // the caller left the screen before the save started
            s.saveSystem(config, gba, listOf(mgba))
            reached.set(true)
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(reached.get())
        assertEquals(before, bytes(dir))
    }

    @Test
    fun cancellationDuringTheCommitFinishesItWhole() = runBlocking {
        val dir = desktopCard()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val io = object : CommitIo() {
            override fun write(file: File, bytes: ByteArray) {
                if (entered.count > 0L) {
                    entered.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
                super.write(file, bytes)
            }
        }
        val s = store(dir, io)
        val config = s.load()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = scope.launch { s.saveSystem(config, gba, listOf(mgba)) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        scope.cancel()
        release.countDown()
        job.join()
        assertTrue(job.isCancelled)
        val after = store(dir).load()
        assertTrue(after.systems.any { it.id == "gba" } && after.profiles.any { it.id == "ra-mgba" })
        assertCompleteSet(dir)
    }

    @Test
    fun invalidInputIsRejectedWithoutWriting() = runBlocking {
        val dir = desktopCard()
        val before = bytes(dir)
        val s = store(dir)
        val config = s.load()
        val bad = listOf(
            gba.copy(profiles = listOf("not-configured")) to listOf(mgba),
            gba.copy(roots = listOf("  ")) to listOf(mgba),
            gba.copy(roots = emptyList()) to listOf(mgba),
            gba.copy(extensions = listOf("iso files")) to listOf(mgba),
            gba.copy(id = "Bad/Id") to listOf(mgba),
            gba.copy(label = " ") to listOf(mgba),
            gba.copy(colour = "blue") to listOf(mgba),
            gba to listOf(mgba.copy(`package` = null)),
            gba to listOf(mgba, mgba)
        )
        for ((system, profiles) in bad) {
            try {
                s.saveSystem(config, system, profiles)
                fail("accepted $system / ${profiles.map { it.id }}")
            } catch (e: IllegalArgumentException) {
            }
        }
        try {
            s.removeSystem(config, "nope")
            fail("removed a missing system")
        } catch (e: IllegalArgumentException) {
        }
        assertEquals(before, bytes(dir))
    }

    @Test
    fun existingIdsOutsideTheNewIdRulesStayEditable() = runBlocking {
        val dir = desktopCard()
        File(dir, "systems.json").writeText("""{"systems": [{"id": "PS2 Disc", "roots": ["a"], "profiles": ["open-with"], "colour": "orange"}]}""")
        val s = store(dir)
        val config = s.load()
        val saved = s.saveSystem(config, config.systems.single().copy(label = "PS2"), emptyList())
        assertEquals("PS2 Disc", saved.systems.single().id)
        assertEquals("orange", saved.systems.single().colour)
    }

    /** ManifestStore loads under the commit lock after recovery, so it never publishes a mixed set. */
    @Test
    fun manifestStoreFinishesAnInterruptedCommitBeforePublishing() = runBlocking {
        val dir = desktopCard()
        try {
            store(dir, FaultyIo(at = 5)).saveSystem(store(dir).load(), gba, listOf(mgba)) // systems.json renamed, launchers.json staged
            fail("should crash")
        } catch (e: SimulatedCrash) {
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val snapshot = ManifestStore(dir, scope).snapshot.value
            assertTrue(snapshot.systems.any { it.id == "gba" })
            assertTrue("ra-mgba" in snapshot.profiles)
            assertTrue(snapshot.errors.toString(), snapshot.errors.isEmpty())
            assertCompleteSet(dir)
        } finally {
            scope.cancel()
        }
    }

    /** systems.json already new, launchers.json's pending copy damaged: no consumer may see gba without its profile. */
    @Test
    fun aPartialCommitWithADamagedCopyIsNeverPublishedOrWrittenOver() = runBlocking {
        val dir = desktopCard()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val manifests = ManifestStore(dir, scope)
            val good = manifests.snapshot.value
            assertEquals(listOf("ps2", "snes"), good.systems.map { it.id })
            try {
                store(dir, FaultyIo(at = 5)).saveSystem(store(dir).load(), gba, listOf(mgba)) // systems.json renamed, launchers.json staged
                fail("should crash")
            } catch (e: SimulatedCrash) {
            }
            File(dir, "launchers.json" + ManifestCommit.STAGED_SUFFIX).writeText("{\"profiles\": []}")
            val onDisk = bytes(dir)

            manifests.reloadNow()
            val kept = manifests.snapshot.value
            assertEquals(good.systems, kept.systems) // the last complete set, not the new systems.json beside the old launchers
            assertEquals(good.profiles, kept.profiles)
            assertTrue(kept.errors.toString(), kept.errors.single().contains("launchers.json"))

            // a fresh process has no complete set yet: it publishes no library rather than a mixed one
            val fresh = ManifestStore(dir, scope).snapshot.value
            assertTrue(fresh.systems.isEmpty() && fresh.profiles.isEmpty())
            assertTrue(fresh.errors.isNotEmpty())

            // setup shows no mixed configuration and refuses to write
            val config = store(dir).load()
            assertTrue(config.systems.isEmpty() && config.profiles.isEmpty())
            assertTrue(config.errors.isNotEmpty())
            try {
                store(dir).saveSupportPaths(config, "/bios", emptyList())
                fail("wrote over an inconsistent interrupted change")
            } catch (e: IOException) {
            }
            assertEquals(onDisk, bytes(dir))
        } finally {
            scope.cancel()
        }
    }

    /** A desktop edit of a file whose on-device change was still pending wins: it is never overwritten or mixed in. */
    @Test
    fun aPendingChangeNeverOverwritesADesktopEditOfItsTarget() = runBlocking {
        val dir = desktopCard()
        try {
            store(dir, FaultyIo(at = 4)).saveSystem(store(dir).load(), gba, listOf(mgba)) // decided, nothing renamed yet
            fail("should crash")
        } catch (e: SimulatedCrash) {
        }
        val desktop = File(dir, "launchers.json").readText().replace("My tuned Snes9x", "Edited on the desktop")
        File(dir, "launchers.json").writeText(desktop)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val snapshot = ManifestStore(dir, scope).snapshot.value
            assertFalse(snapshot.systems.any { it.id == "gba" })
            assertTrue(snapshot.errors.isNotEmpty())
        } finally {
            scope.cancel()
        }
        val config = store(dir).load() // recovery runs again
        assertTrue(config.errors.isNotEmpty())
        assertEquals(desktop, File(dir, "launchers.json").readText())
        assertNull(entry(File(dir, "systems.json"), "systems", "gba"))
    }

    @Test
    fun everyPresetSavesAndLoadsAsTheAppReadsIt() = runBlocking {
        val dir = File(Files.createTempDirectory("sortie-presets").toFile(), "sortie")
        val s = store(dir)
        var config = s.load()
        for (preset in LibraryPresets.systems) {
            val existing = config.profiles.associateBy { it.id }
            val profiles = preset.profiles.map { existing[it.id] ?: it }
            config = s.saveSystem(config, preset.system.copy(roots = listOf("/storage/emulated/0/ROMs/${preset.system.id}")), profiles)
        }
        assertEquals(LibraryPresets.systems.map { it.system.id }, config.systems.map { it.id })
        assertCompleteSet(dir)
        val launchers = json.decodeFromString(LaunchersManifest.serializer(), File(dir, "launchers.json").readText())
        assertEquals(launchers.profiles.map { it.id }.toSet().size, launchers.profiles.size)
    }
}
