package io.github.esko997.sortie.ui

import androidx.compose.runtime.compositionLocalOf

/** Stable purchase IDs. Ownership and the committed selection are deliberately separate. */
enum class SortieFolderIcons(val id: String, val label: String, val price: Int, val description: String) {
    DEFAULT("default", "Original", 0, "The familiar colour markers and clear library tiles."),
    DRAWERS("drawers", "Drawers", 4, "Small archive cabinets with label plates and recessed pulls."),
    CARTRIDGES("cartridges", "Cartridges", 4, "A pair of game cartridges with ridged shells and contact teeth."),
    PIXEL("pixel", "Pixel", 4, "Stepped pixel folders with a bright tab and chunky outline."),
    INDUSTRIAL("industrial", "Industrial", 4, "Riveted transport cases with corner guards and locking straps.");

    companion object {
        fun fromId(id: String): SortieFolderIcons? = entries.firstOrNull { it.id == id }
    }
}

enum class SortieButtonStyle(val id: String, val label: String, val price: Int, val description: String) {
    DEFAULT("default", "Original", 0, "The familiar clean, rounded launcher controls."),
    INSTRUMENT("instrument", "Instrument", 6, "Bevelled instrument-panel keys with square shoulders and corner screws."),
    ARCADE("arcade", "Arcade", 6, "Raised capsule keys with an inset rim and a solid lower lip.");

    companion object {
        fun fromId(id: String): SortieButtonStyle? = entries.firstOrNull { it.id == id }
    }
}

val LocalSortieFolderIcons = compositionLocalOf { SortieFolderIcons.DEFAULT }
val LocalSortieButtonStyle = compositionLocalOf { SortieButtonStyle.DEFAULT }
