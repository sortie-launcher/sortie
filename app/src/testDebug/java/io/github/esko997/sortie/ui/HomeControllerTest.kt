package io.github.esko997.sortie.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.mecha.MechaArcadeGame
import io.github.esko997.sortie.mecha.MechaFinish
import io.github.esko997.sortie.mecha.MechaSlot
import io.github.esko997.sortie.testing.Controller
import io.github.esko997.sortie.testing.Controller.Companion.A
import io.github.esko997.sortie.testing.Controller.Companion.B
import io.github.esko997.sortie.testing.Controller.Companion.DOWN
import io.github.esko997.sortie.testing.Controller.Companion.LEFT
import io.github.esko997.sortie.testing.Controller.Companion.RIGHT
import io.github.esko997.sortie.testing.Controller.Companion.UP
import io.github.esko997.sortie.testing.DMG
import io.github.esko997.sortie.testing.RP_NOVA
import io.github.esko997.sortie.testing.SortieActivityRule
import io.github.esko997.sortie.testing.SortieCardRule
import io.github.esko997.sortie.testing.THOR_TOP
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Home driven with the controller on the Thor's top screen, over the fixture card (custom sort, five-column grid). The screen is
 * under 600 dp tall, so with the default `browseChrome` (Auto) the header folds while browsing the tiles.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = THOR_TOP)
class HomeControllerTest {
    @get:Rule(order = 0)
    val card = SortieCardRule()

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @get:Rule(order = 2)
    val sortie = SortieActivityRule()

    private val pad = Controller()

