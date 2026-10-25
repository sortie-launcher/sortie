package io.github.esko997.sortie.launch

import io.github.esko997.sortie.data.manifest.LaunchProfile
import io.github.esko997.sortie.data.manifest.SystemDef
import io.github.esko997.sortie.data.manifest.UserData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** When an app picked in Open with… may become the system's default, and what remembering it writes (report item 2). */
class ChooserMemoryTest {
    private val game = "psx/crash.chd"
    private val duckstation = LaunchProfile("duckstation", `package` = "com.github.stenzek.duckstation")
    private val openWith = LaunchProfile("open-with", label = "Open with…", generic = true, display = "bottom")
    private val profiles = listOf(duckstation, openWith)
    private val fallback = SystemDef("psx", roots = listOf("roms/psx"), profiles = listOf("duckstation", "open-with"))
    private val pkg = "Org.Example.Player"
    private val activity = "org.example.player.PlayActivity"

    private fun fallbackOrigin(system: SystemDef = fallback, user: UserData = UserData(), explicit: Boolean = false, all: List<LaunchProfile> = profiles) =
        chooserOrigin(game, system.id, user, system.profiles, all.associateBy { it.id }, openWith, explicit)

    private fun remember(origin: List<String>, user: UserData = UserData(), system: SystemDef = fallback, all: List<LaunchProfile> = profiles) =
        rememberedChoice(game, openWith.id, origin, user, listOf(system), all, pkg, activity, "Example Player")

    private fun order(system: SystemDef, user: UserData, all: List<LaunchProfile>) =
        profileOrder(game, system.id, user, system.profiles, all.associateBy { it.id }).map { it.id }

    @Test
    fun aFallbackPickBecomesTheSettingsDefaultWithTheChoosersMimeTypeAndManifestDefaultUndoesIt() {
        val origin = fallbackOrigin()!!
        assertEquals(listOf("duckstation", "open-with"), origin)
        val choice = remember(origin)!!
        val saved = choice.profile!!
        assertEquals("psx", choice.systemId)
        assertEquals("chosen-org.example.player", choice.profileId)
        assertEquals(choice.profileId, saved.id)
        assertEquals(pkg, saved.`package`)
        assertEquals(activity, saved.activity)
        assertEquals("android.intent.action.VIEW", saved.action)
        assertEquals("\${uri}", saved.data)
        assertEquals("*/*", saved.mimeType)
        assertEquals(listOf("NEW_TASK", "GRANT_READ_URI_PERMISSION"), saved.flags)
        assertEquals("bottom", saved.display)

        // the user data carries the choice; systems.json keeps its order, so clearing the Settings default restores it
        val all = profiles + saved
        val remembered = UserData(systemProfiles = mapOf(choice.systemId to choice.profileId))
        assertEquals(listOf("chosen-org.example.player", "duckstation", "open-with"), order(fallback, remembered, all))
        assertEquals(listOf("duckstation", "open-with"), order(fallback, remembered.copy(systemProfiles = emptyMap()), all))
        // once remembered, the system no longer falls back to the chooser for a second pick
        assertNull(fallbackOrigin(user = remembered, all = all))
    }

    @Test
    fun aPerGameOverrideNeverChangesTheSystem() {
        assertNull(fallbackOrigin(user = UserData(profileOverrides = mapOf(game to "open-with"))))
        // an override to an emulator that failed, falling back to the chooser, is still the game's own choice
        assertNull(fallbackOrigin(user = UserData(profileOverrides = mapOf(game to "duckstation"))))
        // another game's override does not block this one
        assertEquals(listOf("duckstation", "open-with"), fallbackOrigin(user = UserData(profileOverrides = mapOf("psx/other.chd" to "open-with"))))
    }

    @Test
    fun anExplicitOneOffLaunchNeverChangesTheSystem() = assertNull(fallbackOrigin(explicit = true))

    @Test
    fun aSystemThatDeliberatelyOpensWithTheChooserKeepsAsking() {
        assertNull(fallbackOrigin(system = SystemDef("ps3", roots = listOf("roms/ps3"), profiles = listOf("open-with"))))
        assertNull(fallbackOrigin(system = SystemDef("psx", roots = listOf("roms/psx"), profiles = listOf("open-with", "duckstation"))))
        assertNull(fallbackOrigin(user = UserData(systemProfiles = mapOf("psx" to "open-with"))))
        // a Settings default the user already chose wins, even when its emulator failed and the chooser was reached
        assertNull(fallbackOrigin(user = UserData(systemProfiles = mapOf("psx" to "duckstation"))))
        // an unknown first entry is skipped: the chooser is then still the first thing tried
        assertNull(fallbackOrigin(system = SystemDef("psx", roots = listOf("roms/psx"), profiles = listOf("gone", "open-with"))))
    }

    @Test
    fun aConfigurationChangedWhileTheChooserWasOpenIsNotOverwritten() {
        val origin = fallbackOrigin()!!
        val other = LaunchProfile("other", `package` = "org.example.other")
        assertNull(remember(origin, system = fallback.copy(profiles = listOf("open-with"))))
        assertNull(remember(origin, system = fallback.copy(profiles = listOf("other", "duckstation", "open-with")), all = profiles + other))
        assertNull(remember(origin, user = UserData(systemProfiles = mapOf("psx" to "duckstation"))))
        assertNull(remember(origin, user = UserData(profileOverrides = mapOf(game to "open-with"))))
        assertNull(remember(origin, all = listOf(duckstation)))
        assertNull(remember(origin, all = listOf(duckstation, openWith.copy(generic = false, `package` = "org.example.other"))))
        assertNull(rememberedChoice(game, openWith.id, origin, UserData(), emptyList(), profiles, pkg, activity, "Example Player"))
        // unrelated changes (another system's default, another game's override) do not invalidate the pick
        assertNotNull(remember(origin, user = UserData(systemProfiles = mapOf("snes" to "other"), profileOverrides = mapOf("psx/other.chd" to "duckstation"))))
    }

    @Test
    fun anExistingChosenProfileIsReusedRepairedOnlyForTheMissingMimeTypeAndNeverOverwritten() {
        val origin = fallbackOrigin()!!
        val fresh = remember(origin)!!.profile!!

        val current = fresh.copy(label = "My player", display = "top")
        val reused = remember(origin, all = profiles + current)!!
        assertEquals(current.id, reused.profileId)
        assertNull(reused.profile)

        // saved before the MIME type was kept: only the type is added, the user's name and screen stay
        val legacy = current.copy(mimeType = null)
        assertEquals(current, remember(origin, all = profiles + legacy)!!.profile)

        // the same id naming another activity (the user's own profile) is never replaced, and nothing is remembered
        assertNull(remember(origin, all = profiles + fresh.copy(activity = "org.example.player.Other")))
        // a hand-edited profile for the same activity is reused as it is
        val edited = fresh.copy(mimeType = null, extras = mapOf("boot" to "1"))
        assertNull(remember(origin, all = profiles + edited)!!.profile)
    }
}
