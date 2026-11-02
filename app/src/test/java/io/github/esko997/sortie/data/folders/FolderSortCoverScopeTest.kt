package io.github.esko997.sortie.data.folders

import io.github.esko997.sortie.data.apps.LibraryItem
import io.github.esko997.sortie.data.db.GameEntity
import io.github.esko997.sortie.data.manifest.FolderDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderSortCoverScopeTest {
    private fun game(title: String, cover: String? = null, addedAt: Long = 0) = GameEntity(
        id = "ps2/$title.iso", systemId = "ps2", folder = "ps2", fileName = "$title.iso", path = "/x/$title.iso", size = 1, mtime = 0,
        title = title, sortTitle = title.lowercase(), desc = null, rating = null, year = 2004, developer = null, publisher = null,
        genre = null, players = "1", playersMax = 1, fit = 3, hook = null, flags = "", tags = "", handWritten = false,
        cover = cover, screenshot = null, marquee = null, placeholder = false, arriving = false, hack = false, addedAt = addedAt
    )

    private val alpha = game("Alpha", addedAt = 3)
    private val beta = game("Beta", cover = "beta.png", addedAt = 1)
    private val gamma = game("Gamma", cover = "gamma.png", addedAt = 2)

    private fun view(id: String, vararg games: GameEntity, include: List<String> = emptyList(), cover: String? = null) =
        FolderView(FolderDef(id, include = include, cover = cover), games.map { LibraryItem.Game(it) }, emptyList())

    /** Each Sort by… choice orders a folder as its name says; never-played games come last under Recently played. */
    @Test
    fun sortChoicesOrderAsNamed() {
        val subjects = listOf(
            Subject(alpha, playMinutes = 5, lastPlayed = 100, favourite = false),
            Subject(beta, playMinutes = 50, lastPlayed = null, favourite = false),
            Subject(gamma, playMinutes = 0, lastPlayed = 200, favourite = false)
        )
        val byId = listOf(alpha, beta, gamma).associate { it.id to LibraryItem.Game(it) }
        val orders = FOLDER_SORTS.associate { (label, sort) ->
            val def = FolderDef("f", include = byId.keys.toList(), sort = sort)
            label to evaluateFolders(listOf(def), emptySet(), emptyMap(), subjects, byId).single { it.def.id == "f" }.items.map { it.label }
        }
        assertEquals(
            mapOf(
                "Title" to listOf("Alpha", "Beta", "Gamma"),
                "Recently played" to listOf("Gamma", "Alpha", "Beta"),
                "Recently added" to listOf("Alpha", "Gamma", "Beta"),
                "Play time" to listOf("Beta", "Alpha", "Gamma")
            ),
            orders
        )
    }

    /** The tile shows the chosen game's cover while it is a member with one, else the first game's in folder order that has one. */
    @Test
    fun coverIsTheChosenMemberOrTheFirstWithArt() {
        assertEquals("beta.png", view("f", alpha, beta, gamma).coverArt)
        assertEquals("gamma.png", view("f", alpha, beta, gamma, cover = gamma.id).coverArt)
        assertEquals("beta.png", view("f", alpha, beta, cover = gamma.id).coverArt)
        assertEquals("beta.png", view("f", alpha, beta, gamma, cover = alpha.id).coverArt)
        assertNull(view("f", alpha).coverArt)
    }

    /** Search in a folder looks through the games of the folders shown inside it too, as deep as they nest, and nothing else. */
    @Test
    fun folderSearchCoversShownSubfolders() {
        val delta = game("Delta")
        val views = nestFolders(
            listOf(
                view("top", alpha, include = listOf("folder:sub")),
                view("sub", beta, include = listOf("folder:inner", "folder:top")),
                view("inner", gamma),
                view("other", delta)
            ),
            emptySet()
        )
        assertEquals(setOf(alpha.id, beta.id, gamma.id), views.gamesWithin("top"))
        assertEquals(setOf(beta.id, gamma.id), views.gamesWithin("sub"))
        assertEquals(emptySet<String>(), views.gamesWithin("missing"))

        // a folder nested deeper than the cap is not shown inside, so its games are not searched either
        val chain = nestFolders(
            listOf(
                view("l1", include = listOf("folder:l2")),
                view("l2", include = listOf("folder:l3")),
                view("l3", include = listOf("folder:l4")),
                view("l4", alpha, include = listOf("folder:l5")),
                view("l5", beta)
            ),
            emptySet()
        )
        assertEquals(setOf(alpha.id), chain.gamesWithin("l1"))
    }
}
