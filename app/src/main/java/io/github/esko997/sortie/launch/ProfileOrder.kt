package io.github.esko997.sortie.launch

import io.github.esko997.sortie.data.manifest.LaunchProfile
import io.github.esko997.sortie.data.manifest.UserData

/**
 * The launch profiles to try for a game, in order: the per-game "Launch with…" override, the per-system default chosen in
 * Settings, then the system's manifest list. Unknown ids are skipped; the Android chooser is tried only when explicitly selected.
 */
fun profileOrder(gameId: String, systemId: String, user: UserData, systemProfiles: List<String>, profiles: Map<String, LaunchProfile>): List<LaunchProfile> {
    val ids = buildList {
        user.profileOverrides[gameId]?.let { add(it) }
        user.systemProfiles[systemId]?.let { if (it !in this) add(it) }
        systemProfiles.forEach { if (it !in this) add(it) }
    }
    return ids.mapNotNull { profiles[it] }
}
