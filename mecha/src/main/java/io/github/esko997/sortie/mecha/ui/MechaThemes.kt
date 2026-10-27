package io.github.esko997.sortie.mecha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.esko997.sortie.mecha.MechaState
import io.github.esko997.sortie.mecha.MechaStore
import io.github.esko997.sortie.mecha.MechaView
import io.github.esko997.sortie.ui.Accent
import io.github.esko997.sortie.ui.CanvasColour
import io.github.esko997.sortie.ui.PanelColour
import io.github.esko997.sortie.ui.SortiePalette
import io.github.esko997.sortie.ui.SortieTheme

/** The nested preview cannot change the theme above it. Buying never selects a scheme. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MechaThemes(store: MechaStore, view: MechaView, state: MechaState) {
    var palette by rememberSaveable { mutableStateOf(SortiePalette.entries.first { it.id == state.selectedTheme }) }
    var confirming by remember { mutableStateOf(false) }
    var wasConfirming by remember { mutableStateOf(false) }
    val anchor = remember { FocusRequester() }
    val input = LocalInputModeManager.current
    val owned = palette.id in state.ownedThemes
    val applied = palette.id == state.selectedTheme
    LaunchedEffect(confirming) {
        if (wasConfirming && !confirming && input.inputMode == InputMode.Keyboard) anchor.requestFocus()
        wasConfirming = confirming
    }
    val choices: @Composable () -> Unit = {
        Text("Themes", style = MaterialTheme.typography.titleMedium)
        Text("${state.salvage} credits", style = MaterialTheme.typography.titleSmall, color = Accent)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SortiePalette.entries.forEach { choice ->
                MechaChip(
                    choice.label,
                    choice == palette,
                    { palette = choice },
                    modifier = (if (choice == palette) Modifier.focusRequester(anchor) else Modifier).semantics {
                        contentDescription = "Preview ${choice.label}, ${if (choice.id in state.ownedThemes) "owned" else "${choice.price} credits"}"
                    }
                )
            }
        }
        Text(palette.description, style = MaterialTheme.typography.bodyMedium)
        Text(
            if (palette == SortiePalette.DEFAULT) "The original scheme is always free." else "${palette.price} credits to own. Apply it any time for free.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MechaButton(
                if (owned) "Owned" else "Buy · ${palette.price} credits…",
                onClick = { if (!store.view.value.busy) confirming = true },
                enabled = !view.busy && !owned && state.salvage >= palette.price
            )
            MechaButton(
                if (applied) "Applied" else "Apply theme",
                onClick = {
                    if (!store.view.value.busy) store.setTheme(palette)
                    if (input.inputMode == InputMode.Keyboard) anchor.requestFocus()
                },
                enabled = !view.busy && owned && !applied,
                primary = owned && !applied
            )
        }
        if (!owned && state.salvage < palette.price) {
            Text("Need ${palette.price - state.salvage} more credits.", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Text(
            "Previewing is free. Buy and Apply are separate. The whole UI shell changes only after Apply is saved, " +
                "even with the companion hidden. Reader paper settings and artwork-derived accents stay independent.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted
        )
    }
    BoxWithConstraints(Modifier.padding(top = 10.dp).fillMaxWidth()) {
        if (maxWidth >= 560.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(Modifier.weight(0.48f).verticalScroll(rememberScrollState())) { ThemePreview(palette) }
                Column(Modifier.weight(0.52f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { choices() }
            }
        } else {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ThemePreview(palette)
                choices()
            }
        }
    }
    if (confirming) ThemePurchaseConfirmation(store, view, palette, onClose = { confirming = false })
}

@Composable
private fun ThemePreview(palette: SortiePalette) {
    SortieTheme(palette = palette) {
        Surface(shape = RoundedCornerShape(12.dp), color = CanvasColour, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("${palette.label} · preview only", style = MaterialTheme.typography.labelLarge, color = Accent)
                Text("Make yourself at home", style = MaterialTheme.typography.titleLarge)
                Text("Your library, in a different light.", style = MaterialTheme.typography.bodyMedium)
                Surface(shape = RoundedCornerShape(8.dp), color = PanelColour) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Library", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Games · Favourites · Recently played",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    "Selected item",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.background(Accent, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp)
                )
                Text(
                    "This panel is a free colour preview, not a change to your saved theme.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemePurchaseConfirmation(store: MechaStore, view: MechaView, palette: SortiePalette, onClose: () -> Unit) {
    var submitted by remember { mutableStateOf(false) }
    val cancel = remember { FocusRequester() }
    val state = view.state
    val owned = state != null && palette.id in state.ownedThemes
    val allowed = !submitted && !view.busy && !owned && state != null && state.salvage >= palette.price
    Dialog(onDismissRequest = { if (!store.view.value.busy) onClose() }) {
        Surface(shape = RoundedCornerShape(16.dp), color = PanelColour, modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Buy ${palette.label}?", style = MaterialTheme.typography.titleLarge)
                Text("${palette.price} credits once. Adds this theme to your collection; does not apply it.", style = MaterialTheme.typography.bodyMedium)
                Text("Balance: ${state?.salvage ?: 0} credits", style = MaterialTheme.typography.titleSmall, color = Accent)
                if (submitted) {
                    Text(
                        when {
                            view.busy -> "Saving…"
                            view.error != null -> view.error.orEmpty()
                            owned -> "Theme owned. Close this receipt, then choose Apply theme when you want to use it."
                            else -> "Waiting for the saved purchase…"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (view.error != null) Warning else Muted
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MechaButton(
                        if (submitted) "Close receipt" else "Cancel",
                        onClick = { if (!store.view.value.busy) onClose() },
                        enabled = !view.busy,
                        modifier = Modifier.focusRequester(cancel)
                    )
                    MechaButton(
                        "Buy theme",
                        onClick = {
                            if (!submitted && allowed && !store.view.value.busy) {
                                submitted = true
                                store.buyTheme(palette)
                            }
                        },
                        enabled = allowed,
                        primary = true
                    )
                }
            }
        }
        LaunchedEffect(view.busy) { if (!view.busy) cancel.requestFocus() }
    }
}
