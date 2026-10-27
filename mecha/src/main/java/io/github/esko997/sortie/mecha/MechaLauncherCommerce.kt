package io.github.esko997.sortie.mecha

import io.github.esko997.sortie.ui.SortieButtonStyle
import io.github.esko997.sortie.ui.SortieFolderIcons

internal fun mechaLauncherCommerceProblem(state: MechaState): String? = when {
    SortieFolderIcons.DEFAULT.id !in state.ownedFolderIcons -> "its default folder icons are missing"
    state.ownedFolderIcons.any { SortieFolderIcons.fromId(it) == null } -> "it owns unknown folder icons"
    state.selectedFolderIcons !in state.ownedFolderIcons -> "its selected folder icons aren't owned"
    SortieButtonStyle.DEFAULT.id !in state.ownedButtonStyles -> "its default button style is missing"
    state.ownedButtonStyles.any { SortieButtonStyle.fromId(it) == null } -> "it owns an unknown button style"
    state.selectedButtonStyle !in state.ownedButtonStyles -> "its selected button style isn't owned"
    else -> null
}

internal fun MechaState.buyingFolderIcons(pack: SortieFolderIcons): MechaResult {
    if (pack.id in ownedFolderIcons) return MechaResult(this, error = "${pack.label} is already owned.")
    if (salvage < pack.price) return MechaResult(this, error = "${pack.label} costs ${pack.price} credits.")
    return MechaResult(
        upgraded().copy(salvage = salvage - pack.price, ownedFolderIcons = ownedFolderIcons + pack.id),
        message = "Bought ${pack.label}. Apply it whenever you like."
    )
}

internal fun MechaState.selectingFolderIcons(pack: SortieFolderIcons): MechaResult {
    if (pack.id !in ownedFolderIcons) return MechaResult(this, error = "${pack.label} isn't owned.")
    if (selectedFolderIcons == pack.id) return MechaResult(this)
    return MechaResult(upgraded().copy(selectedFolderIcons = pack.id), message = "Applied ${pack.label}.")
}

internal fun MechaState.buyingButtonStyle(style: SortieButtonStyle): MechaResult {
    if (style.id in ownedButtonStyles) return MechaResult(this, error = "${style.label} is already owned.")
    if (salvage < style.price) return MechaResult(this, error = "${style.label} costs ${style.price} credits.")
    return MechaResult(
        upgraded().copy(salvage = salvage - style.price, ownedButtonStyles = ownedButtonStyles + style.id),
        message = "Bought ${style.label}. Apply it whenever you like."
    )
}

internal fun MechaState.selectingButtonStyle(style: SortieButtonStyle): MechaResult {
    if (style.id !in ownedButtonStyles) return MechaResult(this, error = "${style.label} isn't owned.")
    if (selectedButtonStyle == style.id) return MechaResult(this)
    return MechaResult(upgraded().copy(selectedButtonStyle = style.id), message = "Applied ${style.label}.")
}
