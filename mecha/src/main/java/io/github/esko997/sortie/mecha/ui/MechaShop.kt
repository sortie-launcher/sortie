package io.github.esko997.sortie.mecha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.esko997.sortie.mecha.MECHA_CREDIT_CEILING
import io.github.esko997.sortie.mecha.MECHA_FAMILIES
import io.github.esko997.sortie.mecha.MECHA_MINUTES_PER_CREDIT
import io.github.esko997.sortie.mecha.MECHA_PART_PRICE
import io.github.esko997.sortie.mecha.MECHA_SESSION_MINUTES_CAP
import io.github.esko997.sortie.mecha.MechaPaint
import io.github.esko997.sortie.mecha.MechaSlot
import io.github.esko997.sortie.mecha.MechaState
import io.github.esko997.sortie.mecha.MechaStore
import io.github.esko997.sortie.mecha.MechaView
import io.github.esko997.sortie.ui.Accent
import io.github.esko997.sortie.ui.CanvasColour
import io.github.esko997.sortie.ui.PanelColour

private enum class ShopCategory(val label: String) { PARTS("Parts"), PAINT("Paint"), LAUNCHER("Launcher") }
internal enum class PartOwnership(val label: String) { ALL("All parts"), OWNED("Owned parts"), UNOWNED("Unowned parts") }

/** The Shop only adds ownership. Every application lives in the Workshop. */
@Composable
internal fun MechaShop(store: MechaStore, view: MechaView, state: MechaState) {
    var category by rememberSaveable { mutableStateOf(ShopCategory.PARTS) }
    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${state.salvage}/$MECHA_CREDIT_CEILING credits", style = MaterialTheme.typography.titleMedium, color = Accent)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ShopCategory.entries.forEach { choice -> MechaChip(choice.label, category == choice, { category = choice }) }
        }
        Note("Buy here; apply owned items in Workshop. Previews are free.")
        when (category) {
            ShopCategory.PARTS -> ShopColumn {
                Note(
                    if (state.salvage >= MECHA_CREDIT_CEILING) {
                        "Earning paused at $MECHA_CREDIT_CEILING; spend to resume. Extra play is not banked."
                    } else {
                        "1 credit / $MECHA_MINUTES_PER_CREDIT game minutes · ${state.minutesToNextCredit} minutes to next · " +
                            "$MECHA_SESSION_MINUTES_CAP minutes counted per session."
                    }
                )
                PartShop(store, view, state)
            }
            ShopCategory.PAINT -> MechaPaintPicker(store, view, state, purchasing = true)
            ShopCategory.LAUNCHER -> MechaLauncher(store, view, state, purchasing = true)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PartShop(store: MechaStore, view: MechaView, state: MechaState) {
    var slot by rememberSaveable { mutableStateOf(MechaSlot.HEAD) }
    var family by rememberSaveable { mutableStateOf<String?>(null) }
    var ownership by rememberSaveable { mutableStateOf(PartOwnership.ALL) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var familySets by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable(slot, family, ownership) { mutableStateOf(0) }
    val choices = remember(view.parts, state.owned, slot, family, ownership) {
        view.parts.filter {
            it.slot == slot && (family == null || it.family == family) && when (ownership) {
                PartOwnership.ALL -> true
                PartOwnership.OWNED -> it.id in state.owned
                PartOwnership.UNOWNED -> it.id !in state.owned
            }
        }.sortedWith(partOrder)
    }
    val pageCount = (choices.size + 5) / 6
    val currentPage = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    val part = choices.firstOrNull { it.id == selectedId } ?: choices.firstOrNull()
    val owned = part?.id in state.owned
    var confirmingId by remember { mutableStateOf<String?>(null) }
    val anchor = remember { FocusRequester() }
    RestoreShopFocus(confirmingId != null, anchor)
    HangarPreviewBack(selectedId != null) { selectedId = null }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MechaChip("Individual parts", !familySets, { familySets = false })
        MechaChip("Family sets", familySets, { familySets = true })
    }
    val build = remember(view.parts, state.equipped, part, familySets) {
        if (familySets && part != null) {
            view.parts.filter { it.family == part.family && it.variant == part.variant }.associate { it.slot to it.id }
        } else {
            state.equipped + listOfNotNull(part?.let { slot to it.id })
        }
    }
    ShopLayout(
        preview = {
            Preview(view, build, state.equippedPaints, state.motion)
            if (part != null) {
                Text(part.name, style = MaterialTheme.typography.titleMedium)
                Note("${familyName(part.family)} ${part.variant} · ${slot.label()} · $MECHA_PART_PRICE credits")
                if (familySets) {
                    Note(
                        "Whole ${familyName(part.family)} ${part.variant} set preview. Buying adds only the selected ${slot.label().lowercase()} part."
                    )
                }
                MechaButton(if (owned) "Part owned" else "Buy part · $MECHA_PART_PRICE credits", {
                    confirmingId = part.id
                }, enabled = !view.busy && !owned && state.salvage >= MECHA_PART_PRICE, primary = !owned)
                if (owned) Note("Apply owned parts in Workshop → Bot → Assembly.")
                if (!owned && state.salvage < MECHA_PART_PRICE) Note("Need ${MECHA_PART_PRICE - state.salvage} more credits.")
            } else {
                Note("No parts match these filters.")
            }
        },
        choices = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MechaSlot.entries.forEach { s ->
                    MechaChip(s.label(), s == slot, {
                        slot = s
                        selectedId = null
                    }, if (s == slot) Modifier.focusRequester(anchor) else Modifier)
                }
            }
            FamilyFilter(family) {
                family = it
                selectedId = null
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PartOwnership.entries.forEach { filter ->
                    MechaChip(filter.label, ownership == filter, {
                        ownership = filter
                        selectedId = null
                    })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                choices.drop(currentPage * 6).take(6).forEach { choice ->
                    MechaChip(
                        choice.name,
                        choice.id == part?.id,
                        { selectedId = choice.id },
                        Modifier.semantics {
                            contentDescription = "Preview ${choice.name}, ${if (choice.id in state.owned) "owned" else "$MECHA_PART_PRICE credits"}"
                        },
                        dim = choice.id !in state.owned
                    )
                }
            }
            if (pageCount > 1) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MechaButton("Previous parts", {
                        page = currentPage - 1
                        selectedId = choices[page * 6].id
                    }, enabled = currentPage > 0)
                    MechaButton("Next parts", {
                        page = currentPage + 1
                        selectedId = choices[page * 6].id
                    }, enabled = currentPage + 1 < pageCount)
                }
                Note("Parts page ${currentPage + 1} of $pageCount")
            }
        }
    )
    val purchase = view.parts.firstOrNull { it.id == confirmingId }
    if (purchase != null) {
        PurchaseConfirmation(
            "Buy ${purchase.name}?",
            "$MECHA_PART_PRICE credits. Adds this part to your hangar; does not equip it.",
            "Buy part",
            view,
            purchase.id !in state.owned && state.salvage >= MECHA_PART_PRICE,
            { store.buyPart(purchase.id) },
            { confirmingId = null }
        )
    }
}

