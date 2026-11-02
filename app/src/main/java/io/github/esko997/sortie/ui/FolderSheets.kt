package io.github.esko997.sortie.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.esko997.sortie.data.folders.FOLDER_LABEL_MAX
import io.github.esko997.sortie.data.folders.FolderView
import io.github.esko997.sortie.data.folders.MAX_FOLDER_DEPTH
import io.github.esko997.sortie.data.folders.isWithin
import io.github.esko997.sortie.data.folders.path
import io.github.esko997.sortie.data.folders.span
import kotlinx.coroutines.delay

/** What a [SteppedSheet] action may open instead of closing the sheet: a further choice, or a name to type. */
sealed interface SheetStep {
    class Choose(val title: String, val actions: List<SheetAction>) : SheetStep

    class Name(val title: String, val initial: String = "", val save: (String) -> Unit) : SheetStep
}

/**
 * A [ContextSheet] whose actions may go on to a [SheetStep] (a folder to choose, a name to type, a confirmation) before it
 * closes: [actions] gets `next`, which an action calls to open that step. B or Cancel at any step closes the whole sheet.
 */
@Composable
fun SteppedSheet(title: String, onDismiss: () -> Unit, actions: (next: (SheetStep) -> Unit) -> List<SheetAction>) {
    var step by remember { mutableStateOf<SheetStep?>(null) }
    var closed by remember { mutableStateOf(false) }
    val next: (SheetStep) -> Unit = { step = it }
    // a sheet closes before its action runs: an action that opens a step reopens it in the same frame
    val close = {
        step = null
        closed = true
    }
    when (val s = step) {
        null -> if (!closed) ContextSheet(title, actions(next)) { closed = true }
        is SheetStep.Choose -> ContextSheet(s.title, s.actions, close)
        is SheetStep.Name -> NameDialog(s.title, s.initial, onSave = {
            s.save(it)
            close()
        }, onDismiss = close)
    }
    LaunchedEffect(closed, step) { if (closed && step == null) onDismiss() }
}

/** A one-line name ([FOLDER_LABEL_MAX] characters at most) with Save and Cancel; Save needs a name. */
@Composable
private fun NameDialog(title: String, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    val field = remember { FocusRequester() }
    val save = remember { FocusRequester() }
    var focusedOnce by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        val shape = RoundedCornerShape(16.dp)
        Column(
            Modifier.widthIn(max = 460.dp).fillMaxWidth().clip(shape).background(PanelColour).border(1.dp, RuleColour, shape).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            NoteField(
                name,
                { if (it.length <= FOLDER_LABEL_MAX) name = it },
                "Folder name",
                Modifier.focusRequester(field).onFocusChanged { if (it.isFocused) focusedOnce = true },
                down = if (name.isNotBlank()) save else FocusRequester.Default,
                singleLine = true
            )
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlowButton("Save", Modifier.focusRequester(save), enabled = name.isNotBlank(), primary = true) { onSave(name.trim()) }
                GlowButton("Cancel", onClick = onDismiss)
            }
            HintBar("↕" to "move", "A" to "select / type", "B" to "cancel")
        }
        // see ContextSheet: the dialog window gains focus a few frames late, and touch mode refuses focus moves
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) {
            repeat(20) {
                if (focusedOnce) return@LaunchedEffect
                @OptIn(ExperimentalComposeUiApi::class)
                inputMode.requestInputMode(InputMode.Keyboard)
                runCatching { field.requestFocus() }
                delay(40)
            }
        }
    }
}

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

/** In-app folders by their path (`Lists › RPGs`), so folders of the same name tell apart. */
private fun List<FolderView>.choices(targets: List<FolderView>, run: (FolderView) -> Unit): List<SheetAction> =
    targets.map { path(it) to it }.sortedBy { it.first.lowercase() }.map { (label, f) -> SheetAction(label) { run(f) } }

/**
 * "Move to folder…" for tile [tileId] in [from] (an in-app folder, or null for Home): the in-app folders it may go into — not
 * the one it is in, not itself or a folder inside it (a cycle), none that would nest folders more than [MAX_FOLDER_DEPTH]
 * deep — then New folder…, which makes the folder where the tile is and moves it in. Null when there is nowhere to go.
 */
