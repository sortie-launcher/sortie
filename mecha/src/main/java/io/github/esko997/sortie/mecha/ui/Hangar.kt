package io.github.esko997.sortie.mecha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.esko997.sortie.mecha.MECHA_FAMILIES
import io.github.esko997.sortie.mecha.MECHA_NAME_MAX
import io.github.esko997.sortie.mecha.MECHA_STAT_MAX
import io.github.esko997.sortie.mecha.MechaCare
import io.github.esko997.sortie.mecha.MechaSlot
import io.github.esko997.sortie.mecha.MechaState
import io.github.esko997.sortie.mecha.MechaStore
import io.github.esko997.sortie.mecha.MechaView
import io.github.esko997.sortie.mecha.mechaNameProblem
import io.github.esko997.sortie.mecha.owns
import io.github.esko997.sortie.ui.Accent
import io.github.esko997.sortie.ui.CanvasColour
import io.github.esko997.sortie.ui.FocusRow
import io.github.esko997.sortie.ui.PanelColour
import kotlinx.coroutines.delay

private enum class HangarTab(val label: String) {
    WORKSHOP("Workshop"),
    SHOP("Shop"),
    SLOT_MACHINE("Slot machine"),
    ARCADE("Arcade")
}

/** How long a reaction (new part, care, rename) shows in the bay. */
private const val CHEER_MS = 2_400L

/** True for [CHEER_MS] after the mech found a part, was cared for or renamed (compared with the state it had before). */
@Composable
private fun rememberCheer(state: MechaState): Boolean {
    var cheering by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf(state) }
    LaunchedEffect(state) {
        val before = last
        last = state
        if (before === state) return@LaunchedEffect
        val reacted = state.owned.size > before.owned.size || state.bond > before.bond || state.name != before.name
        if (!reacted) return@LaunchedEffect
        cheering = true
        delay(CHEER_MS)
        cheering = false
    }
    return cheering
}

