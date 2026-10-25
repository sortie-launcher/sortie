package io.github.esko997.sortie.mecha

import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A rule's outcome: the next state (the same instance when nothing changed) and what to tell the player. */
internal data class MechaResult(val state: MechaState, val message: String? = null, val error: String? = null)

internal const val MECHA_STARTER_ORIGIN = "Starter kit"

/** The family whose variant-1 parts a new hangar wears. */
internal const val MECHA_STARTER_FAMILY = "yard"

private const val PART_NAME_MAX = 32
private const val STARTER_CHARGE = 80
private const val STARTER_CONDITION = 90
private const val TUNE_STEP = 20
private const val CHARGE_STEP = 25
private const val SALVAGE_WEAR = 5

/** Charge spent per this many credited minutes of play (never below [MECHA_STAT_FLOOR]). */
private const val MINUTES_PER_CHARGE_POINT = 6

private val catalogJson = Json

/**
 * Parses and checks one family's catalog.json: exactly `<family>_<head|body|arms|legs>_<1|2>`, each in its own slot, family and
 * `mecha/<family>/<id>.png` asset (whose sheet [assetProblem] checks: null = fine), with a short name. Throws [IllegalArgumentException]
 * naming every problem. Returns the parts in slot then variant order.
 */
internal fun parseMechaCatalog(family: String, text: String, assetProblem: (String) -> String?): List<MechaPart> {
    val rows = try {
        catalogJson.decodeFromString(ListSerializer(MechaPart.serializer()), text)
    } catch (e: Exception) {
        throw IllegalArgumentException("mecha/$family/catalog.json is not a valid parts list (${e.message?.lineSequence()?.firstOrNull()})")
    }
    val idPattern = Regex("${Regex.escape(family)}_(head|body|arms|legs)_([12])")
    val problems = mutableListOf<String>()
    for (p in rows) {
        val m = idPattern.matchEntire(p.id)
        when {
            p.family != family -> problems += "${p.id} says family \"${p.family}\""
            m == null -> problems += "${p.id} is not named ${family}_<slot>_<1|2>"
            m.groupValues[1] != p.slot.name.lowercase() -> problems += "${p.id} is filed under ${p.slot}"
            p.asset != "mecha/$family/${p.id}.png" -> problems += "${p.id} points at ${p.asset}"
            p.name.isBlank() || p.name != p.name.trim() || p.name.length > PART_NAME_MAX -> problems += "${p.id} needs a short name"
            else -> assetProblem(p.asset)?.let { problems += it }
        }
    }
    val ids = rows.map { it.id }
    ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { problems += "$it is listed twice" }
    val expected = MechaSlot.entries.flatMap { s -> listOf(1, 2).map { "${family}_${s.name.lowercase()}_$it" } }
    expected.filter { it !in ids }.forEach { problems += "$it is not listed" }
    require(problems.isEmpty()) { "mecha/$family/catalog.json: ${problems.joinToString("; ")}" }
    return rows.sortedWith(compareBy({ it.slot.ordinal }, { it.variant }))
}

/** A new hangar: every family's variant-1 parts (twelve), the starter family fitted, [MECHA_STARTER_CREDITS] credits. */
internal fun starterMechaState(parts: List<MechaPart>, now: Long): MechaState {
    val starters = parts.filter { it.variant == 1 }
    val equipped = MechaSlot.entries.associateWith { slot ->
        starters.first { it.family == MECHA_STARTER_FAMILY && it.slot == slot }.id
    }
    return MechaState(
        equipped = equipped,
        owned = starters.mapTo(LinkedHashSet()) { it.id },
        salvage = MECHA_STARTER_CREDITS,
        bond = 0,
        charge = STARTER_CHARGE,
        condition = STARTER_CONDITION,
        sleeping = false,
        origins = starters.associate { it.id to MECHA_STARTER_ORIGIN },
        initializedAt = now,
        creditedMinutes = 0,
        pendingMinutes = 0,
        salvageRuns = 0,
        processedSessions = emptySet()
    )
}

/** Why [name] (already trimmed) can't be the companion's name, or null when it can. */
internal fun mechaNameProblem(name: String): String? = when {
    name.isBlank() -> "Give your mech a name."
    name != name.trim() -> "Names can't start or end with spaces."
    name.length > MECHA_NAME_MAX -> "Names are at most $MECHA_NAME_MAX characters."
    name.any { it.isISOControl() } -> "Names can't contain control characters."
    else -> null
}

