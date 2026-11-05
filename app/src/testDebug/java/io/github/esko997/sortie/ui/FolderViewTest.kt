package io.github.esko997.sortie.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.data.folders.folderLayout
import io.github.esko997.sortie.data.manifest.FAVOURITES_FOLDER_ID
import io.github.esko997.sortie.data.manifest.FolderPreferences
import io.github.esko997.sortie.data.manifest.UserData
import io.github.esko997.sortie.data.manifest.WANT_TO_PLAY_FOLDER_ID
import io.github.esko997.sortie.testing.Controller
import io.github.esko997.sortie.testing.Controller.Companion.A
import io.github.esko997.sortie.testing.Controller.Companion.DOWN
import io.github.esko997.sortie.testing.Controller.Companion.SELECT
import io.github.esko997.sortie.testing.SortieActivityRule
import io.github.esko997.sortie.testing.SortieCardRule
import io.github.esko997.sortie.testing.THOR_TOP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A folder's own order and list/grid, on the Thor's top screen (compact header) over the fixture card: Shooters comes from
 * folders.json, Want to play is built in and empty. The fixture leaves the browse layout at its default, list.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = THOR_TOP)
class FolderViewTest {
    @get:Rule(order = 0)
    val card = SortieCardRule()

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @get:Rule(order = 2)
    val sortie = SortieActivityRule()

    private val pad = Controller()

    private val user: UserData get() = (sortie.activity.application as SortieApp).graph.user.data.value

    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun open(route: String, vararg texts: String) {
        compose.waitUntil(10_000) { shown("Shooters") }
        Selection.openRoute.tryEmit(route)
        compose.waitUntil(10_000) { texts.all(::shown) }
    }

    /** Gives the controller focus to the header button whose label starts with [label], then presses A on it. */
    private fun pressHeaderButton(label: String) {
        compose.onNodeWithText(label, substring = true).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        assertTrue(pad.focusedLabel().orEmpty().startsWith(label))
        pad.press(A)
        // the sheet takes the focus a few frames after it opens
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    /** Robolectric removes a closed dialog without telling the activity that its window has the focus again. */
    private fun dialogClosed() {
        compose.runOnIdle { sortie.controller.windowFocusChanged(true) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    /**
     * Select inside a folder saves that folder's layout, and its Sort reorders it without adopting the folders.json folder or
     * changing what it holds; the shared browse layout, other folders and systems are untouched, and Select on a system list
     * still changes the shared layout without touching the folder's choices.
     */
    @Test
    fun selectAndSortInsideAFolderSaveOnlyThatFolder() {
        open("folder/shooters", "Bolt Runner", "Night Lancer")
        pad.press(SELECT)
        compose.waitUntil(5_000) { user.folderLayout("shooters") == "grid" }
        assertEquals("list", user.browseLayout)
        assertEquals("list", user.folderLayout(FAVOURITES_FOLDER_ID))

        compose.onNodeWithText("Sort:", substring = true).performClick()
        compose.onNodeWithText("Play time").performClick()
        compose.waitUntil(5_000) { shown("Sort: Play time") }
        assertEquals("playMinutes:desc", user.folderViews["shooters"]?.sort)
        assertTrue("sorting adopted the folders.json folder", user.folders.isEmpty())
        assertTrue("sorting changed what the folder holds", shown("Bolt Runner") && shown("Night Lancer"))
        val folderViews = user.folderViews
        assertEquals(mapOf("shooters" to FolderPreferences("playMinutes:desc", "grid")), folderViews)

        open("system/genesis", "Cinder Vale")
        pad.press(SELECT)
        compose.waitUntil(5_000) { user.browseLayout == "grid" }
        assertEquals(folderViews, user.folderViews)
    }

    /**
     * An empty built-in folder keeps its Sort and View in reach of the controller: each sheet saves only that folder's choice
     * and gives the focus back to its button, which shows the new choice; the View sheet's first choice goes back to the
     * browse layout and keeps the sort.
     */
    @Test
    fun emptyBuiltInFolderSortsAndSwitchesViewFromTheController() {
        open("folder/$WANT_TO_PLAY_FOLDER_ID", "Nothing here yet.")
        pad.press(DOWN) // leaves touch mode
        compose.waitForIdle()

        pressHeaderButton("View:")
        pad.press(DOWN, DOWN, A) // the browse layout, List, Grid
        dialogClosed()
        assertEquals("grid", user.folderLayout(WANT_TO_PLAY_FOLDER_ID))
        assertEquals("View: Grid", pad.focusedLabel())

        pressHeaderButton("Sort:")
        pad.press(DOWN, DOWN, DOWN, A) // the folder's own order, Title, Recently played, Recently added
        dialogClosed()
        assertEquals(FolderPreferences("added:desc", "grid"), user.folderViews[WANT_TO_PLAY_FOLDER_ID])
        assertTrue("a view choice copied a built-in folder", user.folders.isEmpty())
        assertTrue(pad.focusedLabel().orEmpty().startsWith("Sort:"))

        pressHeaderButton("View:")
        pad.press(A) // back to the browse layout
        dialogClosed()
        assertEquals(mapOf(WANT_TO_PLAY_FOLDER_ID to FolderPreferences("added:desc", null)), user.folderViews)
        assertEquals("list", user.browseLayout)
        assertEquals("View: List", pad.focusedLabel())
    }
}
