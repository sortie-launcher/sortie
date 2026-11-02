package io.github.esko997.sortie.launch

import io.github.esko997.sortie.data.manifest.LaunchProfile
import io.github.esko997.sortie.data.manifest.UserData
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileOrderTest {
    private val profiles = listOf(
        LaunchProfile("ra-fbneo"),
        LaunchProfile("ra-mame"),
        LaunchProfile("open-with", generic = true),
        LaunchProfile("unrelated")
    ).associateBy { it.id }
    private val manifest = listOf("ra-fbneo", "ra-mame")

    private fun ids(user: UserData) = profileOrder("cps3/jojo.zip", "cps3", user, manifest, profiles).map { it.id }

    @Test
    fun anUnconfiguredChooserCannotHideMissingEmulators() = assertEquals(listOf("ra-fbneo", "ra-mame"), ids(UserData()))

    @Test
    fun systemDefaultFromSettingsComesFirst() = assertEquals(listOf("ra-mame", "ra-fbneo"), ids(UserData(systemProfiles = mapOf("cps3" to "ra-mame"))))

    @Test
    fun perGameOverrideBeatsSystemDefault() = assertEquals(
        listOf("ra-fbneo", "ra-mame"),
        ids(UserData(profileOverrides = mapOf("cps3/jojo.zip" to "ra-fbneo"), systemProfiles = mapOf("cps3" to "ra-mame")))
    )

    @Test
    fun resettingSystemDefaultRestoresManifestOrder() {
        val set = UserData(systemProfiles = mapOf("cps3" to "open-with"))
        assertEquals("open-with", ids(set).first())
        assertEquals("ra-fbneo", ids(set.copy(systemProfiles = emptyMap())).first())
    }

    @Test
    fun unknownAndOtherSystemsChoicesAreIgnored() = assertEquals(
        listOf("ra-fbneo", "ra-mame"),
        ids(UserData(systemProfiles = mapOf("cps3" to "gone", "snes" to "ra-mame")))
    )

    @Test
    fun anExplicitChooserRetainsItsConfiguredPosition() {
        val order = profileOrder("cps3/jojo.zip", "cps3", UserData(), listOf("ra-fbneo", "open-with", "ra-mame"), profiles)
        assertEquals(listOf("ra-fbneo", "open-with", "ra-mame"), order.map { it.id })
        assertEquals(emptyList<LaunchProfile>(), profileOrder("unknown/game", "unknown", UserData(), emptyList(), profiles))
    }

    /** user.json may name any RetroArch core: the id resolves without a launchers.json entry, ahead of the system's list. */
    @Test
    fun storedCoreIdsResolveWithoutAManifestEntry() {
        val user = UserData(profileOverrides = mapOf("cps3/jojo.zip" to "ra64-mame2003_plus"), systemProfiles = mapOf("cps3" to "ra-fbalpha2012_cps3"))
        val order = profileOrder("cps3/jojo.zip", "cps3", user, manifest, profiles)
        assertEquals(listOf("ra64-mame2003_plus", "ra-fbalpha2012_cps3", "ra-fbneo", "ra-mame"), order.map { it.id })
        assertEquals("com.retroarch.aarch64", order[0].`package`)
        assertEquals("RetroArch · FB Alpha 2012 CPS-3", order[1].label)
    }

    /** A manifest profile of the same id wins over the synthesized one. */
    @Test
    fun aManifestProfileOfTheSameIdWins() {
        assertEquals(LaunchProfile("ra-mame"), profiles.resolve("ra-mame"))
        assertEquals("com.retroarch", emptyMap<String, LaunchProfile>().resolve("ra-mame")?.`package`)
        assertEquals(null, profiles.resolve("gone"))
    }
}
