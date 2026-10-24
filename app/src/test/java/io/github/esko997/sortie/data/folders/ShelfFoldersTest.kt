package io.github.esko997.sortie.data.folders

import io.github.esko997.sortie.data.apps.LibraryItem
import io.github.esko997.sortie.data.db.GameEntity
import io.github.esko997.sortie.data.manifest.FAVOURITES_FOLDER_ID
import io.github.esko997.sortie.data.manifest.FolderDef
import io.github.esko997.sortie.data.manifest.WANT_TO_PLAY_FOLDER_ID
import io.github.esko997.sortie.data.user.FINISHED_FOLDER_ID
import io.github.esko997.sortie.data.user.GameProgress
import io.github.esko997.sortie.data.user.SHELVED_FOLDER_ID
import io.github.esko997.sortie.data.user.Shelf
import io.github.esko997.sortie.data.user.withShelf
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfFoldersTest {
    private val today = LocalDate.of(2026, 10, 24)

    private fun game(title: String) = GameEntity(
        id = "ps2/$title.iso", systemId = "ps2", folder = "ps2", fileName = "$title.iso", path = "/x/$title.iso", size = 1, mtime = 0,
        title = title, sortTitle = title.lowercase(), desc = null, rating = null, year = 2004, developer = null, publisher = null,
        genre = null, players = "1", playersMax = 1, fit = 3, hook = null, flags = "", tags = "", handWritten = false,
        cover = null, screenshot = null, marquee = null, placeholder = false, arriving = false, hack = false, addedAt = 0
    )

    /** The repository's inputs after its hidden filter: [games] are the indexed, unhidden library. */
    private fun resolve(
        progress: Map<String, GameProgress>,
        games: List<GameEntity>,
        queue: Set<String> = emptySet(),
        defs: List<FolderDef> = emptyList()
    ): Map<String, FolderView> {
        val items = games.map { LibraryItem.Game(it) }
        return evaluateFolders(defs, queue, progress, games.map { Subject(it, 0, null, false) }, items.associateBy { it.id }).associateBy { it.def.id }
    }

    private fun Map<String, FolderView>.ids(folder: String) = getValue(folder).items.map { it.id }

    /** Set, switch and clear move a game between the two shelves only; Want to play membership is its own, and unknown ids wait. */
    @Test
    fun aGameIsOnTheShelfItsStatusNamesAndNoOther() {
        val ac3 = game("Armored Core 3")
        val trapt = game("Trapt")
        val library = listOf(ac3, trapt)
        val queue = setOf(ac3.id)

        val finished = emptyMap<String, GameProgress>()
            .withShelf(ac3.id, Shelf.FINISHED, "Garage heaven.", today)
            .withShelf("ps2/Not Yet Indexed.iso", Shelf.FINISHED, "", today)
            .withShelf(trapt.id, Shelf.SHELVED, "", today)
        resolve(finished, library, queue).let {
            assertEquals(listOf(ac3.id), it.ids(FINISHED_FOLDER_ID))
            assertEquals(listOf(trapt.id), it.ids(SHELVED_FOLDER_ID))
            assertEquals(listOf(ac3.id), it.ids(WANT_TO_PLAY_FOLDER_ID))
        }

        val switched = finished.withShelf(ac3.id, Shelf.SHELVED, "Garage heaven.", today)
        resolve(switched, library, queue).let {
            assertTrue(it.ids(FINISHED_FOLDER_ID).isEmpty())
            assertEquals(listOf(ac3.id, trapt.id), it.ids(SHELVED_FOLDER_ID))
            assertEquals(listOf(ac3.id), it.ids(WANT_TO_PLAY_FOLDER_ID))
        }

        val cleared = switched.withShelf(ac3.id, null, "", today)
        resolve(cleared, library, queue).let {
            assertTrue(it.ids(FINISHED_FOLDER_ID).isEmpty())
            assertEquals(listOf(trapt.id), it.ids(SHELVED_FOLDER_ID))
            assertEquals(listOf(ac3.id), it.ids(WANT_TO_PLAY_FOLDER_ID))
        }
        assertTrue("ps2/Not Yet Indexed.iso" in cleared)
    }

    /** Both shelf folders exist while empty; a manifest folder claiming a shelf id is dropped and reported on that shelf only. */
    @Test
    fun shelfFoldersAreAlwaysPresentAndOwnTheirIds() {
        val trapt = game("Trapt")
        val defs = listOf(FolderDef(SHELVED_FOLDER_ID, "Mine", include = listOf(trapt.id)), FolderDef("all", include = listOf(trapt.id)))
        val views = resolve(emptyMap(), listOf(trapt), defs = defs)
        assertEquals(setOf("all", FAVOURITES_FOLDER_ID, WANT_TO_PLAY_FOLDER_ID, FINISHED_FOLDER_ID, SHELVED_FOLDER_ID), views.keys)
        assertTrue(views.ids(SHELVED_FOLDER_ID).isEmpty())
        assertEquals(1, views.getValue(SHELVED_FOLDER_ID).problems.size)
        assertTrue(views.getValue(FINISHED_FOLDER_ID).problems.isEmpty())
        assertEquals(listOf(trapt.id), views.ids("all"))
    }
}
