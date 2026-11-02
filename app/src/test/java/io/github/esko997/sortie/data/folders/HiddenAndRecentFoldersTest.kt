package io.github.esko997.sortie.data.folders

import android.content.ComponentName
import io.github.esko997.sortie.data.apps.AppEntry
import io.github.esko997.sortie.data.db.GameEntity
import io.github.esko997.sortie.data.manifest.FAVOURITES_FOLDER_ID
import io.github.esko997.sortie.data.manifest.FolderDef
import io.github.esko997.sortie.data.manifest.UserData
import io.github.esko997.sortie.data.manifest.WANT_TO_PLAY_FOLDER_ID
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenAndRecentFoldersTest {
    private val now = TimeUnit.DAYS.toMillis(1000)

    private fun game(title: String, addedDaysAgo: Long = 100) = GameEntity(
        id = "ps2/$title.iso", systemId = "ps2", folder = "ps2", fileName = "$title.iso", path = "/x/$title.iso", size = 1, mtime = 0,
        title = title, sortTitle = title.lowercase(), desc = null, rating = null, year = 2004, developer = null, publisher = null,
        genre = null, players = "1", playersMax = 1, fit = 3, hook = null, flags = "", tags = "", handWritten = false,
        cover = null, screenshot = null, marquee = null, placeholder = false, arriving = false, hack = false,
        addedAt = now - TimeUnit.DAYS.toMillis(addedDaysAgo) - 1
    )

    private val ac3 = game("Armored Core 3")
    private val trapt = game("Trapt", addedDaysAgo = 0)
    private val katamari = game("Katamari Damacy", addedDaysAgo = 13)
    private val godHand = game("God Hand", addedDaysAgo = 14)
    private val reader = AppEntry("com.example.reader", "Reader", ComponentName("com.example.reader", "com.example.reader.Main"))

    /** The repository's own assembly: every candidate, hidden ones included, evaluated with the user's hidden ids. */
    private fun folders(
        user: UserData,
        games: List<GameEntity> = listOf(ac3, trapt, katamari, godHand),
        defs: List<FolderDef> = emptyList()
    ): List<FolderView> {
        val items = folderCandidates(games, listOf(reader), emptyList(), user)
        val subjects = items.map { subjectOf(it, null, it.id in user.favourites, now) }
        return evaluateFolders(defs, user.wantToPlay, user.gameProgress, subjects, items.associateBy { it.id }, user.folders, user.hidden)
    }

    private fun List<FolderView>.ids(id: String) = single { it.def.id == id }.items.map { it.id }

    /** Hidden lists hidden games and apps by title and nothing else; every other folder, built-in or not, leaves them out. */
    @Test
    fun hiddenEntriesAreOnlyInTheHiddenFolder() {
        val user = UserData(
            favourites = setOf(ac3.id, trapt.id, reader.id),
            wantToPlay = setOf(trapt.id),
            hidden = setOf(trapt.id, reader.id, "ps2/Not Yet Indexed.iso"),
            folders = listOf(FolderDef("mine", "Mine", include = listOf(ac3.id, trapt.id)))
        )
        val views = folders(user, defs = listOf(FolderDef("desk", "Desk", include = listOf(trapt.id, katamari.id))))
        assertEquals(listOf(reader.id, trapt.id), views.ids(HIDDEN_FOLDER_ID))
        assertEquals(listOf(ac3.id), views.ids(FAVOURITES_FOLDER_ID))
        assertTrue(views.ids(WANT_TO_PLAY_FOLDER_ID).isEmpty())
        assertEquals(listOf(ac3.id), views.ids("mine"))
        assertEquals(listOf(katamari.id), views.ids("desk"))
        assertEquals(listOf(katamari.id), views.ids(RECENTLY_ADDED_FOLDER_ID))

        val unhidden = folders(user.copy(hidden = emptySet()), defs = listOf(FolderDef("desk", "Desk", include = listOf(trapt.id, katamari.id))))
        assertTrue(unhidden.ids(HIDDEN_FOLDER_ID).isEmpty())
        assertEquals(listOf(ac3.id, reader.id, trapt.id), unhidden.ids(FAVOURITES_FOLDER_ID))
        assertEquals(listOf(ac3.id, trapt.id), unhidden.ids("mine"))
    }

    /** Recently added holds games first seen less than 14 days ago, newest first; older games and apps never. */
    @Test
    fun recentlyAddedIsTheLastFourteenDaysNewestFirst() {
        assertEquals(listOf(trapt.id, katamari.id), folders(UserData()).ids(RECENTLY_ADDED_FOLDER_ID))
        assertTrue(folders(UserData(), games = listOf(ac3, godHand)).ids(RECENTLY_ADDED_FOLDER_ID).isEmpty())
    }

    /** Custom drops Hidden and Recently added while they are empty; other built-ins keep their tile empty or not. */
    @Test
    fun hiddenAndRecentlyAddedShowOnCustomOnlyWhenFilled() {
        val empty = folders(UserData(), games = listOf(ac3)).onCustom().map { it.def.id }
        assertTrue(HIDDEN_FOLDER_ID !in empty && RECENTLY_ADDED_FOLDER_ID !in empty)
        assertTrue(FAVOURITES_FOLDER_ID in empty)

        val filled = folders(UserData(hidden = setOf(ac3.id)), games = listOf(ac3, trapt)).onCustom().map { it.def.id }
        assertTrue(HIDDEN_FOLDER_ID in filled && RECENTLY_ADDED_FOLDER_ID in filled)
    }
}
