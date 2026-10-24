package io.github.esko997.sortie.keyboard

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadialKeyboardModelTest {
    /** Stick vector at [degrees] clockwise from north, in Android axes (y grows downwards). */
    private fun RadialKeyboardModel.left(degrees: Double, r: Float = 1f) = updateLeftStick(x(degrees, r), y(degrees, r))
    private fun RadialKeyboardModel.right(degrees: Double, r: Float = 1f) = updateRightStick(x(degrees, r), y(degrees, r))
    private fun x(degrees: Double, r: Float) = (r * sin(Math.toRadians(degrees))).toFloat()
    private fun y(degrees: Double, r: Float) = (-r * cos(Math.toRadians(degrees))).toFloat()

    private fun RadialKeyboardModel.neutral() {
        updateLeftStick(0f, 0f)
        updateRightStick(0f, 0f)
    }

    @Test
    fun sectorsRunClockwiseFromNorthWithAndroidAxes() {
        val model = RadialKeyboardModel()
        for (g in 0 until 8) {
            model.neutral()
            model.left(g * 45.0)
            assertEquals("group at ${g * 45} degrees", g, model.selectedGroup)
        }
        for (s in 0 until 4) {
            model.neutral()
            model.right(s * 90.0)
            assertEquals("slot at ${s * 90} degrees", s, model.selectedIndex)
        }
        model.neutral()
        model.updateLeftStick(0f, -1f) // stick pushed up
        assertEquals(0, model.selectedGroup)
        model.neutral()
        model.updateLeftStick(-1f, 0f) // pushed left
        assertEquals(6, model.selectedGroup)
    }

    @Test
    fun deadZoneIgnoresSmallDeflectionAndSelectionLatchesAtNeutral() {
        val model = RadialKeyboardModel()
        assertFalse(model.left(90.0, r = 0.4f))
        assertEquals(RadialKeyboardModel.NONE, model.selectedGroup)
        assertTrue(model.left(90.0))
        model.left(90.0, r = 0.4f)
        model.left(250.0, r = 0.2f) // spring-back overshoot through the centre
        model.neutral()
        assertEquals(2, model.selectedGroup)
        model.right(180.0)
        model.neutral()
        assertEquals("k", model.selectedCharacter) // east group "ijkl", south slot
    }

    @Test
    fun boundaryJitterKeepsTheSelectionUntilClearlyPastIt() {
        val model = RadialKeyboardModel()
        model.left(10.0)
        assertEquals(0, model.selectedGroup)
        for (a in listOf(21.0, 24.0, 20.0, 26.0, 23.0, 29.0)) assertFalse(model.left(a)) // N/NE boundary is 22.5
        assertEquals(0, model.selectedGroup)
        assertTrue(model.left(33.0))
        assertEquals(1, model.selectedGroup)
        assertFalse(model.left(20.0)) // and the same margin applies on the way back
        assertEquals(1, model.selectedGroup)

        model.right(10.0)
        assertEquals(0, model.selectedIndex)
        assertFalse(model.right(29.0)) // N/NE boundary is 22.5, with an 8-degree hysteresis margin
        assertTrue(model.right(33.0))
        assertEquals(KeyboardAction.BACKSPACE, model.selectedAction)
        assertEquals("", model.selectedCharacter)
    }

    @Test
    fun diagonalsSelectActionsWithoutTypingAndCardinalsRestoreCharacters() {
        val model = RadialKeyboardModel()
        model.selectGroup(0)
        val actions = listOf(KeyboardAction.BACKSPACE, KeyboardAction.SPACE, KeyboardAction.TOGGLE_SYMBOLS, KeyboardAction.TOGGLE_SHIFT)
        actions.forEachIndexed { i, action ->
            model.neutral()
            model.right(45.0 + i * 90.0)
            assertEquals(action, model.selectedAction)
            assertEquals("", model.selectedCharacter)
            model.neutral()
            assertEquals(action, model.selectedAction) // release before A still commits the action
            model.right(i * 90.0)
            assertEquals(null, model.selectedAction)
            assertEquals("abcd"[i].toString(), model.selectedCharacter)
        }
        model.selectRightSector(7)
        model.selectCharacter(2) // touch selection replaces a controller-selected action
        assertEquals("c", model.selectedCharacter)
        assertEquals(null, model.selectedAction)
        model.reset()
        assertEquals(RadialKeyboardModel.NONE, model.selectedSector)
        assertEquals(null, model.selectedAction)
    }

    @Test
    fun hysteresisDoesNotBiasAFreshPushAfterNeutral() {
        val model = RadialKeyboardModel()
        model.left(0.0)
        model.left(0.0, r = 0.1f)
        model.left(26.0) // would be held by hysteresis if the stick had stayed out
        assertEquals(1, model.selectedGroup)
    }

    @Test
    fun hysteresisBandInsideTheDeadZoneHoldsSectorUntilNeutral() {
        val model = RadialKeyboardModel()
        model.left(0.0)
        model.left(180.0, r = 0.4f) // still engaged but below the select threshold: no change
        assertEquals(0, model.selectedGroup)
        model.left(90.0, r = 0.45f)
        assertEquals(0, model.selectedGroup)
    }

    @Test
    fun heldLayerReleasesBackToLatchedLayer() {
        val model = RadialKeyboardModel()
        model.selectGroup(0)
        model.selectCharacter(0)
        assertTrue(model.setShiftHeld(true))
        assertEquals("A", model.selectedCharacter)
        assertFalse(model.setShiftHeld(true)) // key and axis both reporting the hold must not double-toggle
        assertTrue(model.setShiftHeld(false))
        assertEquals("a", model.selectedCharacter)

        model.toggleShift()
        assertFalse(model.setShiftHeld(true))
        assertFalse(model.setShiftHeld(false)) // releasing the trigger keeps the touch latch
        assertEquals("A", model.selectedCharacter)

        assertTrue(model.setSymbolsHeld(true))
        assertEquals("(", model.selectedCharacter)
        assertTrue(model.setSymbolsHeld(false))
        assertEquals("A", model.selectedCharacter)
    }

    @Test
    fun resetClearsSelectionLatchesHoldsAndStickEngagement() {
        val model = RadialKeyboardModel()
        model.left(0.0)
        model.right(90.0)
        model.toggleShift()
        model.toggleSymbols()
        model.setShiftHeld(true)
        model.setSymbolsHeld(true)
        model.reset()
        assertEquals(RadialKeyboardModel.NONE, model.selectedGroup)
        assertEquals(RadialKeyboardModel.NONE, model.selectedIndex)
        assertEquals("", model.selectedCharacter)
        assertFalse(model.shifted)
        assertFalse(model.symbols)
        assertEquals(listOf("", "", "", ""), model.characterLabels)
        model.left(26.0) // stick still held after reset: a fresh push, so no hysteresis toward the old group
        assertEquals(1, model.selectedGroup)

        model.reset(numeric = true)
        model.selectGroup(0)
        model.selectCharacter(0)
        assertEquals("1", model.selectedCharacter)
        model.reset()
        assertFalse(model.symbols)
    }

    @Test
    fun outOfRangeSelectionIsRejected() {
        val model = RadialKeyboardModel()
        assertFalse(model.selectGroup(8))
        assertFalse(model.selectGroup(-1))
        assertFalse(model.selectCharacter(4))
        assertEquals("", model.selectedCharacter)
    }

    @Test
    fun everyPrintableAsciiCharacterIsReachable() {
        val model = RadialKeyboardModel()
        val reachable = mutableSetOf(' ') // the dedicated Space control
        for (shift in listOf(false, true)) {
            for (symbols in listOf(false, true)) {
                model.reset()
                model.setShiftHeld(shift)
                model.setSymbolsHeld(symbols)
                for (g in 0 until 8) {
                    model.selectGroup(g)
                    assertEquals(4, model.groupLabels[g].length)
                    for (s in 0 until 4) {
                        model.selectCharacter(s)
                        val c = model.selectedCharacter
                        assertEquals("one character per slot", 1, c.length)
                        assertEquals(model.groupLabels[g][s].toString(), c)
                        reachable += c[0]
                    }
                }
            }
        }
        assertEquals((' '..'~').toSet(), reachable)
    }

    @Test
    fun backspaceFallbackNeverSplitsASurrogatePair() {
        assertEquals(0, backspaceLength(null))
        assertEquals(0, backspaceLength(""))
        assertEquals(1, backspaceLength("ab"))
        assertEquals(2, backspaceLength("a\uD83D\uDE00"))
        assertEquals(2, backspaceLength("\uD83D\uDE00"))
        assertEquals(0, backspaceLength("\uDE00")) // incomplete context: let the editor handle DEL instead
        assertEquals(0, backspaceLength("\uD83D")) // cursor may be inside a pair
        assertEquals(1, backspaceLength("\uD83Da")) // stray high surrogate before a normal char
    }
}
