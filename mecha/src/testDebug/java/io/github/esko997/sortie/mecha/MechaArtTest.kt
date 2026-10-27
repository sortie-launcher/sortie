package io.github.esko997.sortie.mecha

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises the packaged files with the production parser, not synthetic catalog fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MechaArtTest {
    private val assets = File("src/main/assets")
    private val parts = MECHA_FAMILIES.flatMap { family ->
        parseMechaCatalog(family, File(assets, "mecha/$family/catalog.json").readText()) { asset ->
            val image = BitmapFactory.decodeFile(File(assets, asset).path)
            when {
                image == null -> "$asset is not a readable image"
                image.width != 512 || image.height != 64 -> "$asset is not an eight-pose 512x64 sheet"
                !image.hasAlpha() || image.config != Bitmap.Config.ARGB_8888 -> "$asset is not RGBA"
                else -> null
            }
        }
    }

    @Test
    fun fiftyCompleteAuthoredSetsIncludeSemanticMasks() {
        assertEquals(25, MECHA_FAMILIES.size)
        assertEquals(200, parts.size)
        assertEquals(200, parts.map { it.id }.toSet().size)
        val originals = setOf("yard", "survey", "courier", "foundry", "orbital", "tidal", "domestic")
        for (family in MECHA_FAMILIES) {
            val familyParts = parts.filter { it.family == family }
            assertEquals("$family: two complete four-part sets", 8, familyParts.size)
            for (slot in MechaSlot.entries) {
                for (variant in 1..2) {
                    assertTrue("$family: missing $slot set $variant", familyParts.any { it.id == "${family}_${slot.name.lowercase()}_$variant" })
                }
            }
        }
        for (part in parts) {
            val sheet = requireNotNull(BitmapFactory.decodeFile(File(assets, part.asset).path))
            val maskPath = File(assets, part.asset.removeSuffix(".png") + ".paint.png")
            val mask = requireNotNull(BitmapFactory.decodeFile(maskPath.path, BitmapFactory.Options().apply { inPremultiplied = false }))
            assertEquals("${part.id}: mask width", 512, mask.width)
            assertEquals("${part.id}: mask height", 64, mask.height)
            val bytes = maskPath.readBytes()
            assertEquals("${part.id}: mask bit depth", 8, bytes[24].toInt())
            assertEquals("${part.id}: mask RGBA", 6, bytes[25].toInt())
            for (frame in 0..7) {
                val roles = IntArray(3)
                for (y in 0..63) {
                    for (x in 0..63) {
                        val source = sheet.getPixel(frame * 64 + x, y)
                        val semantic = mask.getPixel(frame * 64 + x, y)
                        val alpha = semantic ushr 24
                        val role = semantic ushr 16 and 255
                        val tone = semantic ushr 8 and 255
                        val eligibility = semantic and 255
                        assertEquals("${part.id}: mask alpha $frame:$x,$y", source ushr 24, alpha)
                        assertTrue("${part.id}: invalid role", role in 0..2)
                        assertTrue("${part.id}: invalid tone", tone in 0..3)
                        assertTrue("${part.id}: invalid Original eligibility", eligibility in 0..1)
                        if (alpha == 0) assertEquals("${part.id}: transparent mask RGB", 0, semantic)
                        if (role == 0) assertEquals("${part.id}: fixed tone", 0, tone)
                        if (alpha != 0) roles[role]++
                        if (part.family in originals) {
                            val rgb = source and 0xffffff
                            val mechanical = rgb == 0x18232d || rgb == 0x344650 || rgb == 0x768a91 || rgb == 0xa6b5b1 || rgb == 0xd7dfd5
                            val face = part.slot == MechaSlot.HEAD && x in 18..45 && y in 12..23
                            val dark = maxOf(source ushr 16 and 255, source ushr 8 and 255, source and 255) < 96
                            val expected = if (source ushr 24 == 255 && !mechanical && !face && !dark) 1 else 0
                            assertEquals("${part.id}: Original finish compatibility", expected, eligibility)
                        } else {
                            assertEquals("${part.id}: new paint eligibility", if (role == 0) 0 else 1, eligibility)
                        }
                    }
                }
                assertTrue("${part.id} pose $frame: no painted region", roles[1] + roles[2] > 0)
                if (part.family !in originals) {
                    assertTrue("${part.id} pose $frame: missing primary region", roles[1] >= 4)
                    assertTrue("${part.id} pose $frame: missing secondary region", roles[2] >= 4)
                }
            }
            mask.recycle()
            sheet.recycle()
        }
    }

    @Test
    fun everyPoseKeepsHardPixelsSharedOutlineAndAttachmentZones() {
        for (part in parts) {
            val sheet = requireNotNull(BitmapFactory.decodeFile(File(assets, part.asset).path))
            // PNG IHDR: eight-bit RGBA, not indexed artwork or an RGB image with a transparency key.
            val bytes = File(assets, part.asset).readBytes()
            assertEquals("${part.id}: PNG bit depth", 8, bytes[24].toInt())
            assertEquals("${part.id}: PNG color type", 6, bytes[25].toInt())
            for (frame in 0..7) {
                var opaque = 0
                var outline = 0
                for (y in 0..63) {
                    for (x in 0..63) {
                        val pixel = sheet.getPixel(frame * 64 + x, y)
                        val alpha = pixel ushr 24
                        assertTrue("${part.id} pose $frame: antialiased pixel at $x,$y", alpha == 0 || alpha == 255)
                        if (alpha == 255) opaque++
                        if (pixel == 0xff18232d.toInt()) outline++
                    }
                }
                assertTrue("${part.id} pose $frame: empty pose", opaque > 0)
                assertTrue("${part.id} pose $frame: shared outline missing", outline > 0)
                for (zone in attachmentZones(part.slot)) {
                    assertTrue("${part.id} pose $frame: missing attachment $zone", occupied(sheet, frame, zone))
                }
            }
        }
    }

    // Same attachment regions as workshop/procedural/assemble.py's shared rig. Bounds are exclusive at right/bottom;
    // they accommodate the authored outlines around neck (32,25), shoulders (19/45,29), and hips (26/38,44).
    private data class Zone(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun attachmentZones(slot: MechaSlot): List<Zone> {
        val neck = Zone(27, 21, 38, 28)
        val shoulders = listOf(Zone(16, 26, 24, 34), Zone(41, 26, 49, 34))
        val hips = listOf(Zone(22, 40, 30, 48), Zone(35, 40, 43, 48))
        return when (slot) {
            MechaSlot.HEAD -> listOf(neck)
            MechaSlot.BODY -> listOf(neck) + shoulders + hips
            MechaSlot.ARMS -> shoulders
            MechaSlot.LEGS -> hips
        }
    }

    private fun occupied(image: Bitmap, frame: Int, zone: Zone): Boolean {
        for (y in zone.top until zone.bottom) {
            for (x in zone.left until zone.right) {
                if (image.getPixel(frame * 64 + x, y) ushr 24 == 255) return true
            }
        }
        return false
    }
}
