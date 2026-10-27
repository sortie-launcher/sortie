package io.github.esko997.sortie.mecha

import io.github.esko997.sortie.ui.SortieButtonStyle
import io.github.esko997.sortie.ui.SortieFolderIcons
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MechaCommerceTest {
    private val parts = MechaRulesTest.parts
    private fun starter() = MechaRulesTest.starter()

    @Test
    fun everyNewPartIsPurchasableExactlyOnceWithoutAutoEquipping() {
        var state = starter().copy(salvage = 500)
        for (part in state.unownedIn(parts)) {
            val before = state
            val result = state.buyingPart(part.id, parts)
            assertNull(result.error)
            state = result.state
            assertEquals(before.salvage - MECHA_PART_PRICE, state.salvage)
            assertEquals(before.copy(salvage = state.salvage, owned = before.owned + part.id, origins = state.origins), state)
            assertNull(mechaStateProblem(state, parts))
            assertSame(state, state.buyingPart(part.id, parts).state)
            assertNotNull(state.buyingPart(part.id, parts).error)
            assertEquals(part.id, state.equipping(part.id, parts).state.equipped[part.slot])
        }
        assertEquals(200, state.owned.size)
        assertEquals(500 - 188 * MECHA_PART_PRICE, state.salvage)
        val broke = starter().copy(salvage = 1)
        assertSame(broke, broke.buyingPart("foundry_head_1", parts).state)
        assertSame(broke, broke.buyingPart("missing", parts).state)
    }

    @Test
    fun allThirtyThreePaintEditionsHaveIndependentPricesAndReusableOwnership() {
        assertEquals(11, MechaPaintScheme.entries.size)
        for (scheme in MechaPaintScheme.entries) {
            for (finish in MechaFinish.entries) {
                val paint = MechaPaint(scheme, finish)
                val expectedPrice = when (finish) {
                    MechaFinish.PLAIN -> if (scheme == MechaPaintScheme.ORIGINAL) 0 else 2
                    MechaFinish.ALLOY -> 6
                    MechaFinish.HOLO -> 10
                }
                assertEquals(expectedPrice, paint.price)
                val initial = starter().copy(salvage = expectedPrice)
                if (paint == MechaPaint()) {
                    assertSame(initial, initial.buyingPaint(paint).state)
                    continue
                }
                assertSame(initial, initial.applyingPaint(paint, null).state)
                val bought = initial.buyingPaint(paint).state
                assertEquals(initial.copy(salvage = 0, ownedPaints = initial.ownedPaints + paint), bought)
                assertSame(bought, bought.buyingPaint(paint).state)
                val fitted = bought.applyingPaint(paint, null).state
                assertEquals(MechaSlot.entries.associateWith { paint }, fitted.equippedPaints)
                assertEquals(0, fitted.salvage)
                assertSame(fitted, fitted.applyingPaint(paint, null).state)
                assertEquals(fitted.equippedPaints, fitted.equipping("survey_head_1", parts).state.equippedPaints)
                val headOriginal = fitted.applyingPaint(MechaPaint(), MechaSlot.HEAD).state
                assertFalse(MechaSlot.HEAD in headOriginal.equippedPaints)
                assertEquals(paint, headOriginal.equippedPaints[MechaSlot.BODY])
                assertTrue(headOriginal.applyingPaint(MechaPaint(), null).state.equippedPaints.isEmpty())
                assertNull(mechaStateProblem(fitted))
                val poor = initial.copy(salvage = paint.price - 1)
                assertSame(poor, poor.buyingPaint(paint).state)
            }
        }
    }

    @Test
    fun paintApplicationRejectsUnownedEditionsAtomically() {
        val standard = MechaPaint(MechaPaintScheme.HAZARD)
        val alloy = standard.copy(finish = MechaFinish.ALLOY)
        val state = starter().buyingPaint(standard).state.applyingPaint(standard, MechaSlot.HEAD).state
        assertSame(state, state.applyingPaint(alloy, null).state)
        assertSame(state, state.applyingPaint(alloy, MechaSlot.HEAD).state)
        assertNotNull(state.applyingPaint(alloy, null).error)
        assertNotNull(mechaStateProblem(state.copy(ownedPaints = emptySet())))
        assertNotNull(mechaStateProblem(state.copy(equippedPaints = mapOf(MechaSlot.BODY to alloy))))
        assertNotNull(mechaStateProblem(state.copy(version = 3)))
        val text = Json.encodeToString(MechaState.serializer(), state)
        assertEquals(state, decodeMechaState(text))
        assertTrue(runCatching { decodeMechaState(text.replace("HAZARD", "UNKNOWN")) }.isFailure)
    }

    @Test
    fun folderAndButtonPurchasesNeverApplyAndOwnedStylesApplyFreely() {
        for (pack in SortieFolderIcons.entries.filter { it != SortieFolderIcons.DEFAULT }) {
            val initial = starter().copy(salvage = pack.price)
            assertSame(initial, initial.selectingFolderIcons(pack).state)
            val bought = initial.buyingFolderIcons(pack).state
            assertEquals(initial.copy(salvage = 0, ownedFolderIcons = initial.ownedFolderIcons + pack.id), bought)
            assertSame(bought, bought.buyingFolderIcons(pack).state)
            val applied = bought.selectingFolderIcons(pack).state
            assertEquals(pack.id, applied.selectedFolderIcons)
            assertEquals(0, applied.salvage)
            assertSame(applied, applied.selectingFolderIcons(pack).state)
            assertEquals("default", applied.selectingFolderIcons(SortieFolderIcons.DEFAULT).state.selectedFolderIcons)
            val poor = initial.copy(salvage = pack.price - 1)
            assertSame(poor, poor.buyingFolderIcons(pack).state)
            assertNull(mechaStateProblem(applied))
        }
        for (style in SortieButtonStyle.entries.filter { it != SortieButtonStyle.DEFAULT }) {
            val initial = starter().copy(salvage = style.price)
            assertSame(initial, initial.selectingButtonStyle(style).state)
            val bought = initial.buyingButtonStyle(style).state
            assertEquals(initial.copy(salvage = 0, ownedButtonStyles = initial.ownedButtonStyles + style.id), bought)
            assertSame(bought, bought.buyingButtonStyle(style).state)
            val applied = bought.selectingButtonStyle(style).state
            assertEquals(style.id, applied.selectedButtonStyle)
            assertEquals(0, applied.salvage)
            assertSame(applied, applied.selectingButtonStyle(style).state)
            assertEquals("default", applied.selectingButtonStyle(SortieButtonStyle.DEFAULT).state.selectedButtonStyle)
            val poor = initial.copy(salvage = style.price - 1)
            assertSame(poor, poor.buyingButtonStyle(style).state)
            assertNull(mechaStateProblem(applied))
        }
    }

    @Test
    fun unknownUnownedAndMissingLauncherDefaultsAreRejected() {
        val state = starter()
        val invalid = listOf(
            state.copy(ownedFolderIcons = emptySet()), state.copy(ownedButtonStyles = emptySet()),
            state.copy(ownedFolderIcons = setOf("default", "unknown")), state.copy(ownedButtonStyles = setOf("default", "unknown")),
            state.copy(selectedFolderIcons = "drawers"), state.copy(selectedButtonStyle = "arcade"),
            state.copy(selectedFolderIcons = "unknown"), state.copy(selectedButtonStyle = "unknown"),
            state.copy(version = 3, ownedFolderIcons = setOf("default", "drawers")),
            state.copy(version = 3, ownedButtonStyles = setOf("default", "arcade"))
        )
        invalid.forEach { assertNotNull(mechaStateProblem(it)) }
    }

    @Test
    fun fittingValidatesOwnershipAndSlotsWithoutConstrainingFamilies() {
        val initial = starter()
        val good = initial.equipped
        for (build in listOf(
            good - MechaSlot.HEAD,
            good + (MechaSlot.HEAD to "foundry_head_1"),
            good + (MechaSlot.HEAD to good.getValue(MechaSlot.BODY)),
            good + (MechaSlot.HEAD to "missing")
        )) {
            assertSame(initial, initial.fittingBuild(build, parts).state)
            assertNotNull(initial.fittingBuild(build, parts).error)
        }
        val manual = mapOf(
            MechaSlot.HEAD to "yard_head_1",
            MechaSlot.LEGS to "survey_legs_1",
            MechaSlot.BODY to "courier_body_1",
            MechaSlot.ARMS to "courier_arms_1"
        )
        assertEquals(initial.copy(equipped = manual), initial.fittingBuild(manual, parts).state)
        val mutable = manual.toMutableMap()
        val fitted = initial.fittingBuild(mutable, parts).state
        mutable.clear()
        assertEquals(manual, fitted.equipped)
    }
}