/** Shop buys; Workshop applies. Back discards local previews before navigating or closing. */
@Composable
fun Hangar(store: MechaStore, onClose: () -> Unit) {
    val view by store.view.collectAsState()
    var tab by rememberSaveable { mutableStateOf(HangarTab.WORKSHOP) }
    var introductionOpen by rememberSaveable { mutableStateOf(false) }
    var arcadeBackRequest by remember { mutableStateOf(0) }
    var workshopFocusRequest by remember { mutableStateOf(0) }
    val previewBack = remember { HangarPreviewBackStack() }
    val returnToWorkshop: () -> Unit = {
        tab = HangarTab.WORKSHOP
        // A followed immediately by B can coalesce to the already-composed tab. Focus restoration
        // is a navigation event, not merely a consequence of a different selected-tab value.
        workshopFocusRequest++
    }
    // Merely initializing the companion does not open its introduction.
    LaunchedEffect(view.state?.hangarIntroSeen) {
        if (view.state?.hangarIntroSeen == false) introductionOpen = true
    }
    LaunchedEffect(Unit) { store.refreshSessions() }
    CompositionLocalProvider(LocalHangarPreviewBack provides previewBack) {
        Dialog(
            onDismissRequest = {
                when {
                    previewBack.cancel() -> Unit
                    tab == HangarTab.ARCADE -> arcadeBackRequest++
                    tab != HangarTab.WORKSHOP -> returnToWorkshop()
                    else -> onClose()
                }
            },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = PanelColour,
                modifier = Modifier.widthIn(max = 880.dp).fillMaxWidth(0.96f).fillMaxHeight(0.94f)
            ) {
                // Focus targets stay attached while their page is not selected.
                val tabFocus = remember { HangarTab.entries.associateWith { FocusRequester() } }
                val closeFocus = remember { FocusRequester() }
                val first = if (view.state == null) closeFocus else tabFocus.getValue(tab)
                var focusedAny by remember { mutableStateOf(false) }
                val inputMode = LocalInputModeManager.current
                LaunchedEffect(inputMode.inputMode) {
                    // Do not steal the first directional move from an already-focused tab.
                    if (!introductionOpen && !focusedAny && inputMode.inputMode == InputMode.Keyboard) first.requestFocus()
                }
                Column(Modifier.padding(14.dp).onFocusChanged { if (it.hasFocus) focusedAny = true }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Hangar", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (view.state != null) MechaButton("Help", onClick = { introductionOpen = true })
                        MechaButton("Close", onClick = onClose, modifier = Modifier.focusRequester(closeFocus))
                    }
                    if (view.state != null) {
                        // One scrollable row preserves the play area on short landscape displays.
                        // Focused tabs are brought into view by the scroll container.
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 8.dp).fillMaxWidth().horizontalScroll(rememberScrollState())
                        ) {
                            HangarTab.entries.forEach { item ->
                                MechaChip(
                                    item.label,
                                    tab == item,
                                    {
                                        if (item == HangarTab.ARCADE && tab != item) arcadeBackRequest = 0
                                        tab = item
                                    },
                                    Modifier.focusRequester(tabFocus.getValue(item)).onFocusChanged { if (it.hasFocus) focusedAny = true }
                                )
                            }
                        }
                    }
                    LaunchedEffect(tab, introductionOpen, view.state != null, workshopFocusRequest) {
                        if (!introductionOpen && inputMode.inputMode == InputMode.Keyboard) first.requestFocus()
                    }
                    Outcome(view, showMessage = tab != HangarTab.ARCADE)
                    val state = view.state
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            state == null && view.error != null -> Damaged(store, view)
                            state == null -> Text(
                                "Opening the hangar…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Muted,
                                modifier = Modifier.padding(top = 12.dp)
                            )
                            tab == HangarTab.WORKSHOP -> Workshop(store, view, state, onHide = onClose)
                            tab == HangarTab.SHOP -> MechaShop(store, view, state)
                            tab == HangarTab.SLOT_MACHINE -> MechaSlotMachine(store, view, state)
                            tab == HangarTab.ARCADE -> MechaArcade(store, view, state, arcadeBackRequest, onBack = returnToWorkshop)
                        }
                    }
                }
            }
            if (introductionOpen && view.state != null) {
                MechaIntroduction(store, view) { hidden ->
                    introductionOpen = false
                    if (hidden) onClose()
                }
            }
        }
    }
}

/** The last action's outcome, or the store's error; both stay until the next action replaces them. */
@Composable
private fun Outcome(view: MechaView, showMessage: Boolean = true) {
    val error = view.error
    val message = view.message
    when {
        view.busy -> Text("Saving…", style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 6.dp))
        error != null && view.state != null -> Text(error, style = MaterialTheme.typography.bodySmall, color = Warning, modifier = Modifier.padding(top = 6.dp))
        showMessage && message != null -> Text(message, style = MaterialTheme.typography.bodySmall, color = Accent, modifier = Modifier.padding(top = 6.dp))
    }
}

/** The saved hangar can't be read. Nothing is replaced unless asked twice; the damaged file is kept beside the new one. */
@Composable
private fun Damaged(store: MechaStore, view: MechaView) {
    var confirming by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 12.dp).widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(view.error.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = Warning)
        if (!view.canStartFresh) {
            Text("Your saved hangar is not replaced. Try again after the storage or catalog problem is resolved.", style = MaterialTheme.typography.bodyMedium)
            MechaButton("Try again", onClick = { store.retryOpen() })
            return@Column
        }
        Text(
            "Sortie leaves the hangar file as it is. Starting fresh keeps the damaged file in the same folder and begins a new " +
                "hangar with the starter parts.",
            style = MaterialTheme.typography.bodyMedium
        )
        if (confirming) {
            Text("Start a new hangar? Your parts and credits from the damaged file won't carry over.", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MechaButton("Keep it", onClick = { confirming = false })
                MechaButton("Start fresh", onClick = { store.startFresh() }, primary = true)
            }
        } else {
            MechaButton("Start fresh…", onClick = { confirming = true })
        }
    }
}

private enum class WorkshopArea(val label: String) { BOT("Bot"), LAUNCHER("Launcher") }
private enum class BotArea(val label: String) { ASSEMBLY("Assembly"), PAINT("Paint"), CARE("Care") }

