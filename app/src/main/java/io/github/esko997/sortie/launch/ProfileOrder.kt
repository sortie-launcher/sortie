package io.github.esko997.sortie.launch

import io.github.esko997.sortie.data.manifest.LaunchProfile
import io.github.esko997.sortie.data.manifest.UserData
import io.github.esko997.sortie.data.setup.LibraryPresets

/**
 * The profile [id] names: the manifest's (launchers.json) if it defines one, else the RetroArch core profile a `ra-`,
 * `ra64-` or `ra32-<core>` id stands for ([LibraryPresets.coreProfile]), else null. Every lookup of a stored id goes
 * through here, so user.json may hold such ids without a launchers.json entry.
 */
fun Map<String, LaunchProfile>.resolve(id: String): LaunchProfile? = this[id] ?: LibraryPresets.coreProfile(id)

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
    return ids.mapNotNull { profiles.resolve(it) }
}
