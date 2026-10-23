package io.github.esko997.sortie.ui

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Accent and backdrop derived from a cover: the "procedural theme" juice. Cached per path; cheap because the bitmap is downsampled. */
data class GamePalette(val accent: Color, val backdrop: Color)

private val cache = HashMap<String, GamePalette?>()

suspend fun paletteFor(path: String?): GamePalette? {
    path ?: return null
    // written from Dispatchers.Default below, so reads take the same lock
    synchronized(cache) { if (cache.containsKey(path)) return cache[path] }
    return withContext(Dispatchers.Default) {
        runCatching {
            // its own 1/8-scale software decode: Coil's covers are hardware bitmaps, whose pixels cannot be read back
            val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
            val bmp = BitmapFactory.decodeFile(path, opts) ?: return@runCatching null
            val p = Palette.from(bmp).clearFilters().generate().also { bmp.recycle() }
            val swatch = p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.dominantSwatch ?: return@runCatching null
            val dark = p.darkMutedSwatch ?: p.darkVibrantSwatch ?: p.mutedSwatch
            GamePalette(
                accent = Color(swatch.rgb).ensureContrastOn(Ink),
                backdrop = (dark?.let { Color(it.rgb) } ?: Ink).copy(alpha = 1f).blend(Ink, 0.75f)
            )
        }.getOrNull().also { synchronized(cache) { cache[path] = it } }
    }
}

@Composable
fun rememberPalette(path: String?): GamePalette? = produceState<GamePalette?>(null, path) { value = paletteFor(path) }.value

private fun Color.blend(other: Color, towardsOther: Float): Color = Color(
    red = red + (other.red - red) * towardsOther,
    green = green + (other.green - green) * towardsOther,
    blue = blue + (other.blue - blue) * towardsOther
)

/** Lift very dark accents so they still read against the ink background. */
private fun Color.ensureContrastOn(bg: Color): Color {
    val lum = 0.2126f * red + 0.7152f * green + 0.0722f * blue
    return if (lum < 0.35f) blend(Color.White, (0.35f - lum) * 1.6f) else this
}
