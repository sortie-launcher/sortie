package io.github.esko997.sortie.data.setup

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryPresetsTest {
    private val presets = LibraryPresets.systems

    /** System ids key every game id (`<system>/<file>`); two presets with one id would merge libraries. */
    @Test
    fun systemIdsAreUnique() {
        assertEquals(presets.size, presets.map { it.system.id }.toSet().size)
    }

    /** One id, one definition: saving two presets that share a profile must never rewrite another system's launch. */
    @Test
    fun sharedProfileIdsHaveIdenticalDefinitions() {
        presets.flatMap { it.profiles }.groupBy { it.id }.forEach { (id, defs) -> assertEquals(id, 1, defs.toSet().size) }
    }
}
