package io.github.esko997.sortie.mecha

/** Persistent paint and slot invariants, also checked by portable backup parsing. */
internal fun mechaCommerceProblem(state: MechaState): String? {
    if (MechaPaint() !in state.ownedPaints) return "its included Original Standard paint is missing"
    if (state.equippedPaints.values.any { it !in state.ownedPaints }) return "it wears an unowned paint"
    return mechaSlotProblem(state) ?: mechaLegacyReceiptProblem(state)
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

internal fun MechaState.buyingPaint(paint: MechaPaint): MechaResult {
    if (paint in ownedPaints) return MechaResult(this, error = "${paint.label} is already owned.")
    if (salvage < paint.price) return MechaResult(this, error = "${paint.label} costs ${paint.price} credits.")
    return MechaResult(
        upgraded().copy(salvage = salvage - paint.price, ownedPaints = ownedPaints + paint),
        message = "Bought ${paint.label}. Apply it whenever you like."
    )
}

/** A null slot applies one owned paint to all four slots in a single save, without buying or charging. */
internal fun MechaState.applyingPaint(paint: MechaPaint, slot: MechaSlot?): MechaResult {
    if (paint !in ownedPaints) return MechaResult(this, error = "${paint.label} isn't owned.")
    val original = paint.scheme == MechaPaintScheme.ORIGINAL && paint.finish == MechaFinish.PLAIN
    if (MechaSlot.entries.all { target ->
            (slot != null && target != slot) || (equippedPaints[target]?.let { it == paint } ?: original)
        }
    ) {
        return MechaResult(this)
    }
    val next = equippedPaints.toMutableMap()
    for (target in MechaSlot.entries) {
        if (slot != null && target != slot) continue
        if (original) next.remove(target) else next[target] = paint
    }
    return MechaResult(
        upgraded().copy(equippedPaints = next),
        message = "Applied ${paint.label} to ${slot?.name?.lowercase() ?: "the whole bot"}."
    )
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
    if (this.equipped == equipped) return MechaResult(this)
    return MechaResult(upgraded().copy(equipped = equipped.toMap()), message = "Fitted the build.")
}
