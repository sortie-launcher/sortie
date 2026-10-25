package io.github.esko997.sortie.mecha

import kotlinx.serialization.Serializable

enum class MechaSlot { HEAD, BODY, ARMS, LEGS }

internal enum class MechaCare { TUNE, CHARGE, REST }

/** A pet counts toward bond at most once in this long, so tapping him over and over earns nothing more. */
internal const val MECHA_PET_BOND_MS = 5 * 60_000L

/** The three part families, in catalog order; each ships `assets/mecha/<family>/catalog.json` with eight parts. */
internal val MECHA_FAMILIES: List<String> = listOf("yard", "survey", "courier")

/** Minutes of completed game play that earn one salvage credit; the remainder carries over. */
internal const val MECHA_MINUTES_PER_CREDIT = 5

/** At most this many minutes of one session count, so a game left running unattended is not a windfall. */
internal const val MECHA_SESSION_MINUTES_CAP = 120

/** Salvage credits in a new hangar. */
internal const val MECHA_STARTER_CREDITS = 3

/** Upper bound of bond, charge and condition. */
internal const val MECHA_STAT_MAX = 100

/** Charge and condition never drop below this: wear from play and salvage stops here, and nothing decays while away. */
internal const val MECHA_STAT_FLOOR = 30

/** Longest companion name, in characters, after trimming. */
internal const val MECHA_NAME_MAX = 16

internal const val MECHA_DEFAULT_NAME = "Mica"

/** One sprite sheet (512x64: eight 64x64 frames) for one slot of one family; [asset] is its path inside the APK's assets. */
@Serializable
data class MechaPart(val id: String, val slot: MechaSlot, val family: String, val name: String, val asset: String) {
    /** 1 for the starter variant, 2 for the one found by salvage. */
    val variant: Int get() = id.substringAfterLast('_').toIntOrNull() ?: 0
}

/**
 * The whole persistent companion, saved as one file (`files/mecha/state.json`) so the parts, credits and the reward bookkeeping
 * ([processedSessions], [pendingMinutes], [creditedMinutes]) always change together.
 *
 * [equipped] holds one owned part per slot. [origins] says how each owned part arrived ("Starter kit", "Salvage run #2").
 * Only game sessions that started at or after [initializedAt] earn credit, each once, identified in [processedSessions] by
 * `<gameId>@<startedAt>` (stable across backup restores and play-history imports, which keep both). [creditedMinutes] is every
 * counted minute so far, [pendingMinutes] the part of them not yet worth a credit (0 until [MECHA_MINUTES_PER_CREDIT]).
 */
@Serializable
data class MechaState(
    val version: Int = 1,
    val name: String = MECHA_DEFAULT_NAME,
    val enabled: Boolean = true,
    val motion: Boolean = true,
    val equipped: Map<MechaSlot, String>,
    val owned: Set<String>,
    val salvage: Int,
    val bond: Int,
    val charge: Int,
    val condition: Int,
    val sleeping: Boolean,
    val origins: Map<String, String>,
    val initializedAt: Long,
    val creditedMinutes: Int,
    val pendingMinutes: Int,
    val salvageRuns: Int,
    val processedSessions: Set<String>
) {
    /** Minutes of play still needed for the next salvage credit. */
    val minutesToNextCredit: Int get() = MECHA_MINUTES_PER_CREDIT - pendingMinutes
}

/**
 * One play session as the host app recorded it, the only input that earns salvage credit. [gameId] with [startedAt] identifies
 * it (`<gameId>@<startedAt>` in [MechaState.processedSessions]); [endedAt] is null while it runs. Only [isGame] sessions count,
 * never apps or shortcuts.
 */
data class PlaySession(val gameId: String, val startedAt: Long, val endedAt: Long?, val abandoned: Boolean, val isGame: Boolean)

/**
 * What the UI shows. Loading: [state] and [error] both null. [error] with a null [state]: the hangar could not be opened (the
 * parts catalog is incomplete, or state.json is unreadable and was left untouched; see [MechaStore.startFresh]). [error] with a
 * state: the last action or save failed and nothing changed. [message]: the outcome of the last action or of newly credited play.
 */
data class MechaView(
    val state: MechaState? = null,
    val parts: List<MechaPart> = emptyList(),
    val error: String? = null,
    val message: String? = null
)
