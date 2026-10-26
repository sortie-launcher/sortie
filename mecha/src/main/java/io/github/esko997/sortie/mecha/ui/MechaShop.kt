package io.github.esko997.sortie.mecha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.esko997.sortie.mecha.MECHA_CREDIT_CEILING
import io.github.esko997.sortie.mecha.MECHA_MINUTES_PER_CREDIT
import io.github.esko997.sortie.mecha.MECHA_PART_PRICE
import io.github.esko997.sortie.mecha.MECHA_SESSION_MINUTES_CAP
import io.github.esko997.sortie.mecha.MechaBuild
import io.github.esko997.sortie.mecha.MechaCabinetTier
import io.github.esko997.sortie.mecha.MechaFinish
import io.github.esko997.sortie.mecha.MechaSlot
import io.github.esko997.sortie.mecha.MechaSpinOutcome
import io.github.esko997.sortie.mecha.MechaSpinReceipt
import io.github.esko997.sortie.mecha.MechaState
import io.github.esko997.sortie.mecha.MechaStore
import io.github.esko997.sortie.mecha.MechaView
import io.github.esko997.sortie.mecha.mechaBuilds
import io.github.esko997.sortie.ui.Amber
import io.github.esko997.sortie.ui.Ink
import io.github.esko997.sortie.ui.Slate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val reelLabels = listOf("Bolt", "Gear", "Star", "Cell", "Chip")

