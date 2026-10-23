package io.github.esko997.sortie.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Amber = Color(0xFFF2B134)
val Ink = Color(0xFF101418)
val Slate = Color(0xFF1B2129)

@Composable
fun SortieTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Amber,
            onPrimary = Ink,
            background = Ink,
            surface = Ink,
            surfaceVariant = Slate,
            onSurface = Color(0xFFE6E8EB),
            onSurfaceVariant = Color(0xFF9AA3AD),
        ),
        content = content,
    )
}

fun parseColour(hex: String): Color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color(0xFF64748B))
