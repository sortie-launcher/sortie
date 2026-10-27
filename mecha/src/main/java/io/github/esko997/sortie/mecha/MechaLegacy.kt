package io.github.esko997.sortie.mecha

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Historical item-cabinet records are never treated as credit-slot payouts. */
@Serializable
enum class MechaLegacyCabinetTier(val stake: Int, val finish: MechaFinish?) {
    PART(1, null),
    ALLOY(3, MechaFinish.ALLOY),
    HOLO(5, MechaFinish.HOLO)
}

@Serializable
enum class MechaLegacySpinOutcome { REWARD, REFUND, MISS }

@Serializable
data class MechaLegacySpinReceipt(
    val sequence: Int,
    val tier: MechaLegacyCabinetTier,
    val reels: List<Int>,
    val outcome: MechaLegacySpinOutcome,
    val returnedCredits: Int,
    val partId: String? = null,
    val finish: MechaFinish? = null
)

/** Shared by disk and nested portable-backup decoding. Version stays historical until an atomic save succeeds. */
fun migrateMechaSave(element: JsonElement): JsonObject {
    val root = element as? JsonObject ?: throw IllegalArgumentException("The companion save is not an object.")
    val version = if ("version" !in root) {
        1
    } else {
        (root["version"] as? JsonPrimitive)?.intOrNull
            ?: throw IllegalArgumentException("The companion save has an invalid version.")
    }
    require(version in 1..4) { "Unsupported companion format version $version." }
    if (version == 4) {
        require("ownedFinishes" !in root && "equippedFinishes" !in root) { "A current save contains obsolete finish fields." }
        return root
    }
    val owned = root["ownedFinishes"]?.let { Json.decodeFromJsonElement(SetSerializer(MechaFinish.serializer()), it) }
        ?: setOf(MechaFinish.PLAIN)
    val equipped = root["equippedFinishes"]?.let {
        Json.decodeFromJsonElement(MapSerializer(MechaSlot.serializer(), MechaFinish.serializer()), it)
    }.orEmpty()
    require(version != 1 || (owned == setOf(MechaFinish.PLAIN) && equipped.isEmpty())) {
        "A legacy hangar contains newer finish fields."
    }
    require("ownedPaints" !in root || owned == setOf(MechaFinish.PLAIN)) { "A legacy save mixes paint and finish ownership." }
    require("equippedPaints" !in root || equipped.isEmpty()) { "A legacy save mixes paint and finish selections." }
    val result = root.toMutableMap()
    result.remove("ownedFinishes")
    result.remove("equippedFinishes")
    if ("ownedPaints" !in result) {
        result["ownedPaints"] = Json.encodeToJsonElement(SetSerializer(MechaPaint.serializer()), owned.mapTo(linkedSetOf()) { MechaPaint(finish = it) })
    }
    result["version"] = JsonPrimitive(version)
    if ("equippedPaints" !in result) {
        result["equippedPaints"] = Json.encodeToJsonElement(
            MapSerializer(MechaSlot.serializer(), MechaPaint.serializer()), equipped.mapValues { MechaPaint(finish = it.value) }
        )
    }
    val receipt = result["lastSpin"]
    if (receipt != null && receipt != JsonNull) {
        require(result["legacyLastSpin"] == null || result["legacyLastSpin"] == JsonNull) { "A legacy save has two cabinet receipts." }
        // Decode now so a backup's permissive unknown-field policy cannot conceal malformed historical records.
        Json.decodeFromJsonElement(MechaLegacySpinReceipt.serializer(), receipt)
        result["legacyLastSpin"] = receipt
        result.remove("lastSpin")
    }
    return JsonObject(result)
}

fun decodeMechaState(text: String): MechaState = Json.decodeFromJsonElement(MechaState.serializer(), migrateMechaSave(Json.parseToJsonElement(text)))

internal fun mechaLegacyReceiptProblem(state: MechaState): String? {
    val receipt = state.legacyLastSpin ?: return null
    if (receipt.sequence <= 0 || receipt.sequence > state.cabinetSpins) return "its historical cabinet sequence is inconsistent"
    if (state.lastSpin != null && receipt.sequence >= state.lastSpin.sequence) return "its historical receipt is not older than its slot receipt"
    if (receipt.reels.size != 3 || receipt.reels.any { it !in 0..4 }) return "its historical cabinet reels are invalid"
    val expectedDistinct = when (receipt.outcome) {
        MechaLegacySpinOutcome.REWARD -> 1
        MechaLegacySpinOutcome.REFUND -> 2
        MechaLegacySpinOutcome.MISS -> 3
    }
    if (receipt.reels.toSet().size != expectedDistinct) return "its historical reels disagree with the outcome"
    val returned = if (receipt.outcome == MechaLegacySpinOutcome.MISS) 0 else receipt.tier.stake
    if (receipt.returnedCredits != returned) return "its historical cabinet credit return is invalid"
    if (receipt.outcome != MechaLegacySpinOutcome.REWARD) {
        if (receipt.partId != null || receipt.finish != null) return "a historical non-reward receipt grants an item"
    } else if (receipt.tier == MechaLegacyCabinetTier.PART) {
        if (receipt.partId == null || receipt.partId !in state.owned || receipt.finish != null) return "its historical part reward isn't owned"
    } else if (receipt.partId != null || receipt.finish != receipt.tier.finish || MechaPaint(finish = receipt.finish!!) !in state.ownedPaints) {
        return "its historical finish reward isn't owned"
    }
    return null
}
