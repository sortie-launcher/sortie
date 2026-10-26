package io.github.esko997.sortie.mecha

/** All persistent commerce invariants, also checked when parsing a backup without a local catalog. */
internal fun mechaCommerceProblem(state: MechaState): String? {
    if (MechaFinish.PLAIN !in state.ownedFinishes) return "its included plain finish is missing"
    if (state.equippedFinishes.values.any { it !in state.ownedFinishes }) return "it wears an unowned finish"
    if (state.cabinetSpins < 0) return "its cabinet spin counter is negative"
    val receipt = state.lastSpin
    if (receipt == null) return if (state.cabinetSpins == 0) null else "its last cabinet receipt is missing"
    if (receipt.sequence <= 0 || receipt.sequence != state.cabinetSpins) return "its cabinet receipt sequence is inconsistent"
    if (receipt.reels.size != 3 || receipt.reels.any { it !in 0..4 }) return "its cabinet reels are invalid"
    val distinct = receipt.reels.toSet().size
    val expectedDistinct = when (receipt.outcome) {
        MechaSpinOutcome.REWARD -> 1
        MechaSpinOutcome.REFUND -> 2
        MechaSpinOutcome.MISS -> 3
    }
    if (distinct != expectedDistinct) return "its cabinet reels disagree with the outcome"
    val expectedReturn = if (receipt.outcome == MechaSpinOutcome.MISS) 0 else receipt.tier.stake
    if (receipt.returnedCredits != expectedReturn) return "its cabinet credit return disagrees with the outcome"
    if (receipt.outcome != MechaSpinOutcome.REWARD) {
        if (receipt.partId != null || receipt.finish != null) return "a non-reward cabinet receipt grants an item"
    } else if (receipt.tier == MechaCabinetTier.PART) {
        if (receipt.partId == null || receipt.partId !in state.owned || receipt.finish != null) {
            return "its cabinet part reward isn't an owned part"
        }
    } else if (receipt.partId != null || receipt.finish != receipt.tier.finish || receipt.finish !in state.ownedFinishes) {
        return "its cabinet finish reward isn't the owned tier finish"
    }
    return null
}

internal fun MechaState.buyingPart(partId: String, parts: List<MechaPart>): MechaResult {
    val part = parts.firstOrNull { it.id == partId } ?: return MechaResult(this, error = "There is no part \"$partId\".")
    if (partId in owned) return MechaResult(this, error = "${part.name} is already owned.")
    if (salvage < MECHA_PART_PRICE) return MechaResult(this, error = "A part costs $MECHA_PART_PRICE credits.")
    return MechaResult(
        upgraded().copy(
            salvage = salvage - MECHA_PART_PRICE,
            owned = owned + partId,
            origins = origins + (partId to "Shop purchase")
        ),
        message = "Bought ${part.name}."
    )
}

internal fun MechaState.buyingFinish(finish: MechaFinish): MechaResult {
    if (finish in ownedFinishes) return MechaResult(this, error = "${finish.label} is already owned.")
    if (salvage < finish.price) return MechaResult(this, error = "${finish.label} costs ${finish.price} credits.")
    return MechaResult(
        upgraded().copy(salvage = salvage - finish.price, ownedFinishes = ownedFinishes + finish),
        message = "Unlocked ${finish.label} for every part."
    )
}

internal fun MechaState.finishing(slot: MechaSlot, finish: MechaFinish): MechaResult {
    if (finish !in ownedFinishes) return MechaResult(this, error = "${finish.label} isn't unlocked.")
    if ((equippedFinishes[slot] ?: MechaFinish.PLAIN) == finish) return MechaResult(this)
    val next = if (finish == MechaFinish.PLAIN) equippedFinishes - slot else equippedFinishes + (slot to finish)
    return MechaResult(upgraded().copy(equippedFinishes = next), message = "Applied ${finish.label} to ${slot.name.lowercase()}.")
}

/** The bench combines one family's body/arms with a different family's head/legs, without inventing pixels. */
internal fun mechaBuilds(parts: List<MechaPart>, owned: Set<String>): List<MechaBuild> {
    val available = parts.filter { it.id in owned }.groupBy { it.family }
        .mapValues { (_, family) -> family.groupBy { it.slot }.mapValues { (_, slot) -> slot.sortedBy { it.id } } }
    val builds = mutableListOf<MechaBuild>()
    for (chassis in MECHA_FAMILIES) {
        val frame = available[chassis] ?: continue
        for (trim in MECHA_FAMILIES) {
            if (trim == chassis) continue
            val accents = available[trim] ?: continue
            for (body in frame[MechaSlot.BODY].orEmpty()) {
                for (arms in frame[MechaSlot.ARMS].orEmpty()) {
                    for (head in accents[MechaSlot.HEAD].orEmpty()) {
                        for (legs in accents[MechaSlot.LEGS].orEmpty()) {
                            builds += MechaBuild(
                                mapOf(MechaSlot.HEAD to head.id, MechaSlot.BODY to body.id, MechaSlot.ARMS to arms.id, MechaSlot.LEGS to legs.id)
                            )
                        }
                    }
                }
            }
        }
    }
    return builds
}

