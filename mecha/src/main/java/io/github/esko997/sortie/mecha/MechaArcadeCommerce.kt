package io.github.esko997.sortie.mecha

import io.github.esko997.sortie.ui.SortiePalette

internal fun MechaState.hasLegacyArcadeDefaults(): Boolean = ownedThemes == setOf("default") && selectedTheme == "default" && !hangarIntroSeen &&
    arcadeRuns == 0 && activeArcade == null && arcadeBest.isEmpty()

/** Version-three invariants shared by disk loading and portable backup validation. */
internal fun mechaArcadeCommerceProblem(state: MechaState): String? {
    if (SortiePalette.DEFAULT.id !in state.ownedThemes) return "its original colour scheme is missing"
    if (state.ownedThemes.any { id -> SortiePalette.entries.none { it.id == id } }) return "it owns an unknown colour scheme"
    if (state.selectedTheme !in state.ownedThemes) return "its selected colour scheme isn't owned"
    if (state.arcadeRuns < 0) return "its arcade entry counter is negative"
    val ticket = state.activeArcade
    if (ticket != null && (ticket.sequence <= 0 || ticket.sequence != state.arcadeRuns)) {
        return "its paid arcade ticket sequence is inconsistent"
    }
    if (state.arcadeBest.any { (game, score) -> !game.acceptsScore(score) }) return "an arcade best score is invalid"
    val finishedCapacity = state.arcadeRuns - if (ticket == null) 0 else 1
    if (state.arcadeBest.size > finishedCapacity) return "its arcade scores have no completed paid entries"
    return null
}

internal fun MechaState.buyingTheme(palette: SortiePalette): MechaResult {
    if (palette.id in ownedThemes) return MechaResult(this, error = "${palette.label} is already owned.")
    if (salvage < palette.price) return MechaResult(this, error = "${palette.label} costs ${palette.price} credits.")
    return MechaResult(
        upgraded().copy(salvage = salvage - palette.price, ownedThemes = ownedThemes + palette.id),
        message = "Bought ${palette.label}. Apply it whenever you like."
    )
}

internal fun MechaState.selectingTheme(palette: SortiePalette): MechaResult {
    if (palette.id !in ownedThemes) return MechaResult(this, error = "${palette.label} isn't owned.")
    if (selectedTheme == palette.id) return MechaResult(this)
    return MechaResult(upgraded().copy(selectedTheme = palette.id), message = "Applied ${palette.label}.")
}

internal fun MechaState.introduced(hideCompanion: Boolean): MechaResult {
    if (hangarIntroSeen && (!hideCompanion || !enabled)) return MechaResult(this)
    return MechaResult(
        upgraded().copy(hangarIntroSeen = true, enabled = enabled && !hideCompanion),
        message = if (hideCompanion) "$name is hidden. Reopen the Hangar from Extras." else null
    )
}

internal fun MechaState.arcadeEntryProblem(expectedSequence: Int): String? = when {
    expectedSequence != arcadeRuns -> "That arcade entry has already been used."
    activeArcade != null -> "Resume or abandon the paid round before starting another."
    arcadeRuns == Int.MAX_VALUE -> "The arcade entry counter is full."
    salvage < MECHA_ARCADE_PRICE -> "An arcade run costs $MECHA_ARCADE_PRICE credit."
    else -> null
}

/** The store draws the seed once on its serialized worker, after checking the entry guard. */
internal fun MechaState.insertingArcadeCoin(expectedSequence: Int, seed: Long): MechaResult {
    arcadeEntryProblem(expectedSequence)?.let { return MechaResult(this, error = it) }
    val ticket = MechaArcadeTicket(arcadeRuns + 1, null, seed)
    return MechaResult(
        upgraded().copy(salvage = salvage - MECHA_ARCADE_PRICE, arcadeRuns = ticket.sequence, activeArcade = ticket),
        message = "Coin saved. Choose one game for this run. No credit prizes."
    )
}

/** Bind a saved coin once; choosing a game never debits the wallet again. */
internal fun MechaState.selectingArcadeGame(ticket: MechaArcadeTicket, game: MechaArcadeGame): MechaResult {
    if (activeArcade != ticket) return MechaResult(this, error = "That paid round is no longer active.")
    if (ticket.game != null) return MechaResult(this, error = "That paid round already has a game.")
    return MechaResult(
        copy(activeArcade = ticket.copy(game = game)),
        message = "${game.label} selected. This coin pays for one run."
    )
}

internal fun MechaState.finishingArcade(ticket: MechaArcadeTicket, score: Int): MechaResult {
    if (activeArcade != ticket) return MechaResult(this, error = "That paid round is no longer active.")
    val game = ticket.game ?: return MechaResult(this, error = "Choose a game before finishing this paid round.")
    if (!game.acceptsScore(score)) return MechaResult(this, error = "That arcade score is invalid.")
    val best = arcadeBest[game]
    return MechaResult(
        copy(activeArcade = null, arcadeBest = if (best == null || score > best) arcadeBest + (game to score) else arcadeBest),
        message = "${game.label}: $score points saved. No credit prizes."
    )
}

internal fun MechaState.abandoningArcade(ticket: MechaArcadeTicket): MechaResult {
    if (activeArcade != ticket) return MechaResult(this, error = "That paid round is no longer active.")
    return MechaResult(copy(activeArcade = null), message = "Round abandoned. No refund or reward.")
}