fun moveToFolderAction(
    vm: SortieViewModel,
    folders: List<FolderView>,
    tileId: String,
    from: String?,
    context: Context,
    next: (SheetStep) -> Unit
): SheetAction? {
    val span = folders.span(tileId)
    val targets = folders.filter { it.inApp && it.def.id != from && !folders.isWithin(it.def.id, tileId) && it.level + span <= MAX_FOLDER_DEPTH }
    val newLevel = (from?.let { id -> folders.firstOrNull { it.def.id == id }?.level } ?: 0) + 1
    val canCreate = newLevel + span <= MAX_FOLDER_DEPTH
    if (targets.isEmpty() && !canCreate) return null
    return SheetAction("Move to folder…") {
        val choices = folders.choices(targets) { to ->
            vm.moveToFolder(tileId, from, to.def.id)
            toast(context, "Moved to ${to.def.label}")
        }
        val create = SheetAction("New folder…") {
            next(
                SheetStep.Name("New folder") { label ->
                    vm.createFolder(label, into = from, beside = tileId, moving = tileId)
                    toast(context, "Moved to $label")
                }
            )
        }
        next(SheetStep.Choose("Move to folder", choices + listOfNotNull(create.takeIf { canCreate })))
    }
}

/** "Add to folder…" for game, app or shortcut [id]: an in-app folder that does not list it yet, or a New folder… on Home. */
fun addToFolderAction(vm: SortieViewModel, folders: List<FolderView>, id: String, context: Context, next: (SheetStep) -> Unit): SheetAction =
    SheetAction("Add to folder…") {
        val choices = folders.choices(folders.filter { it.inApp && id !in it.def.include }) { to ->
            vm.addToFolder(id, to.def.id)
            toast(context, "Added to ${to.def.label}")
        }
        val create = SheetAction("New folder…") {
            next(
                SheetStep.Name("New folder") { label ->
                    vm.addToFolder(id, vm.createFolder(label, into = null, beside = null))
                    toast(context, "Added to $label")
                }
            )
        }
        next(SheetStep.Choose("Add to folder", choices + create))
    }

/** "New folder…": an empty in-app folder in [into] (null: Home) just before tile [beside]. */
fun newFolderAction(vm: SortieViewModel, into: String?, beside: String?, next: (SheetStep) -> Unit): SheetAction =
    SheetAction("New folder…") { next(SheetStep.Name("New folder") { label -> vm.createFolder(label, into, beside) }) }

/**
 * "Move out to …" for tile [tileId] listed in in-app folder [from]: into the in-app folder showing [from] (its first such
 * parent), else onto Home.
 */
fun moveOutAction(vm: SortieViewModel, folders: List<FolderView>, tileId: String, from: FolderView, context: Context): SheetAction {
    val parent = from.parents.firstNotNullOfOrNull { p -> folders.firstOrNull { it.def.id == p && it.inApp } }
    val where = parent?.def?.label ?: "Home"
    return SheetAction("Move out to $where") {
        vm.moveOutOfFolder(tileId, from.def.id, parent?.def?.id)
        toast(context, "Moved to $where")
    }
}

/** "Remove from folder" for game, app or shortcut [id] listed in in-app folder [from]; it stays in the library. */
fun removeFromFolderAction(vm: SortieViewModel, id: String, from: FolderView, context: Context): SheetAction = SheetAction("Remove from folder") {
    vm.removeFromFolder(id, from.def.id)
    toast(context, "Removed from ${from.def.label}")
}

/** Rename… and Delete folder… for in-app folder [folder]; deleting asks first and never takes anything out of the library. */
fun editFolderActions(vm: SortieViewModel, folder: FolderView, context: Context, next: (SheetStep) -> Unit): List<SheetAction> = listOf(
    SheetAction("Rename…") { next(SheetStep.Name("Rename folder", folder.def.label) { vm.renameFolder(folder.def.id, it) }) },
    SheetAction("Delete folder…") {
        next(
            SheetStep.Choose(
                "Delete “${folder.def.label}”? Nothing leaves your library.",
                listOf(
                    SheetAction("Delete folder") {
                        vm.deleteFolder(folder.def.id)
                        toast(context, "Deleted ${folder.def.label}")
                    }
                )
            )
        )
    }
)
