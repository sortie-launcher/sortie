package io.github.esko997.sortie.data.folders

import io.github.esko997.sortie.data.apps.LibraryItem
import io.github.esko997.sortie.data.db.GameEntity
import io.github.esko997.sortie.data.manifest.FolderDef
import io.github.esko997.sortie.data.manifest.WANT_TO_PLAY_FOLDER_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WantToPlayFolderTest {
    private fun game(title: String, sortTitle: String = title.lowercase()) = GameEntity(
        id = "ps2/$title.iso", systemId = "ps2", folder = "ps2", fileName = "$title.iso", path = "/x/$title.iso", size = 1, mtime = 0,
        title = title, sortTitle = sortTitle, desc = null, rating = null, year = 2004, developer = null, publisher = null,
        genre = null, players = "1", playersMax = 1, fit = 3, hook = null, flags = "", tags = "", handWritten = false,
        cover = null, screenshot = null, marquee = null, placeholder = false, arriving = false, hack = false, addedAt = 0
    )

    /** The repository's inputs after its hidden filter: [games] are the indexed, unhidden library. */
    private fun resolve(defs: List<FolderDef>, queue: Set<String>, games: List<GameEntity>): List<FolderView> {
        val items = games.map { LibraryItem.Game(it) }
        return evaluateFolders(defs, queue, emptyMap(), games.map { Subject(it, 0, null, false) }, items.associateBy { it.id })
    }

    private fun List<FolderView>.queue() = single { it.def.id == WANT_TO_PLAY_FOLDER_ID }

    /** Members show in title order; ids the index does not (yet) have resolve to nothing and do not break the folder. */
    @Test
    fun queueListsIndexedMembersByTitleAndSkipsUnknownIds() {
        val ac3 = game("Armored Core 3")
        val trapt = game("Trapt")
        val godHand = game("The God Hand", sortTitle = "god hand")
        val view = resolve(
            emptyList(),
            setOf(trapt.id, "ps2/Not Yet Indexed.iso", godHand.id, ac3.id),
            listOf(trapt, godHand, ac3, game("Katamari Damacy"))
        ).queue()
        assertEquals(listOf(ac3.id, godHand.id, trapt.id), view.items.map { it.id })
        assertTrue(view.problems.isEmpty())
    }

    /** An empty queue still yields the folder, so a placed home tile keeps opening it after the last removal. */
    @Test
    fun emptyQueueIsStillAFolder() {
        val view = resolve(emptyList(), emptySet(), listOf(game("Trapt"))).queue()
        assertTrue(view.items.isEmpty())
    }

    /** A manifest folder claiming the reserved id is dropped, so `folder:want-to-play` names one folder; other folders are untouched. */
    @Test
    fun manifestFolderWithTheReservedIdIsIgnored() {
        val trapt = game("Trapt")
        val defs = listOf(FolderDef(WANT_TO_PLAY_FOLDER_ID, "Mine", include = listOf(trapt.id)), FolderDef("all", include = listOf(trapt.id)))
        val views = resolve(defs, emptySet(), listOf(trapt))
        assertEquals(listOf(trapt.id), views.single { it.def.id == "all" }.items.map { it.id })
        assertTrue(views.queue().items.isEmpty())
        assertEquals(1, views.queue().problems.size)
    }
}
