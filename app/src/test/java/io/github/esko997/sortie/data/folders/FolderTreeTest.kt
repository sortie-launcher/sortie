package io.github.esko997.sortie.data.folders

import io.github.esko997.sortie.data.manifest.FAVOURITES_FOLDER_ID
import io.github.esko997.sortie.data.manifest.FolderDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderTreeTest {
    private fun view(id: String, vararg include: String, inApp: Boolean = false) =
        FolderView(FolderDef(id, include = include.toList()), emptyList(), emptyList(), inApp = inApp)

    private fun List<FolderView>.of(id: String) = single { it.def.id == id }

    /**
     * Pointers resolve to folders and known systems in include order (unknown ones are skipped, not reported); what a folder
     * shows leaves Home's top level, a folder with two parents shows in both, and a folder nothing points to stays on Home.
     */
    @Test
    fun pointersNestFoldersAndSystemsAndHideThemFromHome() {
        val views = nestFolders(
            listOf(
                view("lists", "folder:favourites", "ps2/Trapt.iso", "system:gone", "system:snes", "folder:missing", "folder:favourites"),
                view("mine", "folder:favourites", inApp = true),
                view("rpgs"),
                view(FAVOURITES_FOLDER_ID)
            ),
            systems = setOf("snes", "psx")
        )
        assertEquals(listOf("folder:favourites", "system:snes"), views.of("lists").children)
        assertEquals(listOf("lists", "mine"), views.of(FAVOURITES_FOLDER_ID).parents)
        assertEquals(2, views.of(FAVOURITES_FOLDER_ID).level)
        assertEquals(1, views.of("lists").level)
        assertEquals(setOf("folder:favourites", "system:snes"), views.nestedTileIds())
        assertTrue(views.all { it.problems.isEmpty() })
    }

    /** Hand-edited cycles are cut where they close, reported there, and never leave a folder without a way in from Home. */
    @Test
    fun cyclesStopWhereTheyCloseAndEveryFolderStaysReachable() {
        val loop = nestFolders(listOf(view("a", "folder:b"), view("b", "folder:a"), view("self", "folder:self")), emptySet())
        assertEquals(listOf("folder:b"), loop.of("a").children)
        assertEquals(emptyList<String>(), loop.of("b").children)
        assertEquals(1, loop.of("b").problems.size)
        assertEquals(emptyList<String>(), loop.of("self").children)
        assertEquals(1, loop.of("self").problems.size)
        assertEquals(setOf("folder:b"), loop.nestedTileIds())

        // a folder nothing points to is walked first, so the cycle below it is cut at its deepest point
        val rooted = nestFolders(listOf(view("b", "folder:a"), view("a", "folder:b"), view("root", "folder:a")), emptySet())
        assertEquals(listOf("root"), rooted.of("a").parents)
        assertEquals(listOf("a"), rooted.of("b").parents)
        assertEquals(setOf("folder:a", "folder:b"), rooted.nestedTileIds())
        assertEquals(3, rooted.of("b").level)
    }

    /** Folders nest four deep: a deeper pointer is not followed (and is reported), so that folder stays on Home; systems still show. */
    @Test
    fun nestingStopsAtTheDepthCap() {
        val views = nestFolders(
            listOf(view("l1", "folder:l2"), view("l2", "folder:l3"), view("l3", "folder:l4"), view("l4", "folder:l5", "system:snes"), view("l5")),
            setOf("snes")
        )
        assertEquals(listOf(1, 2, 3, 4, 1), listOf("l1", "l2", "l3", "l4", "l5").map { views.of(it).level })
        assertEquals(listOf("system:snes"), views.of("l4").children)
        assertEquals(1, views.of("l4").problems.size)
        assertFalse("folder:l5" in views.nestedTileIds())
        assertEquals(mapOf("l1" to 1, "l2" to 2, "l3" to 3, "l4" to 4), views.below("folder:l1"))
        assertEquals(mapOf("l4" to 1), views.below("folder:l4"))
        assertEquals(emptyMap<String, Int>(), views.below("system:snes"))
    }

    /** What a move may target: not the folder itself or anything inside it, at the deepest depth a folder shows up below. */
    @Test
    fun belowFollowsTheResolvedTree() {
        val views = nestFolders(
            listOf(view("lists", "folder:rpgs", "folder:jrpgs"), view("rpgs", "folder:jrpgs"), view("jrpgs"), view("other")),
            emptySet()
        )
        assertEquals(mapOf("lists" to 1, "rpgs" to 2, "jrpgs" to 3), views.below("folder:lists"))
        assertFalse("other" in views.below("folder:lists"))
    }

    /** An in-app folder keeps an id a folders.json entry also uses; one claiming a built-in id is dropped. */
    @Test
    fun inAppFolderWinsOverFoldersJsonAndLosesToBuiltIns() {
        val views = evaluateFolders(
            defs = listOf(FolderDef("rpgs", "Desktop RPGs"), FolderDef("fighters")),
            wantToPlay = emptySet(),
            progress = emptyMap(),
            subjects = emptyList(),
            byId = emptyMap(),
            inApp = listOf(FolderDef("rpgs", "My RPGs"), FolderDef(FAVOURITES_FOLDER_ID, "Mine"), FolderDef("rpgs", "Second"))
        )
        val rpgs = views.single { it.def.id == "rpgs" }
        assertEquals("My RPGs", rpgs.def.label)
        assertTrue(rpgs.inApp)
        assertTrue(rpgs.problems.isEmpty())
        assertFalse(views.single { it.def.id == "fighters" }.inApp)
        val favourites = views.single { it.def.id == FAVOURITES_FOLDER_ID }
        assertFalse(favourites.inApp)
        assertTrue(favourites.problems.isEmpty())
    }
}
