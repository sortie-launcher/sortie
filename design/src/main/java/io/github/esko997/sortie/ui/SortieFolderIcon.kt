package io.github.esko997.sortie.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp

/** The very same static illustration used by home grid tiles and list rows. No bitmap or ticker. */
@Composable
fun SortieFolderIconPreview(pack: SortieFolderIcons, modifier: Modifier = Modifier) {
    SortieFolderIcon(pack = pack, modifier = modifier.size(64.dp))
}

@Composable
fun SortieFolderIcon(modifier: Modifier = Modifier, pack: SortieFolderIcons = LocalSortieFolderIcons.current, colour: Color = Accent) {
    val scheme = MaterialTheme.colorScheme
    val ink = scheme.surfaceContainerLowest
    val shell = scheme.surfaceContainerHighest
    val edge = scheme.onSurfaceVariant
    val paper = scheme.onSurface
    Box(
        modifier.drawWithCache {
            val unit = minOf(size.width, size.height) / 64f
            val ridge = ink.copy(alpha = 0.45f)
            onDrawBehind {
                translate((size.width - 64f * unit) / 2f, (size.height - 64f * unit) / 2f) {
                    scale(unit, unit, Offset.Zero) {
                        when (pack) {
                            SortieFolderIcons.DEFAULT -> block(colour, 24f, 24f, 16f, 16f, 5f)
                            SortieFolderIcons.DRAWERS -> {
                                block(ink, 10f, 9f, 44f, 48f, 4f)
                                block(edge, 11f, 8f, 42f, 46f, 3f)
                                block(shell, 13f, 11f, 38f, 40f, 2f)
                                block(colour, 15f, 13f, 34f, 16f, 1f)
                                block(colour, 15f, 32f, 34f, 16f, 1f)
                                block(ink, 22f, 17f, 20f, 8f, 1f)
                                block(paper, 25f, 19f, 14f, 3f)
                                block(ink, 22f, 36f, 20f, 8f, 1f)
                                block(paper, 25f, 38f, 14f, 3f)
                                block(ink, 15f, 54f, 6f, 3f)
                                block(ink, 43f, 54f, 6f, 3f)
                            }
                            SortieFolderIcons.CARTRIDGES -> {
                                block(ink, 7f, 9f, 34f, 43f, 3f)
                                block(edge, 9f, 8f, 30f, 41f, 2f)
                                block(shell, 12f, 11f, 24f, 15f, 1f)
                                block(ink, 21f, 17f, 36f, 41f, 3f)
                                block(colour, 23f, 16f, 32f, 38f, 2f)
                                block(ink, 27f, 20f, 24f, 18f, 1f)
                                block(paper, 30f, 23f, 18f, 3f)
                                block(edge, 30f, 29f, 11f, 2f)
                                for (y in 41..47 step 3) block(ridge, 27f, y.toFloat(), 24f, 1f)
                                block(ink, 29f, 50f, 20f, 6f)
                                for (x in 31..46 step 4) block(colour, x.toFloat(), 51f, 2f, 4f)
                            }
                            SortieFolderIcons.PIXEL -> {
                                block(ink, 8f, 12f, 24f, 8f)
                                block(ink, 4f, 20f, 56f, 32f)
                                block(ink, 8f, 52f, 48f, 4f)
                                block(colour, 12f, 16f, 16f, 8f)
                                block(colour, 8f, 24f, 48f, 24f)
                                block(colour, 12f, 48f, 40f, 4f)
                                block(paper, 12f, 24f, 40f, 4f)
                                block(paper, 12f, 28f, 4f, 8f)
                                block(shell, 20f, 40f, 28f, 4f)
                                block(shell, 44f, 32f, 4f, 8f)
                            }
                            SortieFolderIcons.INDUSTRIAL -> {
                                block(ink, 8f, 17f, 48f, 38f, 3f)
                                block(edge, 10f, 16f, 44f, 36f, 2f)
                                block(shell, 13f, 19f, 38f, 30f)
                                block(ink, 24f, 9f, 16f, 9f, 2f)
                                block(edge, 27f, 11f, 10f, 3f)
                                block(colour, 18f, 19f, 5f, 30f)
                                block(colour, 41f, 19f, 5f, 30f)
                                block(ink, 13f, 31f, 38f, 2f)
                                block(paper, 17f, 29f, 7f, 7f, 1f)
                                block(paper, 40f, 29f, 7f, 7f, 1f)
                                block(ink, 27f, 37f, 10f, 7f, 1f)
                                block(colour, 29f, 39f, 6f, 2f)
                                drawCircle(ink, 1.3f, Offset(13f, 20f))
                                drawCircle(ink, 1.3f, Offset(50f, 20f))
                                drawCircle(ink, 1.3f, Offset(13f, 47f))
                                drawCircle(ink, 1.3f, Offset(50f, 47f))
                            }
                        }
                    }
                }
            }
        }
    )
}

private fun DrawScope.block(colour: Color, x: Float, y: Float, width: Float, height: Float, radius: Float = 0f) {
    drawRoundRect(colour, Offset(x, y), Size(width, height), CornerRadius(radius))
}
