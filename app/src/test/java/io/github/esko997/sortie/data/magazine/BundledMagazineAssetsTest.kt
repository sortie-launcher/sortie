package io.github.esko997.sortie.data.magazine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The magazines a build staged from -PsortieMagazinesDir (app/build.gradle.kts stageMagazineAssets, which hands the folder over
 * as the system property sortie.stagedMagazines) read cleanly with the app's own parser and reader and are the pinned catalog.
 * A build without magazines stages nothing, and this test is skipped.
 */
class BundledMagazineAssetsTest {
    private val staged = System.getProperty("sortie.stagedMagazines")?.let(::File)?.takeIf { File(it, CATALOG).isFile }

    @Test
    fun everyBundledIssueIsCompleteAndConsistent() {
        assumeTrue("no magazines staged (build without -PsortieMagazinesDir)", staged != null)
        val dir = staged!!
        val catalog = checkNotNull(parseCatalog(File(dir, CATALOG).readText())) { "catalog.json is malformed" }
        val pinned = File("magazines.lock").readLines().map { it.trim() }.single { it.isNotEmpty() && !it.startsWith("#") }
        assertEquals("staged catalog vs app/magazines.lock", pinned, catalog.digest)
        val files = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.toSet()
        assertEquals(setOf(CATALOG) + catalog.issues.flatMap { i -> i.files.map { "${i.id}/${it.path}" } }, files)
        val shelf = readShelf(dir, IssueSource.BUNDLED)
        assertEquals(emptyList<BrokenIssue>(), shelf.broken)
        assertEquals(catalog.issues.map { it.id }.sorted(), shelf.issues.map { it.issue.id }.sorted())

        val identity = HashMap<String, Pair<String, Triple<String, String, String>>>()
        for (s in shelf.issues) {
            val issue = s.issue
            val where = issue.id
            assertEquals(where, catalog.issues.first { it.id == issue.id }.revision, issue.revision)
            assertTrue("$where: rights notice", issue.rightsNotice.isNotBlank())
            s.cover?.let { assertNotNull("$where: cover ${it.path}", it.file) }
            s.images.values.forEach { assertNotNull("$where: lead image ${it.path}", it.file) }
            for (article in issue.articles) {
                val content = readArticle(s.dir, article)
                assertTrue("$where/${article.id}: $content", content is ArticleContent.Ready)
                (content as ArticleContent.Ready).images.values.forEach { assertNotNull("$where/${article.id}: ${it.path}", it.file) }
                for (ref in article.games) {
                    val (first, seen) = identity.getOrPut(ref.id) { "$where/${article.id}" to Triple(ref.title, ref.systemId, ref.edition) }
                    assertEquals("game ${ref.id} in $where/${article.id} vs $first", seen, Triple(ref.title, ref.systemId, ref.edition))
                }
            }
        }
    }
}
