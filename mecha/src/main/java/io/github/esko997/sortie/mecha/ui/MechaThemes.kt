package io.github.esko997.sortie.mecha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp
import io.github.esko997.sortie.mecha.MechaState
import io.github.esko997.sortie.mecha.MechaStore
import io.github.esko997.sortie.mecha.MechaView
import io.github.esko997.sortie.ui.Accent
import io.github.esko997.sortie.ui.CanvasColour
import io.github.esko997.sortie.ui.PanelColour
import io.github.esko997.sortie.ui.SortieButtonStyle
import io.github.esko997.sortie.ui.SortieButtonStylePreview
import io.github.esko997.sortie.ui.SortieFolderIconPreview
import io.github.esko997.sortie.ui.SortieFolderIcons
import io.github.esko997.sortie.ui.SortiePalette
import io.github.esko997.sortie.ui.SortieTheme

private enum class LauncherCategory(val label: String, val item: String) {
    THEMES("Themes", "theme"),
    FOLDERS("Folders", "folder icons"),
    BUTTONS("Buttons", "button style")
}

/** A local visual sandbox: ownership is bought in Shop, committed appearance applied in Workshop. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MechaLauncher(store: MechaStore, view: MechaView, state: MechaState, purchasing: Boolean) {
    var category by rememberSaveable { mutableStateOf(LauncherCategory.THEMES) }
    var palette by rememberSaveable(state.selectedTheme) { mutableStateOf(SortiePalette.entries.first { it.id == state.selectedTheme }) }
    var folders by rememberSaveable(state.selectedFolderIcons) { mutableStateOf(SortieFolderIcons.entries.first { it.id == state.selectedFolderIcons }) }
    var buttons by rememberSaveable(state.selectedButtonStyle) { mutableStateOf(SortieButtonStyle.entries.first { it.id == state.selectedButtonStyle }) }
    var previewing by rememberSaveable { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    val anchor = remember { FocusRequester() }
    val input = LocalInputModeManager.current
    val cancel: () -> Unit = {
        palette = SortiePalette.entries.first { it.id == state.selectedTheme }
        folders = SortieFolderIcons.entries.first { it.id == state.selectedFolderIcons }
        buttons = SortieButtonStyle.entries.first { it.id == state.selectedButtonStyle }
        previewing = false
        if (input.inputMode == InputMode.Keyboard) anchor.requestFocus()
    }
    HangarPreviewBack(previewing, cancel)
    RestoreShopFocus(confirming, anchor)
    val label = when (category) {
        LauncherCategory.THEMES -> palette.label
        LauncherCategory.FOLDERS -> folders.label
        LauncherCategory.BUTTONS -> buttons.label
    }
    val price = when (category) {
        LauncherCategory.THEMES -> palette.price
        LauncherCategory.FOLDERS -> folders.price
        LauncherCategory.BUTTONS -> buttons.price
    }
    val owned = when (category) {
        LauncherCategory.THEMES -> palette.id in state.ownedThemes
        LauncherCategory.FOLDERS -> folders.id in state.ownedFolderIcons
        LauncherCategory.BUTTONS -> buttons.id in state.ownedButtonStyles
    }
    val description = when (category) {
        LauncherCategory.THEMES -> palette.description
        LauncherCategory.FOLDERS -> folders.description
        LauncherCategory.BUTTONS -> buttons.description
    }
    Column(Modifier.padding(top = 10.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LauncherCategory.entries.forEach { choice ->
                MechaChip(choice.label, category == choice, {
                    cancel()
                    category = choice
                }, if (category == choice) Modifier.focusRequester(anchor) else Modifier)
            }
        }
        key(category) {
            ShopColumn {
                ShopLayout(
                    preview = {
                        when (category) {
                            LauncherCategory.THEMES -> ThemePreview(palette)
                            LauncherCategory.FOLDERS -> SortieFolderIconPreview(folders)
                            LauncherCategory.BUTTONS -> SortieButtonStylePreview(buttons)
                        }
                        Text("$label · ${if (previewing) "preview only" else "saved appearance"}", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            MechaButton(if (purchasing) "Cancel preview" else "Cancel", cancel, enabled = previewing)
                            if (purchasing) {
                                MechaButton(if (owned) "Owned" else "Buy ${category.item} · $price credits", {
                                    confirming = true
                                }, enabled = !view.busy && !owned && state.salvage >= price, primary = !owned)
                            } else {
                                MechaButton("Apply ${category.item}", {
                                    if (!store.view.value.busy && owned && previewing) {
                                        when (category) {
                                            LauncherCategory.THEMES -> store.setTheme(palette)
                                            LauncherCategory.FOLDERS -> store.setFolderIcons(folders)
                                            LauncherCategory.BUTTONS -> store.setButtonStyle(buttons)
                                        }
                                        previewing = false
                                        if (input.inputMode == InputMode.Keyboard) anchor.requestFocus()
                                    }
                                }, enabled = !view.busy && owned && previewing, primary = true)
                            }
                        }
                    },
                    choices = {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            when (category) {
                                LauncherCategory.THEMES -> SortiePalette.entries.filter { purchasing || it.id in state.ownedThemes }.forEach { choice ->
                                    MechaChip(choice.label, choice == palette, {
                                        palette = choice
                                        previewing = true
                                    })
                                }
                                LauncherCategory.FOLDERS -> SortieFolderIcons.entries.filter {
                                    purchasing || it.id in state.ownedFolderIcons
                                }.forEach { choice ->
                                    MechaChip(choice.label, choice == folders, {
                                        folders = choice
                                        previewing = true
                                    })
                                }
                                LauncherCategory.BUTTONS -> SortieButtonStyle.entries.filter {
                                    purchasing || it.id in state.ownedButtonStyles
                                }.forEach { choice ->
                                    MechaChip(choice.label, choice == buttons, {
                                        buttons = choice
                                        previewing = true
                                    })
                                }
                            }
                        }
                        Text(description, style = MaterialTheme.typography.bodyMedium)
                        Note(
                            if (purchasing) {
                                "$price credits once. Buying adds ownership only; apply it in Workshop → Launcher."
                            } else {
                                "Owned items only. Preview freely, then Apply or Cancel. Find more in Shop → Launcher."
                            }
                        )
                        if (purchasing && !owned && state.salvage < price) Note("Need ${price - state.salvage} more credits.")
                        Note("Saved launcher appearance stays active even when the bot is hidden. Reader paper and custom folder artwork stay independent.")
                    }
                )
            }
        }
    }
    if (confirming) {
        PurchaseConfirmation(
            "Buy $label?",
            "$price credits once. Adds this ${category.item} to your collection; does not apply it.",
            "Buy ${category.item}",
            view,
            !owned && state.salvage >= price,
            {
                when (category) {
                    LauncherCategory.THEMES -> store.buyTheme(palette)
                    LauncherCategory.FOLDERS -> store.buyFolderIcons(folders)
                    LauncherCategory.BUTTONS -> store.buyButtonStyle(buttons)
                }
            },
            { confirming = false }
        )
    }
}

@Composable
private fun ThemePreview(palette: SortiePalette) {
    SortieTheme(palette = palette) {
        Surface(shape = RoundedCornerShape(12.dp), color = CanvasColour, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${palette.label} · preview only", style = MaterialTheme.typography.labelLarge, color = Accent)
                Text("Your library", style = MaterialTheme.typography.titleLarge)
                Surface(shape = RoundedCornerShape(8.dp), color = PanelColour) {
                    Text("Games · Favourites · Recently played", style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(12.dp))
                }
                Text(
                    "Selected item",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.background(Accent, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
}
