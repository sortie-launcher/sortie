package io.github.esko997.sortie.data.magazine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MagazineReadingTest {
    private fun issue(n: Int) = MagazineIssue(
        id = "field-notes-00$n",
        title = "Field Notes $n",
        published = "2026-10-0$n",
        articles = listOf("a", "b").map { MagazineArticle(id = it, title = it, body = "articles/$it.md") }
    )

    // newest first, as the shelf lists them
    private val shelf = listOf(issue(4), issue(3), issue(2), issue(1))

    private fun choose(lastRead: String?, vararg started: String) = newerUnreadIssue(shelf, lastRead) { issue, article -> "$issue/$article" in started }?.id

    @Test
    fun newestUnreadIssueAboveTheLastReadIsChosen() {
        assertEquals("field-notes-004", choose("field-notes-002", "field-notes-002/a"))
    }

    /** One article started is enough for an issue to count as read, so it is passed over for an older unread one. */
    @Test
    fun startedIssuesAreSkipped() {
        assertEquals("field-notes-003", choose("field-notes-002", "field-notes-004/b", "field-notes-002/a"))
        assertNull(choose("field-notes-002", "field-notes-004/b", "field-notes-003/a", "field-notes-002/a"))
    }

    @Test
    fun olderUnreadIssuesNeverTakeTheShelf() {
        assertNull(choose("field-notes-004", "field-notes-004/a"))
    }

    /** Nothing read yet, or the last-read issue left the card: the shelf keeps its usual focus. */
    @Test
    fun noLastReadIssueOnTheShelfChoosesNothing() {
        assertNull(choose(null))
        assertNull(choose("field-notes-009", "field-notes-009/a"))
    }
}
