package io.github.esko997.sortie.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.delay

/**
 * The navigation screen's background: a diagonal gradient tinted by the game on the companion screen (or the selected game).
 * It only moves when that game changes — a 2.5 s blend sampled at 20 Hz (50 redraws), then it is a static brush: an idle
 * launcher must not draw. The hero rotation itself is 20 s, so the tint shifts about three times a minute while idle.
 */
@Composable
fun AmbientBackground(content: @Composable () -> Unit) {
    val palette by Selection.heroPalette.collectAsState()
    val target = remember(palette) { palette?.let { Pair(lerp(Ink, it.backdrop, 0.55f), lerp(Ink, it.accent, 0.22f)) } ?: Pair(Ink, Ink) }
    var top by remember { mutableStateOf(Ink) }
    var bottom by remember { mutableStateOf(Ink) }
    LaunchedEffect(target) {
        val (t0, b0) = top to bottom
        val steps = (BLEND_S * 20).toInt()
        for (i in 1..steps) {
            delay(50)
            val f = i / steps.toFloat()
            val e = f * f * (3 - 2 * f) // smoothstep
            top = lerp(t0, target.first, e)
            bottom = lerp(b0, target.second, e)
        }
    }
    Box(
        Modifier.fillMaxSize().drawBehind {
            drawRect(Brush.linearGradient(listOf(top, Ink, bottom), start = Offset.Zero, end = Offset(size.width, size.height)))
        }
    ) { content() }
}

private const val BLEND_S = 2.5
