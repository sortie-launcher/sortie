package io.github.esko997.sortie.data.user

import io.github.esko997.sortie.data.manifest.ControllerSupport
import io.github.esko997.sortie.data.manifest.GameAnnotation
import io.github.esko997.sortie.data.manifest.GameBookmark
import io.github.esko997.sortie.data.manifest.UserData
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserStoreTest {
    private fun fresh(): File = File(Files.createTempDirectory("sortie").toFile(), "user.json")

    /** A burst of edits must leave the final state on disk, not whichever write happened to finish last. */
    @Test
    fun rapidUpdatesPersistTheLatestState() = runBlocking {
        val file = fresh()
        val store = UserStore(file, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        val ids = (1..200).map { "game$it" }
        ids.forEach { store.addToLayout(it) }
        ids.take(50).forEach { store.toggleFavourite(it) }
        store.setLayout(ids.reversed())
        store.setHomeSort("custom")
        val expected = store.data.value
        withTimeout(5_000) { store.written.first { it == expected } }
        val onDisk = Json { ignoreUnknownKeys = true }.decodeFromString(UserData.serializer(), file.readText())
        assertEquals(expected, onDisk)
        assertEquals(ids.reversed(), onDisk.layout)
        assertEquals(ids.take(50).toSet(), onDisk.favourites)
        assertEquals(expected, UserStore(file).data.value)
    }

    /** An edit made before the writer coroutine ever ran must still be saved once it runs. */
    @Test
    fun editBeforeTheWriterStartsIsSaved() {
        val file = fresh()
        val gate = Gate()
        val store = UserStore(file, CoroutineScope(SupervisorJob() + gate))
        store.setHomeSort("custom")
        assertEquals(false, file.exists())
        gate.open()
        assertEquals("custom", UserStore(file).data.value.homeSort)
    }

    /** The browse header mode survives a reload; a value no build knows is stored as "auto", never as-is. */
    @Test
    fun browseChromePersistsAndUnknownValuesBecomeAuto() = runBlocking {
        val file = fresh()
        val store = UserStore(file, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        store.setBrowseChrome("compact")
        withTimeout(5_000) { store.written.first { it.browseChrome == "compact" } }
        assertEquals("compact", UserStore(file).data.value.browseChrome)
        store.setBrowseChrome("sideways")
        assertEquals("auto", store.data.value.browseChrome)
        withTimeout(5_000) { store.written.first { it.browseChrome == "auto" } }
        assertEquals("auto", UserStore(file).data.value.browseChrome)
    }

    /** A durable save is on disk when it returns and published only then. */
    @Test
    fun commitWritesBeforePublishing() = runBlocking {
        val file = fresh()
        val store = UserStore(file, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        val note = GameAnnotation(ControllerSupport.POINTER)
        store.saveGameAnnotation("snes/a.sfc", note)
        assertEquals(note, UserStore(file).data.value.gameAnnotations["snes/a.sfc"])
        store.saveGameAnnotation("snes/a.sfc", GameAnnotation())
        assertEquals(emptyMap<String, GameAnnotation>(), UserStore(file).data.value.gameAnnotations)
    }

    /** One bookmark per game: a save replaces the previous text, is trimmed and timed; a blank save (Clear) removes it. */
    @Test
    fun bookmarkSaveReplacesAndBlankRemoves() = runBlocking {
        val file = fresh()
        var now = 1_000L
        val store = UserStore(file, CoroutineScope(SupervisorJob() + Dispatchers.IO), clock = { now })
        store.saveBookmark("snes/a.sfc", "  World 2, the ice cave  ")
        assertEquals(mapOf("snes/a.sfc" to GameBookmark("World 2, the ice cave", 1_000)), UserStore(file).data.value.bookmarks)
        now = 2_000
        store.saveBookmark("snes/a.sfc", "World 3 boss")
        assertEquals(mapOf("snes/a.sfc" to GameBookmark("World 3 boss", 2_000)), UserStore(file).data.value.bookmarks)
        store.saveBookmark("snes/a.sfc", " \n ")
        assertEquals(emptyMap<String, GameBookmark>(), UserStore(file).data.value.bookmarks)
    }

    /** An over-long bookmark is refused whole: the saved one stays as it was, nothing is truncated. */
    @Test
    fun overLongBookmarkIsRefusedAndTheSavedOneKept() = runBlocking {
        val file = fresh()
        val store = UserStore(file, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        store.saveBookmark("snes/a.sfc", "keep me")
        assertTrue(runCatching { store.saveBookmark("snes/a.sfc", "x".repeat(2_001)) }.isFailure)
        assertEquals("keep me", UserStore(file).data.value.bookmarks["snes/a.sfc"]?.text)
    }

    /**
     * An older user.json: its journal, mapping notes and "where was I?" notes are archived (readable and exact) before the file
     * is rewritten; the note becomes the bookmark, the controller entry stays, unknown fields of the journal survive in the archive.
     */
    @Test
    fun legacyUserJsonIsArchivedThenMigrated() = runBlocking {
        val file = fresh()
        file.writeText(LEGACY)
        val store = UserStore(file, CoroutineScope(SupervisorJob() + Dispatchers.IO), clock = { 5_000L })
        val loaded = store.data.value
        assertEquals(mapOf("snes/a.sfc" to GameBookmark("World 3, before the boss")), loaded.bookmarks)
        assertEquals(mapOf("snes/a.sfc" to GameAnnotation(ControllerSupport.GAMEPAD)), loaded.gameAnnotations)
        assertEquals(setOf("snes/a.sfc"), loaded.favourites)
        val archive = File(file.parentFile, "archive")
        val md = archive.listFiles { f -> f.name.endsWith(".md") }!!.single().readText()
        assertTrue(md, "World 3, before the boss" in md && "Y is jump" in md && "First airborne build." in md)
        assertTrue(md, "sortie/journal/media/entry/shot.png" in md)
        val exact = Json.parseToJsonElement(archive.listFiles { f -> f.name.endsWith(".json") }!!.single().readText()).jsonObject
        val entry = (exact["journalEntries"] as JsonArray).single().jsonObject
        assertEquals("kept", (entry["futureField"] as JsonPrimitive).content)
        // the rewrite drops the legacy fields; a reload neither loses the bookmark nor archives a second copy
        withTimeout(5_000) { store.written.first() }
        val rewritten = Json.parseToJsonElement(file.readText()).jsonObject
        assertFalse("journalEntries" in rewritten)
        assertFalse("returnNote" in rewritten.toString() || "mappingNotes" in rewritten.toString())
        assertEquals(loaded, UserStore(file).data.value)
        assertEquals(2, archive.list()!!.size)
    }

    /** Until the archive can be written, user.json keeps its old writing: every save fails and leaves the file as it was. */
    @Test
    fun unarchivableLegacyWritingBlocksEverySave() = runBlocking {
        val file = fresh()
        file.writeText(LEGACY)
        val bytes = file.readBytes()
        val blocked = Files.createTempFile("sortie", "not-a-folder").toFile() // a file where the archive folder should be
        val store = UserStore(file, CoroutineScope(SupervisorJob() + Dispatchers.IO), archiveDir = File(blocked, "archive"))
        assertEquals("World 3, before the boss", store.data.value.bookmarks["snes/a.sfc"]?.text)
        assertTrue(runCatching { store.commit { it.copy(homeSort = "custom") } }.isFailure)
        assertTrue(runCatching { store.saveBookmark("snes/a.sfc", "new") }.isFailure)
        assertArrayEquals(bytes, file.readBytes())
        assertNotNull(store.writeError.value)
        assertFalse(store.reloadable()) // loaded and owned: no restart loop
        assertEquals("World 3, before the boss", store.data.value.bookmarks["snes/a.sfc"]?.text)
    }

    /**
     * Exports and safety copies hold the migrated state, which lacks the old writing: the gate they call first refuses while the
     * archive cannot be written, and passes (writing it) once it can, so a backup never silently leaves that writing out.
     */
    @Test
    fun legacyArchiveGateRefusesUntilTheArchiveIsWritten() = runBlocking {
        val file = fresh()
        file.writeText(LEGACY)
        val blocked = File(file.parentFile, "archive-parent")
        assertTrue(blocked.createNewFile()) // a file where the archive's parent folder should be
        val archive = File(blocked, "archive")
        val store = UserStore(file, CoroutineScope(SupervisorJob() + Dispatchers.IO), archiveDir = archive)
        assertTrue(runCatching { store.ensureLegacyArchived() }.isFailure)
        assertTrue(runCatching { store.ensureLegacyArchived() }.isFailure) // still pending: every caller keeps being refused
        assertTrue(blocked.delete())
        store.ensureLegacyArchived()
        assertTrue(archive.listFiles { f -> f.name.endsWith(".md") }!!.single().readText().contains("First airborne build."))
        store.ensureLegacyArchived() // nothing left to archive
        // a current user.json never has anything to archive
        UserStore(fresh(), CoroutineScope(SupervisorJob() + Dispatchers.IO)).ensureLegacyArchived()
    }

    /** An ordinary edit made while a durable write is in flight is neither lost nor reordered under the durable result. */
    @Test
    fun editDuringDurableWriteIsKept() = runBlocking {
        val file = fresh()
        val store = UserStore(file, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        val note = GameAnnotation(ControllerSupport.TOUCH)
        store.commit { base ->
            store.setDockSlot(0, "app:org.ppsspp.ppsspp") // lands after the durable op read its base
            base.copy(gameAnnotations = base.gameAnnotations + ("snes/a.sfc" to note))
        }
        val published = store.data.value
        assertEquals(note, published.gameAnnotations["snes/a.sfc"])
        assertEquals("app:org.ppsspp.ppsspp", published.dock.first())
        withTimeout(5_000) { store.written.first { it == published } }
        assertEquals(published, UserStore(file).data.value)
    }

    /** When user.json cannot be written, a durable save throws and publishes nothing; edits made meanwhile still stand. */
    @Test
    fun failedCommitPublishesNothing() = runBlocking {
        val blocker = Files.createTempFile("sortie", "file").toFile() // a regular file where the directory should be
        val store = UserStore(File(blocker, "user.json"), CoroutineScope(SupervisorJob() + Dispatchers.IO))
        val failure = runCatching {
            store.commit { base ->
                store.setHomeSort("custom")
                base.copy(bookmarks = mapOf("snes/a.sfc" to GameBookmark("World 2")))
            }
        }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(emptyMap<String, GameBookmark>(), store.data.value.bookmarks)
        assertEquals("custom", store.data.value.homeSort)
        assertNotNull(store.writeError.value)
    }

    /** A replace whose expected state is stale writes nothing and changes nothing. */
    @Test
    fun staleReplaceWritesNothing() = runBlocking {
        val file = fresh()
        val store = UserStore(file, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        val replaced = store.replace(expected = UserData(homeSort = "count"), next = UserData(favourites = setOf("x")))
        assertEquals(false, replaced)
        assertEquals(UserData(), store.data.value)
        assertEquals(false, file.exists())
    }

    /**
     * A user.json from before the queue loads with an empty queue and every other preference intact; toggling keeps favourites,
     * the layout and the rest untouched, keeps ids the index does not have and persists across a reload.
     */
    @Test
    fun wantToPlayTogglesPersistAndLeaveTheLayout() = runBlocking {
        val file = fresh()
        file.writeText(
            """{"favourites": ["snes/a.sfc"], "homeSort": "custom", "layout": ["system:snes", "apps"], "gridColumns": 7,""" +
                """ "gameAnnotations": {"snes/a.sfc": {"returnNote": "World 3"}}}"""
        )
        val store = UserStore(file, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        val before = store.data.value
        assertEquals(emptySet<String>(), before.wantToPlay)
        assertEquals(listOf("system:snes", "apps"), before.layout)

        assertTrue(store.toggleWantToPlay("snes/a.sfc"))
        assertTrue(store.toggleWantToPlay("gba/Not Indexed Yet.gba"))
        assertEquals(before.layout, store.data.value.layout) // the built-in folder is always on Custom; nothing is placed
        assertEquals(setOf("snes/a.sfc"), store.data.value.favourites)

        store.toggleFavourite("snes/a.sfc") // favourites and the queue are independent both ways
        assertEquals(setOf("snes/a.sfc", "gba/Not Indexed Yet.gba"), store.data.value.wantToPlay)
        assertFalse(store.toggleWantToPlay("snes/a.sfc"))
        assertFalse(store.toggleWantToPlay("gba/Not Indexed Yet.gba"))
        assertTrue(store.toggleWantToPlay("snes/b.sfc"))
        assertFalse(store.toggleWantToPlay("app:org.ppsspp.ppsspp")) // games only

        val expected = before.copy(favourites = emptySet(), wantToPlay = setOf("snes/b.sfc"))
        assertEquals(expected, store.data.value)
        withTimeout(5_000) { store.written.first { it == expected } }
        assertEquals(expected, UserStore(file).data.value)
    }

    /**
     * A remembered library folder missing at startup: saves fail without recreating it, and once it is restored its user.json is
     * never overwritten by the empty state this store started from (ordinary or durable writes); a restart loads it instead.
     */
    @Test
    fun missingRememberedFolderIsNotRecreatedAndItsRestoredFileIsKept() = runBlocking {
        val dir = File(Files.createTempDirectory("sortie").toFile(), "sortie")
        val file = File(dir, "user.json")
        val gate = Gate()
        val store = UserStore(file, CoroutineScope(SupervisorJob() + gate), mayCreateDirectory = { false })
        assertEquals(UserData(), store.data.value)
        store.toggleFavourite("snes/while-missing.sfc")
        assertTrue(runCatching { store.commit { it.copy(homeSort = "custom") } }.isFailure)
        gate.open() // the ordinary write runs now, inline
        assertFalse(dir.exists())
        assertNotNull(store.writeError.value)
        assertFalse(store.reloadable())

        val restored = UserData(favourites = setOf("snes/kept.sfc"), homeSort = "count", gridColumns = 7)
        assertTrue(dir.mkdirs())
        file.writeText(Json.encodeToString(UserData.serializer(), restored))
        val bytes = file.readBytes()
        store.toggleFavourite("snes/after-restore.sfc") // collector runs inline: the gate is open
        assertTrue(runCatching { store.commit { it.copy(homeSort = "recent") } }.isFailure)
        assertTrue(runCatching { store.replace(store.data.value, UserData(gridColumns = 3)) }.isFailure)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(emptyList<String>(), dir.list()!!.filter { it != "user.json" })
        assertNotNull(store.writeError.value)
        assertTrue(store.reloadable())
        assertEquals(restored, UserStore(file).data.value)
    }

    /** Unpinned first run saves (creating the folder); after setup remembers it, a vanished folder is not recreated. */
    @Test
    fun firstRunPreferencesSaveButARememberedFolderIsNeverRecreated() = runBlocking {
        val dir = File(Files.createTempDirectory("sortie").toFile(), "sortie")
        val file = File(dir, "user.json")
        var pinned = false
        val store = UserStore(file, CoroutineScope(SupervisorJob() + Dispatchers.IO), mayCreateDirectory = { !pinned })
        val first = store.commit { it.copy(homeSort = "custom") }
        assertEquals(first, UserStore(file).data.value)
        pinned = true // library setup remembered the folder in this same process
        val second = store.commit { it.copy(favourites = setOf("snes/a.sfc")) }
        assertEquals(second, UserStore(file).data.value)
        assertFalse(store.reloadable())
        assertTrue(dir.deleteRecursively()) // its storage goes away
        assertTrue(runCatching { store.commit { it.copy(homeSort = "count") } }.isFailure)
        assertFalse(dir.exists())
        assertEquals(second, store.data.value)
    }

    /** A user.json that does not parse is never replaced by defaults; only once it parses does a restart help (no restart loop). */
    @Test
    fun unparsableFileIsKeptAndOnlyAFixedOneAsksForARestart() = runBlocking {
        val file = fresh()
        file.writeText("""{"favourites": ["snes/a.sfc"""")
        val bytes = file.readBytes()
        val store = UserStore(file, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        assertEquals(UserData(), store.data.value)
        assertTrue(runCatching { store.commit { it.copy(homeSort = "custom") } }.isFailure)
        assertArrayEquals(bytes, file.readBytes())
        assertFalse(store.reloadable())
        file.writeText("""{"favourites": ["snes/a.sfc"]}""")
        assertTrue(store.reloadable())
        assertEquals(setOf("snes/a.sfc"), UserStore(file).data.value.favourites)
    }

    /** Holds every dispatched block until [open]; then runs them inline on the calling thread. */
    private class Gate : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        private var opened = false

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (opened) block.run() else queue += block
        }

        fun open() {
            opened = true
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    private companion object {
        val LEGACY = """
            {"favourites": ["snes/a.sfc"],
             "gameAnnotations": {"snes/a.sfc": {"controller": "gamepad", "returnNote": "World 3, before the boss", "mappingNotes": "Y is jump"}},
             "journalEntries": [{"id": "entry", "date": "2026-10-24", "text": "First airborne build.", "gameId": "snes/a.sfc",
               "attachments": [{"path": "journal/media/entry/shot.png", "name": "Garage", "width": 1280, "height": 720}],
               "futureField": "kept"}]}
        """.trimIndent()
    }
}
