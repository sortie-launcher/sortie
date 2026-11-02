package io.github.esko997.sortie.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeIdsTest {
    private val folders = listOf("folder:fighters", "folder:want-to-play")

    /** Saved pins keep their order (unresolved ids too); Continue leads, then the missing folders and Apps follow, once each. */
    @Test
    fun customKeepsTheLayoutAndAddsEveryAutomaticEntryOnce() {
        val layout = listOf("system:snes", "folder:want-to-play", "gone/Removed.sfc", "app:org.ppsspp.ppsspp", "system:snes")
        assertEquals(
            listOf("continue", "system:snes", "folder:want-to-play", "gone/Removed.sfc", "app:org.ppsspp.ppsspp", "folder:fighters", "apps"),
            HomeIds.custom(layout, folders)
        )
    }

    /** A saved (moved) order is a fixed point: the entries the user placed stay where they were dropped. */
    @Test
    fun customKeepsMovedAutomaticEntries() {
        val moved = listOf("folder:fighters", "apps", "system:snes", "continue", "folder:want-to-play")
        assertEquals(moved, HomeIds.custom(moved, folders))
        assertEquals(listOf("continue", "folder:fighters", "folder:want-to-play", "apps"), HomeIds.custom(emptyList(), folders))
    }

    @Test
    fun automaticEntriesAreNotGamesOrRemovable() {
        assertTrue(HomeIds.isAutomatic("continue"))
        assertTrue(HomeIds.isAutomatic("apps"))
        assertTrue(HomeIds.isAutomatic("folder:want-to-play"))
        assertFalse(HomeIds.isAutomatic("system:snes"))
        assertFalse(HomeIds.isAutomatic("snes/Game.sfc"))
        assertFalse(HomeIds.isGame("continue"))
        assertTrue(HomeIds.isGame("snes/Game.sfc"))
    }
}
