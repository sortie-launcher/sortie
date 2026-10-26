package io.github.esko997.sortie.ui

import androidx.compose.animation.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * An artwork-tinted backdrop that settles after a selection. Visibility is observed outside the frame clock: a hidden
 * activity cancels its blend immediately, even when a companion on another display keeps changing the shared palette.
 */
@Composable
fun AmbientBackground(content: @Composable () -> Unit) {
    val shown = rememberShown()
    val top = remember { Animatable(Ink) }
    val bottom = remember { Animatable(Ink) }
    LaunchedEffect(shown) {
        combine(shown, Selection.heroPalette) { visible, palette -> visible to palette }
            .collectLatest { (visible, palette) ->
                val upper = palette?.let { lerp(Ink, it.backdrop, 0.55f) } ?: Ink
                val lower = palette?.let { lerp(Ink, it.accent, 0.22f) } ?: Ink
                if (visible) {
                    coroutineScope {
                        launch { top.animateTo(upper, tween(420)) }
                        launch { bottom.animateTo(lower, tween(420)) }
                    }
                } else {
                    top.snapTo(upper)
                    bottom.snapTo(lower)
                }
            }
    }
    Box(
        Modifier.fillMaxSize().drawWithCache {
            val brush = Brush.linearGradient(listOf(top.value, Ink, bottom.value), start = Offset.Zero, end = Offset(size.width, size.height))
            onDrawBehind { drawRect(brush) }
        }
    ) { content() }
}