/** Previews are free. Only the labelled purchase/apply/fit controls change the save. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MechaShop(store: MechaStore, view: MechaView, state: MechaState) {
    var finishes by rememberSaveable { mutableStateOf(false) }
    ShopColumn {
        Wallet(state)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MechaChip("Parts", !finishes, { finishes = false })
            MechaChip("Finishes", finishes, { finishes = true })
        }
        if (finishes) FinishShop(store, view, state) else PartShop(store, view, state)
    }
}

@Composable
private fun Wallet(state: MechaState) {
    Text("${state.salvage}/$MECHA_CREDIT_CEILING credits", style = MaterialTheme.typography.titleMedium, color = Amber)
    Note(
        if (state.salvage >= MECHA_CREDIT_CEILING) {
            "Earning paused until you spend below $MECHA_CREDIT_CEILING credits. Extra play is not banked for later."
        } else {
            "Earn 1 credit every $MECHA_MINUTES_PER_CREDIT game minutes, up to $MECHA_SESSION_MINUTES_CAP minutes per session. " +
                "${state.minutesToNextCredit} minutes to the next credit. Earning pauses at $MECHA_CREDIT_CEILING; extra play is not banked."
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PartShop(store: MechaStore, view: MechaView, state: MechaState) {
    var slot by rememberSaveable { mutableStateOf(MechaSlot.HEAD) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val choices = remember(view.parts, slot) { view.parts.filter { it.slot == slot }.sortedWith(partOrder) }
    val part = choices.firstOrNull { it.id == selectedId } ?: choices.firstOrNull() ?: return
    val owned = part.id in state.owned
    var confirming by remember { mutableStateOf(false) }
    val anchor = remember { FocusRequester() }
    val input = LocalInputModeManager.current
    RestoreShopFocus(confirming, anchor)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MechaSlot.entries.forEach { s ->
            MechaChip(s.label(), s == slot, { slot = s }, if (s == slot) Modifier.focusRequester(anchor) else Modifier)
        }
    }
    Preview(view, state.equipped + (slot to part.id), state.equippedFinishes)
    Text(part.name, style = MaterialTheme.typography.titleMedium)
    Note("${familyName(part.family)} ${part.variant} · ${slot.label()} · $MECHA_PART_PRICE credits. Previewing never spends or equips.")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MechaButton(
            if (owned) "Owned" else "Buy part · $MECHA_PART_PRICE credits",
            onClick = { confirming = true },
            enabled = !view.busy && !owned && state.salvage >= MECHA_PART_PRICE,
            primary = !owned
        )
        if (owned) {
            MechaButton(
                if (state.equipped[slot] == part.id) "Wearing" else "Equip part",
                onClick = {
                    store.equip(part.id)
                    if (input.inputMode == InputMode.Keyboard) anchor.requestFocus()
                },
                enabled = !view.busy && state.equipped[slot] != part.id
            )
        }
    }
    if (!owned && state.salvage < MECHA_PART_PRICE) Note("Need ${MECHA_PART_PRICE - state.salvage} more credits to buy this part.")
    if (state.owned.size == view.parts.size) Note("Every part is owned. You can still mix builds and apply finishes.")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        choices.forEach { choice ->
            MechaChip(
                choice.name,
                choice.id == part.id,
                { selectedId = choice.id },
                Modifier.semantics { contentDescription = "Preview ${choice.name}, ${if (choice.id in state.owned) "owned" else "$MECHA_PART_PRICE credits"}" },
                dim = choice.id !in state.owned
            )
        }
    }
    if (confirming) {
        PurchaseConfirmation(
            title = "Buy ${part.name}?",
            detail = "$MECHA_PART_PRICE credits. Adds this part to your hangar; does not equip it.",
            action = "Buy part",
            view = view,
            allowed = !owned && state.salvage >= MECHA_PART_PRICE,
            onConfirm = { store.buyPart(part.id) },
            onClose = { confirming = false }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FinishShop(store: MechaStore, view: MechaView, state: MechaState) {
    var slot by rememberSaveable { mutableStateOf(MechaSlot.HEAD) }
    var finish by rememberSaveable { mutableStateOf(MechaFinish.PLAIN) }
    var confirming by remember { mutableStateOf(false) }
    val owned = finish in state.ownedFinishes
    val applied = (state.equippedFinishes[slot] ?: MechaFinish.PLAIN) == finish
    val anchor = remember { FocusRequester() }
    val input = LocalInputModeManager.current
    RestoreShopFocus(confirming, anchor)
    Note("Unlock once, reuse on any part. Choose a slot and preview freely; Apply changes only that slot. Plain removes its finish for free.")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MechaSlot.entries.forEach { s -> MechaChip(s.label(), s == slot, { slot = s }, if (s == slot) Modifier.focusRequester(anchor) else Modifier) }
    }
    Preview(view, state.equipped, state.equippedFinishes + (slot to finish), motion = state.motion && state.enabled)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MechaFinish.entries.forEach { f ->
            MechaChip("${f.label} · ${if (f == MechaFinish.PLAIN) "free" else "${f.price} credits"}", f == finish, { finish = f })
        }
    }
    Text("${finish.label} on ${slot.label().lowercase()} · preview", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MechaButton(
            if (owned) "Finish owned" else "Buy finish · ${finish.price} credits",
            onClick = { confirming = true },
            enabled = !view.busy && !owned && state.salvage >= finish.price,
            primary = !owned
        )
        MechaButton(
            if (applied) "Applied" else "Apply finish",
            onClick = {
                store.setFinish(slot, finish)
                if (input.inputMode == InputMode.Keyboard) anchor.requestFocus()
            },
            enabled = !view.busy && owned && !applied
        )
    }
    if (!owned && state.salvage < finish.price) Note("Need ${finish.price - state.salvage} more credits to unlock this finish.")
    if (confirming) {
        PurchaseConfirmation(
            title = "Unlock ${finish.label}?",
            detail = "${finish.price} credits once. Reusable on all slots and parts; does not apply automatically.",
            action = "Buy finish",
            view = view,
            allowed = !owned && state.salvage >= finish.price,
            onConfirm = { store.buyFinish(finish) },
            onClose = { confirming = false }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MechaBuildBench(store: MechaStore, view: MechaView, state: MechaState) {
    val builds by produceState<List<MechaBuild>?>(null, view.parts, state.owned) {
        value = withContext(Dispatchers.Default) { mechaBuilds(view.parts, state.owned) }
    }
    var chassis by rememberSaveable { mutableStateOf<String?>(null) }
    var trim by rememberSaveable { mutableStateOf<String?>(null) }
    var index by rememberSaveable { mutableStateOf(0) }
    val parts = remember(view.parts) { view.parts.associateBy { it.id } }
    val all = builds
    ShopColumn {
        Text("Build bench · free", style = MaterialTheme.typography.titleMedium, color = Amber)
        Note(
            "Two authored families, shared joints: body + arms form the chassis; head + legs supply the trim. " +
                "Only owned parts are used. Nothing changes until Fit build."
        )
        when {
            all == null -> Note("Finding compatible owned parts…")
            all.isEmpty() -> Note(
                "No compatible two-family build yet. Own body + arms from one family and head + legs from another; " +
                    "buy missing parts in the Shop. Manual equipping still works in Workshop."
            )
            else -> {
                val chassisChoices = remember(all, parts) { all.mapNotNull { parts[it.equipped[MechaSlot.BODY]]?.family }.distinct() }
                val chosenChassis = chassis?.takeIf { it in chassisChoices } ?: chassisChoices.first()
                val chassisBuilds = remember(all, chosenChassis, parts) { all.filter { parts[it.equipped[MechaSlot.BODY]]?.family == chosenChassis } }
                val anchor = remember { FocusRequester() }
                val input = LocalInputModeManager.current
                val trimChoices = remember(chassisBuilds, parts) { chassisBuilds.mapNotNull { parts[it.equipped[MechaSlot.HEAD]]?.family }.distinct() }
                val chosenTrim = trim?.takeIf { it in trimChoices } ?: trimChoices.first()
                val choices = remember(chassisBuilds, chosenTrim, parts) { chassisBuilds.filter { parts[it.equipped[MechaSlot.HEAD]]?.family == chosenTrim } }
                val selected = index.coerceIn(0, choices.lastIndex)
                val build = choices[selected].equipped
                Text("Chassis", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    chassisChoices.forEach { family ->
                        MechaChip(
                            familyName(family),
                            family == chosenChassis,
                            {
                                chassis = family
                                index = 0
                            },
                            if (family == chosenChassis) Modifier.focusRequester(anchor) else Modifier
                        )
                    }
                }
                Text("Trim", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    trimChoices.forEach { family ->
                        MechaChip(familyName(family), family == chosenTrim, {
                            trim = family
                            index = 0
                        })
                    }
                }
                Preview(view, build, state.equippedFinishes)
                Note(MechaSlot.entries.joinToString(" · ") { slot -> "${slot.label()}: ${parts[build[slot]]?.name.orEmpty()}" })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MechaButton("Previous", { index = (selected + choices.size - 1) % choices.size }, enabled = choices.size > 1)
                    MechaButton("Next", { index = (selected + 1) % choices.size }, enabled = choices.size > 1)
                    MechaButton(
                        if (build == state.equipped) "Build fitted" else "Fit build · free",
                        onClick = {
                            store.fitBuild(build)
                            if (input.inputMode == InputMode.Keyboard) anchor.requestFocus()
                        },
                        enabled = !view.busy && build != state.equipped,
                        primary = true
                    )
                }
                Note("Variant ${selected + 1} of ${choices.size}. ${all.size} compatible builds across your collection.")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MechaCabinet(store: MechaStore, view: MechaView, state: MechaState) {
    var tier by rememberSaveable { mutableStateOf(MechaCabinetTier.PART) }
    var confirming by remember { mutableStateOf(false) }
    val anchor = remember { FocusRequester() }
    RestoreShopFocus(confirming, anchor)
    val complete = when (tier) {
        MechaCabinetTier.PART -> view.parts.all { it.id in state.owned }
        MechaCabinetTier.ALLOY -> MechaFinish.ALLOY in state.ownedFinishes
        MechaCabinetTier.HOLO -> MechaFinish.HOLO in state.ownedFinishes
    }
    ShopColumn {
        Wallet(state)
        Text("Salvage cabinet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Note(
            "20% reward + stake back · 30% stake back · 50% miss. Each spin is independent; no guaranteed reward. " +
                "Reels show the saved outcome, not separate chances."
        )
        Note(
            "Average cost per reward is 25% more than buying directly: part 2.5 vs 2 credits, Alloy 7.5 vs 6, Holo 12.5 vs 10. " +
                "The Shop is always the certain option."
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MechaCabinetTier.entries.forEach { t ->
                MechaChip(
                    "${t.label} · ${t.stake} ${if (t.stake == 1) "credit" else "credits"}",
                    t == tier,
                    { tier = t },
                    if (t == tier) Modifier.focusRequester(anchor) else Modifier
                )
            }
        }
        Note(
            "${tier.label}: stake ${tier.stake}. Reward or refund returns ${tier.stake} credits (net 0); " +
                "a miss returns 0 (net −${tier.stake}). Rewards never equip automatically."
        )
        MechaButton(
            if (complete) "Tier complete" else "Spin · ${tier.stake} ${if (tier.stake == 1) "credit" else "credits"}…",
            onClick = { confirming = true },
            enabled = !view.busy && !complete && state.salvage >= tier.stake,
            primary = true
        )
        when {
            view.busy -> Note("Saving the current action…")
            complete -> Note("You already own every reward in this tier. No duplicates, no credits spent.")
            state.salvage < tier.stake -> Note("Need ${tier.stake - state.salvage} more credits for this tier.")
        }
        state.lastSpin?.let { receipt -> Receipt(receipt, view) }
    }
    if (confirming) {
        val sequence = remember { state.cabinetSpins }
        PurchaseConfirmation(
            title = "Spin ${tier.label}?",
            detail = "Stake ${tier.stake} credits. 20% reward + refund, 30% refund, 50% lose the stake. One spin only.",
            action = "Spin once",
            view = view,
            allowed = !complete && state.salvage >= tier.stake && state.cabinetSpins == sequence,
            onConfirm = { store.spin(tier, sequence) },
            onClose = { confirming = false },
            result = { state.lastSpin?.takeIf { it.sequence == sequence + 1 }?.let { Receipt(it, view) } }
        )
    }
}

/** A receipt is descriptive only: opening or dismissing it never calls the store. */
@Composable
private fun Receipt(receipt: MechaSpinReceipt, view: MechaView) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ink).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Saved spin #${receipt.sequence} · ${receipt.tier.label}", style = MaterialTheme.typography.titleSmall, color = Amber)
        Text(
            receipt.reels.joinToString("   ") { reelLabels[it] },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { contentDescription = "Saved reels ${receipt.reels.joinToString()}" }
        )
        Text(
            when (receipt.outcome) {
                MechaSpinOutcome.REWARD -> {
                    val name = receipt.partId?.let { id -> view.parts.firstOrNull { it.id == id }?.name } ?: receipt.finish?.label.orEmpty()
                    "Unlocked $name."
                }
                MechaSpinOutcome.REFUND -> "Stake returned. No unlock."
                MechaSpinOutcome.MISS -> "No reward this time."
            },
            style = MaterialTheme.typography.bodyMedium
        )
        Note(
            "Staked ${receipt.tier.stake}; returned ${receipt.returnedCredits}; net ${receipt.returnedCredits - receipt.tier.stake} credits. " +
                "Already saved; viewing this receipt grants nothing again."
        )
    }
}

