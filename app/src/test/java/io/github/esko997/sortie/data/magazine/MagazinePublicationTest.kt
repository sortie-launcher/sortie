package io.github.esko997.sortie.data.magazine

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MagazinePublicationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun v2(id: String, revision: Int = 2, title: String = "Field Notes", games: String = DW5XL, extraArticle: String = "") = """
        {"schemaVersion": 2, "id": "$id", "revision": $revision, "title": "$title", "published": "2026-10-24",
         "rightsNotice": "Game images belong to their rights holders.",
         "articles": [{"id": "a-one", "title": "Officer runs", "body": "articles/a-one.md", "games": [$games]}$extraArticle]}
    """.trimIndent()

    private fun invalid(text: String, folder: String = "field-notes-008"): String = (parseIssue(text, folder) as IssueParse.Invalid).reason

    @Test
    fun schema2ReadsReferencesRevisionAndRightsNotice() {
        val issue = (parseIssue(v2("field-notes-008", games = "$DW5XL, $INFO"), "field-notes-008") as IssueParse.Valid).issue
        assertEquals(2, issue.revision)
        assertEquals("Game images belong to their rights holders.", issue.rightsNotice)
        assertEquals(
            listOf(
                MagazineGameReference(
                    "ps2-dynasty-warriors-5-xtreme-legends",
                    "Dynasty Warriors 5: Xtreme Legends",
                    "ps2",
                    "Original PS2 release",
                    listOf("Dynasty Warriors 5 - Xtreme Legends")
                ),
                MagazineGameReference("devil-may-cry-5", "Devil May Cry 5")
            ),
            issue.articles.single().games
        )
        assertEquals(issue.articles, issue.articlesMentioning("devil-may-cry-5"))
    }

    @Test
    fun schema2RejectsMalformedReferencesAndRevisions() {
        assertTrue(invalid(v2("field-notes-008").replace("\"revision\": 2, ", "")).contains("\"revision\" is required"))
        assertTrue(invalid(v2("field-notes-008", revision = 0)).contains("revision"))
        assertTrue(invalid(v2("field-notes-008").replace("\"games\"", "\"gameIds\"")).contains("version 1 field"))
        assertTrue(invalid(v2("field-notes-008", games = """{"id": "../x", "title": "X"}""")).contains("id"))
        assertTrue(invalid(v2("field-notes-008", games = """{"id": "x", "title": " "}""")).contains("\"title\" must not be empty"))
        assertTrue(invalid(v2("field-notes-008", games = "$DW5XL, $DW5XL")).contains("listed twice"))
        assertTrue(invalid(v2("field-notes-008", games = """{"id": "x", "title": "X", "matchNames": [""]}""")).contains("matchNames"))
        // one id is one game across the issue's articles: platform/title/edition must agree, aliases are unioned
        val other = """, {"id": "b", "title": "B", "body": "articles/b.md", "games": [{"id": "ps2-dynasty-warriors-5-xtreme-legends",
            "title": "Dynasty Warriors 5", "systemId": "ps2"}]}"""
        assertTrue(invalid(v2("field-notes-008", extraArticle = other)).contains("different title, systemId or edition"))
        val alias = """, {"id": "b", "title": "B", "body": "articles/b.md", "games": [{"id": "ps2-dynasty-warriors-5-xtreme-legends",
            "title": "Dynasty Warriors 5: Xtreme Legends", "systemId": "ps2", "edition": "Original PS2 release",
            "matchNames": ["Shin Sangoku Musou 4 - Mushouden"]}]}"""
        val merged = (parseIssue(v2("field-notes-008", extraArticle = alias), "field-notes-008") as IssueParse.Valid).issue
        val names = listOf("Dynasty Warriors 5 - Xtreme Legends", "Shin Sangoku Musou 4 - Mushouden")
        assertEquals(listOf(names, names), merged.articles.map { it.games.single().matchNames })
        assertEquals(1, merged.games.size)
    }

    @Test
    fun legacyTranslatedFileKeepsItsEditionWhenConverted() {
        val ref = legacyReference("psx/Baroque - Yuganda Mousou (Japan) (En) (v1.02 Translation).chd", null)
        assertEquals("psx", ref.systemId)
        assertEquals(listOf("Baroque - Yuganda Mousou (Japan) (En) (v1.02 Translation)"), ref.matchNames)
        assertEquals("Baroque - Yuganda Mousou (v1.02 Translation)", ref.title)
        assertTrue(GAME_REF_ID.matches(ref.id))
        assertFalse(ref.id == legacyReference("psx/Baroque - Yuganda Mousou (Japan).chd", null).id)
    }

    // ---- shelf merge -------------------------------------------------------------------------------------------------------

    private fun writePack(root: File, id: String, json: String): File {
        val dir = File(root, id)
        File(dir, "articles").mkdirs()
        File(dir, "issue.json").writeText(json)
        File(dir, "articles/a-one.md").writeText("Body of $id.")
        return dir
    }

    @Test
    fun higherRevisionWinsAndEqualRevisionPrefersTheCard() {
        val bundled = tmp.newFolder("bundled")
        val card = tmp.newFolder("card")
        writePack(bundled, "field-notes-001", v2("field-notes-001", revision = 2, title = "Bundled"))
        writePack(bundled, "field-notes-002", v2("field-notes-002", revision = 2, title = "Bundled"))
        writePack(bundled, "field-notes-003", v2("field-notes-003", revision = 2, title = "Bundled"))
        writePack(card, "field-notes-001", LEGACY.replace("ID", "field-notes-001")) // old v1 copy (revision 1) on the card
        writePack(card, "field-notes-002", v2("field-notes-002", revision = 2, title = "Card"))
        writePack(card, "field-notes-003", v2("field-notes-003", revision = 3, title = "Card"))
        writePack(card, "my-own-zine", v2("my-own-zine", revision = 1, title = "Card"))

        val shelf = mergeShelves(readShelf(card), readShelf(bundled, IssueSource.BUNDLED))
        val byId = shelf.issues.associateBy { it.issue.id }
        assertEquals(4, shelf.issues.size) // exactly one entry per issue id
        assertEquals(IssueSource.BUNDLED, byId.getValue("field-notes-001").source)
        assertEquals(IssueSource.CARD, byId.getValue("field-notes-002").source)
        assertEquals("Card", byId.getValue("field-notes-003").issue.title)
        assertEquals(IssueSource.CARD, byId.getValue("my-own-zine").source)
        assertTrue(shelf.broken.isEmpty())
    }

    @Test
    fun sameDayNumberedIssuesShowTheLatestIssueWithoutOutrankingANewerPublicationDate() {
        val bundled = tmp.newFolder("bundled")
        val card = tmp.newFolder("card")
        writePack(bundled, "field-notes-001", v2("field-notes-001"))
        writePack(bundled, "field-notes-009", v2("field-notes-009"))
        writePack(card, "field-notes-999", v2("field-notes-999").replace("2026-10-24", "2026-10-23"))

        val shelf = mergeShelves(readShelf(card), readShelf(bundled, IssueSource.BUNDLED))
        assertEquals(listOf("field-notes-009", "field-notes-001", "field-notes-999"), shelf.issues.map { it.issue.id })
    }

    @Test
    fun malformedCardPackNeverHidesTheBundledIssue() {
        val bundled = tmp.newFolder("bundled")
        val card = tmp.newFolder("card")
        writePack(bundled, "field-notes-004", v2("field-notes-004"))
        writePack(card, "field-notes-004", "{ broken")
        writePack(card, "elsewhere", "{ broken")

        val shelf = mergeShelves(readShelf(card), readShelf(bundled, IssueSource.BUNDLED))
        assertEquals(listOf(IssueSource.BUNDLED), shelf.issues.map { it.source })
        assertEquals(listOf("elsewhere", "field-notes-004"), shelf.broken.map { it.folder })
        assertTrue(shelf.broken.last().reason.contains("built-in edition is shown"))
        assertTrue(File(card, "field-notes-004/issue.json").readText() == "{ broken") // card folders are never rewritten
    }

    /** A newer card copy missing an article body would fail on open; the complete bundled edition is shown instead. */
    @Test
    fun incompleteHigherRevisionCardCopyFallsBackToTheBundledIssue() {
        val bundled = tmp.newFolder("bundled")
        val card = tmp.newFolder("card")
        writePack(bundled, "field-notes-005", v2("field-notes-005", revision = 2, title = "Bundled"))
        File(writePack(card, "field-notes-005", v2("field-notes-005", revision = 3, title = "Card")), "articles/a-one.md").delete()

        val shelf = mergeShelves(readShelf(card), readShelf(bundled, IssueSource.BUNDLED))
        assertEquals(listOf("Bundled"), shelf.issues.map { it.issue.title })
        assertTrue(readArticle(shelf.issues.single().dir, shelf.issues.single().issue.articles.single()) is ArticleContent.Ready)
        val reason = shelf.broken.single().reason
        assertTrue(reason, reason.contains("built-in edition is shown") && reason.contains("missing or unreadable"))
    }

    @Test
    fun problemsOfEitherSourceAreReported() {
        val card = MagazineShelf(File(tmp.root, "card"), emptyList(), emptyList(), "Could not list the card")
        val bundled = MagazineShelf(File(tmp.root, "bundled"), emptyList(), emptyList(), "Could not prepare the built-in magazines: disk full")
        val merged = mergeShelves(card, bundled)
        assertTrue(merged.problem!!.contains("Could not list the card") && merged.problem!!.contains("disk full"))
        assertTrue(mergeShelves(card.copy(problem = null), bundled).problem!!.contains("disk full"))
        assertNull(mergeShelves(card.copy(problem = null), bundled.copy(problem = null)).problem)
    }

    // ---- bundled assets --------------------------------------------------------------------------------------------------

    private class Assets(val dir: File) : MagazineAssets {
        var opens = 0

        override fun open(path: String) = File(dir, path).takeIf { it.isFile }?.also { opens++ }?.inputStream()
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** What tools/publish-magazines.py writes: every file of every issue with its size and digest. */
    private fun publish(assets: File, vararg issues: Pair<String, String>, corrupt: String? = null) {
        assets.deleteRecursively()
        val entries = issues.map { (id, json) ->
            val dir = writePack(assets, id, json)
            val files = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.sorted().map { p ->
                val bytes = File(dir, p).readBytes()
                Triple(p, bytes.size, sha(bytes))
            }.toList()
            val digest = sha(files.joinToString("\n") { (p, size, hash) -> "$p\t$size\t$hash" }.toByteArray())
            val listed = files.joinToString(",") { (p, size, hash) -> """{"path": "$p", "size": $size, "sha256": "$hash"}""" }
            Triple(id, digest, """{"id": "$id", "revision": 2, "digest": "$digest", "files": [$listed]}""")
        }
        corrupt?.let { File(assets, it).appendText(" tampered") }
        val digest = sha(entries.joinToString("\n") { "${it.first}\t2\t${it.second}" }.toByteArray())
        File(assets, CATALOG).writeText("""{"format": 1, "digest": "$digest", "issues": [${entries.joinToString(",") { it.third }}]}""")
    }

    @Test
    fun bundledIssuesLoadOfflineWithoutAnyCardFolder() = runBlocking {
        val assetDir = tmp.newFolder("assets")
        publish(assetDir, "field-notes-008" to v2("field-notes-008"), "field-notes-009" to v2("field-notes-009"))
        val assets = Assets(assetDir)
        val private = File(tmp.root, "files/magazines-bundled")
        val repo = MagazineRepository(File(tmp.root, "no-card"), BundledMagazines(assets, private))

        val shelf = repo.shelf()
        assertNull(shelf.problem)
        assertTrue(shelf.issues.all { it.source == IssueSource.BUNDLED })
        val article = repo.article(shelf.issues[0], shelf.issues[0].issue.articles[0])
        assertTrue(article is ArticleContent.Ready)
        assertEquals(1, private.list()!!.size) // the snapshot only: no staging left behind

        // later opens, and a fresh process with the same APK, do not copy again
        val opens = assets.opens
        repo.shelf()
        MagazineRepository(File(tmp.root, "no-card"), BundledMagazines(assets, private)).shelf()
        assertEquals(opens + 1, assets.opens) // the new process reads catalog.json only; the first instance reads nothing
    }

    @Test
    fun appUpdateReplacesTheSnapshotAndADamagedIssueIsReportedNotHidden() = runBlocking {
        val assetDir = tmp.newFolder("assets")
        val private = File(tmp.root, "files/magazines-bundled")
        publish(assetDir, "field-notes-008" to v2("field-notes-008", title = "Old"))
        BundledMagazines(Assets(assetDir), private).shelf()
        val old = private.list()!!.single()

        publish(
            assetDir,
            "field-notes-008" to v2("field-notes-008", title = "New"),
            "field-notes-009" to v2("field-notes-009"),
            corrupt = "field-notes-009/articles/a-one.md"
        )
        val shelf = BundledMagazines(Assets(assetDir), private).shelf()
        assertEquals(listOf("New"), shelf.issues.map { it.issue.title })
        assertEquals("field-notes-009", shelf.broken.single().folder)
        assertTrue(shelf.broken.single().reason.contains("damaged"))
        assertFalse(old in private.list()!!) // the previous snapshot is gone, the new one complete
        assertEquals(1, private.list()!!.size)
    }

    @Test
    fun cardUpdateIsSeenWithoutRestartingAndNeverWritten() = runBlocking {
        val assetDir = tmp.newFolder("assets")
        publish(assetDir, "field-notes-008" to v2("field-notes-008", title = "Bundled"))
        val sortie = tmp.newFolder("sortie")
        val repo = MagazineRepository(sortie, BundledMagazines(Assets(assetDir), File(tmp.root, "files/magazines-bundled")))
        assertEquals(IssueSource.BUNDLED, repo.shelf().issue("field-notes-008")!!.source)

        val magazines = File(sortie, "magazines")
        writePack(magazines, "field-notes-008", v2("field-notes-008", revision = 3, title = "Card"))
        assertEquals("Card", repo.shelf().issue("field-notes-008")!!.issue.title)
        assertEquals(setOf("articles", "issue.json"), File(magazines, "field-notes-008").list()!!.toSet())
    }

    @Test
    fun malformedCatalogIsReportedAndCardIssuesStillLoad() = runBlocking {
        val assetDir = tmp.newFolder("assets")
        File(assetDir, CATALOG).writeText("""{"format": 1, "digest": "nope", "issues": []}""")
        val sortie = tmp.newFolder("sortie")
        writePack(File(sortie, "magazines"), "my-own-zine", v2("my-own-zine", revision = 1))
        val shelf = MagazineRepository(sortie, BundledMagazines(Assets(assetDir), File(tmp.root, "files/magazines-bundled"))).shelf()
        assertEquals(listOf("my-own-zine"), shelf.issues.map { it.issue.id })
        assertEquals(listOf(CATALOG), shelf.broken.map { it.folder })
    }

    private companion object {
        const val DW5XL = """{"id": "ps2-dynasty-warriors-5-xtreme-legends", "title": "Dynasty Warriors 5: Xtreme Legends", "systemId": "ps2",
            "edition": "Original PS2 release", "matchNames": ["Dynasty Warriors 5 - Xtreme Legends"]}"""
        const val INFO = """{"id": "devil-may-cry-5", "title": "Devil May Cry 5"}"""
        const val LEGACY = """{"schemaVersion": 1, "id": "ID", "title": "Old card copy", "published": "2026-10-23",
            "articles": [{"id": "a-one", "title": "Garage", "gameIds": ["ps2/Armored Core 3 (USA).chd"], "body": "articles/a-one.md"}]}"""
    }
}
