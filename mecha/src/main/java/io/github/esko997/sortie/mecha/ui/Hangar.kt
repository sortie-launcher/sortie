package io.github.esko997.sortie.mecha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.esko997.sortie.mecha.MECHA_FAMILIES
import io.github.esko997.sortie.mecha.MECHA_NAME_MAX
import io.github.esko997.sortie.mecha.MECHA_STAT_MAX
import io.github.esko997.sortie.mecha.MechaCare
import io.github.esko997.sortie.mecha.MechaPart
import io.github.esko997.sortie.mecha.MechaSlot
import io.github.esko997.sortie.mecha.MechaState
import io.github.esko997.sortie.mecha.MechaStore
import io.github.esko997.sortie.mecha.MechaView
import io.github.esko997.sortie.mecha.mechaNameProblem
import io.github.esko997.sortie.mecha.owns
import io.github.esko997.sortie.mecha.unownedIn
import io.github.esko997.sortie.ui.Amber
import io.github.esko997.sortie.ui.FocusRow
import io.github.esko997.sortie.ui.Ink
import io.github.esko997.sortie.ui.Slate
import kotlinx.coroutines.delay

private enum class HangarTab(val label: String) {
    WORKSHOP("Workshop"),
    SHOP("Shop"),
    BENCH("Build bench"),
    CABINET("Cabinet"),
    GALLERY("Gallery")
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

/**
 * The companion's hangar, over whatever screen opened it (that screen stays composed underneath, so a reader keeps its page).
 * Workshop is the real, saved hangar: every change goes through [MechaStore]. Gallery previews all the art and never changes
 * what is owned or worn, except through the same equip call when every previewed part is already owned.
 * B returns from any preview or shop to the Workshop, then closes the Hangar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Hangar(store: MechaStore, onClose: () -> Unit) {
    val view by store.view.collectAsState()
    var tab by rememberSaveable { mutableStateOf(HangarTab.WORKSHOP) }
    // the explicit foreground refresh: sessions finished since the last look count now
    LaunchedEffect(Unit) { store.refreshSessions() }
    Dialog(
        onDismissRequest = { if (tab != HangarTab.WORKSHOP) tab = HangarTab.WORKSHOP else onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Slate,
            modifier = Modifier.widthIn(max = 880.dp).fillMaxWidth(0.96f).fillMaxHeight(0.94f)
        ) {
            val first = remember { FocusRequester() }
            var focusedAny by remember { mutableStateOf(false) }
            val inputMode = LocalInputModeManager.current
            LaunchedEffect(Unit) {
                // opened by touch the window starts in touch mode; the controller still has to land somewhere (see MagazineMenu)
                repeat(25) {
                    if (focusedAny) return@LaunchedEffect
                    if (inputMode.inputMode == InputMode.Keyboard) runCatching { first.requestFocus() }
                    delay(40)
                }
            }
            Column(Modifier.padding(14.dp).onFocusChanged { if (it.hasFocus) focusedAny = true }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Hangar", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    MechaButton("Close", onClick = onClose, modifier = if (view.state == null) Modifier.focusRequester(first) else Modifier)
                }
                if (view.state != null) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        HangarTab.entries.forEach { item ->
                            MechaChip(
                                item.label,
                                tab == item,
                                { tab = item },
                                if (tab == item) Modifier.focusRequester(first) else Modifier
                            )
                        }
                    }
                }
                LaunchedEffect(tab) {
                    if (inputMode.inputMode == InputMode.Keyboard) runCatching { first.requestFocus() }
                }
                Outcome(view)
                val state = view.state
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
                    tab == HangarTab.BENCH -> MechaBuildBench(store, view, state)
                    tab == HangarTab.CABINET -> MechaCabinet(store, view, state)
                    else -> Gallery(store, view, state)
                }
            }
        }
    }
}

/** The last action's outcome, or the store's error; both stay until the next action replaces them. */
@Composable
private fun Outcome(view: MechaView) {
    val error = view.error
    val message = view.message
    when {
        view.busy -> Text("Saving…", style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 6.dp))
        error != null && view.state != null -> Text(error, style = MaterialTheme.typography.bodySmall, color = Warning, modifier = Modifier.padding(top = 6.dp))
        message != null -> Text(message, style = MaterialTheme.typography.bodySmall, color = Amber, modifier = Modifier.padding(top = 6.dp))
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

@Composable
private fun Workshop(store: MechaStore, view: MechaView, state: MechaState, onHide: () -> Unit) {
    BoxWithConstraints(Modifier.padding(top = 10.dp)) {
        if (maxWidth >= 560.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(0.42f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Bay(store, view, state, compact = false)
                    Care(store, state)
                    Settings(store, state, onHide)
                }
                Column(Modifier.weight(0.58f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Parts(store, view, state)
                }
            }
        } else {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Bay(store, view, state, compact = true)
                Care(store, state)
                Parts(store, view, state)
                Settings(store, state, onHide)
            }
        }
    }
}

/**
 * The mech large on a dark bay floor and its name. [compact] (a narrow window, e.g. a handheld's landscape screen at a high
 * density): mech and name side by side, so the controls are reached sooner.
 */
@Composable
private fun Bay(store: MechaStore, view: MechaView, state: MechaState, compact: Boolean) {
    val cheer = rememberCheer(state)
    val pose = when {
        cheer && !state.sleeping -> MechaPose.CELEBRATE
        state.sleeping -> MechaPose.SLEEP
        else -> MechaPose.IDLE
    }
    val floor = Modifier.clip(RoundedCornerShape(12.dp)).background(Ink).padding(10.dp)
    if (compact) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(floor) { MechaFigure(view.parts, state.equipped, pose, state.motion && state.enabled, target = 112.dp, finishes = state.equippedFinishes) }
            Box(Modifier.weight(1f)) { Rename(store, state) }
        }
    } else {
        Box(Modifier.fillMaxWidth().then(floor), contentAlignment = Alignment.Center) {
            MechaFigure(view.parts, state.equipped, pose, state.motion && state.enabled, target = 176.dp, finishes = state.equippedFinishes)
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
private fun Settings(store: MechaStore, state: MechaState, onHide: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FocusRow(onClick = { store.setMotion(!state.motion) }) {
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
            store.setEnabled(!state.enabled)
            if (state.enabled) onHide()
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
private fun Parts(store: MechaStore, view: MechaView, state: MechaState) {
    var slot by rememberSaveable { mutableStateOf(MechaSlot.HEAD) }
    val inSlot = remember(view.parts, slot) { view.parts.filter { it.slot == slot }.sortedWith(partOrder) }
    val owned = inSlot.filter { state.owns(it.id) }
    val unownedHere = state.unownedIn(view.parts, slot)

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MechaSlot.entries.forEach { s ->
            val total = view.parts.count { it.slot == s }
            val have = view.parts.count { it.slot == s && state.owns(it.id) }
            MechaChip("${s.label()} $have/$total", s == slot, { slot = s })
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        owned.forEach { part ->
            val worn = state.equipped[slot] == part.id
            FocusRow(onClick = { if (!worn) store.equip(part.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // the whole mech wearing this part, so a swap is judged in context
                    MechaFigure(
                        view.parts,
                        state.equipped + (slot to part.id),
                        MechaPose.IDLE,
                        motion = false,
                        target = 64.dp,
                        finishes = state.equippedFinishes
                    )
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(
                            part.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val origin = state.origins[part.id]
                        Text(
                            listOfNotNull("${familyName(part.family)} ${part.variant}", origin).joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        if (worn) "Wearing" else "Equip",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (worn) Amber else MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        if (unownedHere.isNotEmpty()) {
            val n = unownedHere.size
            Text(
                "$n more ${slot.label().lowercase()} ${if (n == 1) "part" else "parts"} available in the Shop or Cabinet. The Gallery previews them.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
    FamilySets(store, view, state)
}

/** Each family's parts that are already in the hangar, as one outfit: wearing a set is four ordinary equips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FamilySets(store: MechaStore, view: MechaView, state: MechaState) {
    Text("Family sets", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
    Text("Built from parts you own. Mixing families slot by slot works too.", style = MaterialTheme.typography.bodySmall, color = Muted)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MECHA_FAMILIES.forEach { family ->
            val set = remember(view.parts, state.owned, family) { ownedSet(view.parts, state, family) }
            val wearing = set != null && set.all { (s, id) -> state.equipped[s] == id }
            Box(Modifier.widthIn(min = 120.dp)) {
                FocusRow(onClick = { set?.forEach { (s, id) -> if (state.equipped[s] != id) store.equip(id) } }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (set != null) {
                            MechaFigure(view.parts, set, MechaPose.IDLE, motion = false, target = 72.dp, finishes = state.equippedFinishes)
                        }
                        Text(familyName(family), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            when {
                                set == null -> "Not all slots owned"
                                wearing -> "Wearing"
                                else -> "Wear this set"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (wearing) Amber else Muted
                        )
                    }
                }
            }
        }
    }
}

/** For every slot, the family's owned part (variant 1 first), or null when a slot has none owned. */
private fun ownedSet(parts: List<MechaPart>, state: MechaState, family: String): Map<MechaSlot, String>? {
    val set = HashMap<MechaSlot, String>()
    for (slot in MechaSlot.entries) {
        val part = parts.filter { it.family == family && it.slot == slot && state.owns(it.id) }.minByOrNull { it.variant } ?: return null
        set[slot] = part.id
    }
    return set
}

/**
 * Every part in the catalogue, owned or not, for comparing art and animation. Nothing here unlocks anything: unfound parts
 * are labelled as previews and a build can be worn only when every part of it is owned.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Gallery(store: MechaStore, view: MechaView, state: MechaState) {
    var build by rememberSaveable(stateSaver = buildSaver) { mutableStateOf(state.equipped) }
    var pose by rememberSaveable { mutableStateOf(MechaPose.IDLE) }
    val missing = remember(build, state.owned, view.parts) {
        MechaSlot.entries.mapNotNull { s -> build[s]?.takeIf { !state.owns(it) }?.let { id -> view.parts.firstOrNull { it.id == id } } }
    }
    Column(Modifier.padding(top = 10.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Gallery previews every part and pose for free. Buy missing parts in the Shop or try the Cabinet.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted
        )
        // one build in all four poses, side by side, at the largest whole scale that fits four across
        BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ink).padding(10.dp)) {
            val target = mechaFit((maxWidth - 14.dp * 3) / 4, 112.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MechaPose.entries.forEach { p ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        MechaFigure(view.parts, build, p, state.motion && state.enabled, target = target, finishes = state.equippedFinishes)
                        Text(p.label(), style = MaterialTheme.typography.labelMedium, color = Muted)
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (missing.isEmpty()) {
                    "Every part of this build is in your hangar."
                } else {
                    "Preview only. Not found yet: " + missing.joinToString(", ") { it.name }
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (missing.isEmpty()) MaterialTheme.colorScheme.onSurface else Amber,
                modifier = Modifier.weight(1f)
            )
            MechaButton(
                if (build == state.equipped) "Wearing" else "Wear this build",
                onClick = { MechaSlot.entries.forEach { s -> build[s]?.let { id -> if (state.equipped[s] != id) store.equip(id) } } },
                enabled = missing.isEmpty() && build != state.equipped,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        MechaSlot.entries.forEach { slot ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(slot.label(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    view.parts.filter { it.slot == slot }.sortedWith(partOrder).forEach { part ->
                        val owned = state.owns(part.id)
                        MechaChip(
                            if (owned) part.name else "${part.name} (preview)",
                            build[slot] == part.id,
                            { build = build + (slot to part.id) },
                            Modifier.semantics { contentDescription = "${part.name}, ${familyName(part.family)}, ${if (owned) "owned" else "not found yet"}" },
                            dim = !owned
                        )
                    }
                }
            }
        }
        FamilyGrid(view, state, pose, onPose = { pose = it }, onPick = { build = it })
    }
}

/** Complete family builds in one pose; choosing one loads its free preview above. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.FamilyGrid(view: MechaView, state: MechaState, pose: MechaPose, onPose: (MechaPose) -> Unit, onPick: (Map<MechaSlot, String>) -> Unit) {
    Text("Families", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MechaPose.entries.forEach { p -> MechaChip(p.label(), p == pose, { onPose(p) }) }
    }
    MECHA_FAMILIES.forEach { family ->
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 2).forEach { variant ->
                val pieces = view.parts.filter { it.family == family && it.variant == variant }
                val set = pieces.associate { it.slot to it.id }
                if (set.size == MechaSlot.entries.size) {
                    val have = set.values.count { state.owns(it) }
                    Box(Modifier.widthIn(min = 150.dp)) {
                        FocusRow(onClick = { onPick(set) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MechaFigure(view.parts, set, pose, state.motion && state.enabled, target = 88.dp, finishes = state.equippedFinishes)
                                Column(Modifier.padding(start = 8.dp)) {
                                    Text("${familyName(family)} $variant", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (have == set.size) "All owned" else "Preview, $have of ${set.size} owned",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (have == set.size) Amber else Muted
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}

private fun MechaPose.label(): String = when (this) {
    MechaPose.IDLE -> "Idle"
    MechaPose.WALK -> "Walk"
    MechaPose.SLEEP -> "Sleep"
    MechaPose.CELEBRATE -> "Cheer"
}

private val buildSaver = Saver<Map<MechaSlot, String>, ArrayList<String>>(
    save = { b -> ArrayList(MechaSlot.entries.map { b[it].orEmpty() }) },
    restore = { saved -> MechaSlot.entries.zip(saved).filter { it.second.isNotEmpty() }.toMap() }
)