@Composable
internal fun FamilyFilter(selected: String?, onSelect: (String?) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MechaChip("All families", selected == null, { onSelect(null) })
        MECHA_FAMILIES.forEach { family -> MechaChip(familyName(family), selected == family, { onSelect(family) }) }
    }
}

/** Cancel is the safe landing point, including a controller connected after touch. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PurchaseConfirmation(
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
        Surface(shape = RoundedCornerShape(16.dp), color = PanelColour, modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Note(detail)
                    Text("Balance: ${view.state?.salvage ?: 0} credits", style = MaterialTheme.typography.titleSmall, color = Accent)
                    if (submitted) {
                        if (view.busy) {
                            Note("Saving… Wait for the saved result before trying another action.")
                        } else {
                            Text(
                                view.error ?: view.message.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (view.error != null) Warning else Accent
                            )
                            result()
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MechaButton(if (submitted) "Close receipt" else "Cancel", onClose, modifier = Modifier.focusRequester(safe))
                    MechaButton(action, {
                        if (!submitted && !view.busy && allowed) {
                            submitted = true
                            onConfirm()
                            if (input.inputMode == InputMode.Keyboard) safe.requestFocus()
                        }
                    }, enabled = !submitted && !view.busy && allowed, primary = true)
                }
                LaunchedEffect(input.inputMode) {
                    if (input.inputMode == InputMode.Keyboard) safe.requestFocus()
                }
            }
        }
    }
}

@Composable
internal fun Preview(view: MechaView, equipped: Map<MechaSlot, String>, paints: Map<MechaSlot, MechaPaint>, motion: Boolean) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CanvasColour).padding(8.dp), contentAlignment = Alignment.Center) {
        MechaFigure(view.parts, equipped, MechaPose.IDLE, motion = motion, target = 128.dp, paints = paints)
    }
}

/** Actions precede long catalogs. Narrow windows stack without shrinking labels. */
@Composable
internal fun ShopLayout(preview: @Composable ColumnScope.() -> Unit, choices: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 520.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(0.42f), verticalArrangement = Arrangement.spacedBy(10.dp), content = preview)
                Column(Modifier.weight(0.58f), verticalArrangement = Arrangement.spacedBy(10.dp), content = choices)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                preview()
                choices()
            }
        }
    }
}

@Composable
internal fun ShopColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(top = 10.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

@Composable
internal fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Muted)
}

@Composable
internal fun RestoreShopFocus(showing: Boolean, anchor: FocusRequester) {
    var wasShowing by remember { mutableStateOf(false) }
    val input = LocalInputModeManager.current
    LaunchedEffect(showing) {
        if (wasShowing && !showing && input.inputMode == InputMode.Keyboard) anchor.requestFocus()
        wasShowing = showing
    }
}
