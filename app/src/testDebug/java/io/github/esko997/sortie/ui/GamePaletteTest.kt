package io.github.esko997.sortie.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GamePaletteTest {
    @get:Rule
    val files = TemporaryFolder()

    @Test
    fun artworkCacheSeparatesShellCanvasesAndKeepsAccentReadable() = runBlocking {
        val image = files.newFile("cover.png")
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.rgb(170, 65, 35))
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        val original = paletteFor(image.path, SortiePalette.DEFAULT.colours.background)
        assertNotNull(original)
        for (palette in SortiePalette.entries) {
            val canvas = palette.colours.background
            val derived = paletteFor(image.path, canvas)
            assertNotNull(derived)
            requireNotNull(derived)
            assertSame(derived, paletteFor(image.path, canvas))
            if (palette != SortiePalette.DEFAULT) assertNotEquals(original!!.backdrop, derived.backdrop)
            assertReadable(derived.accent, canvas)
            assertReadable(derived.accent, derived.backdrop)
        }
    }

    private fun assertReadable(accent: Color, background: Color) {
        val contrast = (accent.luminance() + 0.05f) / (background.luminance() + 0.05f)
        assertTrue("art accent contrast $contrast", contrast >= 4.5f)
    }
}
