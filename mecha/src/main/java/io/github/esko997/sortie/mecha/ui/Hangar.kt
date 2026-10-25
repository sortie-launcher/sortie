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
import androidx.compose.ui.platform.LocalInputModeManager
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
import io.github.esko997.sortie.mecha.MECHA_MINUTES_PER_CREDIT
import io.github.esko997.sortie.mecha.MECHA_NAME_MAX
import io.github.esko997.sortie.mecha.MECHA_SESSION_MINUTES_CAP
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

private enum class HangarTab { WORKSHOP, GALLERY }

/**
 * The companion's hangar, over whatever screen opened it (that screen stays composed underneath, so a reader keeps its page).
 * Workshop is the real, saved hangar: every change goes through [MechaStore]. Gallery previews all the art and never changes
 * what is owned or worn, except through the same equip call when every previewed part is already owned.
 * B closes the Gallery back to the Workshop, then the Hangar.
 */
@Composable
fun Hangar(store: MechaStore, onClose: () -> Unit) {
    val view by store.view.collectAsState()
    var tab by rememberSaveable { mutableStateOf(HangarTab.WORKSHOP) }
    // the explicit foreground refresh: sessions finished since the last look count now
    LaunchedEffect(Unit) { store.refreshSessions() }
    Dialog(
        onDismissRequest = { if (tab == HangarTab.GALLERY) tab = HangarTab.WORKSHOP else onClose() },
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
                    Text("Hangar", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
                    if (view.state != null) {
                        MechaChip(
                            "Workshop",
                            tab == HangarTab.WORKSHOP,
                            { tab = HangarTab.WORKSHOP },
                            if (tab == HangarTab.WORKSHOP) Modifier.focusRequester(first) else Modifier
                        )
                        MechaChip(
                            "Gallery",
                            tab == HangarTab.GALLERY,
                            { tab = HangarTab.GALLERY },
                            if (tab == HangarTab.GALLERY) Modifier.focusRequester(first) else Modifier
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    MechaButton("Close", onClick = onClose, modifier = if (view.state == null) Modifier.focusRequester(first) else Modifier)
                }
                Outcome(view)
                val state = view.state
                when {
                    state == null && view.error != null -> Damaged(store, view.error.orEmpty())
                    state == null -> Text(
                        "Opening the hangar…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    tab == HangarTab.WORKSHOP -> Workshop(store, view, state, onHide = onClose)
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
        error != null && view.state != null -> Text(error, style = MaterialTheme.typography.bodySmall, color = Warning, modifier = Modifier.padding(top = 6.dp))
        message != null -> Text(message, style = MaterialTheme.typography.bodySmall, color = Amber, modifier = Modifier.padding(top = 6.dp))
    }
}

/** The saved hangar can't be read. Nothing is replaced unless asked twice; the damaged file is kept beside the new one. */
@Composable
private fun Damaged(store: MechaStore, error: String) {
    var confirming by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 12.dp).widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(error, style = MaterialTheme.typography.bodyMedium, color = Warning)
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
            Box(floor) { MechaFigure(view.parts, state.equipped, pose, state.motion, target = 112.dp) }
            Box(Modifier.weight(1f)) { Rename(store, state) }
        }
    } else {
        Box(Modifier.fillMaxWidth().then(floor), contentAlignment = Alignment.Center) {
            MechaFigure(view.parts, state.equipped, pose, state.motion, target = 176.dp)
        }
        Rename(store, state)
    }
}

@Composable
private fun Rename(store: MechaStore, state: MechaState) {
    var draft by rememberSaveable(state.name) { mutableStateOf(state.name) }
    val problem = if (draft.trim() == state.name) null else mechaNameProblem(draft.trim())
    val canSave = draft.trim() != state.name && problem == null
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.take(MECHA_NAME_MAX + 4) },
            label = { Text("Name") },
            singleLine = true,
            isError = problem != null,
            supportingText = { Text(problem ?: "${draft.trim().length}/$MECHA_NAME_MAX") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (canSave) store.rename(draft.trim()) }),
            modifier = Modifier.weight(1f)
        )
        MechaButton("Rename", onClick = { store.rename(draft.trim()) }, enabled = canSave, modifier = Modifier.padding(start = 8.dp))
    }
}

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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            store.setEnabled(false)
            onHide()
        }) {
            Column {
                Text("Hide companion", style = MaterialTheme.typography.titleSmall)
                Text("Show it again from the bottom of Home. Parts and credits stay.", style = MaterialTheme.typography.bodySmall, color = Muted)
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
    val unownedAll = state.unownedIn(view.parts)

    Salvage(store, state, slot, unownedHere.size, unownedAll.size)

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
                    MechaFigure(view.parts, state.equipped + (slot to part.id), MechaPose.IDLE, motion = false, target = 64.dp)
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
                "$n more ${slot.label().lowercase()} ${if (n == 1) "part" else "parts"} to find by salvaging. The Gallery previews them.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
    FamilySets(store, view, state)
}

@Composable
private fun Salvage(store: MechaStore, state: MechaState, slot: MechaSlot, leftHere: Int, leftAll: Int) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ink).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            if (state.salvage == 1) "1 salvage credit" else "${state.salvage} salvage credits",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Amber
        )
        Text(
            "Playing a game earns 1 credit every $MECHA_MINUTES_PER_CREDIT minutes, counting up to $MECHA_SESSION_MINUTES_CAP minutes of each " +
                "session. ${state.minutesToNextCredit} more ${if (state.minutesToNextCredit == 1) "minute" else "minutes"} to the next one; " +
                "${state.creditedMinutes} minutes counted so far. Play from before the companion arrived doesn't count.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            MechaButton(
                "Salvage ${slot.label().lowercase()} (1 credit)",
                onClick = { store.salvage(slot) },
                enabled = state.salvage > 0 && leftHere > 0,
                primary = true
            )
            MechaButton("Any slot (1 credit)", onClick = { store.salvage(null) }, enabled = state.salvage > 0 && leftAll > 0)
        }
        when {
            leftAll == 0 -> Text("Every part is in the hangar. Credits keep until there's something new to find.", style = MaterialTheme.typography.bodySmall)
            leftHere == 0 -> Text("Every ${slot.label().lowercase()} part is found; salvage another slot.", style = MaterialTheme.typography.bodySmall)
            state.salvage == 0 -> Text("No credits yet. Play something and check back.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Each family's parts that are already in the hangar, as one outfit: wearing a set is four ordinary equips. */
@Composable
private fun FamilySets(store: MechaStore, view: MechaView, state: MechaState) {
    Text("Family sets", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
    Text("Built from parts you own. Mixing families slot by slot works too.", style = MaterialTheme.typography.bodySmall, color = Muted)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        MECHA_FAMILIES.forEach { family ->
            val set = remember(view.parts, state.owned, family) { ownedSet(view.parts, state, family) }
            val wearing = set != null && set.all { (s, id) -> state.equipped[s] == id }
            Box(Modifier.widthIn(min = 120.dp)) {
                FocusRow(onClick = { set?.forEach { (s, id) -> if (state.equipped[s] != id) store.equip(id) } }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (set != null) {
                            MechaFigure(view.parts, set, MechaPose.IDLE, motion = false, target = 72.dp)
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
            "Gallery previews every part and pose. It doesn't add parts to your hangar: unfound parts come only from salvage.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted
        )
        // one build in all four poses, side by side, at the largest whole scale that fits four across
        BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ink).padding(10.dp)) {
            val target = mechaFit((maxWidth - 14.dp * 3) / 4, 112.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MechaPose.entries.forEach { p ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        MechaFigure(view.parts, build, p, state.motion, target = target)
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

/** All six complete family builds (variant 1 and 2) in one pose, to compare silhouettes and motion; choosing one loads it above. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.FamilyGrid(view: MechaView, state: MechaState, pose: MechaPose, onPose: (MechaPose) -> Unit, onPick: (Map<MechaSlot, String>) -> Unit) {
    Text("Families", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MechaPose.entries.forEach { p -> MechaChip(p.label(), p == pose, { onPose(p) }) }
    }
    MECHA_FAMILIES.forEach { family ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            listOf(1, 2).forEach { variant ->
                val pieces = view.parts.filter { it.family == family && it.variant == variant }
                val set = pieces.associate { it.slot to it.id }
                if (set.size == MechaSlot.entries.size) {
                    val have = set.values.count { state.owns(it) }
                    Box(Modifier.widthIn(min = 150.dp)) {
                        FocusRow(onClick = { onPick(set) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MechaFigure(view.parts, set, pose, state.motion, target = 88.dp)
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