internal fun MechaState.fittingBuild(equipped: Map<MechaSlot, String>, parts: List<MechaPart>): MechaResult {
    val byId = parts.associateBy { it.id }
    if (equipped.size != MechaSlot.entries.size || MechaSlot.entries.any { slot ->
            val id = equipped[slot]
            id !in owned || byId[id]?.slot != slot
        }
    ) {
        return MechaResult(this, error = "A build needs four owned parts in their correct slots.")
    }
    val body = byId.getValue(equipped.getValue(MechaSlot.BODY)).family
    val arms = byId.getValue(equipped.getValue(MechaSlot.ARMS)).family
    val head = byId.getValue(equipped.getValue(MechaSlot.HEAD)).family
    val legs = byId.getValue(equipped.getValue(MechaSlot.LEGS)).family
    if (body != arms || head != legs || body == head) return MechaResult(this, error = "Choose one chassis family and a different trim family.")
    if (this.equipped == equipped) return MechaResult(this)
    return MechaResult(upgraded().copy(equipped = equipped.toMap()), message = "Fitted the build.")
}

/** Roll and reward index are drawn once by the store; this function has no random or persistence side effects. */
internal fun MechaState.cabinetSpun(tier: MechaCabinetTier, parts: List<MechaPart>, roll: Int, rewardIndex: Int): MechaResult {
    if (roll !in 0..99) return MechaResult(this, error = "The cabinet roll is invalid.")
    if (cabinetSpins == Int.MAX_VALUE) return MechaResult(this, error = "The cabinet spin counter is full.")
    if (salvage < tier.stake) return MechaResult(this, error = "${tier.label} needs ${tier.stake} credits to spin.")
    val candidates = if (tier == MechaCabinetTier.PART) unownedIn(parts) else emptyList()
    if (tier == MechaCabinetTier.PART) {
        if (candidates.isEmpty()) return MechaResult(this, error = "Every part is already owned.")
        if (rewardIndex !in candidates.indices) return MechaResult(this, error = "The cabinet reward index is invalid.")
    } else if (tier.finish in ownedFinishes) {
        return MechaResult(this, error = "${tier.label} is already unlocked.")
    }
    val outcome = when (roll) {
        in 0..19 -> MechaSpinOutcome.REWARD
        in 20..49 -> MechaSpinOutcome.REFUND
        else -> MechaSpinOutcome.MISS
    }
    val symbol = roll % 5
    val reels = when (outcome) {
        MechaSpinOutcome.REWARD -> listOf(symbol, symbol, symbol)
        MechaSpinOutcome.REFUND -> listOf(symbol, symbol, (symbol + 1) % 5)
        MechaSpinOutcome.MISS -> listOf(symbol, (symbol + 1) % 5, (symbol + 2) % 5)
    }
    val returned = if (outcome == MechaSpinOutcome.MISS) 0 else tier.stake
    val part = if (outcome == MechaSpinOutcome.REWARD && tier == MechaCabinetTier.PART) candidates[rewardIndex] else null
    val finish = if (outcome == MechaSpinOutcome.REWARD) tier.finish else null
    val sequence = cabinetSpins + 1
    val receipt = MechaSpinReceipt(sequence, tier, reels, outcome, returned, part?.id, finish)
    val next = upgraded().copy(
        salvage = salvage - tier.stake + returned,
        owned = if (part == null) owned else owned + part.id,
        origins = if (part == null) origins else origins + (part.id to "Cabinet spin #$sequence"),
        ownedFinishes = if (finish == null) ownedFinishes else ownedFinishes + finish,
        cabinetSpins = sequence,
        lastSpin = receipt
    )
    val message = when (outcome) {
        MechaSpinOutcome.REWARD -> "Unlocked ${part?.name ?: finish?.label}; ${tier.stake} credits returned."
        MechaSpinOutcome.REFUND -> "${tier.stake} credits returned."
        MechaSpinOutcome.MISS -> "No reward; ${tier.stake} credits spent."
    }
    return MechaResult(next, message = message)
}