@Composable
private fun Workshop(store: MechaStore, view: MechaView, state: MechaState, onHide: () -> Unit) {
    var area by rememberSaveable { mutableStateOf(WorkshopArea.BOT) }
    var bot by rememberSaveable { mutableStateOf(BotArea.ASSEMBLY) }
    var draft by rememberSaveable(state.equipped, stateSaver = buildSaver) { mutableStateOf(state.equipped) }
    val assemblyFocus = remember { FocusRequester() }
    val input = LocalInputModeManager.current
    val resetDraft: () -> Unit = {
        draft = state.equipped
        if (input.inputMode == InputMode.Keyboard) assemblyFocus.requestFocus()
    }
    HangarPreviewBack(area == WorkshopArea.BOT && bot == BotArea.ASSEMBLY && draft != state.equipped, resetDraft)
    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WorkshopArea.entries.forEach { choice ->
                MechaChip(choice.label, area == choice, {
                    draft = state.equipped
                    area = choice
                })
            }
            if (area == WorkshopArea.BOT) {
                Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(24.dp).background(Muted))
                BotArea.entries.forEach { choice ->
                    MechaChip(
                        choice.label,
                        bot == choice,
                        {
                            draft = state.equipped
                            bot = choice
                        },
                        if (choice == BotArea.ASSEMBLY) Modifier.focusRequester(assemblyFocus) else Modifier
                    )
                }
            }
        }
        if (area == WorkshopArea.LAUNCHER) {
            MechaLauncher(store, view, state, purchasing = false)
        } else {
            if (bot == BotArea.PAINT) {
                MechaPaintPicker(store, view, state, purchasing = false)
            } else {
                val preview: @Composable (Boolean) -> Unit = { compact ->
                    Bay(store, view, state, draft, compact = compact, target = 112.dp)
                    if (bot == BotArea.ASSEMBLY) {
                        Text(if (draft == state.equipped) "Saved build" else "Preview · not fitted", style = MaterialTheme.typography.bodySmall, color = Muted)
                        AssemblyActions(
                            view.busy,
                            draft != state.equipped,
                            onFit = {
                                if (!store.view.value.busy) store.fitBuild(draft)
                                if (input.inputMode == InputMode.Keyboard) assemblyFocus.requestFocus()
                            },
                            onReset = resetDraft
                        )
                    }
                }
                val controls: @Composable () -> Unit = {
                    if (bot == BotArea.CARE) {
                        Care(store, state)
                        Settings(store, view, state, onHide)
                    } else {
                        Assembly(view, state, draft, onDraft = { draft = it })
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth >= 480.dp) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(
                                Modifier.weight(0.42f).verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) { preview(false) }
                            Column(
                                Modifier.weight(0.58f).verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) { controls() }
                        }
                    } else {
                        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            preview(true)
                            controls()
                        }
                    }
                }
            }
        }
    }
}

/**
 * The mech large on a dark bay floor and its name. [compact] (a narrow window, e.g. a handheld's landscape screen at a high
 * density): mech and name side by side, so the controls are reached sooner.
 */
@Composable
private fun Bay(store: MechaStore, view: MechaView, state: MechaState, draft: Map<MechaSlot, String>, compact: Boolean, target: Dp) {
    val cheer = rememberCheer(state)
    val pose = when {
        cheer && !state.sleeping -> MechaPose.CELEBRATE
        state.sleeping -> MechaPose.SLEEP
        else -> MechaPose.IDLE
    }
    val floor = Modifier.clip(RoundedCornerShape(12.dp)).background(CanvasColour).padding(10.dp)
    if (compact) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(floor) { MechaFigure(view.parts, draft, pose, state.motion && state.enabled, target = target, paints = state.equippedPaints) }
            Box(Modifier.weight(1f)) { Rename(store, state) }
        }
    } else {
        Box(Modifier.fillMaxWidth().then(floor), contentAlignment = Alignment.Center) {
            MechaFigure(view.parts, draft, pose, state.motion && state.enabled, target = target, paints = state.equippedPaints)
        }
        Rename(store, state)
    }
}