/** Why a loaded [MechaState] can't be used with [parts], or null when it is consistent. */
internal fun mechaStateProblem(state: MechaState, parts: List<MechaPart>): String? {
    val byId = parts.associateBy { it.id }
    val unknown = state.owned.filter { it !in byId }
    val stats = MECHA_STAT_FLOOR..MECHA_STAT_MAX
    return when {
        state.version !in 1..MECHA_SAVE_VERSION -> "it is format version ${state.version}, this build reads versions 1 to $MECHA_SAVE_VERSION"
        mechaNameProblem(state.name) != null -> "its name \"${state.name}\" is invalid"
        unknown.isNotEmpty() -> "it owns parts this build doesn't have (${unknown.joinToString()})"
        MechaSlot.entries.any { slot -> state.equipped[slot].let { id -> id == null || id !in state.owned || byId[id]?.slot != slot } } ->
            "its fitted parts aren't owned parts of the right slots"
        state.origins.keys.any { it !in state.owned } -> "it records where unowned parts came from"
        state.salvage < 0 || state.salvageRuns < 0 || state.creditedMinutes < 0 -> "a counter is negative"
        state.pendingMinutes !in 0 until MECHA_MINUTES_PER_CREDIT -> "its pending play minutes (${state.pendingMinutes}) are out of range"
        state.bond !in 0..MECHA_STAT_MAX -> "its bond (${state.bond}) is out of range"
        state.charge !in stats || state.condition !in stats -> "its charge or condition is out of range"
        state.initializedAt <= 0 -> "it has no start time"
        state.lastPetAt < 0 -> "its last pet time is negative"
        else -> null
    }
}

/**
 * A pet (a tap on him): a little bond, at most once per [MECHA_PET_BOND_MS] however often he is tapped; a pet inside that
 * window changes nothing and saves nothing. A clock that went backwards (now before [MechaState.lastPetAt]) does not lock
 * pets out. No message: the mech's own reaction is the feedback.
 */
internal fun MechaState.petted(now: Long): MechaResult {
    if (lastPetAt > 0 && now - lastPetAt in 0 until MECHA_PET_BOND_MS) return MechaResult(this)
    return MechaResult(copy(lastPetAt = now, bond = bondPlus(1)))
}

/**
 * This hangar (from a backup) restored over the device's own hangar [current] (null: none open): every session either one
 * already credited stays credited, so play that earned salvage on either side never earns it again here. Play the backup's
 * hangar had not got to yet still counts, once. Saved in this build's format.
 */
internal fun MechaState.restoredOver(current: MechaState?): MechaState = copy(
    version = MECHA_SAVE_VERSION,
    processedSessions = if (current == null) processedSessions else LinkedHashSet(processedSessions).apply { addAll(current.processedSessions) }
)

/**
 * [sessions] counted as already credited without earning anything: play a restore merges in from a backup that carries no
 * hangar was played (and paid for) on another hangar, so it must not pay again here.
 */
internal fun MechaState.creditedElsewhere(sessions: Collection<PlaySession>): MechaResult {
    val processed = LinkedHashSet(processedSessions)
    sessions.mapTo(processed, ::mechaSessionKey)
    return if (processed.size == processedSessions.size) MechaResult(this) else MechaResult(copy(processedSessions = processed))
}

/** Parts not yet in the hangar, of [slot] (any when null), sorted by id. */
internal fun MechaState.unownedIn(parts: List<MechaPart>, slot: MechaSlot? = null): List<MechaPart> =
    parts.filter { it.id !in owned && (slot == null || it.slot == slot) }.sortedBy { it.id }

internal fun MechaState.owns(partId: String): Boolean = partId in owned

internal fun MechaState.renamed(input: String): MechaResult {
    val next = input.trim()
    mechaNameProblem(next)?.let { return MechaResult(this, error = it) }
    if (next == name) return MechaResult(this, message = "Still called $name.")
    return MechaResult(copy(name = next), message = "$name answers to $next now.")
}

internal fun MechaState.equipping(partId: String, parts: List<MechaPart>): MechaResult {
    val part = parts.firstOrNull { it.id == partId } ?: return MechaResult(this, error = "There is no part \"$partId\".")
    if (partId !in owned) return MechaResult(this, error = "${part.name} isn't in the hangar yet; salvage can turn it up.")
    if (equipped[part.slot] == partId) return MechaResult(this, message = "${part.name} is already fitted.")
    return MechaResult(copy(equipped = equipped + (part.slot to partId)), message = "Fitted ${part.name}.")
}

internal fun MechaState.cared(action: MechaCare): MechaResult = when (action) {
    MechaCare.TUNE ->
        if (condition >= MECHA_STAT_MAX) {
            MechaResult(this, message = "$name is already running smooth.")
        } else {
            val next = min(MECHA_STAT_MAX, condition + TUNE_STEP)
            MechaResult(copy(condition = next, bond = bondPlus(2)), message = "Tuned $name: condition $next%.")
        }
    MechaCare.CHARGE ->
        if (charge >= MECHA_STAT_MAX) {
            MechaResult(this, message = "$name's cells are already full.")
        } else {
            val next = min(MECHA_STAT_MAX, charge + CHARGE_STEP)
            MechaResult(copy(charge = next, bond = bondPlus(2)), message = "Charged $name: $next%.")
        }
    MechaCare.REST ->
        if (sleeping) {
            MechaResult(copy(sleeping = false), message = "$name is awake.")
        } else {
            MechaResult(copy(sleeping = true), message = "$name powers down for a nap.")
        }
}

