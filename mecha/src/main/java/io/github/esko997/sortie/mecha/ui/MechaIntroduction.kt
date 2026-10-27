package io.github.esko997.sortie.mecha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.esko997.sortie.mecha.MechaStore
import io.github.esko997.sortie.mecha.MechaView
import io.github.esko997.sortie.ui.Accent
import io.github.esko997.sortie.ui.CanvasColour
import io.github.esko997.sortie.ui.PanelColour

/** Acknowledgement and optional hiding are one saved action; opening Help never enables the companion. */
@Composable
internal fun MechaIntroduction(store: MechaStore, view: MechaView, onComplete: (hidden: Boolean) -> Unit) {
    var page by rememberSaveable { mutableStateOf(0) }
    var pendingHide by remember { mutableStateOf<Boolean?>(null) }
    var attempted by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val complete: (Boolean) -> Unit = { hide ->
        if (pendingHide == null && !store.view.value.busy) {
            pendingHide = hide
            attempted = true
            store.completeIntroduction(hide)
        }
    }
    LaunchedEffect(view, pendingHide) {
        val hide = pendingHide ?: return@LaunchedEffect
        // Read the current store value: submission marks busy synchronously, before composition catches up.
        val current = store.view.value
        if (current.busy) return@LaunchedEffect
        if (current.error != null) {
            pendingHide = null
        } else if (current.state?.hangarIntroSeen == true && (!hide || current.state?.enabled == false)) {
            onComplete(hide)
        }
    }
    val enabled = pendingHide == null && !view.busy
    Dialog(
        onDismissRequest = { complete(false) },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = PanelColour,
            modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(0.94f).fillMaxHeight(0.9f)
        ) {
            BoxWithConstraints(Modifier.padding(16.dp)) {
                val card: @Composable () -> Unit = {
                    IntroductionCard(page, view)
                }
                val controls: @Composable () -> Unit = {
                    IntroductionControls(
                        page = page,
                        enabled = enabled,
                        saving = pendingHide != null || view.busy,
                        error = view.error.takeIf { attempted },
                        first = first,
                        onPage = { page = it },
                        onComplete = complete
                    )
                }
                if (maxWidth >= 560.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(Modifier.weight(0.42f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { card() }
                        Column(Modifier.weight(0.58f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { controls() }
                    }
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        card()
                        controls()
                    }
                }
            }
        }
    }
}

@Composable
private fun IntroductionCard(page: Int, view: MechaView) {
    Text("Companion guide · ${page + 1} of 3", style = MaterialTheme.typography.labelLarge, color = Accent)
    Text(
        when (page) {
            0 -> "A little company, if you want it"
            1 -> "Play games. Make it yours."
            else -> "Always your choice"
        },
        style = MaterialTheme.typography.titleLarge
    )
    view.state?.let { state ->
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CanvasColour).padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            MechaFigure(view.parts, state.equipped, MechaPose.IDLE, motion = false, target = 112.dp, finishes = state.equippedFinishes)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IntroductionControls(
    page: Int,
    enabled: Boolean,
    saving: Boolean,
    error: String?,
    first: FocusRequester,
    onPage: (Int) -> Unit,
    onComplete: (Boolean) -> Unit
) {
    Text(
        when (page) {
            0 ->
                "Your companion is optional. Mix parts, rename it, or just let it wander. " +
                    "Charge and condition never run down while you are away. There is no daily upkeep."
            1 ->
                "Completed game sessions earn 1 credit per 5 minutes, up to 120 minutes per session. " +
                    "Earning pauses at 50 credits; extra play is not banked. Spend on parts, finishes, themes or arcade games. " +
                    "Insert 1 credit, then choose one arcade game for one run. Arcade games never award credits or parts."
            else ->
                "Turn Animate companion off for still poses, or hide the companion altogether. " +
                    "Reopen this Hangar from Home → Extras; Help replays this guide. Your parts and credits stay saved. " +
                    "Applied themes stay active even when the companion is hidden; reader paper settings remain separate."
        },
        style = MaterialTheme.typography.bodyMedium
    )
    if (saving) Text("Saving…", style = MaterialTheme.typography.bodySmall, color = Muted)
    if (error != null) Text(error, style = MaterialTheme.typography.bodySmall, color = Warning)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MechaButton(
            if (page == 2) "Got it" else "Skip",
            onClick = { onComplete(false) },
            enabled = enabled,
            modifier = Modifier.focusRequester(first)
        )
        if (page > 0) MechaButton("Previous", onClick = { onPage(page - 1) }, enabled = enabled)
        if (page < 2) MechaButton("Next", onClick = { onPage(page + 1) }, enabled = enabled, primary = true)
    }
    MechaButton("Hide companion", onClick = { onComplete(true) }, enabled = enabled)
    Text("Hide also finishes this guide. You can show the companion again in Workshop.", style = MaterialTheme.typography.bodySmall, color = Muted)
    // Run in the controls' subcomposition, after its focus target is attached.
    LaunchedEffect(enabled) { if (enabled) first.requestFocus() }
}
