package io.github.esko997.sortie.data.folders

import io.github.esko997.sortie.data.manifest.FolderDef
import io.github.esko997.sortie.data.manifest.UserData
import org.junit.Assert.assertEquals
import org.junit.Test

class InAppFoldersTest {
    private val trapt = "ps2/Trapt.iso"

    /** Ids are slugs of the name, made unique against every folder id with a counting suffix; the same inputs, the same id. */
    @Test
    fun newIdsAreStableSlugsUniqueAmongAllFolders() {
        assertEquals("my-rpgs", newFolderId("  My RPGs!  ", emptySet()))
        assertEquals("my-rpgs-3", newFolderId("My RPGs", setOf("my-rpgs", "my-rpgs-2")))
        assertEquals("favourites-2", newFolderId("Favourites", BUILT_IN_FOLDER_IDS))
        assertEquals("folder", newFolderId("ゲーム", emptySet()))
    }

    /**
     * Move to folder… → New folder… on Home: the new folder takes the tile's place (the whole Custom order is saved, as a Move
     * saves it) and the tile goes inside; a further move from that folder into another one takes it out of the first.
     */
    @Test
    fun movingFromHomeIntoANewFolderAndOnward() {
        val home = listOf("continue", "system:snes", trapt, "folder:favourites", "apps")
        val created = UserData(layout = listOf("system:snes", trapt)).withNewFolder("lists", "  Lists ", into = null, beside = "system:snes", home = home)
        val moved = created.withMovedToFolder("system:snes", from = null, to = "lists", home = created.layout)
        assertEquals(listOf("continue", "folder:lists", trapt, "folder:favourites", "apps"), moved.layout)
        assertEquals(listOf(FolderDef("lists", "Lists", include = listOf("system:snes"))), moved.folders)

        val inner = moved.withNewFolder("snes-stuff", "SNES", into = "lists", beside = "system:snes", home = moved.layout)
        assertEquals(listOf("folder:snes-stuff", "system:snes"), inner.folders.single { it.id == "lists" }.include)
        val onward = inner.withMovedToFolder("system:snes", from = "lists", to = "snes-stuff", home = inner.layout)
        assertEquals(listOf("folder:snes-stuff"), onward.folders.single { it.id == "lists" }.include)
        assertEquals(listOf("system:snes"), onward.folders.single { it.id == "snes-stuff" }.include)
        assertEquals(moved.layout, onward.layout)
    }

    /** Move out goes to the parent in-app folder, or to Home, just after the folder it left; Remove only drops the entry. */
    @Test
    fun moveOutAndRemove() {
        val user = UserData(
            layout = listOf("continue", "folder:lists", "apps"),
            folders = listOf(
                FolderDef("lists", include = listOf("folder:rpgs", "system:psx")),
                FolderDef("rpgs", include = listOf(trapt, "app:org.ppsspp.ppsspp"))
            )
        )
        val toParent = user.withMovedOut(trapt, from = "rpgs", to = "lists", home = user.layout)
        assertEquals(listOf("folder:rpgs", trapt, "system:psx"), toParent.folders[0].include)
        assertEquals(listOf("app:org.ppsspp.ppsspp"), toParent.folders[1].include)

        val toHome = user.withMovedOut("system:psx", from = "lists", to = null, home = user.layout)
        assertEquals(listOf("continue", "folder:lists", "system:psx", "apps"), toHome.layout)

        val removed = user.withRemovedFromFolder("app:org.ppsspp.ppsspp", from = "rpgs")
        assertEquals(listOf(trapt), removed.folders[1].include)
        assertEquals(user.layout, removed.layout)
    }

    /** Add to folder… keeps the item wherever else it is, lists it once and clears a hand-written exclude that would hide it. */
    @Test
    fun addToFolderListsOnce() {
        val user = UserData(layout = listOf(trapt), folders = listOf(FolderDef("rpgs", exclude = listOf(trapt))))
        val added = user.withAddedToFolder(trapt, "rpgs").withAddedToFolder(trapt, "rpgs")
        assertEquals(FolderDef("rpgs", include = listOf(trapt)), added.folders.single())
        assertEquals(listOf(trapt), added.layout)
    }

    /** The parents [nestFolders] resolves for [id] among [user]'s in-app folders, as the folder screens show them. */
    private fun parentsOf(user: UserData, id: String) =
        nestFolders(user.folders.map { FolderView(it, emptyList(), emptyList(), inApp = true) }, setOf("snes", "psx")).single { it.def.id == id }.parents

    /**
     * Deleting a folder puts the folders and systems it held where it was shown — in its in-app parent, else in its place on
     * Home — and drops only its own list of games. Here the two folders also point at each other (hand-edited): the pointer
     * the tree did not follow just goes, and none is copied into the folder it names.
     */
    @Test
    fun deleteReturnsPointersWhereTheFolderWas() {
        val user = UserData(
            layout = listOf("continue", "folder:lists", "apps"),
            folders = listOf(
                FolderDef("lists", label = "Lists", include = listOf("folder:favourites", "system:snes", trapt, "folder:rpgs")),
                FolderDef("rpgs", include = listOf("system:psx", "folder:lists", trapt))
            )
        )
        assertEquals(emptyList<String>(), parentsOf(user, "lists"))
        val top = user.withoutFolder("lists", parentsOf(user, "lists"), home = user.layout)
        assertEquals(listOf("continue", "folder:favourites", "system:snes", "folder:rpgs", "apps"), top.layout)
        assertEquals(listOf(FolderDef("rpgs", include = listOf("system:psx", trapt))), top.folders)

        assertEquals(listOf("lists"), parentsOf(user, "rpgs"))
        val nested = user.withoutFolder("rpgs", parentsOf(user, "rpgs"), home = user.layout)
        assertEquals(listOf("folder:favourites", "system:snes", trapt, "system:psx"), nested.folders.single().include)
        assertEquals(user.layout, nested.layout)

        // an automatic folder tile has its place in the Custom order even when the saved layout lacks it
        val automatic = UserData(folders = listOf(FolderDef("lists", include = listOf("system:snes"))))
        val deleted = automatic.withoutFolder("lists", emptyList(), home = listOf("continue", "folder:lists", "apps"))
        assertEquals(listOf("continue", "system:snes", "apps"), deleted.layout)
    }

    /** Restoring a hand-edited backup: blank and repeated ids go (the first kept), names are trimmed and bounded. */
    @Test
    fun restoredFoldersAreCleaned() {
        val cleaned = listOf(
            FolderDef(" ", "Blank"),
            FolderDef("a", "  " + "x".repeat(FOLDER_LABEL_MAX + 5), include = listOf(trapt, trapt), limit = -3),
            FolderDef("a", "Second")
        ).cleaned()
        assertEquals(listOf(FolderDef("a", "x".repeat(FOLDER_LABEL_MAX), include = listOf(trapt))), cleaned)
    }
}
