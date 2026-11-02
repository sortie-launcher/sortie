package io.github.esko997.sortie.data.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /** Every listed core has its own id and a name, the catalog's cores included, and the list is in name order (letter jump). */
    @Test
    fun coreListIsUniqueNamedSortedAndCoversTheCatalog() {
        val ids = RETROARCH_CORES.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(RETROARCH_CORES.all { it.name.isNotBlank() && LibraryPresets.coreProfile("ra-${it.id}") != null })
        assertEquals(RETROARCH_CORES.map { it.name.lowercase() }, RETROARCH_CORES.map { it.name.lowercase() }.sorted())
        val catalogCores = presets.flatMap { it.profiles }.filter { it.id.startsWith("ra-") }.map { it.id.removePrefix("ra-") }
        assertEquals(emptyList<String>(), catalogCores.filter { it !in ids })
    }

    /** A synthesized core profile is exactly what the catalog builds for that core, apart from its label, in every build. */
    @Test
    fun coreIdsSynthesizeTheCatalogProfilePerBuild() {
        val catalog = presets.flatMap { it.profiles }.associateBy { it.id }
        for (id in listOf("ra-gambatte", "ra64-gambatte", "ra32-gambatte")) {
            assertEquals(id, catalog.getValue(id).copy(label = ""), LibraryPresets.coreProfile(id)?.copy(label = ""))
        }
        assertEquals("RetroArch (AArch64) · Gambatte", LibraryPresets.coreProfile("ra64-gambatte")?.label)
        val ra32 = LibraryPresets.coreProfile("ra32-pcsx_rearmed")?.extras?.get("LIBRETRO")
        assertEquals("/data/user/0/com.retroarch.ra32/cores/pcsx_rearmed_libretro_android.so", ra32)
    }

    /** A core the list lacks still launches (labelled by its id); ids of other shapes, or leaving the cores folder, do not resolve. */
    @Test
    fun unknownCoresResolveAndOtherIdsDoNot() {
        val custom = LibraryPresets.coreProfile("ra-my_core")
        assertEquals("RetroArch · my_core", custom?.label)
        assertEquals("/data/user/0/com.retroarch/cores/my_core_libretro_android.so", custom?.extras?.get("LIBRETRO"))
        val others = listOf("ra-", "ra-../x", "ra-a/b", "rax-gambatte", "ra16-gambatte", "gambatte", "open-with", "dolphin")
        others.forEach { assertNull(it, LibraryPresets.coreProfile(it)) }
    }
}
