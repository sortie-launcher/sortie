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
import io.github.esko997.sortie.data.apps.LibraryItem
import io.github.esko997.sortie.data.folders.FOLDER_LABEL_MAX
import io.github.esko997.sortie.data.folders.FOLDER_SORTS
import io.github.esko997.sortie.data.folders.FolderView
import io.github.esko997.sortie.data.folders.MAX_FOLDER_DEPTH
import io.github.esko997.sortie.data.folders.below
import io.github.esko997.sortie.data.folders.ownedSubfolders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

/** Folders by label. */
private fun choices(targets: List<FolderView>, run: (FolderView) -> Unit): List<SheetAction> =
    targets.sortedBy { it.def.label.lowercase() }.map { f -> SheetAction(f.def.label) { run(f) } }

/**
 * "Move to folder…" for tile [tileId] in [from] (an editable folder, or null for Home). Inside a folder its first choice moves
 * the tile out, to the editable folder showing [from] or to Home; then the editable folders it may go into — not the one it is
 * in, not itself or a folder inside it (a cycle), none that would nest folders more than [MAX_FOLDER_DEPTH] deep — then New
 * folder…, which makes the folder where the tile is and moves it in. Null when there is nowhere to go.
 */
private fun moveToFolderAction(
    vm: SortieViewModel,
    folders: List<FolderView>,
    tileId: String,
    from: FolderView?,
    context: Context,
    next: (SheetStep) -> Unit
): SheetAction? {
    val below = folders.below(tileId)
    val span = below.values.maxOrNull() ?: 0
    val parent = from?.parents?.firstNotNullOfOrNull { p -> folders.firstOrNull { it.def.id == p && it.editable } }
    val targets = folders.filter {
        it.editable && it.def.id != from?.def?.id && it.def.id != parent?.def?.id && it.def.id !in below && it.level + span <= MAX_FOLDER_DEPTH
    }
    val canCreate = (from?.level ?: 0) + 1 + span <= MAX_FOLDER_DEPTH
    val move = { to: FolderView?, label: String ->
        vm.moveTile(tileId, from?.def?.id, to?.def?.id) { toast(context, "Moved to $label") }
    }
    // inside a folder the first choice moves the tile up a level: to the editable parent, else Home
    val out = from?.let {
        val where = parent?.def?.label ?: "Home"
        SheetAction("Out to $where") { move(parent, where) }
    }
    if (out == null && targets.isEmpty() && !canCreate) return null
    return SheetAction("Move to folder…") {
        val create = SheetAction("New folder…") {
            next(
                SheetStep.Name("New folder") { label ->
                    vm.createFolder(label, into = from?.def?.id, beside = tileId, moving = tileId) { toast(context, "Moved to $label") }
                }
            )
        }
        val into = choices(targets) { move(it, it.def.label) }
        next(SheetStep.Choose("Move to folder", listOfNotNull(out) + into + listOfNotNull(create.takeIf { canCreate })))
    }
}

/**
 * Hold-A filing actions for tile [tileId] on Home ([from] null; any tile but Continue and Apps) or for a folder or system
 * shown in folder [from]: Move to folder… when it sits on Home or in an editable folder, and Rename… / Sort by… / Delete
 * folder… when it is an editable folder itself.
 */
fun folderTileActions(
    vm: SortieViewModel,
    folders: List<FolderView>,
    tileId: String,
    from: FolderView?,
    context: Context,
    next: (SheetStep) -> Unit
): List<SheetAction> {
    val movable = (from == null || from.editable) && tileId != HomeIds.CONTINUE && tileId != HomeIds.APPS
    val move = if (movable) moveToFolderAction(vm, folders, tileId, from, context, next) else null
    val own = folders.firstOrNull { it.editable && HomeIds.FOLDER + it.def.id == tileId }
    return listOfNotNull(move) + own?.let { editFolderActions(vm, folders, it, context, next) }.orEmpty()
}

/** "Add to folder…" for game, app or shortcut [id]: an editable folder that does not list it yet, or a New folder… on Home. */
fun addToFolderAction(vm: SortieViewModel, folders: List<FolderView>, id: String, context: Context, next: (SheetStep) -> Unit): SheetAction =
    SheetAction("Add to folder…") {
        val choices = choices(folders.filter { it.editable && id !in it.def.include }) { to ->
            vm.addToFolder(id, to.def.id) { toast(context, "Added to ${to.def.label}") }
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

/** "Remove from folder" for game, app or shortcut [id] listed in editable folder [from]; it stays in the library. */
fun removeFromFolderAction(vm: SortieViewModel, id: String, from: FolderView, context: Context): SheetAction = SheetAction("Remove from folder") {
    vm.removeFromFolder(id, from.def.id) { toast(context, "Removed from ${from.def.label}") }
}

/**
 * "Use as folder cover" for [item] in editable folder [folder] when it is a game with a cover: the folder's tile shows it from
 * then on. On the game already chosen it goes back to the automatic cover ([FolderView.coverArt]). Null for anything else.
 */
fun folderCoverAction(vm: SortieViewModel, item: LibraryItem, folder: FolderView, context: Context): SheetAction? {
    if (!folder.editable || item !is LibraryItem.Game || item.game.cover == null) return null
    val chosen = folder.def.cover == item.id
    return SheetAction(if (chosen) "Use automatic folder cover" else "Use as folder cover") {
        vm.setFolderCover(folder.def.id, item.id.takeUnless { chosen }) { toast(context, "Cover of ${folder.def.label} changed") }
    }
}

/**
 * Hold R1 on a folder or system tile [t]: a random playable game of it opens, or a toast says there is none. Null when the
 * tile has no random pick.
 */
internal fun randomPick(t: HomeTile, scope: CoroutineScope, context: Context, onGame: (String) -> Unit): (() -> Unit)? = t.random?.let { pick ->
    {
        scope.launch {
            val g = pick()
            if (g != null) onGame(g.id) else Toast.makeText(context, "Nothing playable in ${t.title}", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * Rename…, Sort by… and Delete folder… for editable folder [folder]. Deleting asks first; when it holds folders of its own, the
 * folder can go with them. Folders are only pointers: no game, system or file ever leaves the library.
 */
private fun editFolderActions(
    vm: SortieViewModel,
    folders: List<FolderView>,
    folder: FolderView,
    context: Context,
    next: (SheetStep) -> Unit
): List<SheetAction> = listOf(
    SheetAction("Rename…") { next(SheetStep.Name("Rename folder", folder.def.label) { vm.renameFolder(folder.def.id, it) }) },
    SheetAction("Sort by…") {
        val sorts = FOLDER_SORTS.map { (label, sort) ->
            SheetAction(if (sort == folder.def.sort) "✓ $label" else label) { vm.sortFolder(folder.def.id, sort) }
        }
        next(SheetStep.Choose("Sort “${folder.def.label}” by", sorts))
    },
    SheetAction("Delete folder…") {
        val subfolders = folders.ownedSubfolders(folder.def.id).size
        val only = SheetAction(if (subfolders > 0) "Delete folder, keep its folders" else "Delete folder") {
            vm.deleteFolder(folder.def.id) { toast(context, "Deleted ${folder.def.label}") }
        }
        val all = SheetAction("Delete folder and ${plural(subfolders, "folder")} inside") {
            vm.deleteFolder(folder.def.id, withFolders = true) { toast(context, "Deleted ${folder.def.label} and ${plural(subfolders, "folder")}") }
        }
        next(
            SheetStep.Choose(
                "Delete “${folder.def.label}”? Games and files are never deleted.",
                listOfNotNull(only, all.takeIf { subfolders > 0 })
            )
        )
    }
)
