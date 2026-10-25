package io.github.esko997.sortie.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A picture of the open article, in reading order: the lead image (key "hero") or a Markdown image (key "b" + block index). */
internal class ArticleFigure(val key: String, val file: File, val caption: String, val size: IntSize?)

/** An image file's pixel size, read from its header without decoding it; null when it is not a readable image. Blocking. */
internal fun imageSize(file: File): IntSize? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, o)
    return if (o.outWidth > 0 && o.outHeight > 0) IntSize(o.outWidth, o.outHeight) else null
}

/** A figure's size on screen, in dp. [upscaled]: drawn larger than its pixels, so it is drawn unsmoothed. */
internal data class FigureBox(val width: Float, val height: Float, val upscaled: Boolean)

/** From this width:height on (16:9 shots, diagrams) a picture may use the full reading width rather than the text measure. */
internal const val WIDE_ASPECT = 1.6f

/**
 * Where a [pxWidth]×[pxHeight] picture goes, in dp: its own aspect ratio (no letterbox bars), as wide as the text [column] — or
 * the wider [wide] reading width for a wide picture — and never taller than [maxHeight]; a portrait picture is narrower instead.
 * Enlarged two or more screen pixels per image pixel, the scale drops to a whole number so pixel art keeps even pixels.
 */
internal fun figureBox(pxWidth: Int, pxHeight: Int, column: Float, wide: Float, maxHeight: Float, density: Float): FigureBox {
    val aspect = pxWidth.toFloat() / pxHeight
    val limit = if (aspect >= WIDE_ASPECT) max(column, wide) else column
    var width = min(limit, maxHeight * aspect)
    val scale = width * density / pxWidth
    if (scale >= 2f) width = floor(scale) * pxWidth / density
    return FigureBox(width, width / aspect, upscaled = width * density > pxWidth + 0.5f)
}

/** A picture whose size could not be read: a 16:9 slate the width of the [column]. */
internal fun unknownFigureBox(column: Float, maxHeight: Float): FigureBox = FigureBox(column, min(column * 9f / 16f, maxHeight), upscaled = false)

/**
 * The article's [figures] full screen on black, starting at the one keyed [start], each fitted to the screen. Pinch, double tap,
 * A and L2/R2 zoom; the D-pad (or stick) pans a zoomed picture and otherwise Left/Right step, as do L1/R1 and the touch buttons.
 * Y or the caption button shows or hides the caption. B, Back or a single tap closes; the article underneath is never moved.
 * A dialog of the reader's own window, so it opens on the screen the reader is on.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FigureViewer(figures: List<ArticleFigure>, start: String, onClose: () -> Unit) {
    if (figures.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    var index by rememberSaveable(start) { mutableIntStateOf(figures.indexOfFirst { it.key == start }.coerceAtLeast(0)) }
    index = index.coerceIn(0, figures.lastIndex)
    var captions by rememberSaveable { mutableStateOf(true) }
    val figure = figures[index]
    val focus = remember { FocusRequester() }
    val page = { delta: Int -> index = (index + delta).coerceIn(0, figures.lastIndex) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onPreviewKeyEvent { e ->
                    val fresh = e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0
                    when (e.key) {
                        Key.ButtonL1, Key.ButtonR1 -> {
                            if (fresh) page(if (e.key == Key.ButtonR1) 1 else -1)
                            true
                        }
                        Key.ButtonY -> {
                            if (fresh) captions = !captions
                            true
                        }
                        else -> false
                    }
                }
        ) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                key(figure.key) {
                    ZoomableImage(
                        figure.file,
                        description = figure.caption.ifEmpty { "Picture ${index + 1} of ${figures.size}" },
                        modifier = Modifier.fillMaxSize(),
                        controller = true,
                        focusRequester = focus,
                        onStep = page,
                        onTap = onClose
                    )
                }
            }
            if (captions && figure.caption.isNotEmpty()) {
                Text(
                    inlineText(figure.caption),
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    color = Color(0xFFD5DAE0),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp)
                )
            }
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "${index + 1} of ${figures.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
                // touch twins of the controller keys; never focused, so the D-pad stays on the picture
                val touchOnly = Modifier.focusProperties { canFocus = false }
                OutlinedButton(onClick = { page(-1) }, enabled = index > 0, modifier = touchOnly) { Text("Previous") }
                OutlinedButton(onClick = { page(1) }, enabled = index < figures.lastIndex, modifier = touchOnly) { Text("Next") }
                if (figure.caption.isNotEmpty()) {
                    OutlinedButton(onClick = { captions = !captions }, modifier = touchOnly) { Text(if (captions) "Hide caption" else "Caption") }
                }
                OutlinedButton(onClick = onClose, modifier = touchOnly) { Text("Close") }
            }
            HintBar(
                "L1/R1 ◀ ▶" to "previous / next",
                "A" to "zoom",
                "L2/R2" to "zoom out / in",
                "D-pad" to "pan when zoomed",
                "Y" to "caption",
                "B" to "close"
            )
        }
    }
}
