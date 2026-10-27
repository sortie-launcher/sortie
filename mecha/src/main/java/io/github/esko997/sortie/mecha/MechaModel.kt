package io.github.esko997.sortie.mecha

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@Serializable
enum class MechaSlot { HEAD, BODY, ARMS, LEGS }

internal enum class MechaCare { TUNE, CHARGE, REST }

@Serializable
enum class MechaFinish(val label: String) {
    PLAIN("Standard"),
    ALLOY("Alloy"),
    HOLO("Holo")
}

internal const val MECHA_ARCADE_PRICE = 1

@Serializable
enum class MechaArcadeGame(val label: String, val price: Int = MECHA_ARCADE_PRICE) {
    FLAPPY_FLIGHT("Flappy Flight"),
    SCRAP_CATCH("Scrap Catch"),
    CIRCUIT_MATCH("Circuit Match")
}

@Serializable
data class MechaArcadeTicket(val sequence: Int, val game: MechaArcadeGame?, val seed: Long)

internal const val MECHA_PART_PRICE = 2

/** Future play earnings stop here; existing larger balances are preserved. */
internal const val MECHA_CREDIT_CEILING = 50

/** A pet counts toward bond at most once in this long, so tapping him over and over earns nothing more. */
internal const val MECHA_PET_BOND_MS = 5 * 60_000L

/** Authored families in catalog order, each with two variants per slot. */
internal val MECHA_FAMILIES: List<String> = listOf(
    "yard", "survey", "courier", "foundry", "orbital", "tidal", "domestic",
    "rescue", "medical", "ranger", "harvest", "apiary", "orchard", "abyssal", "salvage", "nautical",
    "observatory", "radio", "satellite", "ceramic", "textile", "clockwork", "quarry", "rail", "demolition"
)

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

    /** Authored material roles, aligned pixel-for-pixel with the base sheet. */
    val paintAsset: String get() = asset.removeSuffix(".png") + ".paint.png"
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
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault
    val version: Int = 4,
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
    val ownedPaints: Set<MechaPaint> = setOf(MechaPaint()),
    val equippedPaints: Map<MechaSlot, MechaPaint> = emptyMap(),
    val cabinetSpins: Int = 0,
    val lastSpin: MechaSpinReceipt? = null,
    val legacyLastSpin: MechaLegacySpinReceipt? = null,
    val ownedThemes: Set<String> = setOf("default"),
    val selectedTheme: String = "default",
    val ownedFolderIcons: Set<String> = setOf("default"),
    val selectedFolderIcons: String = "default",
    val ownedButtonStyles: Set<String> = setOf("default"),
    val selectedButtonStyle: String = "default",
    val hangarIntroSeen: Boolean = false,
    val arcadeRuns: Int = 0,
    val activeArcade: MechaArcadeTicket? = null,
    val arcadeBest: Map<MechaArcadeGame, Int> = emptyMap()
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