    /** Home with the whole library: the index (6 games) and the smart folders, which resolve after it. */
    private fun awaitHome() = compose.waitUntil(10_000) {
        listOf("6 games", "Shooters").all { compose.onAllNodesWithText(it, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private val mecha get() = (sortie.activity.application as SortieApp).graph.mecha

    /** Unrelated Hangar scenarios start with an acknowledged guide, without buying or equipping anything. */
    private fun seedHangarIntroductionSeen() {
        compose.waitUntil(5_000) { mecha.view.value.state != null && !mecha.view.value.busy }
        runBlocking { mecha.restore(mecha.view.value.state!!.copy(hangarIntroSeen = true), emptyList()) }
    }

    /** Presses [keys] one by one and returns what has the focus after each. */
    private fun walk(vararg keys: Int): List<String?> = keys.map { key ->
        pad.press(key)
        compose.waitForIdle()
        pad.focusedLabel()
    }

    /** Presses [key] until [label] has the focus, at most [max] times; fails when it never gets there. */
    private fun pressUntil(key: Int, label: String, max: Int = 8) {
        repeat(max) {
            if (pad.focusedLabel() == label) return
            pad.press(key)
            compose.waitForIdle()
        }
        assertEquals(label, pad.focusedLabel())
    }

    private fun shown(description: String) = compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()

    private fun sortRowShown() = compose.onAllNodesWithText("Sort").fetchSemanticsNodes().isNotEmpty()

    /** The first tile of the fixture's custom layout (Continue is absent: nothing has been played). */
    private val firstTile = "PlayStation 1 · 32-bit"

    /**
     * Regression for "Home, Home, B sent Sortie away" (docs/HARDENING.md): where Sortie runs in an ordinary task, Back on Home
     * moved the task to the back and left Recents or a stale app on screen. Home is the root, so Back must do nothing there.
     */
    @Test
    fun backOnHomeAfterHomePressesKeepsSortieOnHome() {
        awaitHome()
        sortie.pressHome()
        sortie.pressHome()
        pad.press(B)
        compose.waitForIdle()
        val activity = sortie.activity
        assertFalse("Back finished Sortie", activity.isFinishing)
        assertFalse("Back moved Sortie's task behind other apps", shadowOf(activity).isTaskMovedToBack)
        compose.onNodeWithText("Sort").assertIsDisplayed()
        // Home still answers the controller: the library remains reachable.
        pressUntil(DOWN, firstTile)
    }

    /**
     * The header opens with Home and its actions remain reachable from the controller. Walking the header does not fold it;
     * Down reaches the tiles, which folds it to the strip. Up from the first row opens it again with the focus on the chosen
     * sort (the row just above the grid), never on the strip, and Down goes back into the tiles, folding it again.
     */
    @Test
    fun enteringTheTilesFoldsTheHeaderAndUpFromTheFirstRowOpensItOnTheSort() {
        awaitHome()
        focusHeader()
        assertEquals(listOf("Search", "Apps"), walk(RIGHT, LEFT))
        assertTrue("choosing a header action folded the header", sortRowShown())
        repeat(4) { if (!shown(SHOW_HEADER)) walk(DOWN) }
        assertFalse("the sort row is still shown while browsing the tiles", sortRowShown())
        assertTrue("the folded strip has no chevron", shown(SHOW_HEADER))

        assertEquals(listOf("Custom"), walk(UP))
        assertTrue("Up did not open the header", sortRowShown())
        assertTrue(shown(HIDE_HEADER))
        assertFalse(shown(SHOW_HEADER))

        val back = walk(DOWN).single()
        assertNotNull("the focus was lost on the way back into the tiles", back)
        assertFalse("the header did not fold again", sortRowShown())
    }

    /** The chevron folds and opens the header in place from the controller: the focus stays on the chevron both ways. */
    @Test
    fun chevronFoldsAndOpensTheHeaderKeepingTheFocus() {
        awaitHome()
        pad.press(DOWN)
        compose.waitForIdle()
        pressUntil(RIGHT, HIDE_HEADER)
        assertEquals(listOf(SHOW_HEADER), walk(A))
        assertFalse(sortRowShown())
        assertEquals(listOf(HIDE_HEADER), walk(A))
        assertTrue(sortRowShown())
    }

    /** A tap on the folded strip opens the header, and so does a pull down that starts on the strip. */
    @Test
    fun tapOrPullDownOnTheStripOpensTheHeader() {
        awaitHome()
        compose.onNodeWithContentDescription(HIDE_HEADER).performClick()
        compose.waitForIdle()
        assertTrue(shown(SHOW_HEADER))
        compose.onNodeWithText("games · Custom", substring = true).performTouchInput { click() }
        compose.waitForIdle()
        assertTrue("a tap on the strip did not open the header", sortRowShown())

        compose.onNodeWithContentDescription(HIDE_HEADER).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("games · Custom", substring = true).performTouchInput { swipe(center, center + Offset(0f, 200f), 200) }
        compose.waitForIdle()
        assertTrue("a pull down from the strip did not open the header", sortRowShown())
    }

    /** With `browseChrome` Expanded the header never folds, however far into the tiles the controller goes. */
    @Test
    fun expandedPreferenceNeverFolds() {
        awaitHome()
        (sortie.activity.application as SortieApp).graph.user.setBrowseChrome("expanded")
        compose.waitUntil(5_000) { !shown(HIDE_HEADER) }
        pad.press(DOWN)
        compose.waitForIdle()
        pressUntil(DOWN, firstTile)
        walk(RIGHT, DOWN)
        assertTrue("the Expanded header folded", sortRowShown())
        assertFalse(shown(SHOW_HEADER))
    }

    /** A on a focused tile opens it, and B returns to Home with the same tile focused and the header still folded. */
    @Test
    fun aOpensTheFocusedSystemAndBReturnsToTheSameTile() {
        awaitHome()
        pressUntil(DOWN, firstTile)
        assertEquals(listOf("Ace Sortie Mega Drive ★", "Mega Drive 3 · 16-bit"), walk(RIGHT, RIGHT))
        pad.press(A)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Cinder Vale").fetchSemanticsNodes().isNotEmpty() }
        pad.press(B)
        awaitHome()
        assertEquals("Mega Drive 3 · 16-bit", pad.focusedLabel())
        assertTrue("Back from the system opened the folded header", shown(SHOW_HEADER))
    }

    /** Reach the header regardless of which row spatial focus chooses first in this layout. */
    private fun focusHeader() {
        pad.press(DOWN)
        compose.waitForIdle()
        pressUntil(UP, "Apps")
    }

    /** Walks the header to Extras and opens its menu with A: the focus lands on the menu's first entry. */
    private fun openExtras() {
        focusHeader()
        assertEquals(listOf("Search", "Extras"), walk(RIGHT, RIGHT))
        pad.press(A)
        compose.waitForIdle()
        compose.waitUntil(5_000) { pad.focusedLabel() == "Tonight" }
    }

    /** B closes the Extras menu and the focus is back on the Extras button, where the header walk carries on. */
    @Test
    fun extrasMenuClosesWithBAndReturnsToTheButton() {
        awaitHome()
        openExtras()
        assertEquals(listOf("Hangar", "Magazines"), walk(DOWN, DOWN))
        pad.press(B)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Tonight").fetchSemanticsNodes().isEmpty() }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertEquals("Extras", pad.focusedLabel())
        assertEquals(listOf("Settings"), walk(RIGHT))
    }

    /** Extras → Hangar opens the Hangar from the controller; B closes it and the focus is back on Extras. */
    @Test
    fun hangarFromExtrasOpensTheHangarAndBReturnsToExtras() {
        awaitHome()
        seedHangarIntroductionSeen()
        openExtras()
        assertEquals(listOf("Hangar"), walk(DOWN))
        pad.press(A)
        compose.waitUntil(5_000) { listOf("Workshop", "Gallery").all { compose.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() } }
        compose.waitUntil(5_000) { pad.focusedLabel() == "Workshop" }
        assertEquals(listOf("Shop", "Slot machine", "Gallery", "Arcade", "Themes"), walk(RIGHT, RIGHT, RIGHT, RIGHT, RIGHT))
        pad.press(A)
        pad.press(B)
        compose.waitForIdle()
        compose.waitUntil(5_000) { pad.focusedLabel() == "Workshop" }
        pad.press(B)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Workshop").fetchSemanticsNodes().isEmpty() }
        compose.mainClock.advanceTimeBy(500) // the button takes the focus back once the Hangar's window is gone
        compose.waitForIdle()
        assertEquals("Extras", pad.focusedLabel())
    }

    @Test
    @Config(qualifiers = DMG)
    fun controllerCanSkipTheFirstGuideWithoutSpendingOrChangingTheCompanion() {
        awaitHome()
        compose.waitUntil(5_000) { mecha.view.value.state != null && !mecha.view.value.busy }
        val before = mecha.view.value.state!!
        openExtras()
        assertEquals(listOf("Hangar"), walk(DOWN))
        pad.press(A)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Skip").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { pad.focusedLabel() == "Skip" }
        pad.press(A)
        compose.waitUntil(5_000) {
            mecha.view.value.state!!.hangarIntroSeen && !mecha.view.value.busy &&
                compose.onAllNodesWithText("Skip").fetchSemanticsNodes().isEmpty()
        }
        assertEquals(before.copy(hangarIntroSeen = true), mecha.view.value.state)
        assertEquals(before.copy(hangarIntroSeen = true), runBlocking { mecha.backupSave() })
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test
    fun guideAcknowledgementAndHelpReplayPreserveAnAlreadyHiddenCompanion() {
        awaitHome()
        compose.waitUntil(5_000) { mecha.view.value.state != null && !mecha.view.value.busy }
        runBlocking {
            mecha.restore(
                mecha.view.value.state!!.copy(
                    enabled = false,
                    motion = false,
                    salvage = 17,
                    ownedThemes = setOf("default", "harbor"),
                    selectedTheme = "harbor"
                ),
                emptyList()
            )
        }
        val before = mecha.view.value.state!!
        assertFalse(before.hangarIntroSeen)
        compose.onAllNodesWithText("Skip").assertCountEquals(0)
        openExtras()
        compose.onNodeWithText("Hangar").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Skip").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("Next").performScrollTo().performClick()
        compose.onNodeWithText("Previous").performScrollTo().performClick()
        assertEquals(before, mecha.view.value.state)
        compose.onNodeWithText("Next").performScrollTo().performClick()
        compose.onNodeWithText("Next").performScrollTo().performClick()
        compose.onNodeWithText("Got it").performScrollTo().performClick()
        compose.waitUntil(5_000) { mecha.view.value.state!!.hangarIntroSeen && !mecha.view.value.busy }
        val acknowledged = before.copy(hangarIntroSeen = true)
        assertEquals(acknowledged, mecha.view.value.state)
        assertEquals(acknowledged, runBlocking { mecha.backupSave() })

        compose.onNodeWithText("Help").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Skip").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(acknowledged, mecha.view.value.state)
        pad.press(B)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Skip").fetchSemanticsNodes().isEmpty() }
        assertEquals(acknowledged, runBlocking { mecha.backupSave() })
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Extras").performClick()
        compose.onNodeWithText("Hangar").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Workshop").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Skip").assertCountEquals(0)
        assertEquals(acknowledged, mecha.view.value.state)
    }

    @Test
    fun guideHidePersistsAcknowledgementWithoutDiscardingCompanionOrTheme() {
        awaitHome()
        compose.waitUntil(5_000) { mecha.view.value.state != null && !mecha.view.value.busy }
        runBlocking {
            mecha.restore(
                mecha.view.value.state!!.copy(
                    salvage = 23,
                    motion = false,
                    ownedThemes = setOf("default", "plum"),
                    selectedTheme = "plum",
                    ownedFinishes = setOf(MechaFinish.PLAIN, MechaFinish.ALLOY),
                    equippedFinishes = mapOf(MechaSlot.HEAD to MechaFinish.ALLOY)
                ),
                emptyList()
            )
        }
        val before = mecha.view.value.state!!
        openExtras()
        compose.onNodeWithText("Hangar").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Skip").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Hide companion").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Workshop").fetchSemanticsNodes().isEmpty() && !mecha.view.value.busy }
        val hidden = before.copy(hangarIntroSeen = true, enabled = false)
        assertEquals(hidden, mecha.view.value.state)
        assertEquals(hidden, runBlocking { mecha.backupSave() })
        compose.onNodeWithText("Extras").performClick()
        compose.onNodeWithText("Hangar").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Workshop").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Skip").assertCountEquals(0)
        assertEquals(hidden, mecha.view.value.state)
    }

    @Test
    fun arcadeCoinConfirmationAndGameSelectionKeepOneSavedTicketUntilExplicitAbandonment() {
        awaitHome()
        seedHangarIntroductionSeen()
        openExtras()
        compose.onNodeWithText("Hangar").performClick()
        compose.onNodeWithText("Arcade").performScrollTo().performClick()
        val before = mecha.view.value.state!!
        compose.onNodeWithText("Insert coin · 1 credit").performScrollTo().performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) {
            compose.waitForIdle()
            pad.focusedLabel() == "Cancel"
        }
        pad.press(A)
        compose.onNodeWithText("Insert coin · 1 credit").performScrollTo().assertIsDisplayed()
        assertEquals(before, mecha.view.value.state)
        compose.onNodeWithText("Insert coin · 1 credit").performScrollTo().performClick()
        pad.press(B)
        compose.onNodeWithText("Insert coin · 1 credit").performScrollTo().assertIsDisplayed()
        assertEquals(before, mecha.view.value.state)

        compose.onNodeWithText("Insert coin · 1 credit").performScrollTo().performClick()
        compose.onNodeWithText("Spend 1 credit").performScrollTo().performClick()
        compose.waitUntil(5_000) { mecha.view.value.state!!.activeArcade != null && !mecha.view.value.busy }
        val inserted = mecha.view.value.state!!
        val coin = inserted.activeArcade!!
        assertNull(coin.game)
        assertEquals(before.arcadeRuns + 1, coin.sequence)
        assertEquals(before.copy(salvage = before.salvage - 1, arcadeRuns = coin.sequence, activeArcade = coin), inserted)
        assertEquals(inserted, runBlocking { mecha.backupSave() })
        compose.onNodeWithText("Choose your game").performScrollTo().assertIsDisplayed()
        listOf("Flappy Flight", "Scrap Catch", "Circuit Match").forEach {
            compose.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
        compose.onAllNodesWithText("Insert coin · 1 credit").assertCountEquals(0)

        // Leaving before choosing keeps the paid coin, not a free replacement or an automatic game choice.
        pad.press(B)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Fit build").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Extras").performClick()
        compose.onNodeWithText("Hangar").performClick()
        compose.onNodeWithText("Arcade").performScrollTo().performClick()
        compose.onNodeWithText("Choose your game").performScrollTo().assertIsDisplayed()
        assertEquals(inserted, mecha.view.value.state)
        compose.onNodeWithText("Flappy Flight").performScrollTo().performClick()
        compose.waitUntil(5_000) { mecha.view.value.state!!.activeArcade?.game == MechaArcadeGame.FLAPPY_FLIGHT && !mecha.view.value.busy }
        val paid = inserted.copy(activeArcade = coin.copy(game = MechaArcadeGame.FLAPPY_FLIGHT))
        assertEquals(paid, mecha.view.value.state)
        assertEquals(paid, runBlocking { mecha.backupSave() })
        compose.onNodeWithText("Start paid run").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Choose your game").assertCountEquals(0)
        compose.onAllNodesWithText("Insert coin · 1 credit").assertCountEquals(0)

        // A paid but not started run has no live clock. Back returns to Workshop, never refunds or charges.
        pad.press(B)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Fit build").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(paid, mecha.view.value.state)
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Extras").performClick()
        compose.onNodeWithText("Hangar").performClick()
        compose.onNodeWithText("Arcade").performScrollTo().performClick()
        compose.onNodeWithText("Start paid run").performScrollTo().assertIsDisplayed()
        assertEquals(paid, mecha.view.value.state)
        compose.onNodeWithText("Abandon…").performScrollTo().performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) {
            compose.waitForIdle()
            pad.focusedLabel() == "Keep run"
        }
        pad.press(A)
        assertEquals(paid, mecha.view.value.state)
        compose.onNodeWithText("Abandon…").performScrollTo().performClick()
        pad.press(B)
        compose.onNodeWithText("Start paid run").performScrollTo().assertIsDisplayed()
        assertEquals(paid, mecha.view.value.state)
        compose.onNodeWithText("Abandon…").performScrollTo().performClick()
        compose.onNodeWithText("Abandon run").performScrollTo().performClick()
        compose.waitUntil(5_000) { mecha.view.value.state!!.activeArcade == null && !mecha.view.value.busy }
        assertNull(mecha.view.value.state!!.activeArcade)
        assertEquals(paid.copy(activeArcade = null), mecha.view.value.state)
        assertEquals(paid.copy(activeArcade = null), runBlocking { mecha.backupSave() })
    }

    @Test
    fun companionNameOnlyEditsAfterRenameAndCancelDoesNotSave() {
        awaitHome()
        seedHangarIntroductionSeen()
        openExtras()
        compose.onNodeWithText("Hangar").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Workshop").fetchSemanticsNodes().isNotEmpty() }
        val store = (sortie.activity.application as SortieApp).graph.mecha
        val original = store.view.value.state!!.name
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText(original).performScrollTo().performTouchInput { click() }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)

        compose.onNodeWithText("Rename").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Unsaved name")
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        assertEquals(original, store.view.value.state!!.name)

        compose.onNodeWithText("Rename").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement(" ")
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextReplacement("  Rivet II  ")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitUntil(5_000) { store.view.value.state?.name == "Rivet II" }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test
    @Config(qualifiers = RP_NOVA)
    fun partPreviewAndCancelledPurchaseAreFreeAndBuyingDoesNotEquip() {
        awaitHome()
        seedHangarIntroductionSeen()
        openExtras()
        compose.onNodeWithText("Hangar").performClick()
        val store = (sortie.activity.application as SortieApp).graph.mecha
        compose.waitUntil(5_000) { store.view.value.state != null && !store.view.value.busy }
        val before = store.view.value.state!!
        val part = store.view.value.parts.first { it.slot == MechaSlot.HEAD && it.id !in before.owned }
        compose.onNodeWithText("Shop").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Preview ${part.name}, 2 credits").performScrollTo().performClick()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        assertEquals(before, store.view.value.state)

        compose.onNodeWithText("Buy part · 2 credits").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("Cancel", pad.focusedLabel())
        pad.press(A)
        compose.waitForIdle()
        assertEquals(before, store.view.value.state)

        compose.onNodeWithText("Buy part · 2 credits").performScrollTo().performClick()
        compose.onNodeWithText("Buy part").performClick()
        compose.waitUntil(5_000) { part.id in store.view.value.state!!.owned && !store.view.value.busy }
        compose.onNodeWithText("Buy part").assertIsNotEnabled()
        val bought = store.view.value.state!!
        assertEquals(before.salvage - 2, bought.salvage)
        assertEquals(before.equipped, bought.equipped)
        assertEquals(before.owned + part.id, bought.owned)
        compose.onNodeWithText("Close receipt").performClick()
        compose.onNodeWithText("Owned").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Equip part").performScrollTo().performClick()
        compose.waitUntil(5_000) { store.view.value.state!!.equipped[MechaSlot.HEAD] == part.id }
        assertEquals(bought.salvage, store.view.value.state!!.salvage)
    }

    @Test
    @Config(qualifiers = DMG)
    fun finishPreviewUnlockAndPerSlotApplicationAreSeparateAndReusable() {
        awaitHome()
        seedHangarIntroductionSeen()
        openExtras()
        compose.onNodeWithText("Hangar").performClick()
        val store = (sortie.activity.application as SortieApp).graph.mecha
        compose.waitUntil(5_000) { store.view.value.state != null && !store.view.value.busy }
        runBlocking { store.restore(store.view.value.state!!.copy(salvage = 20), emptyList()) }
        val before = store.view.value.state!!
        compose.onNodeWithText("Shop").performScrollTo().performClick()
        compose.onNodeWithText("Finishes").performScrollTo().performClick()
        compose.onNodeWithText("Alloy · 6 credits").performScrollTo().performClick()
        assertEquals(before, store.view.value.state)
        compose.onNodeWithText("Apply finish").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Buy finish · 6 credits").performScrollTo().performClick()
        compose.onNodeWithText("Buy finish").performClick()
        compose.waitUntil(5_000) { MechaFinish.ALLOY in store.view.value.state!!.ownedFinishes && !store.view.value.busy }
        assertEquals(14, store.view.value.state!!.salvage)
        assertEquals(before.equippedFinishes, store.view.value.state!!.equippedFinishes)
        compose.onNodeWithText("Close receipt").performClick()
        compose.onNodeWithText("Apply finish").performScrollTo().performClick()
        compose.waitUntil(5_000) { store.view.value.state!!.equippedFinishes[MechaSlot.HEAD] == MechaFinish.ALLOY && !store.view.value.busy }
        compose.onNodeWithText("Body").performScrollTo().performClick()
        compose.onNodeWithText("Apply finish").performScrollTo().performClick()
        compose.waitUntil(5_000) { store.view.value.state!!.equippedFinishes[MechaSlot.BODY] == MechaFinish.ALLOY && !store.view.value.busy }
        assertEquals(MechaFinish.ALLOY, store.view.value.state!!.equippedFinishes[MechaSlot.HEAD])
        assertEquals(14, store.view.value.state!!.salvage)
        compose.onNodeWithText("Plain · free").performScrollTo().performClick()
        compose.onNodeWithText("Apply finish").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            (store.view.value.state!!.equippedFinishes[MechaSlot.BODY] ?: MechaFinish.PLAIN) == MechaFinish.PLAIN && !store.view.value.busy
        }
        assertEquals(MechaFinish.ALLOY, store.view.value.state!!.equippedFinishes[MechaSlot.HEAD])
        assertEquals(14, store.view.value.state!!.salvage)
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test
    fun workshopMixPreviewIsFreeAndFitPersistsOwnedPartsWithoutChangingFinishes() {
        awaitHome()
        seedHangarIntroductionSeen()
        openExtras()
        compose.onNodeWithText("Hangar").performClick()
        val store = (sortie.activity.application as SortieApp).graph.mecha
        compose.waitUntil(5_000) { store.view.value.state != null && !store.view.value.busy }
        runBlocking {
            store.restore(
                store.view.value.state!!.copy(
                    ownedFinishes = setOf(MechaFinish.PLAIN, MechaFinish.ALLOY),
                    equippedFinishes = mapOf(MechaSlot.HEAD to MechaFinish.ALLOY)
                ),
                emptyList()
            )
        }
        val before = store.view.value.state!!
        val manual = store.view.value.parts.first { it.slot == MechaSlot.HEAD && it.id in before.owned && it.id != before.equipped[MechaSlot.HEAD] }
        compose.onNodeWithText(manual.name).performScrollTo().performClick()
        assertEquals(before, store.view.value.state)
        compose.onNodeWithText("Reset preview").performScrollTo().performClick()
        compose.onNodeWithText("Fit build").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Mixes").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Mix build").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Mix build").performScrollTo().performClick()
        compose.onNodeWithText("Next mix").performScrollTo().performClick()
        compose.onNodeWithText("Previous mix").performScrollTo().performClick()
        assertEquals(before, store.view.value.state)
        compose.onNodeWithText("Fit build").performScrollTo().performClick()
        compose.waitUntil(5_000) { store.view.value.state!!.equipped != before.equipped && !store.view.value.busy }
        val fitted = store.view.value.state!!
        val parts = store.view.value.parts.associateBy { it.id }
        assertEquals(before.salvage, fitted.salvage)
        assertEquals(before.owned, fitted.owned)
        assertEquals(before.equippedFinishes, fitted.equippedFinishes)
        assertTrue(fitted.equipped.values.all { it in before.owned })
        assertEquals(parts[fitted.equipped[MechaSlot.BODY]]!!.family, parts[fitted.equipped[MechaSlot.ARMS]]!!.family)
        assertEquals(parts[fitted.equipped[MechaSlot.HEAD]]!!.family, parts[fitted.equipped[MechaSlot.LEGS]]!!.family)
        assertTrue(parts[fitted.equipped[MechaSlot.BODY]]!!.family != parts[fitted.equipped[MechaSlot.HEAD]]!!.family)
        assertEquals(fitted.equipped, runBlocking { store.backupSave() }!!.equipped)
        compose.onNodeWithText("Fit build").performScrollTo().assertIsNotEnabled()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)

        val replacement = store.view.value.parts.first { it.slot == MechaSlot.HEAD && it.id in fitted.owned && it.id != fitted.equipped[MechaSlot.HEAD] }
        compose.onNodeWithText("Parts").performScrollTo().performClick()
        compose.onNodeWithText(replacement.name).performScrollTo().performClick()
        assertEquals(fitted, store.view.value.state)
        compose.onNodeWithText("Fit build").performScrollTo().performClick()
        compose.waitUntil(5_000) { store.view.value.state!!.equipped[MechaSlot.HEAD] == replacement.id && !store.view.value.busy }
        assertEquals(fitted.copy(equipped = fitted.equipped + (MechaSlot.HEAD to replacement.id)), runBlocking { store.backupSave() })
    }

    @Test
    fun slotMachineRequiresConfirmationAndReopeningReceiptNeverSpinsAgain() {
        awaitHome()
        seedHangarIntroductionSeen()
        openExtras()
        compose.onNodeWithText("Hangar").performClick()
        val store = (sortie.activity.application as SortieApp).graph.mecha
        compose.waitUntil(5_000) { store.view.value.state != null && !store.view.value.busy }
        val before = store.view.value.state!!
        compose.onNodeWithText("Slot machine").performScrollTo().performClick()
        compose.onNodeWithText("Spin · 1 credit…").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("Cancel", pad.focusedLabel())
        pad.press(B)
        compose.waitForIdle()
        assertEquals(before, store.view.value.state)
        compose.onNodeWithText("Spin · 1 credit…").performScrollTo().performClick()
        compose.onNodeWithText("Spin once").performClick()
        compose.waitUntil(5_000) { store.view.value.state!!.cabinetSpins == before.cabinetSpins + 1 && !store.view.value.busy }
        val after = store.view.value.state!!
        val receipt = after.lastSpin!!
        assertEquals(before.salvage - receipt.tier.stake + receipt.returnedCredits, after.salvage)
        assertEquals(before.equipped, after.equipped)
        compose.onNodeWithText("Spin once").assertIsNotEnabled()
        compose.onNodeWithText("Close receipt").performClick()
        compose.onNodeWithText("Close").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertEquals("Extras", pad.focusedLabel())
        pad.press(A)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Hangar").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Hangar").performClick()
        compose.onNodeWithText("Slot machine").performScrollTo().performClick()
        compose.onNodeWithText("Saved spin #${receipt.sequence} · ${receipt.tier.label}").performScrollTo().assertIsDisplayed()
        assertEquals(after, store.view.value.state)
        assertEquals(receipt, runBlocking { store.backupSave() }!!.lastSpin)
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test
    fun emptyWalletAndCompletedCollectionCannotSpendThroughShopOrSlotMachine() {
        awaitHome()
        seedHangarIntroductionSeen()
        openExtras()
        compose.onNodeWithText("Hangar").performClick()
        val store = (sortie.activity.application as SortieApp).graph.mecha
        compose.waitUntil(5_000) { store.view.value.state != null && !store.view.value.busy }
        runBlocking { store.restore(store.view.value.state!!.copy(salvage = 0), emptyList()) }
        val emptyWallet = store.view.value.state!!
        val part = store.view.value.parts.first { it.slot == MechaSlot.HEAD && it.id !in emptyWallet.owned }
        compose.onNodeWithText("Shop").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Preview ${part.name}, 2 credits").performScrollTo().performClick()
        compose.onNodeWithText("Buy part · 2 credits").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Slot machine").performScrollTo().performClick()
        compose.onNodeWithText("Spin · 1 credit…").performScrollTo().assertIsNotEnabled()
        assertEquals(emptyWallet, store.view.value.state)
        compose.onNodeWithText("Arcade").performScrollTo().performClick()
        compose.onNodeWithText("Insert coin · 1 credit").performScrollTo().assertIsNotEnabled()
        assertEquals(emptyWallet, store.view.value.state)
        compose.onNodeWithText("Slot machine").performScrollTo().performClick()

        runBlocking {
            store.restore(
                emptyWallet.copy(
                    salvage = 70,
                    pendingMinutes = 0,
                    owned = store.view.value.parts.map { it.id }.toSet(),
                    ownedFinishes = MechaFinish.entries.toSet()
                ),
                emptyList()
            )
        }
        val complete = store.view.value.state!!
        compose.onNodeWithText("Tier complete").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Alloy · 3 credits").performScrollTo().performClick()
        compose.onNodeWithText("Tier complete").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Holo · 5 credits").performScrollTo().performClick()
        compose.onNodeWithText("Tier complete").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Shop").performScrollTo().performClick()
        compose.onNodeWithText("Owned").performScrollTo().assertIsNotEnabled()
        assertEquals(complete, store.view.value.state)
    }
}