/** Cancel receives focus first; a submitted confirmation cannot submit again, even after an error. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PurchaseConfirmation(
    title: String,
    detail: String,
    action: String,
    view: MechaView,
    allowed: Boolean,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
    result: @Composable () -> Unit = {}
) {
    var submitted by remember { mutableStateOf(false) }
    val safe = remember { FocusRequester() }
    val input = LocalInputModeManager.current
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(16.dp), color = Slate, modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Note(detail)
                Text("Balance: ${view.state?.salvage ?: 0} credits", style = MaterialTheme.typography.titleSmall, color = Amber)
                if (submitted) {
                    if (view.busy) {
                        Note("Saving… Wait for the saved result before trying another action.")
                    } else {
                        Text(
                            view.error ?: view.message.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (view.error != null) Warning else Amber
                        )
                        result()
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MechaButton(if (submitted) "Close receipt" else "Cancel", onClose, modifier = Modifier.focusRequester(safe))
                    MechaButton(
                        action,
                        onClick = {
                            if (!submitted && !view.busy && allowed) {
                                submitted = true
                                onConfirm()
                                if (input.inputMode == InputMode.Keyboard) safe.requestFocus()
                            }
                        },
                        enabled = !submitted && !view.busy && allowed,
                        primary = true
                    )
                }
            }
        }
        LaunchedEffect(Unit) {
            safe.requestFocus()
        }
    }
}

@Composable
private fun Preview(view: MechaView, equipped: Map<MechaSlot, String>, finishes: Map<MechaSlot, MechaFinish>, motion: Boolean = false) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ink).padding(8.dp), contentAlignment = Alignment.Center) {
        MechaFigure(view.parts, equipped, MechaPose.IDLE, motion = motion, target = 128.dp, finishes = finishes)
    }
}

@Composable
private fun ShopColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(top = 10.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Muted)
}

/** Restore only after the nested dialog has left composition, not while its window still owns focus. */
@Composable
private fun RestoreShopFocus(showing: Boolean, anchor: FocusRequester) {
    var wasShowing by remember { mutableStateOf(false) }
    val input = LocalInputModeManager.current
    LaunchedEffect(showing) {
        if (wasShowing && !showing && input.inputMode == InputMode.Keyboard) anchor.requestFocus()
        wasShowing = showing
    }
}
