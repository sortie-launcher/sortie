package io.github.esko997.sortie.data.magazine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The issues published into the APK (tools/publish-magazines.py) read cleanly with the app's own parser and reader. */
class BundledMagazineAssetsTest {
    private val dir = File("src/main/assets/magazines")

    @Test
    fun everyBundledIssueIsCompleteAndConsistent() {
        assumeTrue("no bundled magazines published", File(dir, CATALOG).isFile)
        val catalog = checkNotNull(parseCatalog(File(dir, CATALOG).readText())) { "catalog.json is malformed" }
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
