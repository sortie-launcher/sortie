package io.github.esko997.sortie.mecha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp
import io.github.esko997.sortie.mecha.MechaFinish
import io.github.esko997.sortie.mecha.MechaPaint
import io.github.esko997.sortie.mecha.MechaPaintScheme
import io.github.esko997.sortie.mecha.MechaSlot
import io.github.esko997.sortie.mecha.MechaState
import io.github.esko997.sortie.mecha.MechaStore
import io.github.esko997.sortie.mecha.MechaView

/** Only this selected figure animates; scheme swatches and inventory choices are static. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MechaPaintPicker(store: MechaStore, view: MechaView, state: MechaState, purchasing: Boolean) {
    var scheme by rememberSaveable { mutableStateOf(MechaPaintScheme.ORIGINAL) }
    var finish by rememberSaveable { mutableStateOf(MechaFinish.PLAIN) }
    var slot by rememberSaveable { mutableStateOf<MechaSlot?>(null) }
    var previewing by rememberSaveable { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    val paint = MechaPaint(scheme, finish)
    val owned = paint in state.ownedPaints
    val anchor = remember { FocusRequester() }
    val input = LocalInputModeManager.current
    val cancel: () -> Unit = {
        previewing = false
        if (input.inputMode == InputMode.Keyboard) anchor.requestFocus()
    }
    HangarPreviewBack(previewing, cancel)
    RestoreShopFocus(confirming, anchor)
    val previewPaints = remember(state.equippedPaints, paint, slot, previewing) {
        if (!previewing) {
            state.equippedPaints
        } else {
            state.equippedPaints +
                (slot?.let { listOf(it) } ?: MechaSlot.entries).associateWith { paint }
        }
    }
    ShopColumn {
        ShopLayout(
            preview = {
                Preview(view, state.equipped, previewPaints, state.motion)
                Text(if (previewing) "${paint.label} · preview" else "Saved paint", style = MaterialTheme.typography.titleMedium)
                Note(if (slot == null) "Whole bot · all four slots" else "${slot!!.label()} only")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MechaButton(if (purchasing) "Cancel preview" else "Cancel", cancel, enabled = previewing)
                    if (purchasing) {
                        MechaButton(
                            if (owned) "Paint owned" else "Buy paint · ${paint.price} credits",
                            { confirming = true },
                            enabled = !view.busy && !owned && state.salvage >= paint.price,
                            primary = !owned
                        )
                    } else {
                        MechaButton("Apply paint", {
                            if (!store.view.value.busy && owned && previewing) {
                                store.applyPaint(paint, slot)
                                previewing = false
                                if (input.inputMode == InputMode.Keyboard) anchor.requestFocus()
                            }
                        }, enabled = !view.busy && owned && previewing, primary = true)
                    }
                }
                if (purchasing) {
                    Note("Each scheme edition is a separate reusable purchase. Buying never applies it.")
                    if (!owned && state.salvage < paint.price) Note("Need ${paint.price - state.salvage} more credits.")
                } else {
                    Note("Owned editions only. Find more in Shop → Paint. Applying costs no credits.")
                }
            },
            choices = {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MechaChip("Whole bot", slot == null, { slot = null }, Modifier.focusRequester(anchor))
                    MechaSlot.entries.forEach { choice -> MechaChip(choice.label(), slot == choice, { slot = choice }) }
                }
                Text("Two-tone schemes", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MechaPaintScheme.entries.filter { candidate -> purchasing || state.ownedPaints.any { it.scheme == candidate } }.forEach { choice ->
                        MechaChip(choice.label, scheme == choice, {
                            scheme = choice
                            if (!purchasing && MechaPaint(choice, finish) !in state.ownedPaints) {
                                finish = MechaFinish.entries.first { MechaPaint(choice, it) in state.ownedPaints }
                            }
                            previewing = true
                        })
                    }
                }
                if (scheme != MechaPaintScheme.ORIGINAL) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(24.dp).background(Color(scheme.primary)))
                        Box(Modifier.size(24.dp).background(Color(scheme.secondary)))
                        Text("Primary / secondary", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Note("Original keeps each part's authored colours.")
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MechaFinish.entries.filter { purchasing || MechaPaint(scheme, it) in state.ownedPaints }.forEach { edition ->
                        val choice = MechaPaint(scheme, edition)
                        val label = if (purchasing) {
                            "${edition.label} · ${if (choice.price == 0) "free" else "${choice.price} credits"}"
                        } else {
                            edition.label
                        }
                        MechaChip(label, finish == edition, {
                            finish = edition
                            previewing = true
                        })
                    }
                }
                Note("Standard keeps the two-tone colours flat; Alloy adds a metallic sweep; Holo adds a moving colour sheen. Motion off keeps a still finish.")
            }
        )
    }
    if (confirming) {
        PurchaseConfirmation(
            "Buy ${paint.label}?",
            "${paint.price} credits once. Reuse this scheme edition on any slot or the whole bot. Apply it later in Workshop.",
            "Buy paint",
            view,
            !owned && state.salvage >= paint.price,
            { store.buyPaint(paint) },
            { confirming = false }
        )
    }
}
