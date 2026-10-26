package io.github.esko997.sortie.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MagazineFiguresTest {
    private val column = 680f
    private val wide = 920f

    @Test
    fun portraitFrameIsHeightCappedAndKeepsItsShape() {
        // issue 010's 1200×1600 arcade frame on a short landscape screen: as tall as allowed, narrower than the text
        val box = figureBox(1200, 1600, column, wide, maxHeight = 300f, density = 2f)
        assertEquals(225f, box.width, 0.01f)
        assertEquals(300f, box.height, 0.01f)
        assertFalse(box.upscaled)
    }

    @Test
    fun wideDiagramUsesTheFullReadingWidthWhenTheHeightAllows() {
        val tall = figureBox(1200, 660, column, wide, maxHeight = 1000f, density = 1f)
        assertEquals(920f, tall.width, 0.01f) // wider than the 680 text measure
        assertEquals(920f * 660 / 1200, tall.height, 0.01f)
        // a short screen caps the height; the width follows the aspect ratio, still without bars
        val short = figureBox(1200, 660, column, wide, maxHeight = 300f, density = 1f)
        assertEquals(300f, short.height, 0.01f)
        assertEquals(300f * 1200 / 660, short.width, 0.01f)
    }

    @Test
    fun fourByThreeShotStaysInTheTextMeasure() {
        val box = figureBox(1280, 960, column, wide, maxHeight = 1000f, density = 1f)
        assertEquals(680f, box.width, 0.01f)
        assertEquals(510f, box.height, 0.01f)
        assertFalse(box.upscaled)
        // the same shot never gets the wide treatment, even on a screen with room
        assertEquals(column, figureBox(1280, 960, column, 2000f, maxHeight = 2000f, density = 1f).width, 0.01f)
    }

    @Test
    fun smallPixelArtSnapsToAWholeScaleAndIsDrawnUnsmoothed() {
        // 256×224 at density 2: the column would be 5.3 screen pixels per image pixel, so 5× = 1280 px = 640 dp
        val box = figureBox(256, 224, column, wide, maxHeight = 1000f, density = 2f)
        assertEquals(640f, box.width, 0.01f)
        assertEquals(560f, box.height, 0.01f)
        assertTrue(box.upscaled)
        // between 1× and 2× the scale is not snapped (that would halve the picture), but still unsmoothed
        val slight = figureBox(600, 450, column, wide, maxHeight = 1000f, density = 1f)
        assertEquals(680f, slight.width, 0.01f)
        assertTrue(slight.upscaled)
    }

    @Test
    fun snappingNeverExceedsTheHeightCap() {
        val box = figureBox(160, 144, column, wide, maxHeight = 250f, density = 2.625f)
        assertTrue(box.height <= 250f + 0.01f)
        val pixels = box.width * 2.625f / 160
        assertEquals(pixels, Math.round(pixels).toFloat(), 0.01f)
    }

    @Test
    fun unknownSizeIsASixteenByNineSlate() {
        val box = unknownFigureBox(column, maxHeight = 1000f)
        assertEquals(680f, box.width, 0.01f)
        assertEquals(382.5f, box.height, 0.01f)
        assertEquals(200f, unknownFigureBox(column, maxHeight = 200f).height, 0.01f)
    }

    @Test
    fun aSwipedSequenceRestsOnTheNearestPanelWithinItsPanels() {
        val step = 600f // panel width plus gap, in px
        assertEquals(0, nearestPanel(0, step, 4))
        assertEquals(0, nearestPanel(299, step, 4))
        assertEquals(1, nearestPanel(301, step, 4))
        assertEquals(3, nearestPanel(1800, step, 4))
        // overscroll or a stale position from a longer sequence stays on a real panel
        assertEquals(3, nearestPanel(5000, step, 4))
        assertEquals(0, nearestPanel(-40, step, 4))
        assertEquals(0, nearestPanel(900, 0f, 4)) // not laid out yet
    }
}
