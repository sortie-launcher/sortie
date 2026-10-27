package io.github.esko997.sortie.mecha

import kotlinx.serialization.Serializable

@Serializable
enum class MechaSlotStake(val stake: Int, val label: String) {
    ONE(1, "1 credit"),
    THREE(3, "3 credits"),
    FIVE(5, "5 credits")
}

/** Percentages are over one uniformly drawn integer in 0..99, not three independent reel draws. */
@Serializable
enum class MechaSpinOutcome(val multiplier: Int, val percent: Int, val label: String, val reels: List<Int>) {
    JACKPOT(5, 5, "Jackpot", listOf(2, 2, 2)),
    WIN(2, 20, "Win", listOf(1, 1, 1)),
    REFUND(1, 20, "Stake back", listOf(0, 0, 3)),
    MISS(0, 55, "Miss", listOf(0, 3, 4))
}

/** Persisted with the wallet before any reel animation; inspecting this record never awards credits. */
@Serializable
data class MechaSpinReceipt(
    val sequence: Int,
    val stake: MechaSlotStake,
    val reels: List<Int>,
    val outcome: MechaSpinOutcome,
    val returnedCredits: Int,
    val balanceAfter: Int
)

/** Check before drawing randomness or writing. Reserve room for the full jackpot, never clip a payout. */
internal fun MechaState.slotEntryProblem(stake: MechaSlotStake, expectedSequence: Int): String? = when {
    expectedSequence != cabinetSpins -> "This spin confirmation is out of date. Choose Spin again."
    cabinetSpins < 0 || cabinetSpins == Int.MAX_VALUE -> "The slot spin counter is full or invalid."
    salvage < stake.stake -> "This stake needs ${stake.label}."
    salvage > MECHA_CREDIT_CEILING - 4 * stake.stake ->
        "Spend credits first: a ${stake.label} stake needs a balance of ${MECHA_CREDIT_CEILING - 4 * stake.stake} or less to pay the full jackpot."
    else -> null
}

/** The store serializes confirmations and checks their sequence before calling this pure transition. */
internal fun MechaState.slotSpun(stake: MechaSlotStake, roll: Int): MechaResult {
    slotEntryProblem(stake, cabinetSpins)?.let { return MechaResult(this, error = it) }
    if (roll !in 0..99) return MechaResult(this, error = "The slot roll is invalid.")
    val outcome = when (roll) {
        in 0..4 -> MechaSpinOutcome.JACKPOT
        in 5..24 -> MechaSpinOutcome.WIN
        in 25..44 -> MechaSpinOutcome.REFUND
        else -> MechaSpinOutcome.MISS
    }
    val returned = stake.stake * outcome.multiplier
    val balance = salvage - stake.stake + returned
    val receipt = MechaSpinReceipt(cabinetSpins + 1, stake, outcome.reels, outcome, returned, balance)
    return MechaResult(
        upgraded().copy(
            salvage = balance,
            pendingMinutes = if (balance == MECHA_CREDIT_CEILING) 0 else pendingMinutes,
            cabinetSpins = receipt.sequence,
            lastSpin = receipt
        ),
        message = "${outcome.label}: staked ${stake.stake}, returned $returned credits."
    )
}

/** Historical item receipts are validated by the migration layer, never treated as credit wins. */
internal fun mechaSlotProblem(state: MechaState): String? {
    if (state.cabinetSpins < 0) return "its slot spin counter is negative"
    val receipt = state.lastSpin
        ?: return if (state.cabinetSpins == 0 || state.legacyLastSpin?.sequence == state.cabinetSpins) null else "its last slot receipt is missing"
    if (receipt.sequence <= 0 || receipt.sequence != state.cabinetSpins) return "its slot receipt sequence is inconsistent"
    if (receipt.reels != receipt.outcome.reels) return "its slot reels disagree with the outcome"
    if (receipt.returnedCredits != receipt.stake.stake * receipt.outcome.multiplier) return "its slot payout disagrees with the outcome"
    if (receipt.balanceAfter !in 0..MECHA_CREDIT_CEILING) return "its slot receipt balance is invalid"
    val before = receipt.balanceAfter + receipt.stake.stake - receipt.returnedCredits
    if (before !in receipt.stake.stake..(MECHA_CREDIT_CEILING - 4 * receipt.stake.stake)) return "its slot receipt could not pay a full jackpot"
    return null
}
