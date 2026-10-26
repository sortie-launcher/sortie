package io.github.esko997.sortie.data.user

import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nothing an older Sortie stored as personal writing may be lost or silently dropped by the move to bookmarks. */
class LegacyNotesTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun returnNoteBecomesTheBookmarkAndEverythingRemovedIsReported() {
        val m = LegacyUserMigration.migrate(
            obj(
                """{"favourites": ["a"], "future": {"x": 1},
                   "gameAnnotations": {
                     "a": {"controller": "pointer", "returnNote": "  Act 2  ", "mappingNotes": "B = shoot"},
                     "b": {"controller": "unknown", "returnNote": " "},
                     "c": {"mappingNotes": "only mapping", "someNewField": true}},
                   "journalEntries": [{"id": "e1", "text": "one"}, {"id": "e2", "text": "two"}]}"""
            )
        )
        val user = m.user
        assertEquals(obj("""{"a": {"text": "Act 2"}}"""), user["bookmarks"])
        // a's controller stays; b had nothing left; c keeps the field this build does not know
        assertEquals(obj("""{"a": {"controller": "pointer"}, "c": {"someNewField": true}}"""), user["gameAnnotations"])
        assertFalse("journalEntries" in user)
        assertEquals(obj("""{"x": 1}"""), user["future"]) // unknown preferences pass through untouched
        val legacy = m.legacy!!
        assertEquals(2, legacy.journalEntries)
        assertEquals(LegacyGameNotes("  Act 2  ", "B = shoot"), legacy.games["a"]) // archived exactly as written
        assertEquals(LegacyGameNotes("", "only mapping"), legacy.games["c"])
        assertNull(legacy.games["b"])
    }

    @Test
    fun anExistingBookmarkIsNeverOverwrittenByAnOldNote() {
        val m = LegacyUserMigration.migrate(
            obj("""{"bookmarks": {"a": {"text": "newer", "savedAt": 9}}, "gameAnnotations": {"a": {"returnNote": "older"}}}""")
        )
        assertEquals(obj("""{"a": {"text": "newer", "savedAt": 9}}"""), m.user["bookmarks"])
        assertEquals("older", m.legacy!!.games["a"]?.returnNote)
    }

    @Test
    fun currentFilesAreLeftAlone() {
        val current = obj("""{"bookmarks": {"a": {"text": "x"}}, "gameAnnotations": {"a": {"controller": "touch"}}}""")
        val m = LegacyUserMigration.migrate(current)
        assertTrue(m.user === current)
        assertNull(m.legacy)
        // an empty legacy journal is removed but leaves nothing to archive
        val empty = LegacyUserMigration.migrate(obj("""{"journalEntries": []}"""))
        assertEquals(obj("{}"), empty.user)
        assertNull(empty.legacy)
    }

    @Test
    fun archiveIsReadableExactAndWrittenOnceForTheSameWriting() {
        val dir = File(Files.createTempDirectory("sortie").toFile(), "archive")
        val archive = LegacyNotesArchive(dir, ZoneOffset.UTC)
        val legacy = LegacyUserMigration.migrate(
            obj(
                """{"gameAnnotations": {"a": {"returnNote": "Act 2", "mappingNotes": "B = shoot"}},
                   "journalEntries": [{"id": "e1", "date": "2026-10-24", "gameId": "a", "text": "Long night.", "createdAt": 1700000000000,
                     "attachments": [{"path": "journal/media/e1/f.png", "name": "Boss", "width": 640, "height": 480, "bytes": 1234}]},
                     "not an object"]}"""
            )
        ).legacy!!
        val md = archive.save(legacy, "user.json", 1_700_000_000_000)
        val text = md.readText()
        listOf("Act 2", "B = shoot", "Long night.", "### 2026-10-24 — a", "`sortie/journal/media/e1/f.png` (Boss, 640×480, 1234 bytes)", "not an object")
            .forEach { assertTrue("missing $it in:\n$text", it in text) }
        val files = dir.list()!!.sorted()
        assertEquals(2, files.size)
        val exact = Json.parseToJsonElement(File(dir, files.first { it.endsWith(".json") }).readText()).jsonObject
        assertEquals("sortie-personal-notes-archive", exact["format"].toString().trim('"'))
        // the same writing again (an interrupted migration, the same backup restored twice) finds the existing copy
        assertEquals(md, archive.save(legacy, "backup again.json", 1_800_000_000_000))
        assertEquals(files, dir.list()!!.sorted())
        assertEquals(text, md.readText())
    }

    @Test
    fun anArchiveThatCannotBeWrittenThrows() {
        val blocker = Files.createTempFile("sortie", "file").toFile()
        val legacy = LegacyNotes(games = mapOf("a" to LegacyGameNotes("x", "")))
        assertTrue(runCatching { LegacyNotesArchive(File(blocker, "archive")).save(legacy, "user.json", 1) }.isFailure)
    }
}
