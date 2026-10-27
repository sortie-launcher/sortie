package io.github.esko997.sortie.mecha.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.esko997.sortie.mecha.MechaSlotStake
import io.github.esko997.sortie.mecha.MechaSpinOutcome
import io.github.esko997.sortie.mecha.MechaSpinReceipt
import io.github.esko997.sortie.mecha.MechaState
import io.github.esko997.sortie.mecha.MechaStore
import io.github.esko997.sortie.mecha.MechaView
import io.github.esko997.sortie.mecha.slotEntryProblem
import io.github.esko997.sortie.ui.Accent
import io.github.esko997.sortie.ui.CanvasColour
import io.github.esko997.sortie.ui.PanelColour
import kotlinx.coroutines.delay

/** Wagers award credits only. The saved wallet/receipt drives the reveal, never the animation. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MechaSlotMachine(store: MechaStore, view: MechaView, state: MechaState) {
    var stake by rememberSaveable { mutableStateOf(MechaSlotStake.ONE) }
    var confirming by remember { mutableStateOf(false) }
    val anchor = remember { FocusRequester() }
    RestoreShopFocus(confirming, anchor)
    val problem = state.slotEntryProblem(stake, state.cabinetSpins)
    ShopColumn {
        ShopLayout(
            preview = {
                SlotMachineFace(state.lastSpin, state.salvage)
                state.lastSpin?.let { SlotReceipt(it) }
                state.legacyLastSpin?.let {
                    Note("Historical item-prize spin #${it.sequence}. This older cabinet receipt is not a credit-slot payout.")
                    Note(
                        "Old result: ${it.outcome.name.lowercase()}; returned ${it.returnedCredits} credits." +
                            (it.partId?.let { id -> " Part: ${view.parts.firstOrNull { part -> part.id == id }?.name ?: id}." } ?: "") +
                            (it.finish?.let { finish -> " Finish: ${finish.label}." } ?: "")
                    )
                }
            },
            choices = {
                Text("Slot machine", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MechaSlotStake.entries.forEach { option ->
                        MechaChip(
                            option.label,
                            option == stake,
                            { stake = option },
                            if (option == stake) Modifier.focusRequester(anchor) else Modifier
                        )
                    }
                }
                Text("Payouts for ${stake.label}", style = MaterialTheme.typography.titleSmall, color = Accent)
                MechaSpinOutcome.entries.forEach { outcome ->
                    val payout = outcome.multiplier * stake.stake
                    val net = payout - stake.stake
                    Note("${outcome.percent}% ${outcome.label} · ${outcome.multiplier}× = $payout returned · net ${if (net > 0) "+" else ""}$net")
                }
                Note("Same odds at every stake. Returns include your stake; average return is 85% of credits wagered.")
                MechaButton(
                    "Spin · ${stake.label}…",
                    onClick = { confirming = true },
                    enabled = !view.busy && problem == null,
                    primary = true
                )
                if (view.busy) Note("Saving the current action…") else problem?.let { Note(it) }
                Note("Each spin is independent. Three reels show one saved outcome; there is no stop button or timing advantage.")
            }
        )
    }
    if (confirming) {
        val sequence = remember { state.cabinetSpins }
        val confirmedStake = remember { stake }
        PurchaseConfirmation(
            title = "Spin ${confirmedStake.label}?",
            detail = "Stake ${confirmedStake.label}. 5% pays ${5 * confirmedStake.stake}, " +
                "20% pays ${2 * confirmedStake.stake}, 20% returns ${confirmedStake.stake}, 55% pays 0 credits. One spin only.",
            action = "Spin once",
            view = view,
            allowed = state.slotEntryProblem(confirmedStake, sequence) == null,
            onConfirm = { store.spin(confirmedStake, sequence) },
            onClose = { confirming = false },
            result = {
                state.lastSpin?.takeIf { it.sequence.toLong() == sequence.toLong() + 1 }?.let {
                    SlotMachineFace(it, state.salvage, flourish = state.motion)
                    SlotReceipt(it)
                }
            }
        )
    }
}

/** Descriptive only: reopening and dismissing a receipt cannot repeat its payout. */
@Composable
private fun SlotReceipt(receipt: MechaSpinReceipt) {
    Column(
        Modifier.fillMaxWidth().background(CanvasColour, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Saved spin #${receipt.sequence} · ${receipt.outcome.label}", style = MaterialTheme.typography.titleSmall, color = Accent)
        Note(
            "Staked ${receipt.stake.stake}; returned ${receipt.returnedCredits}; " +
                "net ${receipt.returnedCredits - receipt.stake.stake} credits. Balance after spin: ${receipt.balanceAfter}."
        )
        Note("Already saved; viewing this receipt grants nothing again.")
    }
}

/** Original nine-pixel workshop stamps, in the persisted reel-symbol order. */
private val reelSymbols = arrayOf(
    ReelSymbol("Bolt", intArrayOf(56, 124, 124, 56, 56, 56, 56, 124, 124)),
    ReelSymbol("Gear", intArrayOf(56, 186, 511, 238, 198, 238, 511, 186, 56)),
    ReelSymbol("Star", intArrayOf(16, 56, 56, 511, 254, 124, 238, 198, 130)),
    ReelSymbol("Cell", intArrayOf(56, 124, 68, 84, 124, 84, 68, 124, 0)),
    ReelSymbol("Chip", intArrayOf(170, 254, 130, 511, 146, 511, 130, 254, 170))
)

private class ReelSymbol(val label: String, val rows: IntArray)

/** Only a newly committed confirmation requests a flourish; saved receipts elsewhere are static. */
@Composable
internal fun SlotMachineFace(receipt: MechaSpinReceipt?, balance: Int, flourish: Boolean = false) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var visible by remember { mutableStateOf(false) }
    var consumed by remember(receipt?.sequence) { mutableStateOf(false) }
    var step by remember(receipt?.sequence) { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(receipt?.sequence, flourish, foreground, windowFocused, visible) {
        if (!flourish || receipt == null || consumed || !foreground || !windowFocused || !visible) return@LaunchedEffect
        consumed = true
        try {
            for (frame in 8 downTo 1) {
                step = frame
                delay(65)
            }
        } finally {
            step = 0
        }
    }
    val accent = Accent
    val canvas = CanvasColour
    val panel = PanelColour
    val paper = MaterialTheme.colorScheme.onSurface
    val muted = Muted
    val reelDescription = if (receipt == null) {
        "Three slot machine reels, ready to spin"
    } else {
        "Saved reels ${receipt.reels.joinToString { reelSymbols[it].label }}"
    }
    Column(
        Modifier.fillMaxWidth()
            .border(3.dp, accent, RoundedCornerShape(16.dp))
            .background(panel, RoundedCornerShape(16.dp))
            .padding(12.dp)
            .onGloballyPositioned {
                val bounds = it.boundsInWindow()
                visible = bounds.width > 0f && bounds.height > 0f
            },
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Rivet slot machine", style = MaterialTheme.typography.titleMedium, color = accent, fontWeight = FontWeight.Bold)
        Row(
            Modifier.fillMaxWidth().background(canvas, RoundedCornerShape(8.dp)).padding(6.dp)
                .clearAndSetSemantics { contentDescription = reelDescription },
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            repeat(3) { reel ->
                val saved = receipt?.reels?.get(reel) ?: (reel * 2)
                val symbol = reelSymbols[if (step == 0) saved else (saved + step + reel) % reelSymbols.size]
                Column(
                    Modifier.weight(1f).background(paper, RoundedCornerShape(3.dp)).padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Canvas(Modifier.size(42.dp)) {
                        val pixel = (minOf(size.width, size.height) / 9f).toInt().coerceAtLeast(1).toFloat()
                        val left = (size.width - pixel * 9) / 2f
                        val top = (size.height - pixel * 9) / 2f
                        for (y in 0 until 9) {
                            for (x in 0 until 9) {
                                if (symbol.rows[y] and (1 shl (8 - x)) != 0) {
                                    drawRect(canvas, Offset(left + x * pixel, top + y * pixel), Size(pixel, pixel))
                                }
                            }
                        }
                    }
                    Text(symbol.label, style = MaterialTheme.typography.labelSmall, color = canvas)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().border(1.dp, accent, RoundedCornerShape(5.dp)).background(canvas).padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("Credits", style = MaterialTheme.typography.labelSmall, color = muted)
                Text(balance.toString(), style = MaterialTheme.typography.titleLarge, color = accent)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Last payout", style = MaterialTheme.typography.labelSmall, color = muted)
                Text(receipt?.returnedCredits?.toString() ?: "—", style = MaterialTheme.typography.titleLarge, color = accent)
            }
        }
        Text(
            if (receipt == null) "Choose a stake, then Spin." else "Saved result · spin #${receipt.sequence}",
            style = MaterialTheme.typography.bodySmall,
            color = muted
        )
    }
}
