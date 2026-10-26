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
