package io.github.esko997.sortie.launch

import android.content.Intent
import io.github.esko997.sortie.data.manifest.LaunchProfile
import io.github.esko997.sortie.data.manifest.SystemDef
import io.github.esko997.sortie.data.manifest.UserData
import java.util.Locale

/**
 * The launch order (profile ids) through which [started], a generic Open with… profile, was reached as a fallback for
 * [gameId], or null when an app picked in its chooser must not become [systemId]'s default:
 * - [explicit]: a one-off pick of the profile (game page "Launch with…"),
 * - the game has a per-game override or the system a Settings default (the user already chose),
 * - [started] is the first profile tried: the system deliberately opens with Android's chooser.
 *
 * The order travels with the chooser's private callback; [rememberedChoice] saves the pick only if the configuration still
 * yields the same order, so a change made while the chooser was open is never overwritten by a stale pick.
 */
internal fun chooserOrigin(
    gameId: String,
    systemId: String,
    user: UserData,
    systemProfiles: List<String>,
    profiles: Map<String, LaunchProfile>,
    started: LaunchProfile,
    explicit: Boolean
): List<String>? {
    if (explicit || !started.generic) return null
    if (gameId in user.profileOverrides || systemId in user.systemProfiles) return null
    val order = profileOrder(gameId, systemId, user, systemProfiles, profiles)
    val index = order.indexOfFirst { it.id == started.id }
    // [started] must be the first generic profile in the order: an earlier one would have opened the chooser instead
    if (index <= 0 || order.indexOfFirst { it.generic } != index) return null
    return order.map { it.id }
}

/** Remembering an Open with… pick: [profileId] becomes [systemId]'s Settings default; [profile] is added to launchers.json first (null: already there). */
internal data class RememberedChoice(val systemId: String, val profileId: String, val profile: LaunchProfile?)

/**
 * What remembering the app [pkg]/[activity] picked in [genericId]'s chooser for [gameId] writes, given the current [user]
 * data and library configuration ([systems], [profiles]); null when nothing may be written because the configuration no
 * longer reaches the chooser through [origin] (see [chooserOrigin]) or `chosen-<pkg>` already names a different app.
 *
 * The pick is saved as a launchers.json profile `chosen-<pkg>` (explicit component, VIEW of the read-granted content URI
 * with the chooser's wildcard MIME type, like the chooser sent it) and selected in user.json's systemProfiles only:
 * systems.json is never reordered, so Settings → Default launcher per system → Manifest default undoes it. [label] names a new profile.
 */
internal fun rememberedChoice(
    gameId: String,
    genericId: String,
    origin: List<String>,
    user: UserData,
    systems: List<SystemDef>,
    profiles: List<LaunchProfile>,
    pkg: String,
    activity: String,
    label: String
): RememberedChoice? {
    // game ids are "<systemId>/<file name>" (Indexer)
    val systemId = gameId.substringBefore('/')
    val system = systems.firstOrNull { it.id == systemId } ?: return null
    val byId = profiles.associateBy { it.id }
    val generic = byId[genericId] ?: return null
    if (chooserOrigin(gameId, systemId, user, system.profiles, byId, generic, explicit = false) != origin) return null
    val id = "chosen-" + pkg.lowercase(Locale.ROOT).replace(NOT_ID_CHAR, "-")
    val wanted = LaunchProfile(
        id = id,
        label = label,
        `package` = pkg,
        activity = activity,
        action = Intent.ACTION_VIEW,
        data = "\${uri}",
        mimeType = CHOOSER_MIME,
        flags = listOf("NEW_TASK", "GRANT_READ_URI_PERMISSION"),
        display = generic.display
    )
    val existing = byId[id] ?: return RememberedChoice(systemId, id, wanted)
    return when {
        // the user's own profile under that id: reused unchanged when it opens the picked app, never overwritten
        existing.`package` != pkg || existing.activity != activity -> null
        // saved before the MIME type was kept: the app may not accept the URI without it, so only that is added
        existing == wanted.copy(label = existing.label, display = existing.display, mimeType = null) ->
            RememberedChoice(systemId, id, existing.copy(mimeType = CHOOSER_MIME))
        else -> RememberedChoice(systemId, id, null)
    }
}

/** The type Open with… sends with the game's URI; a remembered app gets the same. */
private const val CHOOSER_MIME = "*/*"

/** Characters outside the profile id charset of Library setup (lower-case letters, digits, '.', '_', '-'). */
private val NOT_ID_CHAR = Regex("[^a-z0-9._-]")
