package io.github.esko997.sortie.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Accent: Color
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.primary
val CanvasColour: Color
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.background
val PanelColour: Color
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surfaceContainer
val RuleColour: Color
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.outlineVariant

private val LocalPalette = staticCompositionLocalOf { SortiePalette.DEFAULT }

// The original shell's small surface steps are retained exactly; alternate shells use their matching Material roles.
val BarColour: Color
    @Composable @ReadOnlyComposable
    get() = if (LocalPalette.current == SortiePalette.DEFAULT) Color(0xFF0B0D10) else MaterialTheme.colorScheme.surfaceContainerLowest
val KeyCapColour: Color
    @Composable @ReadOnlyComposable
    get() = if (LocalPalette.current == SortiePalette.DEFAULT) Color(0xEB2E3742) else MaterialTheme.colorScheme.surfaceBright.copy(alpha = 0xEB / 255f)
val ButtonFaceColour: Color
    @Composable @ReadOnlyComposable
    get() = if (LocalPalette.current == SortiePalette.DEFAULT) Color(0xFF26303A) else MaterialTheme.colorScheme.surfaceContainerHigh
val FocusSurfaceColour: Color
    @Composable @ReadOnlyComposable
    get() = if (LocalPalette.current == SortiePalette.DEFAULT) Color(0x403C485C) else MaterialTheme.colorScheme.outline.copy(alpha = 0x40 / 255f)
val LiftedPanelColour: Color
    @Composable @ReadOnlyComposable
    get() = if (LocalPalette.current == SortiePalette.DEFAULT) Color(0xFF252D37) else MaterialTheme.colorScheme.surfaceContainerHigh
val MonogramColour: Color
    @Composable @ReadOnlyComposable
    get() = if (LocalPalette.current == SortiePalette.DEFAULT) Color(0xFF3B4550) else MaterialTheme.colorScheme.outline
val CoverInitialColour: Color
    @Composable @ReadOnlyComposable
    get() = if (LocalPalette.current == SortiePalette.DEFAULT) Color(0xFF4A525C) else MaterialTheme.colorScheme.outline
val DisabledLabelColour: Color
    @Composable @ReadOnlyComposable
    get() = if (LocalPalette.current == SortiePalette.DEFAULT) Color(0xFF5C6570) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)

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
 * One family of corners: fields 6, chips and rows 10 ([FocusRow]), cards and tiles 14, sheets 16, dialogs 20.
 * Original buttons keep Material's pill; purchased button treatments change their shell, not their layout or targets.
 */
private val SortieShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp)
)

@Composable
fun SortieTheme(
    palette: SortiePalette = SortiePalette.DEFAULT,
    folderIcons: SortieFolderIcons = SortieFolderIcons.DEFAULT,
    buttonStyle: SortieButtonStyle = SortieButtonStyle.DEFAULT,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalPalette provides palette,
        LocalSortieFolderIcons provides folderIcons,
        LocalSortieButtonStyle provides buttonStyle
    ) {
        MaterialTheme(colorScheme = palette.colours, typography = SortieType, shapes = SortieShapes, content = content)
    }
}

fun parseColour(hex: String): Color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color(0xFF64748B))
