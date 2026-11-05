package io.github.esko997.sortie.data.folders

import android.content.ComponentName
import io.github.esko997.sortie.data.apps.AppEntry
import io.github.esko997.sortie.data.db.GameEntity
import io.github.esko997.sortie.data.db.PlayStat
import io.github.esko997.sortie.data.manifest.FAVOURITES_FOLDER_ID
import io.github.esko997.sortie.data.manifest.FolderDef
import io.github.esko997.sortie.data.manifest.FolderPreferences
import io.github.esko997.sortie.data.manifest.UserData
import io.github.esko997.sortie.data.manifest.WANT_TO_PLAY_FOLDER_ID
import io.github.esko997.sortie.ui.HomeIds
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderViewPreferencesTest {
    private val now = TimeUnit.DAYS.toMillis(1000)

    private fun game(title: String, addedDaysAgo: Long) = GameEntity(
        id = "ps2/$title.iso", systemId = "ps2", folder = "ps2", fileName = "$title.iso", path = "/x/$title.iso", size = 1, mtime = 0,
        title = title, sortTitle = title.lowercase(), desc = null, rating = null, year = 2004, developer = null, publisher = null,
        genre = null, players = "1", playersMax = 1, fit = 3, hook = null, flags = "", tags = "", handWritten = false,
        cover = null, screenshot = null, marquee = null, placeholder = false, arriving = false, hack = false,
        addedAt = now - TimeUnit.DAYS.toMillis(addedDaysAgo) - 1
    )

    private val ac3 = game("Armored Core 3", addedDaysAgo = 1)
    private val trapt = game("Trapt", addedDaysAgo = 2)
    private val katamari = game("Katamari Damacy", addedDaysAgo = 3)
    private val godHand = game("God Hand", addedDaysAgo = 30)
    private val zone = game("Zone of the Enders", addedDaysAgo = 0)
    private val reader = AppEntry("com.example.reader", "Reader", ComponentName("com.example.reader", "com.example.reader.Main"))
    private val minutes = mapOf(ac3 to 50, trapt to 5, katamari to 200, godHand to 999, zone to 0)
    private val stats = minutes.entries.associate { (g, m) -> g.id to PlayStat(g.id, m, now - TimeUnit.DAYS.toMillis(m.toLong())) }

    /** The two most played games, most played first: its limit picks members by its own sort. */
    private val mostPlayed = FolderDef(
        "most-played",
        rule = JsonObject(mapOf("playMinutes" to JsonObject(mapOf("gte" to JsonPrimitive(1))))),
        sort = "playMinutes:desc",
        limit = 2
    )

    /** The repository's own assembly, with the user's display choices. */
    private fun folders(user: UserData, defs: List<FolderDef> = listOf(mostPlayed)): List<FolderView> {
        val items = folderCandidates(listOf(ac3, trapt, katamari, godHand, zone), listOf(reader), emptyList(), user)
        val subjects = items.map { subjectOf(it, stats[it.id], it.id in user.favourites, now) }
        return evaluateFolders(defs, user.wantToPlay, user.gameProgress, subjects, items.associateBy { it.id }, user.folders, user.hidden, user.folderViews)
    }

    private fun List<FolderView>.of(id: String) = single { it.def.id == id }

    private fun List<FolderView>.ids(id: String) = of(id).items.map { it.id }

    /**
     * A display sort reorders only the members a smart folder's rule and limit chose by its own sort: re-sorting first would
     * swap the two most played for the two newest. Without a choice (an older user.json) the order is the folder's own.
     */
    @Test
    fun displaySortReordersOnlyTheMembersTheRuleAndLimitChose() {
        val plain = folders(UserData()).of("most-played")
        assertEquals(listOf(godHand.id, katamari.id), plain.items.map { it.id })
        assertEquals("playMinutes:desc", plain.displaySort)

        val newest = folders(UserData().withFolderSort("most-played", "added:desc")).of("most-played")
        assertEquals(listOf(katamari.id, godHand.id), newest.items.map { it.id })
        assertEquals("added:desc", newest.displaySort)
        assertEquals(mostPlayed, newest.def)

        // a value this build does not know is ignored, not a crash or a different order
        val unknown = folders(UserData(folderViews = mapOf("most-played" to FolderPreferences(sort = "fit:desc")))).of("most-played")
        assertEquals(plain, unknown)
    }

    /**
     * Built-in folders take a display sort too, each its own: hidden games stay only in Hidden, the game shelves stay games only
     * and Recently added keeps its fourteen days, whatever the order.
     */
    @Test
    fun builtInFoldersSortIndependentlyAndKeepTheirMembers() {
        val user = UserData(
            favourites = setOf(ac3.id, trapt.id, katamari.id, zone.id, reader.id),
            wantToPlay = setOf(reader.id, trapt.id, ac3.id),
            hidden = setOf(zone.id)
        ).withFolderSort(FAVOURITES_FOLDER_ID, "playMinutes:desc")
            .withFolderSort(RECENTLY_ADDED_FOLDER_ID, "title:asc")
            .withFolderSort(HIDDEN_FOLDER_ID, "playMinutes:desc")
        val views = folders(user)
        assertEquals(listOf(katamari.id, ac3.id, trapt.id, reader.id), views.ids(FAVOURITES_FOLDER_ID))
        assertEquals(listOf(ac3.id, trapt.id), views.ids(WANT_TO_PLAY_FOLDER_ID))
        assertEquals("title:asc", views.of(WANT_TO_PLAY_FOLDER_ID).displaySort)
        assertEquals(listOf(ac3.id, katamari.id, trapt.id), views.ids(RECENTLY_ADDED_FOLDER_ID))
        assertEquals(listOf(zone.id), views.ids(HIDDEN_FOLDER_ID))
        assertEquals(listOf(godHand.id, katamari.id), views.ids("most-played"))
    }

    /**
     * Sort and layout are chosen per folder and apart: clearing one keeps the other, clearing both forgets the folder, and a
     * folder without its own layout follows the browse layout. Choosing never adopts or changes a folder's definition.
     */
    @Test
    fun choicesArePerFolderAndResetsInherit() {
        val user = UserData(browseLayout = "grid")
            .withFolderSort(FAVOURITES_FOLDER_ID, "playMinutes:desc")
            .withFolderLayout(FAVOURITES_FOLDER_ID, "list")
            .withFolderLayout("most-played", "list")
        assertTrue(user.folders.isEmpty())
        assertEquals("list", user.folderLayout(FAVOURITES_FOLDER_ID))
        assertEquals("list", user.folderLayout("most-played"))
        assertEquals("grid", user.folderLayout(WANT_TO_PLAY_FOLDER_ID))

        val layoutReset = user.withFolderLayout(FAVOURITES_FOLDER_ID, null)
        assertEquals(FolderPreferences(sort = "playMinutes:desc"), layoutReset.folderViews[FAVOURITES_FOLDER_ID])
        assertEquals("grid", layoutReset.folderLayout(FAVOURITES_FOLDER_ID))
        assertEquals("list", layoutReset.copy(browseLayout = "list").folderLayout(FAVOURITES_FOLDER_ID))
        assertEquals(setOf("most-played"), layoutReset.withFolderSort(FAVOURITES_FOLDER_ID, null).folderViews.keys)

        // values no build offers clear the choice instead of being stored or shown
        assertEquals(user.withFolderLayout("most-played", null), user.withFolderLayout("most-played", "tiles"))
        assertEquals(FolderPreferences(layout = "list"), user.withFolderSort(FAVOURITES_FOLDER_ID, "random").folderViews[FAVOURITES_FOLDER_ID])
        assertEquals("grid", UserData(browseLayout = "grid", folderViews = mapOf("x" to FolderPreferences(layout = "tiles"))).folderLayout("x"))
    }

    /**
     * Deleting a folder forgets its choices and only its own: a surviving subfolder and the built-ins keep theirs, and a new
     * folder that reuses a freed id starts from the defaults.
     */
    @Test
    fun deletingAFolderForgetsOnlyItsChoices() {
        val user = UserData(
            layout = listOf("continue", "folder:consoles", "apps"),
            folders = listOf(
                FolderDef("consoles", include = listOf("folder:nintendo", "folder:handhelds")),
                FolderDef("nintendo", include = listOf("system:snes")),
                FolderDef("handhelds")
            )
        ).withFolderSort("consoles", "title:asc")
            .withFolderLayout("nintendo", "grid")
            .withFolderLayout("handhelds", "grid")
            .withFolderSort(FAVOURITES_FOLDER_ID, "added:desc")
        val views = nestFolders(
            user.folders.map { FolderView(it, emptyList(), emptyList(), inApp = true) } + FolderView(FolderDef(FAVOURITES_FOLDER_ID), emptyList(), emptyList()),
            setOf("snes")
        )
        val home = HomeIds.custom(user.layout, views.map { "folder:" + it.def.id })

        val alone = user.withoutFolder("consoles", views, home, withFolders = false)
        assertEquals(setOf("nintendo", "handhelds", FAVOURITES_FOLDER_ID), alone.folderViews.keys)
        val whole = user.withoutFolder("consoles", views, home, withFolders = true)
        assertEquals(setOf(FAVOURITES_FOLDER_ID), whole.folderViews.keys)
        assertEquals(user, user.withoutFolder(FAVOURITES_FOLDER_ID, views, home, withFolders = false))

        val reused = UserData(browseLayout = "list", folderViews = mapOf("consoles" to FolderPreferences("title:asc", "grid")))
            .withNewFolder("consoles", "Consoles", into = null, beside = null, home = home)
        assertEquals(emptyMap<String, FolderPreferences>(), reused.folderViews)
        assertEquals("list", reused.folderLayout("consoles"))
    }
}
