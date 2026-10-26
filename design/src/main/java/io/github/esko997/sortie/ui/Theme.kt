package io.github.esko997.sortie.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Amber = Color(0xFFF2B134)
val Ink = Color(0xFF101418)
val Slate = Color(0xFF1B2129)

/** Rules and quiet outlines: a step above Slate, so a 1dp line still reads on Ink and on Slate panels. */
val Hairline = Color(0xFF2A323C)

private val TextColour = Color(0xFFE6E8EB)
private val MutedColour = Color(0xFF9AA3AD)

/**
 * Every Material role is spelled out: the dark baseline would otherwise tint selected chips, switches' tracks and container
 * surfaces with its own violet greys. Containers step up from Ink through Slate to Hairline; selection reads as dark amber.
 */
private val SortieColours = darkColorScheme(
    primary = Amber,
    onPrimary = Ink,
    primaryContainer = Color(0xFF4A3712),
    onPrimaryContainer = Color(0xFFFFDDA6),
    inversePrimary = Color(0xFF7A5A12),
    secondary = Color(0xFFB7C2CE),
    onSecondary = Ink,
    secondaryContainer = Color(0xFF3D3219),
    onSecondaryContainer = Color(0xFFFFDDA6),
    tertiary = Color(0xFF7DD3FC),
    onTertiary = Ink,
    tertiaryContainer = Color(0xFF16384A),
    onTertiaryContainer = Color(0xFFCDEBFA),
    background = Ink,
    onBackground = TextColour,
    surface = Ink,
    onSurface = TextColour,
    surfaceVariant = Slate,
    onSurfaceVariant = MutedColour,
    surfaceTint = MutedColour,
    inverseSurface = TextColour,
    inverseOnSurface = Ink,
    error = Color(0xFFF87171),
    onError = Ink,
    errorContainer = Color(0xFF4C1D1D),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF56616D),
    outlineVariant = Hairline,
    scrim = Color.Black,
    surfaceBright = Color(0xFF303945),
    surfaceDim = Ink,
    surfaceContainerLowest = Color(0xFF0B0E11),
    surfaceContainerLow = Color(0xFF151A20),
    surfaceContainer = Slate,
    surfaceContainerHigh = Color(0xFF222933),
    surfaceContainerHighest = Hairline
)

/**
 * The system sans, set for a handheld held at arm's length: the Material sizes (so layouts keep their density), with titles a
 * weight heavier and tighter, reading text with less of Material's loose tracking, and the smallest label a half step larger
 * (it carries controller hints and badges).
 */
private val SortieType = Typography().run {
    copy(
        displayLarge = displayLarge.tight(FontWeight.SemiBold, (-0.5).sp),
        displayMedium = displayMedium.tight(FontWeight.SemiBold, (-0.25).sp),
        displaySmall = displaySmall.tight(FontWeight.SemiBold, (-0.25).sp),
        headlineLarge = headlineLarge.tight(FontWeight.SemiBold, (-0.25).sp),
        headlineMedium = headlineMedium.tight(FontWeight.SemiBold, (-0.15).sp),
        headlineSmall = headlineSmall.tight(FontWeight.SemiBold, 0.sp),
        titleLarge = titleLarge.tight(FontWeight.SemiBold, 0.sp),
        titleMedium = titleMedium.tight(FontWeight.SemiBold, 0.05.sp),
        titleSmall = titleSmall.tight(FontWeight.SemiBold, 0.1.sp),
        bodyLarge = bodyLarge.copy(letterSpacing = 0.15.sp),
        bodyMedium = bodyMedium.copy(letterSpacing = 0.15.sp),
        bodySmall = bodySmall.copy(letterSpacing = 0.25.sp),
        labelLarge = labelLarge.tight(FontWeight.SemiBold, 0.1.sp),
        labelMedium = labelMedium.copy(letterSpacing = 0.3.sp),
        labelSmall = labelSmall.copy(fontSize = 11.5.sp, letterSpacing = 0.3.sp)
    )
}

private fun TextStyle.tight(weight: FontWeight, tracking: TextUnit) = copy(fontWeight = weight, letterSpacing = tracking)

/**
 * One family of corners: fields 6, chips and rows 10 ([FocusRow]), cards and tiles 14, sheets 16, dialogs 20. Buttons keep
 * Material's pill, which sets actions apart from the things they act on.
 */
private val SortieShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp)
)

@Composable
fun SortieTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SortieColours, typography = SortieType, shapes = SortieShapes, content = content)
}

fun parseColour(hex: String): Color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color(0xFF64748B))
