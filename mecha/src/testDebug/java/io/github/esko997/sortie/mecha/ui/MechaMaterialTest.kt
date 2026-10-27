package io.github.esko997.sortie.mecha.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.esko997.sortie.mecha.MechaFinish
import io.github.esko997.sortie.mecha.MechaPaint
import io.github.esko997.sortie.mecha.MechaPaintScheme
import io.github.esko997.sortie.mecha.MechaSlot
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Authored PNGs and role masks through the same transformer used by the off-main sprite decoder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MechaMaterialTest {
    private val assets = listOf(File("src/main/assets/mecha"), File("mecha/src/main/assets/mecha")).first { it.isDirectory }
    private val width = MECHA_FRAME * 8
    private val height = MECHA_FRAME

    @Test
    fun originalStandardKeepsEveryOriginalPixelWithoutAllocatingAnAtlas() {
        for (file in sheets()) {
            val source = pixels(file)
            val before = source.copyOf()
            val actual = transform(source, mask(file), MechaPaint())
            assertSame(file.name, source, actual)
            assertArrayEquals(file.name, before, actual)
        }
    }

    @Test
    fun allAuthoredMasksPreserveFixedDetailsAlphaAndSourceForEveryEdition() {
        for (file in sheets()) {
            val source = pixels(file)
            val roles = mask(file)
            val before = source.copyOf()
            for (finish in MechaFinish.entries) {
                val atlas = transform(source, roles, MechaPaint(MechaPaintScheme.HAZARD, finish))
                val phases = if (finish == MechaFinish.PLAIN) 1 else MECHA_MATERIAL_PHASES
                assertEquals(source.size * phases, atlas.size)
                var alphaChanged = false
                var fixedChanged = false
                for (phase in 0 until phases) {
                    for (index in source.indices) {
                        val original = source[index]
                        val painted = atlas[phase * source.size + index]
                        alphaChanged = alphaChanged || original ushr 24 != painted ushr 24
                        fixedChanged = fixedChanged || ((roles[index] ushr 16 and 255) == 0 && original != painted)
                    }
                }
                assertFalse("${file.name} $finish alpha/silhouette", alphaChanged)
                assertFalse("${file.name} $finish fixed details", fixedChanged)
                assertArrayEquals("${file.name} source was mutated", before, source)
            }
        }
    }

    @Test
    fun originalAlloyAndHoloMatchTheShippedLegacyTransformExactly() {
        val legacyFamilies = setOf("yard", "survey", "courier", "foundry", "orbital", "tidal", "domestic")
        for (file in sheets().filter { it.parentFile.name in legacyFamilies }) {
            val source = pixels(file)
            val roles = mask(file)
            val slot = MechaSlot.valueOf(file.nameWithoutExtension.split('_')[1].uppercase())
            for (finish in listOf(MechaFinish.ALLOY, MechaFinish.HOLO)) {
                val atlas = transform(source, roles, MechaPaint(finish = finish))
                for (phase in 0 until MECHA_MATERIAL_PHASES) {
                    val expected = IntArray(source.size) { index ->
                        legacyPixel(source[index], index % width % MECHA_FRAME, index / width, phase, slot, finish)
                    }
                    assertArrayEquals("${file.name} $finish phase $phase", expected, atlas.copyOfRange(phase * source.size, (phase + 1) * source.size))
                }
            }
        }
    }

    @Test
    fun tenSchemesHaveDistinctPrimarySecondaryRampsAndAnimatedEditions() {
        val file = File(assets, "yard/yard_body_1.png")
        val source = pixels(file)
        val roles = mask(file)
        val standards = mutableListOf<IntArray>()
        for (scheme in MechaPaintScheme.entries.filter { it != MechaPaintScheme.ORIGINAL }) {
            val standard = transform(source, roles, MechaPaint(scheme))
            assertFalse(source.contentEquals(standard))
            assertTrue(standards.none { it.contentEquals(standard) })
            standards.add(standard)
            val alloy = transform(source, roles, MechaPaint(scheme, MechaFinish.ALLOY))
            val holo = transform(source, roles, MechaPaint(scheme, MechaFinish.HOLO))
            assertTrue(source.indices.any { standard[it] != alloy[it] })
            assertTrue(source.indices.any { standard[it] != holo[it] })
            assertTrue(source.indices.any { alloy[it] != holo[it] })
            for (atlas in listOf(alloy, holo)) {
                assertTrue((1 until MECHA_MATERIAL_PHASES).any { phase -> source.indices.any { atlas[it] != atlas[phase * source.size + it] } })
            }
        }
        assertEquals(10, standards.size)
    }

    @Test
    fun roleAndToneControlPigmentWithoutSourceColourGuessing() {
        val source = IntArray(width * height)
        val roles = IntArray(source.size)
        // Identical black source pigment, including partial-alpha authored pixels: only the mask controls recolouring.
        for (index in 0..7) {
            source[index] = 0x7F000000
            roles[index] = 0x7F000000 or ((index / 4 + 1) shl 16) or ((index % 4) shl 8) or 1
        }
        source[8] = 0xFFABCDEF.toInt()
        roles[8] = 0xFF000001.toInt() // Legacy eligibility never makes a fixed detail paintable in a colour scheme.
        for (scheme in MechaPaintScheme.entries.filter { it != MechaPaintScheme.ORIGINAL }) {
            val atlas = transform(source, roles, MechaPaint(scheme))
            for ((role, swatch) in listOf(scheme.primary, scheme.secondary).withIndex()) {
                val offset = role * 4
                assertEquals(swatch and 0xFFFFFF, atlas[offset + 1] and 0xFFFFFF)
                for (channel in listOf(0, 8, 16)) {
                    val base = swatch ushr channel and 255
                    assertEquals(base * 64 / 100, atlas[offset] ushr channel and 255)
                    assertEquals(base + (255 - base) * 34 / 100, atlas[offset + 2] ushr channel and 255)
                    assertEquals(base + (255 - base) * 64 / 100, atlas[offset + 3] ushr channel and 255)
                }
            }
            for (finish in MechaFinish.entries) {
                val painted = transform(source, roles, MechaPaint(scheme, finish))
                for (index in painted.indices) assertEquals(source[index % source.size] ushr 24, painted[index] ushr 24)
                for (phase in 0 until painted.size / source.size) assertEquals(source[8], painted[phase * source.size + 8])
            }
        }
    }

    @Test
    fun colouredAlloyKeepsItsPigmentInsteadOfTurningEverySchemeSilver() {
        val index = 9 * width + 12
        val source = IntArray(width * height).also { it[index] = 0xFF808080.toInt() }
        val roles = IntArray(source.size).also { it[index] = 0xFF010101.toInt() }
        fun chroma(pixel: Int): Int {
            val channels = listOf(pixel ushr 16 and 255, pixel ushr 8 and 255, pixel and 255)
            return channels.max() - channels.min()
        }
        for (scheme in listOf(MechaPaintScheme.HAZARD, MechaPaintScheme.RESCUE, MechaPaintScheme.MOSS, MechaPaintScheme.CANDY)) {
            val atlas = transform(source, roles, MechaPaint(scheme, MechaFinish.ALLOY))
            for (phase in 0 until MECHA_MATERIAL_PHASES) {
                assertTrue("$scheme lost its pigment in phase $phase", chroma(atlas[phase * source.size + index]) >= chroma(scheme.primary) / 2)
            }
        }
    }

    @Test
    fun malformedMasksAreRejectedInsteadOfPaintingUnidentifiedPixels() {
        val source = IntArray(width * height).also { it[0] = 0xFFABCDEF.toInt() }
        val valid = IntArray(source.size).also { it[0] = 0xFF010101.toInt() }
        for (invalid in listOf(0x7F010101, 0xFF030101.toInt(), 0xFF010401.toInt(), 0xFF010102.toInt())) {
            val mask = valid.copyOf().also { it[0] = invalid }
            assertThrows(IllegalArgumentException::class.java) { transform(source, mask, MechaPaint(MechaPaintScheme.CANDY)) }
        }
        assertThrows(IllegalArgumentException::class.java) { transform(source, valid.copyOf(1), MechaPaint()) }
        val transparent = valid.copyOf().also { it[1] = 0x00010000 }
        assertThrows(IllegalArgumentException::class.java) { transform(source, transparent, MechaPaint()) }
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

    private fun transform(source: IntArray, mask: IntArray, paint: MechaPaint): IntArray = mechaMaterialPixels(source, mask, width, height, paint)

    private fun pixels(file: File): IntArray {
        val options = BitmapFactory.Options().apply { inPremultiplied = false }
        val bitmap = requireNotNull(BitmapFactory.decodeFile(file.path, options))
        return try {
            assertEquals(width, bitmap.width)
            assertEquals(height, bitmap.height)
            bitmap.pixels()
        } finally {
            bitmap.recycle()
        }
    }

    private fun mask(file: File): IntArray = pixels(File(file.parentFile, "${file.nameWithoutExtension}.paint.png"))
    private fun Bitmap.pixels(): IntArray = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }
    private fun sheets(): List<File> = assets.walkTopDown().filter {
        it.extension == "png" && !it.name.endsWith(".paint.png")
    }.toList().also { assertTrue(it.isNotEmpty()) }

    /** Frozen pre-paint v3 arithmetic; intentionally independent of production mask interpretation and coating code. */
    private fun legacyPixel(pixel: Int, x: Int, y: Int, phase: Int, slot: MechaSlot, finish: MechaFinish): Int {
        if (pixel ushr 24 != 255) return pixel
        when (pixel and 0xFFFFFF) {
            0x18232D, 0x344650, 0x768A91, 0xA6B5B1, 0xD7DFD5 -> return pixel
        }
        if (slot == MechaSlot.HEAD && x in 18..45 && y in 12..23) return pixel
        val r = pixel ushr 16 and 255
        val g = pixel ushr 8 and 255
        val b = pixel and 255
        if (maxOf(r, g, b) < 96) return pixel
        fun rgb(red: Int, green: Int, blue: Int): Int = (255 shl 24) or (red.coerceIn(0, 255) shl 16) or (green.coerceIn(0, 255) shl 8) or blue.coerceIn(0, 255)
        return if (finish == MechaFinish.ALLOY) {
            val distance = abs(x + y / 2 - (phase * 12 - 6))
            val glint = if (distance <= 2) 30 else if (distance <= 4) 12 else 0
            val brush = if (y % 4 == 0) -5 else 0
            val base = (r * 54 + g * 183 + b * 19) / 256 + brush + glint
            rgb((base * 7 + r) / 8 - 6, (base * 7 + g) / 8 + 2, (base * 7 + b) / 8 + 10)
        } else {
            val tint = when (((x + y / 2 + phase * 8) / 16) % 4) {
                0 -> 0xA4DCEB
                1 -> 0xB8ACE8
                2 -> 0xE8AECF
                else -> 0xAADBC5
            }
            rgb((r * 3 + (tint ushr 16 and 255)) / 4, (g * 3 + (tint ushr 8 and 255)) / 4, (b * 3 + (tint and 255)) / 4)
        }
    }
}
