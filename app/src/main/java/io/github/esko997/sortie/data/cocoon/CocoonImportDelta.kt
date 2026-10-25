package io.github.esko997.sortie.data.cocoon

import io.github.esko997.sortie.data.backup.BackupSession
import io.github.esko997.sortie.data.backup.planSessionMerge
import io.github.esko997.sortie.data.db.SessionEntity
import io.github.esko997.sortie.data.manifest.FolderDef
import io.github.esko997.sortie.data.manifest.UserData
import io.github.esko997.sortie.data.setup.LibraryConfiguration

/**
 * The import's user.json merge: favourites and hidden games are unions, a per-game emulator is set only for a game the user
 * hasn't chosen one for. Running it again with the same input returns [u] unchanged.
 */
internal fun mergeImportedUser(u: UserData, favourites: Collection<String>, hidden: Collection<String>, overrides: Map<String, String>): UserData = u.copy(
    favourites = u.favourites + favourites,
    hidden = u.hidden + hidden,
    profileOverrides = u.profileOverrides + overrides.filterKeys { it !in u.profileOverrides }
)

/** The import's folders.json merge: an existing folder of the same id keeps its name and rules and gains [members]. */
internal fun mergeImportedFolder(existing: FolderDef?, planned: FolderDef, members: List<String>): FolderDef =
    existing?.let { it.copy(include = (it.include + members).distinct()) } ?: planned.copy(include = members)

/** Cocoon sessions as play-history records for [planSessionMerge]; a profile Sortie doesn't know is dropped, not the session. */
internal fun importedSessions(sessions: List<SessionPlan>, profileKnown: (String) -> Boolean): List<BackupSession> =
    sessions.map { BackupSession(it.gameId, it.profileId?.takeIf(profileKnown), it.startedAt, it.endedAt) }

/** What one category of the import would add ([add]) and what Sortie already has ([present]). */
data class DeltaCount(val add: Int, val present: Int)

/**
 * Folders the import would [create], existing folders of the same id that gain members ([update]) and those already holding
 * every member ([unchanged]); [members] counts the folder entries added in total.
 */
data class FolderDelta(val create: Int, val update: Int, val unchanged: Int, val members: Int)

/** Sessions the play-history merge would insert ([add], [addedMinutes]) and those already recorded ([present]). */
data class SessionDelta(val add: Int, val present: Int, val addedMinutes: Long)

/**
 * The difference a plan makes to the current user data, folders and play history, computed with the import's own merges, so a
 * re-run previews "already here" instead of repeating the plan. Games of systems the import adds aren't indexed until it runs,
 * so these are upper bounds the result then reports exactly.
 */
data class ImportDelta(
    val favourites: DeltaCount,
    val hidden: DeltaCount,
    val overrides: DeltaCount,
    val sessions: SessionDelta,
    val folders: FolderDelta
) {
    companion object {
        fun of(plan: CocoonImportPlan, user: UserData, folders: List<FolderDef>, sessions: List<SessionEntity>): ImportDelta {
            val merged = mergeImportedUser(user, plan.favourites, plan.hidden, plan.profileOverrides)
            val existing = folders.associateBy { it.id }
            var create = 0
            var update = 0
            var unchanged = 0
            var members = 0
            for (fp in plan.folders) {
                val before = existing[fp.def.id]
                val after = mergeImportedFolder(before, fp.def, fp.def.include)
                val had = before?.include.orEmpty().toHashSet()
                val added = after.include.toHashSet().count { it !in had }
                when {
                    before == null -> create++
                    added > 0 -> update++
                    else -> unchanged++
                }
                members += added
            }
            val merge = planSessionMerge(importedSessions(plan.sessions) { true }, sessions)
            return ImportDelta(
                favourites = added(user.favourites, merged.favourites, plan.favourites),
                hidden = added(user.hidden, merged.hidden, plan.hidden),
                overrides = added(user.profileOverrides.keys, merged.profileOverrides.keys, plan.profileOverrides.keys),
                sessions = SessionDelta(merge.toInsert.size, merge.alreadyPresent, merge.addedMinutes),
                folders = FolderDelta(create, update, unchanged, members)
            )
        }

        private fun added(before: Set<String>, after: Set<String>, planned: Collection<String>): DeltaCount {
            val add = after.size - before.size
            return DeltaCount(add, planned.toSet().size - add)
        }
    }
}

/** Whether a previewed plan may still be applied to the library configuration as it is now. */
sealed interface ConfirmCheck {
    object Ready : ConfirmCheck

    /** The configuration can't be written ([LibraryConfiguration.blocking]); fixing it comes before any import. */
    data class Blocked(val reasons: List<String>) : ConfirmCheck

    /** The configuration changed since the preview: the plan may duplicate or contradict it and must be made again. */
    object Changed : ConfirmCheck

    companion object {
        fun of(previewFingerprint: String, current: LibraryConfiguration): ConfirmCheck = when {
            current.blocking.isNotEmpty() -> Blocked(current.blocking)
            current.fingerprint != previewFingerprint -> Changed
            else -> Ready
        }
    }
}
