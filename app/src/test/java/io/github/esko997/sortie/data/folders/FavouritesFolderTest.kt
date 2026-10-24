package io.github.esko997.sortie.data.folders

import android.content.ComponentName
import io.github.esko997.sortie.data.apps.AppEntry
import io.github.esko997.sortie.data.apps.ShortcutEntry
import io.github.esko997.sortie.data.db.GameEntity
import io.github.esko997.sortie.data.manifest.FAVOURITES_FOLDER_ID
import io.github.esko997.sortie.data.manifest.FolderDef
import io.github.esko997.sortie.data.manifest.UserData
import io.github.esko997.sortie.data.manifest.WANT_TO_PLAY_FOLDER_ID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FavouritesFolderTest {
    private fun game(title: String, sortTitle: String = title.lowercase()) = GameEntity(
        id = "ps2/$title.iso", systemId = "ps2", folder = "ps2", fileName = "$title.iso", path = "/x/$title.iso", size = 1, mtime = 0,
        title = title, sortTitle = sortTitle, desc = null, rating = null, year = 2004, developer = null, publisher = null,
        genre = null, players = "1", playersMax = 1, fit = 3, hook = null, flags = "", tags = "", handWritten = false,
        cover = null, screenshot = null, marquee = null, placeholder = false, arriving = false, hack = false, addedAt = 0
    )

    private fun app(pkg: String, label: String) = AppEntry(pkg, label, ComponentName(pkg, "$pkg.Main"))

    private val ac3 = game("Armored Core 3")
    private val godHand = game("The God Hand", sortTitle = "god hand")
    private val trapt = game("Trapt")
    private val katamari = game("Katamari Damacy")
    private val firefox = app("org.mozilla.firefox", "Firefox")
    private val chrome = app("com.android.chrome", "Chrome")
    private val bloodyRoar = ShortcutEntry("com.droiddeck.launcher", "br", "Bloody Roar", manifestOnly = false)
    private val citra = ShortcutEntry("org.citra.emu", "pinned", "Citra game", manifestOnly = false)

    /** The repository's own candidate assembly (hidden filter, chosen shortcuts only) feeding the folder evaluation. */
    private fun folders(user: UserData, defs: List<FolderDef> = emptyList()): List<FolderView> {
        val items = folderCandidates(listOf(trapt, godHand, ac3, katamari), listOf(firefox, chrome), listOf(bloodyRoar, citra), user)
        val subjects = items.map { subjectOf(it, null, it.id in user.favourites, 0) }
        return evaluateFolders(defs, user.wantToPlay, user.gameProgress, subjects, items.associateBy { it.id })
    }

    private fun List<FolderView>.favourites() = single { it.def.id == FAVOURITES_FOLDER_ID }

    /**
     * Favourited games, apps and shortcuts share one title-ordered folder; a favourited shortcut counts without an Apps opt-in,
     * while hidden, uninstalled and unindexed favourites resolve to nothing. The personal game shelves stay game-only.
     */
    @Test
    fun favouritedGamesAppsAndShortcutsListByTitleWithoutHiddenOrMissingEntries() {
        val user = UserData(
            favourites = setOf(trapt.id, firefox.id, bloodyRoar.id, godHand.id, katamari.id, "app:uninstalled.pkg", "ps2/Not Yet Indexed.iso"),
            hidden = setOf(katamari.id),
            wantToPlay = setOf(ac3.id, firefox.id)
        )
        val views = folders(user)
        assertEquals(listOf(bloodyRoar.id, firefox.id, godHand.id, trapt.id), views.favourites().items.map { it.id })
        assertTrue(views.favourites().problems.isEmpty())
        assertEquals(listOf(ac3.id), views.single { it.def.id == WANT_TO_PLAY_FOLDER_ID }.items.map { it.id })
    }

    /** Unfavouriting removes the entry; with none left the folder is still there, empty, so its home tile keeps opening. */
    @Test
    fun unfavouritingRemovesEntriesAndLeavesAnEmptyFolder() {
        val before = UserData(favourites = setOf(trapt.id, firefox.id))
        assertEquals(listOf(firefox.id, trapt.id), folders(before).favourites().items.map { it.id })

        val fewer = before.copy(favourites = setOf(trapt.id))
        assertEquals(listOf(trapt.id), folders(fewer).favourites().items.map { it.id })

        val none = folders(fewer.copy(favourites = emptySet())).favourites()
        assertTrue(none.items.isEmpty())
        assertTrue(none.problems.isEmpty())
    }

    /** A folders.json entry with the reserved id is dropped and reported once; the built-in keeps its members, other folders are untouched. */
    @Test
    fun manifestFolderWithTheReservedIdIsIgnoredAndReported() {
        val everything = FolderDef(FAVOURITES_FOLDER_ID, "Mine", rule = JsonObject(mapOf("favourite" to JsonPrimitive(false))), apps = true)
        val views = folders(UserData(favourites = setOf(trapt.id)), listOf(everything, FolderDef("all", include = listOf(ac3.id))))
        assertEquals(1, views.count { it.def.id == FAVOURITES_FOLDER_ID })
        assertEquals(listOf(trapt.id), views.favourites().items.map { it.id })
        assertEquals(1, views.favourites().problems.size)
        assertEquals(listOf(ac3.id), views.single { it.def.id == "all" }.items.map { it.id })
    }
}
