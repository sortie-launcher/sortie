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
    fun manifestOrderThenGeneric() = assertEquals(listOf("ra-fbneo", "ra-mame", "open-with"), ids(UserData()))

    @Test
    fun systemDefaultFromSettingsComesFirst() =
        assertEquals(listOf("ra-mame", "ra-fbneo", "open-with"), ids(UserData(systemProfiles = mapOf("cps3" to "ra-mame"))))

    @Test
    fun perGameOverrideBeatsSystemDefault() = assertEquals(
        listOf("ra-fbneo", "ra-mame", "open-with"),
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
        listOf("ra-fbneo", "ra-mame", "open-with"),
        ids(UserData(systemProfiles = mapOf("cps3" to "gone", "snes" to "ra-mame")))
    )
}