internal fun MechaState.withEnabled(on: Boolean): MechaResult =
    if (enabled == on) MechaResult(this) else MechaResult(copy(enabled = on), message = if (on) "$name is back." else "$name is hidden.")

internal fun MechaState.withMotion(on: Boolean): MechaResult =
    if (motion == on) MechaResult(this) else MechaResult(copy(motion = on), message = if (on) "Animation on." else "Animation off.")

/**
 * Spends one credit on a part not yet owned in [slot] (any slot when null). The pick is deterministic: the same state always
 * finds the same part. With nothing left to find, or no credit, nothing changes and nothing is spent.
 */
internal fun MechaState.salvaged(parts: List<MechaPart>, slot: MechaSlot? = null): MechaResult {
    val candidates = unownedIn(parts, slot)
    if (candidates.isEmpty()) {
        val what = if (slot == null) "Every part" else "Every ${slot.label()} part"
        return MechaResult(this, message = "$what is already in the hangar; no credit spent.")
    }
    if (salvage <= 0) {
        return MechaResult(this, message = "No salvage credits. Every $MECHA_MINUTES_PER_CREDIT minutes of play earns one ($minutesToNextCredit to go).")
    }
    val pick = candidates[Math.floorMod("salvage-$salvageRuns-${slot?.name ?: "ANY"}".hashCode(), candidates.size)]
    val run = salvageRuns + 1
    val next = copy(
        owned = owned + pick.id,
        origins = origins + (pick.id to "Salvage run #$run"),
        salvage = salvage - 1,
        salvageRuns = run,
        condition = max(MECHA_STAT_FLOOR, condition - SALVAGE_WEAR),
        bond = bondPlus(1)
    )
    return MechaResult(next, message = "Salvage run #$run turned up ${pick.name} (${pick.family} ${pick.slot.label()}). ${creditText(next.salvage)} left.")
}

/** The key that identifies a play session across backup restores and history imports, which keep game and start time. */
internal fun mechaSessionKey(session: PlaySession): String = "${session.gameId}@${session.startedAt}"

/**
 * Credits completed game sessions not seen before: only games ([PlaySession.isGame], not apps or shortcuts), not abandoned, started at or after
 * [MechaState.initializedAt]. Each counts its whole minutes up to [MECHA_SESSION_MINUTES_CAP]; every [MECHA_MINUTES_PER_CREDIT]
 * accumulated minutes is one credit, the rest carries over. Every new eligible session is remembered, so the same session read
 * again (reopened, restored, re-imported) never counts twice. Play wears charge a little, never below [MECHA_STAT_FLOOR].
 */
internal fun MechaState.credited(sessions: List<PlaySession>): MechaResult {
    val processed = LinkedHashSet(processedSessions)
    var pending = pendingMinutes
    var credits = 0
    var minutes = 0
    var played = 0
    for (s in sessions.sortedWith(compareBy({ it.startedAt }, { it.gameId }))) {
        val end = s.endedAt ?: continue
        if (s.abandoned || s.startedAt < initializedAt || !s.isGame) continue
        if (!processed.add(mechaSessionKey(s))) continue
        val counted = ((end - s.startedAt) / 60_000L).coerceIn(0L, MECHA_SESSION_MINUTES_CAP.toLong()).toInt()
        if (counted > 0) played++
        minutes += counted
        pending += counted
        credits += pending / MECHA_MINUTES_PER_CREDIT
        pending %= MECHA_MINUTES_PER_CREDIT
    }
    if (processed.size == processedSessions.size) return MechaResult(this)
    val next = copy(
        processedSessions = processed,
        pendingMinutes = pending,
        creditedMinutes = creditedMinutes + minutes,
        salvage = salvage + credits,
        bond = bondPlus(played),
        charge = max(
            MECHA_STAT_FLOOR,
            charge - ((creditedMinutes + minutes) / MINUTES_PER_CHARGE_POINT - creditedMinutes / MINUTES_PER_CHARGE_POINT)
        )
    )
    val message = when {
        credits > 0 -> "$name logged $minutes min of play: +${creditText(credits)}."
        minutes > 0 -> "$name logged $minutes min of play; ${next.minutesToNextCredit} more for the next credit."
        else -> null
    }
    return MechaResult(next, message = message)
}

private fun MechaState.bondPlus(n: Int): Int = min(MECHA_STAT_MAX, bond + n)

private fun MechaSlot.label(): String = name.lowercase()

private fun creditText(n: Int): String = if (n == 1) "1 salvage credit" else "$n salvage credits"
