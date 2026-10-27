package io.github.esko997.sortie.mecha.ui

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asAndroidBitmap
import io.github.esko997.sortie.mecha.MechaFinish
import io.github.esko997.sortie.mecha.MechaPaint
import io.github.esko997.sortie.mecha.MechaPaintScheme
import io.github.esko997.sortie.mecha.MechaPart
import io.github.esko997.sortie.mecha.MechaSlot
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MechaSheetsTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun actualDecoderSharesConcurrentKeysSeparatesPaintsAndBoundsItsLruWithoutRecyclingVisibleSheets() = runBlocking {
        val sourceRoot = listOf(File("src/main/assets/mecha"), File("mecha/src/main/assets/mecha")).first { it.isDirectory }
        val base = File(sourceRoot, "yard/yard_body_1.png")
        val mask = File(sourceRoot, "yard/yard_body_1.paint.png")
        val part = MechaPart("cache_body_1", MechaSlot.BODY, "yard", "Cache test", "mecha/renderer-cache/body.png")
        // A real asset package gives BitmapFactory/AssetManager the same byte stream as production without app resources.
        val apk = temporary.newFile("renderer-assets.apk")
        ZipOutputStream(apk.outputStream()).use { output ->
            for ((path, file) in listOf(part.asset to base, part.paintAsset to mask)) {
                output.putNextEntry(ZipEntry("assets/$path"))
                file.inputStream().use { it.copyTo(output) }
                output.closeEntry()
            }
        }
        val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
        try {
            val cookie = AssetManager::class.java.getMethod("addAssetPath", String::class.java).invoke(assets, apk.absolutePath) as Int
            assertTrue("Asset package was not opened", cookie != 0)
            val plain = MechaSheets.get(assets, part, MechaPaint())
            assertEquals(1, plain.phases)
            assertEquals(64, plain.image.height)
            val authored = requireNotNull(BitmapFactory.decodeFile(base.path))
            try {
                val expected = IntArray(512 * 64)
                val actual = IntArray(expected.size)
                authored.getPixels(expected, 0, 512, 0, 0, 512, 64)
                plain.image.asAndroidBitmap().getPixels(actual, 0, 512, 0, 0, 512, 64)
                assertArrayEquals(expected, actual)
            } finally {
                authored.recycle()
            }

            val paint = MechaPaint(MechaPaintScheme.MOSS, MechaFinish.ALLOY)
            val concurrent = List(16) { async(Dispatchers.Default) { MechaSheets.get(assets, part, paint) } }.awaitAll()
            val visible = concurrent.first()
            concurrent.forEach { assertSame(visible, it) }
            assertSame(visible, MechaSheets.peek(part.asset, paint))
            assertEquals(MECHA_MATERIAL_PHASES, visible.phases)
            assertNotSame(visible, MechaSheets.get(assets, part, paint.copy(finish = MechaFinish.HOLO)))
            assertNotSame(visible, MechaSheets.get(assets, part, paint.copy(scheme = MechaPaintScheme.PLUM)))

            // Each finished atlas is exactly 1 MiB. Fourteen other keys overflow the 12 MiB cache.
            val paints = MechaPaintScheme.entries.flatMap { scheme ->
                listOf(MechaPaint(scheme, MechaFinish.ALLOY), MechaPaint(scheme, MechaFinish.HOLO))
            }.filter { it != paint }.take(14)
            for (next in paints) MechaSheets.get(assets, part, next)
            assertNull(MechaSheets.peek(part.asset, paint))
            assertEquals(12, paints.count { MechaSheets.peek(part.asset, it) != null })
            assertFalse(visible.image.asAndroidBitmap().isRecycled)
            assertNotSame(visible, MechaSheets.get(assets, part, paint))
        } finally {
            assets.close()
        }
    }
}