@Composable
private fun Rename(store: MechaStore, state: MechaState) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable(state.name) { mutableStateOf(state.name) }
    val problem = if (draft.trim() == state.name) null else mechaNameProblem(draft.trim())
    val field = remember { FocusRequester() }
    val action = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val inputMode = LocalInputModeManager.current
    val finish = {
        keyboard?.hide()
        focus.clearFocus()
        editing = false
        if (inputMode.inputMode == InputMode.Keyboard) action.requestFocus()
    }
    HangarPreviewBack(editing) {
        draft = state.name
        finish()
    }
    val save = {
        if (problem == null) {
            if (draft.trim() != state.name) store.rename(draft.trim())
            finish()
        }
    }
    LaunchedEffect(editing) {
        if (editing) field.requestFocus()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (editing) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(MECHA_NAME_MAX + 4) },
                    label = { Text("Name") },
                    singleLine = true,
                    isError = problem != null,
                    supportingText = { Text(problem ?: "${draft.trim().length}/$MECHA_NAME_MAX") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.weight(1f).focusRequester(field)
                )
            } else {
                Column(Modifier.weight(1f)) {
                    Text("Name", style = MaterialTheme.typography.labelMedium, color = Muted)
                    Text(state.name, style = MaterialTheme.typography.titleLarge)
                }
            }
            MechaButton(
                if (editing) "Save" else "Rename",
                onClick = { if (editing) save() else editing = true },
                enabled = !editing || problem == null,
                modifier = Modifier.focusRequester(action)
            )
        }
        if (editing) {
            MechaButton("Cancel", onClick = {
                draft = state.name
                finish()
            })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Care(store: MechaStore, state: MechaState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatCells("Bond", state.bond, MECHA_STAT_MAX)
        StatCells("Charge", state.charge, MECHA_STAT_MAX)
        StatCells("Condition", state.condition, MECHA_STAT_MAX)
        Text(
            "Charge and condition never run down while you're away.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted
        )
        // wraps rather than clipping on narrow screens (the Hangar's left column is ~250dp on a 640dp-wide display)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MechaButton("Tune up", onClick = { store.care(MechaCare.TUNE) }, enabled = !state.sleeping)
            MechaButton("Charge", onClick = { store.care(MechaCare.CHARGE) }, enabled = !state.sleeping)
            MechaButton(if (state.sleeping) "Wake up" else "Rest", onClick = { store.care(MechaCare.REST) })
        }
    }
}

