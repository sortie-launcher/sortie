package io.github.esko997.sortie.mecha

import kotlinx.serialization.Serializable

enum class MechaSlot { HEAD, BODY, ARMS, LEGS }

internal enum class MechaCare { TUNE, CHARGE, REST }

@Serializable
enum class MechaFinish(val price: Int, val label: String) {
    PLAIN(0, "Plain"),
    ALLOY(6, "Alloy"),
    HOLO(10, "Holo")
}

@Serializable
enum class MechaCabinetTier(val stake: Int, val label: String, val finish: MechaFinish?) {
    PART(1, "Part", null),
    ALLOY(3, "Alloy", MechaFinish.ALLOY),
    HOLO(5, "Holo", MechaFinish.HOLO)
}

@Serializable
enum class MechaSpinOutcome { REWARD, REFUND, MISS }

@Serializable
data class MechaSpinReceipt(
    val sequence: Int,
    val tier: MechaCabinetTier,
    val reels: List<Int>,
    val outcome: MechaSpinOutcome,
    val returnedCredits: Int,
    val partId: String? = null,
    val finish: MechaFinish? = null
)

internal data class MechaBuild(val equipped: Map<MechaSlot, String>)

internal const val MECHA_PART_PRICE = 2

/** Future play earnings stop here; existing larger balances are preserved. */
internal const val MECHA_CREDIT_CEILING = 50

/** A pet counts toward bond at most once in this long, so tapping him over and over earns nothing more. */
internal const val MECHA_PET_BOND_MS = 5 * 60_000L

/** Authored families in catalog order, each with two variants per slot. */
internal val MECHA_FAMILIES: List<String> = listOf("yard", "survey", "courier", "foundry", "orbital", "tidal", "domestic")

/** Minutes of completed game play that earn one salvage credit; the remainder carries over. */
internal const val MECHA_MINUTES_PER_CREDIT = 5

/** At most this many minutes of one session count, so a game left running unattended is not a windfall. */
internal const val MECHA_SESSION_MINUTES_CAP = 120

/** Salvage credits in a new hangar. */
internal const val MECHA_STARTER_CREDITS = 3

/** Upper bound of bond, charge and condition. */
internal const val MECHA_STAT_MAX = 100

/** Charge and condition never drop below this, and nothing decays while away. */
internal const val MECHA_STAT_FLOOR = 30

/** Longest companion name, in characters, after trimming. */
internal const val MECHA_NAME_MAX = 16

internal const val MECHA_DEFAULT_NAME = "Mica"

/** One sprite sheet (512x64: eight 64x64 frames) for one slot of one family; [asset] is its path inside the APK's assets. */
@Serializable
data class MechaPart(val id: String, val slot: MechaSlot, val family: String, val name: String, val asset: String) {
    /** Each family has two authored variants per slot; only the original families include starter parts. */
    val variant: Int get() = id.substringAfterLast('_').toIntOrNull() ?: 0
}

/**
 * The whole persistent companion, saved as one file (`files/mecha/state.json`) so the parts, credits and the reward bookkeeping
 * ([processedSessions], [pendingMinutes], [creditedMinutes]) always change together.
 *
 * [equipped] holds one owned part per slot. [origins] records starter, historical salvage and purchase/cabinet provenance.
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
    val processedSessions: Set<String>,
    val ownedFinishes: Set<MechaFinish> = setOf(MechaFinish.PLAIN),
    val equippedFinishes: Map<MechaSlot, MechaFinish> = emptyMap(),
    val cabinetSpins: Int = 0,
    val lastSpin: MechaSpinReceipt? = null
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
    val message: String? = null,
    val busy: Boolean = false,
    val canStartFresh: Boolean = false
)
