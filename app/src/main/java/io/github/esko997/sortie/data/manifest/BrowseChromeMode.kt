package io.github.esko997.sortie.data.manifest

/**
 * How much room Home and the browse screens give their header and hints (`user.browseChrome`). [AUTO] folds them when the
 * window is short or narrow (measured from the window's actual size, not from the device being dual-screen); [EXPANDED] always
 * shows the full header and hint bar; [COMPACT] always folds the header while browsing and uses the one-row hints.
 */
enum class BrowseChromeMode(val id: String, val label: String) {
    AUTO("auto", "Auto"),
    EXPANDED("expanded", "Expanded"),
    COMPACT("compact", "Compact");

    fun next(): BrowseChromeMode = entries[(ordinal + 1) % entries.size]

    companion object {
        /** The mode stored as [id]; anything unknown (hand-edited, from a newer build) is [AUTO]. */
        fun of(id: String): BrowseChromeMode = entries.firstOrNull { it.id == id } ?: AUTO
    }
}