@Composable
private fun Settings(store: MechaStore, view: MechaView, state: MechaState, onHide: () -> Unit) {
    var hiding by remember { mutableStateOf(false) }
    LaunchedEffect(view, hiding) {
        val current = store.view.value
        if (hiding && !current.busy) {
            hiding = false
            if (current.error == null && current.state?.enabled == false) onHide()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FocusRow(onClick = { if (!store.view.value.busy) store.setMotion(!state.motion) }) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics(mergeDescendants = true) {
                    stateDescription = if (state.motion) "On" else "Off"
                }
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Animate companion", style = MaterialTheme.typography.titleSmall)
                    Text("Off keeps it standing still everywhere.", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                // the row is the control; the switch only shows its state
                Switch(checked = state.motion, onCheckedChange = null)
            }
        }
        FocusRow(onClick = {
            if (!hiding && !store.view.value.busy) {
                if (state.enabled) {
                    hiding = true
                    store.setEnabled(false)
                } else {
                    store.setEnabled(true)
                }
            }
        }) {
            Column {
                if (state.enabled) {
                    Text("Hide companion", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${state.name} stops roaming the screens. Show it again in Home → Extras → Hangar. Parts and credits stay.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted
                    )
                } else {
                    Text("Show companion", style = MaterialTheme.typography.titleSmall)
                    Text("${state.name} is hidden and goes nowhere.", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssemblyActions(busy: Boolean, changed: Boolean, onFit: () -> Unit, onReset: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MechaButton("Fit build", onClick = onFit, enabled = !busy && changed, primary = true)
        MechaButton("Reset preview", onClick = onReset, enabled = changed)
    }
}

private enum class AssemblySource(val label: String) {
    PARTS("Parts"),
    FAMILIES("Family sets")
}

/** All choice modes feed the same draft shown at left; none of them equips anything. */
@Composable
private fun Assembly(view: MechaView, state: MechaState, draft: Map<MechaSlot, String>, onDraft: (Map<MechaSlot, String>) -> Unit) {
    var source by rememberSaveable { mutableStateOf(AssemblySource.PARTS) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AssemblySource.entries.forEach { choice -> MechaChip(choice.label, source == choice, { source = choice }) }
    }
    when (source) {
        AssemblySource.PARTS -> AssemblyParts(view, state, draft, onDraft)
        AssemblySource.FAMILIES -> FamilySets(view, state, draft, onDraft)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssemblyParts(view: MechaView, state: MechaState, draft: Map<MechaSlot, String>, onDraft: (Map<MechaSlot, String>) -> Unit) {
    var slot by rememberSaveable { mutableStateOf(MechaSlot.HEAD) }
    var family by rememberSaveable { mutableStateOf<String?>(null) }
    var partPage by rememberSaveable(slot, family) { mutableStateOf(0) }
    val choices = remember(view.parts, state.owned, slot, family) {
        view.parts.filter { it.slot == slot && state.owns(it.id) && (family == null || it.family == family) }.sortedWith(partOrder)
    }
    FamilyFilter(family) { family = it }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MechaSlot.entries.forEach { s -> MechaChip(s.label(), s == slot, { slot = s }) }
    }
    val lastPage = (choices.size - 1).coerceAtLeast(0) / 4
    val page = partPage.coerceIn(0, lastPage)
    // Two rows of two choices keep the pager visible without reducing type or touch targets.
    for (row in 0..1) {
        if (page * 4 + row * 2 < choices.size) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (column in 0..1) {
                    val index = page * 4 + row * 2 + column
                    Box(Modifier.weight(1f)) {
                        if (index < choices.size) {
                            val part = choices[index]
                            val selected = draft[slot] == part.id
                            FocusRow(onClick = { onDraft(draft + (slot to part.id)) }) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(part.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                    Text(
                                        state.origins[part.id] ?: "${familyName(part.family)} ${part.variant}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Muted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        if (selected) "Previewing" else "Preview",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (selected) Accent else Muted
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (lastPage > 0) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MechaButton("Previous parts", onClick = { partPage = page - 1 }, enabled = page > 0)
            Text("${page + 1}/${lastPage + 1}", style = MaterialTheme.typography.bodySmall, color = Muted)
            MechaButton("Next parts", onClick = { partPage = page + 1 }, enabled = page < lastPage)
        }
    }
}

/** Exactly the authored variants, never precomputed mixed combinations. Manual parts remain unrestricted. */
@Composable
private fun FamilySets(view: MechaView, state: MechaState, draft: Map<MechaSlot, String>, onPick: (Map<MechaSlot, String>) -> Unit) {
    var family by rememberSaveable { mutableStateOf(MECHA_FAMILIES.first()) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MECHA_FAMILIES.forEach { choice -> MechaChip(familyName(choice), family == choice, { family = choice }) }
    }
    Note("Choose an owned family set, or mix any owned parts in Parts. Only Fit build saves the preview.")
    for (variant in 1..2) {
        val pieces = remember(view.parts, family, variant) { view.parts.filter { it.family == family && it.variant == variant } }
        val set = remember(pieces) { pieces.associate { it.slot to it.id } }
        val owned = set.size == MechaSlot.entries.size && set.values.all { state.owns(it) }
        MechaChip(
            "${familyName(family)} $variant · ${if (owned) "owned" else "in Shop"}",
            set == draft,
            { if (owned) onPick(set) },
            dim = !owned
        )
    }
}

private val buildSaver = Saver<Map<MechaSlot, String>, ArrayList<String>>(
    save = { b -> ArrayList(MechaSlot.entries.map { b[it].orEmpty() }) },
    restore = { saved -> MechaSlot.entries.zip(saved).filter { it.second.isNotEmpty() }.toMap() }
)
