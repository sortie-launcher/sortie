package io.github.esko997.sortie.data.magazine

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Where the magazine reader is: the shelf (both null), an issue's contents ([article] null) or an article. */
data class ReaderLocation(val issue: String? = null, val article: String? = null) {
    /** B, one level at a time: article → contents → shelf; null when B on the shelf should leave the magazines. */
    fun back(): ReaderLocation? = when {
        article != null -> copy(article = null)
        issue != null -> ReaderLocation()
        else -> null
    }

    /** After (re)loading the card: an issue or article that is no longer readable falls back one level up instead of failing. */
    fun reconcile(shelf: MagazineShelf): ReaderLocation {
        val found = shelf.issue(issue) ?: return ReaderLocation()
        return if (article == null || found.issue.article(article) != null) this else copy(article = null)
    }
}

/**
 * Reading memory across leaving and re-entering the magazines (the screen's own saved state covers recreation and the screen
 * swap): each article's scroll position and whether it was read to the end, the panel chosen in each picture sequence, the
 * article to continue, and which issue and contents entry had the focus. App-private preferences keyed by issue and article id;
 * not part of user.json or backups.
 * Open it off the main thread ([open]): the first read waits for the file.
 */
class MagazineReadingStore(private val prefs: SharedPreferences) {
    /** First visible list item and its pixel offset in an article. */
    data class Position(val index: Int, val offset: Int)

    fun position(issue: String, article: String): Position =
        Position(prefs.getInt("pos:$issue/$article:i", 0).coerceAtLeast(0), prefs.getInt("pos:$issue/$article:o", 0).coerceAtLeast(0))

    fun finished(issue: String, article: String): Boolean = prefs.getBoolean("done:$issue/$article", false)

    fun started(issue: String, article: String): Boolean = prefs.contains("pos:$issue/$article:i")

    fun savePosition(issue: String, article: String, position: Position, atEnd: Boolean) = prefs.edit {
        putInt("pos:$issue/$article:i", position.index)
        putInt("pos:$issue/$article:o", position.offset)
        if (atEnd) putBoolean("done:$issue/$article", true)
    }

    /**
     * The panel last chosen in the article's picture sequence at body block [block] (0 when none was). A later revision that
     * moves the sequence may hand it another sequence's choice; the reader clamps it to that sequence's panels.
     */
    fun sequencePanel(issue: String, article: String, block: Int): Int = prefs.getInt("seq:$issue/$article:$block", 0).coerceAtLeast(0)

    fun saveSequencePanel(issue: String, article: String, block: Int, panel: Int) = prefs.edit { putInt("seq:$issue/$article:$block", panel) }

    /** The article last opened, to offer on the shelf. */
    fun resume(): ReaderLocation? {
        val v = prefs.getString("resume", null) ?: return null
        val issue = v.substringBefore('/')
        val article = v.substringAfter('/', "")
        return if (issue.isEmpty() || article.isEmpty()) null else ReaderLocation(issue, article)
    }

    fun opened(issue: String, article: String) = prefs.edit { putString("resume", "$issue/$article") }

    var shelfFocus: String?
        get() = prefs.getString("shelf:focus", null)
        set(v) = prefs.edit { putString("shelf:focus", v) }

    fun contentsFocus(issue: String): String? = prefs.getString("contents:$issue", null)

    fun setContentsFocus(issue: String, key: String) = prefs.edit { putString("contents:$issue", key) }

    companion object {
        /** Blocking (loads the preferences file): call from Dispatchers.IO. */
        fun open(context: Context): MagazineReadingStore {
            val prefs = context.applicationContext.getSharedPreferences("magazine_reading", Context.MODE_PRIVATE)
            prefs.contains("resume") // waits for the file load here rather than on the first read in composition
            return MagazineReadingStore(prefs)
        }
    }
}
