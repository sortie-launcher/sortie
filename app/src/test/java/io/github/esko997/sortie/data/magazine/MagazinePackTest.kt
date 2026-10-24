package io.github.esko997.sortie.data.magazine

import io.github.esko997.sortie.data.markdown.MdBlock
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MagazinePackTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun issueJson(
        id: String = "field-notes-001",
        version: String = "1",
        body: String = "articles/a-one.md",
        image: String = "media/a-one.webp",
        cover: String = "media/cover.webp",
        extraArticle: String = ""
    ) = """
        {
          "schemaVersion": $version,
          "id": "$id",
          "title": "Field Notes",
          "subtitle": "Short sorties, deep systems",
          "published": "2026-10-24",
          "cover": "$cover",
          "editorial": "Intro.",
          "articles": [
            {
              "id": "a-one",
              "title": "Garage first",
              "gameTitle": "Armored Core 3",
              "system": "PS2",
              "gameIds": ["ps2/Armored Core 3 (USA).chd"],
              "deck": "Deck.",
              "body": "$body",
              "image": "$image",
              "imageCaption": "Caption.",
              "readingMinutes": 7
            }$extraArticle
          ]
        }
    """.trimIndent()

    private fun invalid(text: String, folder: String = "field-notes-001"): String = (parseIssue(text, folder) as IssueParse.Invalid).reason

    @Test
    fun validIssueParsesWithOptionalDefaults() {
        val minimal = """
            {"schemaVersion": 1, "id": "field-notes-001", "title": "Field Notes", "published": "2026-10-24",
             "articles": [{"id": "a-one", "title": "Garage first", "body": "articles/a-one.md"}]}
        """.trimIndent()
        val issue = (parseIssue(minimal, "field-notes-001") as IssueParse.Valid).issue
        assertEquals("", issue.subtitle)
        assertNull(issue.cover)
        val a = issue.articles.single()
        assertEquals(MagazineArticle(id = "a-one", title = "Garage first", body = "articles/a-one.md"), a)
        assertEquals(java.time.LocalDate.of(2026, 10, 24), issue.publishedDate)

        // a version 1 pack's local file ids become portable references at the input boundary
        val full = (parseIssue(issueJson(), "field-notes-001") as IssueParse.Valid).issue
        assertEquals(1, full.revision)
        assertEquals(
            listOf(MagazineGameReference("ps2-armored-core-3", "Armored Core 3", "ps2", matchNames = listOf("Armored Core 3 (USA)"))),
            full.articles.single().games
        )
        val a1 = full.articles.single()
        assertEquals(7, a1.readingMinutes)
        assertEquals("media/a-one.webp", a1.image)
    }

    @Test
    fun versionAndStructureErrorsAreExplained() {
        assertTrue(invalid(issueJson(version = "3")).contains("Unsupported schemaVersion 3"))
        assertTrue(invalid(issueJson(version = "\"1\"")).contains("schemaVersion"))
        assertTrue(invalid("""{"id": "x"}""", "x").contains("schemaVersion"))
        assertTrue(invalid("{ not json").contains("not valid JSON"))
        assertTrue(invalid("[1, 2]").contains("JSON object"))
        assertTrue(invalid(issueJson(), folder = "renamed").contains("does not match its folder name"))
        assertTrue(invalid(issueJson(id = "../escape"), folder = "../escape").contains("slug"))
        val noArticles = """{"schemaVersion": 1, "id": "i", "title": "T", "published": "2026-10-24", "articles": []}"""
        assertTrue(invalid(noArticles, "i").contains("at least one article"))
        val duplicate = issueJson(extraArticle = """, {"id": "a-one", "title": "Again", "body": "articles/b.md"}""")
        assertTrue(invalid(duplicate).contains("used twice"))
        val untitled = issueJson(extraArticle = """, {"id": "b", "title": "  ", "body": "articles/b.md"}""")
        assertTrue(invalid(untitled).contains("\"title\" must not be empty"))
        val badMinutes = issueJson(extraArticle = """, {"id": "b", "title": "B", "body": "articles/b.md", "readingMinutes": 0}""")
        assertTrue(invalid(badMinutes).contains("readingMinutes"))
    }

    @Test
    fun unsafePathsRejectTheIssue() {
        for (bad in listOf(
            "../outside.md",
            "articles/../../outside.md",
            "/sdcard/x.md",
            "https://example.com/a.md",
            "articles\\\\a.md",
            "./articles/a.md",
            "articles//a.md"
        )) {
            assertTrue(bad, invalid(issueJson(body = bad)).contains("relative to the issue folder"))
        }
        assertTrue(invalid(issueJson(image = "http://example.com/x.webp")).contains("relative to the issue folder"))
        assertTrue(invalid(issueJson(cover = "../cover.webp")).contains("relative to the issue folder"))
    }

    @Test
    fun safePathRules() {
        assertTrue(isSafeIssuePath("media/cover.webp"))
        assertTrue(isSafeIssuePath("articles/a-stable-slug.md"))
        for (bad in listOf("", "/abs", "~/x", "..", "a/../b", "a/./b", "a//b", "a\\b", "c:x", "data:image/png;base64,AAA", "a\nb")) {
            assertFalse(bad, isSafeIssuePath(bad))
        }
    }

    private fun writeIssue(magazines: File, folder: String, json: String): File {
        val dir = File(magazines, folder)
        File(dir, "articles").mkdirs()
        File(dir, "media").mkdirs()
        File(dir, "issue.json").writeText(json)
        return dir
    }

    @Test
    fun oneBrokenIssueDoesNotHideTheOthers() {
        val magazines = tmp.newFolder("magazines")
        val good = writeIssue(magazines, "field-notes-001", issueJson())
        File(good, "articles/a-one.md").writeText("# Title\n\nBody.")
        File(good, "media/cover.webp").writeBytes(byteArrayOf(1))
        writeIssue(magazines, "corrupt", "{ nope")
        writeIssue(magazines, "future", issueJson(id = "future", version = "3"))
        File(magazines, "empty-folder").mkdirs()
        writeIssue(magazines, ".deploying", "{ half written") // hidden: an atomic deploy in progress
        File(magazines, "stray.txt").writeText("not an issue")

        val shelf = readShelf(magazines)
        assertNull(shelf.problem)
        assertEquals(listOf("field-notes-001"), shelf.issues.map { it.issue.id })
        assertEquals(listOf("corrupt", "empty-folder", "future"), shelf.broken.map { it.folder })
        assertTrue(shelf.broken.first { it.folder == "future" }.reason.contains("Unsupported schemaVersion 3"))
        assertTrue(shelf.broken.first { it.folder == "empty-folder" }.reason.contains("No issue.json"))

        val issue = shelf.issues.single()
        assertNotNull(issue.cover?.file)
        // the lead image is optional: missing, it is reported, and the article still opens
        val lead = issue.images.getValue("a-one")
        assertNull(lead.file)
        assertTrue(lead.problem!!.contains("media/a-one.webp"))
        val content = readArticle(issue.dir, issue.issue.articles.single()) as ArticleContent.Ready
        assertEquals(listOf(MdBlock.Heading(1, "Title"), MdBlock.Paragraph("Body.")), content.blocks)
    }

    @Test
    fun missingShelfFolderIsAnEmptyShelf() {
        val shelf = readShelf(File(tmp.root, "magazines"))
        assertTrue(shelf.issues.isEmpty() && shelf.broken.isEmpty())
        assertNull(shelf.problem)
    }

    @Test
    fun missingBodyBreaksTheIssueButEmptyOrVanishedBodyFailsOnlyThatArticle() {
        val magazines = tmp.newFolder("magazines")
        val dir = writeIssue(magazines, "field-notes-001", issueJson())
        assertTrue(readShelf(magazines).broken.single().reason.contains("missing or unreadable"))

        File(dir, "articles/a-one.md").writeText("\n\n")
        val issue = readShelf(magazines).issues.single()
        val article = issue.issue.articles.single()
        assertTrue((readArticle(issue.dir, article) as ArticleContent.Failed).reason.contains("empty"))
        File(issue.dir, "articles/a-one.md").delete() // removed after the shelf was read
        assertTrue((readArticle(issue.dir, article) as ArticleContent.Failed).reason.contains("missing"))
    }

    @Test
    fun markdownImagesStayInsideTheIssue() {
        val magazines = tmp.newFolder("magazines")
        val dir = writeIssue(magazines, "field-notes-001", issueJson())
        File(tmp.root, "secret.webp").writeBytes(byteArrayOf(9))
        File(dir, "media/shot.webp").writeBytes(byteArrayOf(1))
        File(dir, "articles/a-one.md").writeText(
            "Intro\n\n![Shot](media/shot.webp)\n\n![Escape](../../secret.webp)\n\n![Remote](https://example.com/x.webp)\n\n![Gone](media/gone.webp)\n"
        )
        val issue = readShelf(magazines).issues.single()
        val content = readArticle(issue.dir, issue.issue.articles.single()) as ArticleContent.Ready
        assertEquals(File(issue.dir, "media/shot.webp").canonicalFile, content.images.getValue("media/shot.webp").file)
        for (outside in listOf("../../secret.webp", "https://example.com/x.webp")) {
            val image = content.images.getValue(outside)
            assertNull(outside, image.file)
            assertTrue(outside, image.problem!!.contains("inside this issue"))
        }
        assertTrue(content.images.getValue("media/gone.webp").problem!!.contains("missing"))
    }

    @Test
    fun symlinksCannotLeadOutOfTheIssue() {
        val outside = tmp.newFolder("outside")
        File(outside, "a-one.md").writeText("# Not part of the issue")
        val magazines = tmp.newFolder("magazines")
        val dir = writeIssue(magazines, "field-notes-001", issueJson())
        File(dir, "articles").delete()
        val linked = runCatching { Files.createSymbolicLink(File(dir, "articles").toPath(), outside.toPath()) }.isSuccess
        assumeTrue("symlinks unsupported here", linked)

        assertNull(resolveInIssue(dir, "articles/a-one.md"))
        val broken = readShelf(magazines).broken.single()
        assertEquals("field-notes-001", broken.folder)
        assertTrue(broken.reason.contains("leads outside the issue folder"))

        // an issue folder that is itself a link to somewhere else is not read either
        val elsewhere = tmp.newFolder("elsewhere")
        writeIssue(elsewhere, "linked-issue", issueJson(id = "linked-issue"))
        Files.createSymbolicLink(File(magazines, "linked-issue").toPath(), File(elsewhere, "linked-issue").toPath())
        assertTrue(readShelf(magazines).broken.first { it.folder == "linked-issue" }.reason.contains("outside magazines"))
    }

    @Test
    fun readerBacksOutOneLevelAndSurvivesARemovedPack() {
        val article = ReaderLocation("field-notes-001", "a-one")
        assertEquals(ReaderLocation("field-notes-001"), article.back())
        assertEquals(ReaderLocation(), article.back()!!.back())
        assertNull(ReaderLocation().back())

        val magazines = tmp.newFolder("magazines")
        File(writeIssue(magazines, "field-notes-001", issueJson()), "articles/a-one.md").writeText("Body.")
        val shelf = readShelf(magazines)
        assertEquals(article, article.reconcile(shelf))
        assertEquals(ReaderLocation("field-notes-001"), ReaderLocation("field-notes-001", "removed-article").reconcile(shelf))
        assertEquals(ReaderLocation(), ReaderLocation("removed-issue", "a-one").reconcile(shelf))
    }
}
