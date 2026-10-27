package io.github.esko997.sortie.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/** Stable save IDs; buying a palette never applies it. Each immutable scheme can also host a local preview. */
enum class SortiePalette(val id: String, val label: String, val price: Int, val description: String, val colours: ColorScheme) {
    DEFAULT("default", "Original", 0, "Accent instruments on charcoal steel.", originalColours()),
    HARBOR("harbor", "Harbor", 8, "Harbour blue panels with sea-glass controls.", harborColours()),
    PLUM("plum", "Plum", 8, "Black-plum panels with lavender controls.", plumColours()),
    LICHEN("lichen", "Lichen", 8, "Forest-dark panels with sage controls.", lichenColours())
}

private fun originalColours() = darkColorScheme(
    primary = Color(0xFFF2B134), onPrimary = Color(0xFF101418),
    primaryContainer = Color(0xFF4A3712), onPrimaryContainer = Color(0xFFFFDDA6), inversePrimary = Color(0xFF7A5A12),
    secondary = Color(0xFFB7C2CE), onSecondary = Color(0xFF101418),
    secondaryContainer = Color(0xFF3D3219), onSecondaryContainer = Color(0xFFFFDDA6),
    tertiary = Color(0xFF7DD3FC), onTertiary = Color(0xFF101418),
    tertiaryContainer = Color(0xFF16384A), onTertiaryContainer = Color(0xFFCDEBFA),
    background = Color(0xFF101418), onBackground = Color(0xFFE6E8EB),
    surface = Color(0xFF101418), onSurface = Color(0xFFE6E8EB),
    surfaceVariant = Color(0xFF1B2129), onSurfaceVariant = Color(0xFF9AA3AD), surfaceTint = Color(0xFF9AA3AD),
    inverseSurface = Color(0xFFE6E8EB), inverseOnSurface = Color(0xFF101418),
    error = Color(0xFFF87171), onError = Color(0xFF101418),
    errorContainer = Color(0xFF4C1D1D), onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF56616D), outlineVariant = Color(0xFF2A323C), scrim = Color.Black,
    surfaceBright = Color(0xFF303945), surfaceDim = Color(0xFF101418),
    surfaceContainerLowest = Color(0xFF0B0E11), surfaceContainerLow = Color(0xFF151A20),
    surfaceContainer = Color(0xFF1B2129), surfaceContainerHigh = Color(0xFF222933), surfaceContainerHighest = Color(0xFF2A323C)
)

private fun harborColours() = darkColorScheme(
    primary = Color(0xFF83D8CF), onPrimary = Color(0xFF0C171D),
    primaryContainer = Color(0xFF174D50), onPrimaryContainer = Color(0xFFB5F3EB), inversePrimary = Color(0xFF176A68),
    secondary = Color(0xFFA8CADD), onSecondary = Color(0xFF0C171D),
    secondaryContainer = Color(0xFF254453), onSecondaryContainer = Color(0xFFD4EBF8),
    tertiary = Color(0xFFE7C58C), onTertiary = Color(0xFF0C171D),
    tertiaryContainer = Color(0xFF4C3B20), onTertiaryContainer = Color(0xFFFFE3B2),
    background = Color(0xFF0C171D), onBackground = Color(0xFFE1EDF1),
    surface = Color(0xFF0C171D), onSurface = Color(0xFFE1EDF1),
    surfaceVariant = Color(0xFF172832), onSurfaceVariant = Color(0xFFA0B6C1), surfaceTint = Color(0xFFA0B6C1),
    inverseSurface = Color(0xFFE1EDF1), inverseOnSurface = Color(0xFF0C171D),
    error = Color(0xFFFFA69D), onError = Color(0xFF0C171D),
    errorContainer = Color(0xFF512724), onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF587583), outlineVariant = Color(0xFF2A414D), scrim = Color.Black,
    surfaceBright = Color(0xFF344F5D), surfaceDim = Color(0xFF0C171D),
    surfaceContainerLowest = Color(0xFF071116), surfaceContainerLow = Color(0xFF112029),
    surfaceContainer = Color(0xFF172832), surfaceContainerHigh = Color(0xFF20343F), surfaceContainerHighest = Color(0xFF2A414D)
)

private fun plumColours() = darkColorScheme(
    primary = Color(0xFFD6B5F4), onPrimary = Color(0xFF1A121E),
    primaryContainer = Color(0xFF4A305F), onPrimaryContainer = Color(0xFFF0D9FF), inversePrimary = Color(0xFF704C8B),
    secondary = Color(0xFFD7BDCC), onSecondary = Color(0xFF1A121E),
    secondaryContainer = Color(0xFF4B3244), onSecondaryContainer = Color(0xFFF5DCEA),
    tertiary = Color(0xFFA3D7CC), onTertiary = Color(0xFF1A121E),
    tertiaryContainer = Color(0xFF24473F), onTertiaryContainer = Color(0xFFC8F3E8),
    background = Color(0xFF1A121E), onBackground = Color(0xFFEFE5F0),
    surface = Color(0xFF1A121E), onSurface = Color(0xFFEFE5F0),
    surfaceVariant = Color(0xFF2A1F31), onSurfaceVariant = Color(0xFFBAA8C1), surfaceTint = Color(0xFFBAA8C1),
    inverseSurface = Color(0xFFEFE5F0), inverseOnSurface = Color(0xFF1A121E),
    error = Color(0xFFFFABA5), onError = Color(0xFF1A121E),
    errorContainer = Color(0xFF52262C), onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF796383), outlineVariant = Color(0xFF41304B), scrim = Color.Black,
    surfaceBright = Color(0xFF503C5B), surfaceDim = Color(0xFF1A121E),
    surfaceContainerLowest = Color(0xFF120C16), surfaceContainerLow = Color(0xFF221827),
    surfaceContainer = Color(0xFF2A1F31), surfaceContainerHigh = Color(0xFF35273E), surfaceContainerHighest = Color(0xFF41304B)
)

private fun lichenColours() = darkColorScheme(
    primary = Color(0xFFBDCF91), onPrimary = Color(0xFF141810),
    primaryContainer = Color(0xFF3B4B24), onPrimaryContainer = Color(0xFFE0EDB9), inversePrimary = Color(0xFF53682F),
    secondary = Color(0xFFB7C9B2), onSecondary = Color(0xFF141810),
    secondaryContainer = Color(0xFF344833), onSecondaryContainer = Color(0xFFD9EBD2),
    tertiary = Color(0xFFE4BD95), onTertiary = Color(0xFF141810),
    tertiaryContainer = Color(0xFF4F3926), onTertiaryContainer = Color(0xFFFFDFC1),
    background = Color(0xFF141810), onBackground = Color(0xFFE8ECDF),
    surface = Color(0xFF141810), onSurface = Color(0xFFE8ECDF),
    surfaceVariant = Color(0xFF222A1C), onSurfaceVariant = Color(0xFFADB7A0), surfaceTint = Color(0xFFADB7A0),
    inverseSurface = Color(0xFFE8ECDF), inverseOnSurface = Color(0xFF141810),
    error = Color(0xFFFFACA0), onError = Color(0xFF141810),
    errorContainer = Color(0xFF512A22), onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF68795B), outlineVariant = Color(0xFF36432D), scrim = Color.Black,
    surfaceBright = Color(0xFF46543C), surfaceDim = Color(0xFF141810),
    surfaceContainerLowest = Color(0xFF0E120B), surfaceContainerLow = Color(0xFF1A2115),
    surfaceContainer = Color(0xFF222A1C), surfaceContainerHigh = Color(0xFF2B3624), surfaceContainerHighest = Color(0xFF36432D)
)
