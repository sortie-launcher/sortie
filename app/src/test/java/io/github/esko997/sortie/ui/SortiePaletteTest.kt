package io.github.esko997.sortie.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertTrue
import org.junit.Test

class SortiePaletteTest {
    @Test
    fun eachDarkShellHasReadableLabelsAndMaterialActions() {
        for (palette in SortiePalette.entries) {
            val c = palette.colours
            assertTrue("${palette.id} is a dark shell", c.background.luminance() < 0.025f)
            val surfaces =
                listOf(
                    c.background,
                    c.surfaceContainerLowest,
                    c.surfaceContainerLow,
                    c.surfaceContainer,
                    c.surfaceContainerHigh,
                    c.surfaceContainerHighest,
                    c.surfaceBright
                )
            for (surface in surfaces) readable(palette, c.onSurface, surface)
            readable(palette, c.onSurfaceVariant, c.background)
            readable(palette, c.onSurfaceVariant, c.surfaceContainer)
            readable(palette, c.primary, c.background)
            readable(palette, c.primary, c.surfaceContainer)
            readable(palette, c.onPrimary, c.primary)
            readable(palette, c.onPrimaryContainer, c.primaryContainer)
            readable(palette, c.onSecondary, c.secondary)
            readable(palette, c.onSecondaryContainer, c.secondaryContainer)
            readable(palette, c.onTertiary, c.tertiary)
            readable(palette, c.onTertiaryContainer, c.tertiaryContainer)
            readable(palette, c.onError, c.error)
            readable(palette, c.onErrorContainer, c.errorContainer)
            readable(palette, c.error, c.surfaceContainer)
            readable(palette, c.inverseOnSurface, c.inverseSurface)
            readable(palette, c.inversePrimary, c.inverseSurface)
        }
    }

    private fun readable(palette: SortiePalette, foreground: Color, background: Color) {
        val a = foreground.luminance()
        val b = background.luminance()
        val contrast = (max(a, b) + 0.05f) / (min(a, b) + 0.05f)
        assertTrue("${palette.id}: $foreground on $background has contrast $contrast", contrast >= 4.5f)
    }
}
