package io.github.esko997.sortie.mecha.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.esko997.sortie.mecha.MechaFinish
import io.github.esko997.sortie.mecha.MechaSlot
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real authored PNGs through the exact transformer used by MechaSheets, not a replacement/mock renderer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MechaMaterialTest {
    private val assets = listOf(File("src/main/assets/mecha"), File("mecha/src/main/assets/mecha")).first { it.isDirectory }

    @Test
    fun plainKeepsEveryOriginalPixelWithoutAllocatingAnAtlas() {
        for (file in sheets()) {
            val image = requireNotNull(BitmapFactory.decodeFile(file.path))
            val source = image.pixels()
            val before = source.copyOf()
            val actual = mechaMaterialPixels(source, image.width, image.height, slot(file), MechaFinish.PLAIN)
            assertSame(file.name, source, actual)
            assertArrayEquals(file.name, before, actual)
        }
    }

    @Test
    fun everyMaterialPhasePreservesAuthoredSilhouetteMechanicalRampAndEyes() {
        val mechanical = setOf(0x18232D, 0x344650, 0x768A91, 0xA6B5B1, 0xD7DFD5)
        for (file in sheets()) {
            val image = requireNotNull(BitmapFactory.decodeFile(file.path))
            val source = image.pixels()
            val before = source.copyOf()
            val slot = slot(file)
            for (finish in listOf(MechaFinish.ALLOY, MechaFinish.HOLO)) {
                val atlas = mechaMaterialPixels(source, image.width, image.height, slot, finish)
                assertEquals(source.size * MECHA_MATERIAL_PHASES, atlas.size)
                var alphaChanged = false
                var protectedChanged = false
                for (phase in 0 until MECHA_MATERIAL_PHASES) {
                    for (index in source.indices) {
                        val original = source[index]
                        val coated = atlas[phase * source.size + index]
                        val x = index % image.width % MECHA_FRAME
                        val y = index / image.width
                        alphaChanged = alphaChanged || original ushr 24 != coated ushr 24
                        val protected = original ushr 24 != 255 || (original and 0xFFFFFF) in mechanical ||
                            (slot == MechaSlot.HEAD && x in 18..45 && y in 12..23)
                        protectedChanged = protectedChanged || (protected && original != coated)
                    }
                }
                assertFalse("${file.name} $finish alpha/silhouette", alphaChanged)
                assertFalse("${file.name} $finish outline/joints/eyes", protectedChanged)
                assertArrayEquals("${file.name} source was mutated", before, source)
            }
        }
    }

    @Test
    fun realArmorHasDistinctStaticFinishesAndAnimatedPhases() {
        val image = requireNotNull(BitmapFactory.decodeFile(File(assets, "yard/yard_body_1.png").path))
        val source = image.pixels()
        val alloy = mechaMaterialPixels(source, image.width, image.height, MechaSlot.BODY, MechaFinish.ALLOY)
        val holo = mechaMaterialPixels(source, image.width, image.height, MechaSlot.BODY, MechaFinish.HOLO)
        assertTrue(source.indices.any { source[it] != alloy[it] })
        assertTrue(source.indices.any { source[it] != holo[it] })
        assertTrue(source.indices.any { alloy[it] != holo[it] })
        for (atlas in listOf(alloy, holo)) {
            assertTrue(
                (1 until MECHA_MATERIAL_PHASES).any { phase ->
                    source.indices.any { atlas[it] != atlas[phase * source.size + it] }
                }
            )
        }
    }

    @Test
    fun disabledMotionAlwaysSelectsTheSameStaticPhase() {
        for (elapsed in listOf(0L, 399L, 400L, 3199L, 3200L, Long.MAX_VALUE)) {
            assertEquals(0, mechaMaterialPhase(elapsed, motion = false))
            assertTrue(mechaMaterialPhase(elapsed, motion = true) in 0 until MECHA_MATERIAL_PHASES)
        }
        assertEquals(0, mechaMaterialPhase(399, motion = true))
        assertEquals(1, mechaMaterialPhase(400, motion = true))
        assertEquals(7, mechaMaterialPhase(3199, motion = true))
        assertEquals(0, mechaMaterialPhase(3200, motion = true))
    }

    private fun Bitmap.pixels(): IntArray = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

    private fun sheets(): List<File> = assets.walkTopDown().filter { it.extension == "png" }.toList().also { assertTrue(it.isNotEmpty()) }

    private fun slot(file: File): MechaSlot = MechaSlot.valueOf(file.nameWithoutExtension.split('_')[1].uppercase())
}
