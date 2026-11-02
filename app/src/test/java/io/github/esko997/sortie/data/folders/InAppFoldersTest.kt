package io.github.esko997.sortie.data.folders

import io.github.esko997.sortie.data.manifest.FolderDef
import io.github.esko997.sortie.data.manifest.UserData
import io.github.esko997.sortie.ui.HomeIds
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
        val moved = created.withMoved("system:snes", from = null, to = "lists", home = created.layout)
        assertEquals(listOf("continue", "folder:lists", trapt, "folder:favourites", "apps"), moved.layout)
        assertEquals(listOf(FolderDef("lists", "Lists", include = listOf("system:snes"))), moved.folders)

        val inner = moved.withNewFolder("snes-stuff", "SNES", into = "lists", beside = "system:snes", home = moved.layout)
        assertEquals(listOf("folder:snes-stuff", "system:snes"), inner.folders.single { it.id == "lists" }.include)
        val onward = inner.withMoved("system:snes", from = "lists", to = "snes-stuff", home = inner.layout)
        assertEquals(listOf("folder:snes-stuff"), onward.folders.single { it.id == "lists" }.include)
        assertEquals(listOf("system:snes"), onward.folders.single { it.id == "snes-stuff" }.include)
        assertEquals(moved.layout, onward.layout)
    }

    /** Moving up a level puts the tile just after the folder it left, in the parent folder or on Home; Remove only drops the entry. */
    @Test
    fun moveUpAndRemove() {
        val user = UserData(
            layout = listOf("continue", "folder:lists", "apps"),
            folders = listOf(
                FolderDef("lists", include = listOf("folder:rpgs", "system:psx")),
                FolderDef("rpgs", include = listOf("folder:jrpgs", "app:org.ppsspp.ppsspp"))
            )
        )
        val toParent = user.withMoved("folder:jrpgs", from = "rpgs", to = "lists", home = user.layout)
        assertEquals(listOf("folder:rpgs", "folder:jrpgs", "system:psx"), toParent.folders[0].include)
        assertEquals(listOf("app:org.ppsspp.ppsspp"), toParent.folders[1].include)

        val toHome = user.withMoved("system:psx", from = "lists", to = null, home = user.layout)
        assertEquals(listOf("continue", "folder:lists", "system:psx", "apps"), toHome.layout)

        val removed = user.withRemovedFromFolder("app:org.ppsspp.ppsspp", from = "rpgs")
        assertEquals(listOf("folder:jrpgs"), removed.folders[1].include)
        assertEquals(user.layout, removed.layout)
    }

    /**
     * Home's saved order keeps the tiles a folder shows (Home hides them only when drawing). Here a desktop folders.json folder
     * points to `system:snes`: an unrelated folder edit must not drop it from the layout, so it is back in its place on Custom
     * once that pointer goes.
     */
    @Test
    fun folderEditsKeepTilesShownInsideFolders() {
        val user = UserData(layout = listOf("system:snes", "system:psx", trapt), folders = listOf(FolderDef("lists")))
        val home = HomeIds.custom(user.layout, listOf("folder:desktop", "folder:lists"))
        val edited = user.withMoved(trapt, from = null, to = "lists", home = home)
        assertEquals(listOf("continue", "system:snes", "system:psx", "folder:desktop", "folder:lists", "apps"), edited.layout)
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

        // Home's order also holds the pointers themselves (an automatic folder tile is always in it, and nested tiles keep their
        // saved place): they still land where the deleted folder was
        val automatic = UserData(folders = listOf(FolderDef("lists", include = listOf("system:snes", "folder:favourites"))))
        val home = HomeIds.custom(listOf("folder:lists", "system:snes"), listOf("folder:lists", "folder:favourites"))
        val deleted = automatic.withoutFolder("lists", emptyList(), home = home)
        assertEquals(listOf("continue", "system:snes", "folder:favourites", "apps"), deleted.layout)
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
