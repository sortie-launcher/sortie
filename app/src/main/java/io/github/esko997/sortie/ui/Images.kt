package io.github.esko997.sortie.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.ConstraintsSizeResolver
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberConstraintsSizeResolver
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Dimension
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import coil3.size.SizeResolver
import java.io.File

/** Longest edge, in px, decoded when the layout gives the image no bounded size at all. */
private const val UNBOUNDED_FALLBACK_PX = 512

/**
 * The one way Sortie draws a cover or screenshot from local storage. The decode is sized to the composable's measured
 * constraints (INEXACT, so BitmapFactory may stop at the nearest power-of-two sample instead of rescaling), lands in a hardware
 * bitmap (pixels live in graphics memory, not on the heap; nothing may read them back, see [paletteFor]) and is never cross-faded.
 * Callers must give [modifier] a bounded size; a fully unbounded layout falls back to [UNBOUNDED_FALLBACK_PX].
 * A null [path] draws nothing, so callers keep their own placeholder underneath.
 */
@Composable
fun CoverImage(path: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop, alpha: Float = 1f) {
    if (path == null) return
    val context = LocalPlatformContext.current
    val constraints = rememberConstraintsSizeResolver()
    val request = remember(path, constraints, contentScale) {
        ImageRequest.Builder(context)
            .data(File(path))
            .size(BoundedSizeResolver(constraints))
            // an explicit size resolver makes AsyncImage pass the request through untouched, so map the scale ourselves
            .scale(if (contentScale == ContentScale.Fit || contentScale == ContentScale.Inside) Scale.FIT else Scale.FILL)
            .precision(Precision.INEXACT)
            .allowHardware(true)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        modifier = modifier.then(constraints),
        contentScale = contentScale,
        alpha = alpha
    )
}

/** Measured constraints as the decode size; when neither axis is bounded, a [UNBOUNDED_FALLBACK_PX] box instead of the original. */
private class BoundedSizeResolver(private val constraints: ConstraintsSizeResolver) : SizeResolver {
    override suspend fun size(): Size {
        val measured = constraints.size()
        return if (measured.width is Dimension.Undefined && measured.height is Dimension.Undefined) {
            Size(UNBOUNDED_FALLBACK_PX, UNBOUNDED_FALLBACK_PX)
        } else {
            measured
        }
    }
}
