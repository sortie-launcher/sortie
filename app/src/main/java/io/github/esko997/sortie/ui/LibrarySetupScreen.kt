package io.github.esko997.sortie.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.data.manifest.LaunchProfile
import io.github.esko997.sortie.data.manifest.SystemDef
import io.github.esko997.sortie.data.setup.LibraryConfiguration
import io.github.esko997.sortie.data.setup.LibraryPreset
import io.github.esko997.sortie.data.setup.LibraryPresets
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private enum class SetupPage { OVERVIEW, ADD, SYSTEM, EMULATORS, SUPPORT }

/** What a folder chosen in the browser becomes. */
@Serializable
private enum class FolderTarget { GAMES, BIOS, SAVES }

/** The open folder browser: where it is, the child it came up from, and the folder a choice replaces (null = adds). */
@Serializable
private data class BrowseState(val target: FolderTarget, val path: String? = null, val cameFrom: String? = null, val replacing: String? = null)

/**
 * A system being added or edited. [existingId] null = new. [initial]/[initialExtensions] are what the draft started as (for
 * "changed?"); [baseFingerprint] is the configuration it was based on, so a desktop edit made meanwhile is never overwritten
 * unseen. A custom system has a blank id until its first save.
 */
@Serializable
private data class SystemDraft(
    val existingId: String?,
    val presetId: String?,
    val initial: SystemDef,
    val initialExtensions: String,
    val system: SystemDef,
    val extensions: String,
    val baseFingerprint: String
) {
    val changed: Boolean get() = system != initial || extensions != initialExtensions
}

@Serializable
private data class SupportDraft(
    val biosDir: String?,
    val saves: List<String>,
    val initialBios: String?,
    val initialSaves: List<String>,
    val baseFingerprint: String
) {
    val changed: Boolean get() = biosDir != initialBios || saves != initialSaves
}

@Serializable
private enum class SetupConfirm { DISCARD, REMOVE }

/**
 * Everything the screen must not forget: the page, open drafts, the browser's place, a pending confirmation, the last error and
 * the change being saved ([pending] = its token). Saved as one JSON string with the destination, so it survives the X swap,
 * activity recreation, opening a game page and coming back, and the keyboard closing.
 */
@Serializable
private data class SetupUi(
    val page: SetupPage = SetupPage.OVERVIEW,
    val system: SystemDraft? = null,
    val support: SupportDraft? = null,
    val browse: BrowseState? = null,
    val confirm: SetupConfirm? = null,
    val error: String? = null,
    val conflict: Boolean = false,
    val pending: String? = null,
    /** Key of the row that takes the focus when a page shows again. */
    val focus: String? = null
)

private val SETUP_JSON = Json { ignoreUnknownKeys = true }

private val SetupUiSaver = Saver<SetupUi, String>(
    save = { SETUP_JSON.encodeToString(SetupUi.serializer(), it) },
    restore = { runCatching { SETUP_JSON.decodeFromString(SetupUi.serializer(), it) }.getOrNull() }
)

private const val CONFLICT_TEXT =
    "The library settings changed on the card since you started (the desktop or another copy of Sortie). Nothing was saved. " +
        "Reload reads the current settings and keeps your draft; Save then replaces this part with what you see here."

/** Installed-package checks, cached until the window regains focus (e.g. after installing an emulator and coming back). */
private class Installed(private val pm: PackageManager) {
    private val cache = HashMap<String, Boolean>()
    private val labels = HashMap<String, String>()

    fun has(pkg: String): Boolean = cache.getOrPut(pkg) { runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess }

    /** The installed app's own name for [pkg] (the package id when Android won't say). */
    fun label(pkg: String): String = labels.getOrPut(pkg) {
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: pkg
    }

    /** No package: Android's chooser or a generic intent, always startable. */
    fun available(p: LaunchProfile): Boolean = p.`package`?.let(::has) ?: true
}

/**
 * Whether [p] starts a libretro core inside its app (RetroArch). Sortie can see that the app is installed, never whether the
 * core is: cores live in the app's private folder and are downloaded inside the app.
 */
private fun usesCore(p: LaunchProfile): Boolean = "LIBRETRO" in p.extras

/**
 * Library setup: add, edit and remove systems (several game folders each, file types, emulators in order), the BIOS and save
 * folders, all through an in-app browser of real readable folders; no JSON, adb or typed paths. Used for the first setup and
 * for everyday changes later. Each save writes through [io.github.esko997.sortie.data.setup.LibrarySetupStore], pins the
 * settings folder, re-indexes and shows what was found, with indexed games to open ([onGame]) for a test launch. [onDone]
 * leaves the screen.
 */
@Composable
fun LibrarySetupScreen(graph: SortieApp.Graph, onDone: () -> Unit, onGame: (String) -> Unit) {
    val model = remember(graph) { LibrarySetupModel.of(graph) }
    val configState by model.config.collectAsState()
    val work by model.work.collectAsState()
    val snapshot by graph.manifests.snapshot.collectAsState()
    var ui by rememberSaveable(stateSaver = SetupUiSaver) { mutableStateOf(SetupUi()) }
    val config = (configState as? SetupConfig.Loaded)?.config
    val drafting = ui.system != null || ui.support != null
    val context = LocalContext.current
    val volumes = remember { storageVolumes(context) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val installed = remember(windowFocused) { Installed(context.packageManager) }
    val scope = rememberCoroutineScope()
    val working = work as? SetupWork.Working

    // Without a draft, follow the card (desktop edits, a save's reload). A draft keeps the configuration it was based on.
    LaunchedEffect(snapshot.loadedAt, drafting) { if (!drafting || model.config.value !is SetupConfig.Loaded) model.refresh() }

    // The outcome of this screen's change: success closes the draft, failure keeps it with the reason. Reads the model's live
    // value: the collected one can trail a frame behind the click that started the change.
    LaunchedEffect(work, ui.pending) {
        val token = ui.pending ?: return@LaunchedEffect
        val w = model.work.value
        when {
            w is SetupWork.Done && w.token == token -> ui = SetupUi(page = SetupPage.OVERVIEW)
            w is SetupWork.Failed && w.token == token -> {
                ui = ui.copy(pending = null, confirm = null, error = "Not saved: ${w.message}", conflict = w.conflict)
                model.acknowledge(token)
            }
            w is SetupWork.Working && w.token == token -> Unit
            // the change this draft started is unknown to the model: Sortie's process was restarted mid-save
            else -> ui = ui.copy(
                pending = null,
                error = "Sortie stopped before this save finished. Check the systems in the library list; your draft is kept and Save tries again."
            )
        }
    }

    val friendly = { path: String -> friendlyPath(volumes, resolveFolder(graph, path).path) }

    fun openBrowser(target: FolderTarget, replacing: String? = null) {
        val near = replacing ?: when (target) {
            FolderTarget.GAMES -> ui.system?.system?.roots?.lastOrNull()
            FolderTarget.BIOS -> ui.support?.biosDir
            FolderTarget.SAVES -> ui.support?.saves?.lastOrNull()
        }
        val start = near?.let { resolveFolder(graph, it).parentFile }?.takeIf { it.isDirectory }?.path
        ui = ui.copy(browse = BrowseState(target, start, null, replacing))
    }

    fun rebase() {
        scope.launch {
            try {
                val fresh = model.reload()
                val d = ui.system
                val s = ui.support
                ui = ui.copy(
                    system = d?.copy(baseFingerprint = fresh.fingerprint),
                    support = s?.copy(baseFingerprint = fresh.fingerprint),
                    conflict = false,
                    error = "Reloaded the current settings. Your draft is unchanged; Save now replaces this part of the library with it."
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ui = ui.copy(error = "Couldn't read the library settings: ${describe(e)}")
            }
        }
    }

    fun saveSystem() {
        val d = ui.system ?: return
        val cfg = config ?: run {
            ui = ui.copy(error = "The library settings haven't been read yet. Wait a moment, then Save again.")
            return
        }
        val label = d.system.label.trim()
        val (exts, bad) = parseExtensions(d.extensions)
        val problem = when {
            label.isEmpty() -> "Give the system a name."
            d.system.roots.isEmpty() -> "Add at least one folder that holds this system's games."
            d.system.profiles.isEmpty() -> "Choose an emulator or Open with… before saving."
            bad != null -> "“$bad” isn't a file type. Use endings like iso or chd, separated by commas."
            else -> null
        }
        if (problem != null) {
            ui = ui.copy(error = problem, conflict = false)
            return
        }
        if (cfg.fingerprint != d.baseFingerprint) {
            ui = ui.copy(error = CONFLICT_TEXT, conflict = true)
            return
        }
        val id = d.system.id.ifBlank { newSystemId(label, cfg.systems.map { it.id }.toSet() + LibraryPresets.systems.map { it.system.id }) }
        val system = d.system.copy(id = id, folder = d.system.folder.ifBlank { id }, label = label, extensions = exts)
        // only definitions the card does not have yet: an existing (possibly hand-made) profile is never replaced from here
        val defs = system.profiles.filter { pid -> cfg.profiles.none { it.id == pid } }.mapNotNull { profileDef(null, it) }
        val token = UUID.randomUUID().toString()
        ui = if (model.saveSystem(token, cfg, system, defs)) {
            ui.copy(pending = token, error = null, conflict = false)
        } else {
            ui.copy(error = "Another change is still being saved. Wait until it finishes, then Save again.")
        }
    }

    fun removeSystem() {
        val d = ui.system ?: return
        val cfg = config ?: return
        val existing = cfg.systems.firstOrNull { it.id == d.existingId }
        if (existing == null || cfg.fingerprint != d.baseFingerprint) {
            ui = ui.copy(confirm = null, error = CONFLICT_TEXT, conflict = true)
            return
        }
        val token = UUID.randomUUID().toString()
        ui = if (model.removeSystem(token, cfg, existing)) {
            ui.copy(pending = token, confirm = null, error = null, conflict = false)
        } else {
            ui.copy(confirm = null, error = "Another change is still being saved. Wait until it finishes, then try again.")
        }
    }

    fun saveSupport() {
        val s = ui.support ?: return
        val cfg = config ?: run {
            ui = ui.copy(error = "The library settings haven't been read yet. Wait a moment, then Save again.")
            return
        }
        if (cfg.fingerprint != s.baseFingerprint) {
            ui = ui.copy(error = CONFLICT_TEXT, conflict = true)
            return
        }
        val token = UUID.randomUUID().toString()
        ui = if (model.saveSupportPaths(token, cfg, s.biosDir, s.saves)) {
            ui.copy(pending = token, error = null, conflict = false)
        } else {
            ui.copy(error = "Another change is still being saved. Wait until it finishes, then Save again.")
        }
    }

    fun closeDraft() {
        ui = ui.copy(page = SetupPage.OVERVIEW, system = null, support = null, confirm = null, error = null, conflict = false)
    }

    val browse = ui.browse
    when {
        browse != null -> {
            val draftSystem = ui.system?.system
            val chosen: Set<String> = when (browse.target) {
                FolderTarget.GAMES -> draftSystem?.roots.orEmpty().map { resolveFolder(graph, it).path }.toSet()
                FolderTarget.BIOS -> setOfNotNull(ui.support?.biosDir?.let { resolveFolder(graph, it).path })
                FolderTarget.SAVES -> ui.support?.saves.orEmpty().map { resolveFolder(graph, it).path }.toSet()
            }
            val (title, explanation) = when (browse.target) {
                FolderTarget.GAMES -> "Game folder for ${draftSystem?.label?.ifBlank { null } ?: "this system"}" to
                    "Open the folder that holds the game files themselves. Sortie lists files directly inside it, not in its subfolders."
                FolderTarget.BIOS -> "BIOS folder" to "Open the folder that holds your BIOS files."
                FolderTarget.SAVES -> "Save folder" to "Open a folder where an emulator keeps its saves or save states."
            }
            val extensions = if (browse.target == FolderTarget.GAMES) ui.system?.let { parseExtensions(it.extensions).first.toSet() } else null
            FolderBrowser(
                title = title,
                explanation = explanation,
                path = browse.path,
                cameFrom = browse.cameFrom,
                extensions = extensions,
                chosen = chosen,
                onNavigate = { path, from -> ui = ui.copy(browse = browse.copy(path = path, cameFrom = from)) },
                onUse = { path ->
                    ui = when (browse.target) {
                        FolderTarget.GAMES -> ui.system?.let { d ->
                            val roots = d.system.roots.replaceOrAdd(browse.replacing, path)
                            ui.copy(system = d.copy(system = d.system.copy(roots = roots)), browse = null, focus = "add-folder")
                        } ?: ui.copy(browse = null)
                        FolderTarget.BIOS -> ui.support?.let { s ->
                            ui.copy(support = s.copy(biosDir = path), browse = null, focus = "bios")
                        } ?: ui.copy(browse = null)
                        FolderTarget.SAVES -> ui.support?.let { s ->
                            ui.copy(support = s.copy(saves = s.saves.replaceOrAdd(browse.replacing, path)), browse = null, focus = "add-save")
                        } ?: ui.copy(browse = null)
                    }
                },
                onClose = { ui = ui.copy(browse = null) }
            )
        }
        ui.page == SetupPage.SYSTEM && ui.system != null -> SystemEditor(
            graph = graph,
            draft = ui.system!!,
            config = config,
            installed = installed,
            friendly = friendly,
            error = ui.error,
            conflict = ui.conflict,
            working = working?.takeIf { it.token == ui.pending },
            focusKey = ui.focus,
            onChange = { ui = ui.copy(system = it) },
            onAddFolder = { openBrowser(FolderTarget.GAMES) },
            onReplaceFolder = { openBrowser(FolderTarget.GAMES, it) },
            onAddEmulator = { ui = ui.copy(page = SetupPage.EMULATORS) },
            onSave = ::saveSystem,
            onReload = ::rebase,
            onCancel = { if (ui.system?.changed == true) ui = ui.copy(confirm = SetupConfirm.DISCARD) else closeDraft() },
            onRemove = { ui = ui.copy(confirm = SetupConfirm.REMOVE) }
        )
        ui.page == SetupPage.EMULATORS && ui.system != null -> EmulatorPicker(
            draft = ui.system!!,
            config = config,
            installed = installed,
            onPick = { id ->
                val d = ui.system!!
                ui = ui.copy(page = SetupPage.SYSTEM, system = d.copy(system = d.system.copy(profiles = d.system.profiles + id)), focus = "add-emulator")
            },
            onBack = { ui = ui.copy(page = SetupPage.SYSTEM, focus = "add-emulator") }
        )
        ui.page == SetupPage.SUPPORT && ui.support != null -> SupportEditor(
            draft = ui.support!!,
            friendly = friendly,
            error = ui.error,
            conflict = ui.conflict,
            working = working?.takeIf { it.token == ui.pending },
            focusKey = ui.focus,
            onChange = { ui = ui.copy(support = it) },
            onChooseBios = { openBrowser(FolderTarget.BIOS) },
            onAddSave = { openBrowser(FolderTarget.SAVES) },
            onReplaceSave = { openBrowser(FolderTarget.SAVES, it) },
            onSave = ::saveSupport,
            onReload = ::rebase,
            onCancel = { if (ui.support?.changed == true) ui = ui.copy(confirm = SetupConfirm.DISCARD) else closeDraft() }
        )
        ui.page == SetupPage.ADD && config != null -> AddSystemPage(
            config = config,
            installed = installed,
            onPreset = { preset ->
                val existing = config.systems.firstOrNull { it.id == preset.system.id }
                ui = if (existing != null) {
                    ui.copy(page = SetupPage.SYSTEM, system = draftOf(existing, config), error = null, conflict = false, focus = null)
                } else {
                    val sys = preset.system.copy(roots = emptyList())
                    val ext = extensionsText(sys.extensions)
                    val draft = SystemDraft(null, preset.system.id, sys, ext, sys, ext, config.fingerprint)
                    ui.copy(page = SetupPage.SYSTEM, system = draft, error = null, conflict = false, focus = null)
                }
            },
            onCustom = {
                val sys = SystemDef(id = "", folder = "", label = "", roots = emptyList())
                val draft = SystemDraft(null, null, sys, "", sys, "", config.fingerprint)
                ui = ui.copy(page = SetupPage.SYSTEM, system = draft, error = null, conflict = false, focus = null)
            },
            onBack = { ui = ui.copy(page = SetupPage.OVERVIEW, focus = "add") }
        )
        else -> Overview(
            graph = graph,
            model = model,
            configState = configState,
            work = work,
            installed = installed,
            friendly = friendly,
            focusKey = ui.focus,
            onSystem = { def ->
                if (config != null && working == null) {
                    ui = ui.copy(page = SetupPage.SYSTEM, system = draftOf(def, config), error = null, conflict = false, focus = "system:${def.id}")
                }
            },
            onAdd = { if (config != null && working == null) ui = ui.copy(page = SetupPage.ADD, focus = "add") },
            onSupport = {
                if (config != null && working == null) {
                    val draft = SupportDraft(config.biosDir, config.saves, config.biosDir, config.saves, config.fingerprint)
                    ui = ui.copy(page = SetupPage.SUPPORT, support = draft, error = null, conflict = false, focus = "support")
                }
            },
            onReindex = { if (working == null) model.reindex(UUID.randomUUID().toString()) },
            onGame = { id ->
                ui = ui.copy(focus = "game:$id")
                onGame(id)
            },
            onDone = onDone
        )
    }

    when (ui.confirm) {
        SetupConfirm.DISCARD -> ConfirmDialog(
            title = "Discard your changes?",
            body = if (ui.system?.existingId == null && ui.support == null) {
                "This system isn't saved yet. Discarding drops it; nothing on the card changes."
            } else {
                "Everything goes back to how it is saved on the card."
            },
            keep = "Keep editing",
            confirm = "Discard changes",
            onConfirm = ::closeDraft,
            onDismiss = { ui = ui.copy(confirm = null) }
        )
        SetupConfirm.REMOVE -> {
            val label = ui.system?.initial?.label.orEmpty()
            ConfirmDialog(
                title = "Remove $label from the library?",
                body = "Its games leave Sortie's lists. Nothing is deleted from storage: game files, saves and BIOS files stay where they " +
                    "are. Favourites, notes and play history stay in your records and show again if these games are indexed under " +
                    "this system again.",
                keep = "Keep $label",
                confirm = "Remove from the library",
                onConfirm = ::removeSystem,
                onDismiss = { ui = ui.copy(confirm = null) }
            )
        }
        null -> Unit
    }
}

private fun <T> List<T>.replaceOrAdd(old: T?, new: T): List<T> = when {
    new in this -> this
    old != null && old in this -> map { if (it == old) new else it }
    else -> this + new
}

private fun extensionsText(extensions: List<String>): String = extensions.joinToString(", ") { it.removePrefix(".") }

private fun draftOf(def: SystemDef, config: LibraryConfiguration): SystemDraft {
    val ext = extensionsText(def.extensions)
    return SystemDraft(def.id, LibraryPresets.systems.firstOrNull { it.system.id == def.id }?.system?.id, def, ext, def, ext, config.fingerprint)
}

/** A card profile by id (it wins: possibly hand-made), else the catalog's. */
private fun profileDef(config: LibraryConfiguration?, id: String): LaunchProfile? =
    config?.profiles?.firstOrNull { it.id == id } ?: LibraryPresets.systems.firstNotNullOfOrNull { p -> p.profiles.firstOrNull { it.id == id } }

private fun presetOf(id: String?): LibraryPreset? = id?.let { pid -> LibraryPresets.systems.firstOrNull { it.system.id == pid } }

/** A readable, valid, unused id for a custom system ("My Arcade" → "my-arcade"); it never changes after the first save. */
private fun newSystemId(label: String, taken: Set<String>): String {
    val slug = label.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trim('-')
    val base = if (slug.isEmpty()) "custom" else slug
    var id = base
    var n = 2
    while (id in taken) id = "$base-${n++}"
    return id
}

/** "installed" etc. for an emulator, and whether that is a problem. */
private fun profileStatus(p: LaunchProfile, installed: Installed): Pair<String, Boolean> {
    val pkg = p.`package` ?: return "asks Android which app opens the file" to false
    return when {
        !installed.has(pkg) -> "not installed ($pkg)" to true
        usesCore(p) -> "app installed · set up the core inside the app; Sortie can't check cores" to false
        else -> "installed" to false
    }
}

private fun folderStatus(c: FolderCheck, anyType: Boolean): Pair<String, Boolean> = when (c.state) {
    FolderState.MISSING -> "Not found. Is the card or drive inserted?" to true
    FolderState.UNREADABLE -> "Android doesn't let Sortie read this folder." to true
    FolderState.OK -> {
        val main = if (anyType) "${plural(c.matching, "file")} directly inside" else "${plural(c.matching, "game file")} of these types directly inside"
        val sub = if (c.subfoldersWithGames > 0) {
            ". ${plural(c.subfoldersWithGames, "subfolder")} hold game files too; Sortie doesn't look inside subfolders, so add them as folders to list them"
        } else {
            ""
        }
        "$main$sub." to (c.matching == 0)
    }
}

// ---------------------------------------------------------------------------------------------------------------------------
// Overview

@Composable
private fun Overview(
    graph: SortieApp.Graph,
    model: LibrarySetupModel,
    configState: SetupConfig,
    work: SetupWork,
    installed: Installed,
    friendly: (String) -> String,
    focusKey: String?,
    onSystem: (SystemDef) -> Unit,
    onAdd: () -> Unit,
    onSupport: () -> Unit,
    onReindex: () -> Unit,
    onGame: (String) -> Unit,
    onDone: () -> Unit
) {
    val counts by remember { graph.db.games().countsBySystem() }.collectAsState(emptyList())
    val config = (configState as? SetupConfig.Loaded)?.config
    val done = (work as? SetupWork.Done)?.result
    val working = work as? SetupWork.Working
    val orphanFailure = work as? SetupWork.Failed
    val systems = config?.systems.orEmpty()
    val keys = buildList {
        if (working != null) add("work")
        done?.games?.forEach { add("game:${it.id}") }
        if (done != null) add("result-ok")
        if (orphanFailure != null) add("failure-ok")
        if (configState is SetupConfig.Failed) add("retry")
        systems.forEach { add("system:${it.id}") }
        add("add")
        add("support")
    }
    val target = when {
        working != null -> "work"
        done != null && focusKey != null && focusKey in keys && focusKey.startsWith("game:") -> focusKey
        done != null -> done.games.firstOrNull()?.let { "game:${it.id}" } ?: "result-ok"
        orphanFailure != null -> "failure-ok"
        focusKey != null && focusKey in keys -> focusKey
        configState is SetupConfig.Failed -> "retry"
        else -> keys.firstOrNull { it.startsWith("system:") } ?: "add"
    }
    val first = remember { FocusRequester() }
    FocusOnShow(target to (config != null), first)
    val at = { key: String -> if (key == target) Modifier.focusRequester(first) else Modifier }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Storage missing at startup and now back: the stores read it once per process, so only a restart reloads Home, systems
    // and preferences. Otherwise just re-read the settings.
    val retry: () -> Unit = {
        scope.launch {
            val activity = context.activity()
            if (activity != null && withContext(Dispatchers.IO) { graph.needsRestart() }) SortieApp.restart(activity) else model.refresh()
        }
    }

    SetupPageFrame(hints = arrayOf("A" to "choose", "B" to "back")) {
        PageTitle("Library setup")
        Text(
            "The kinds of games on this device, the folders that hold them and the emulators that start them.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
        )
        if (working != null) {
            FocusRow(onClick = {}, modifier = at("work")) {
                WorkProgress(
                    working.step,
                    detail = "Sortie reads every file name in each folder; a large library takes a while. You can leave this screen: the work carries on."
                )
            }
        }
        orphanFailure?.let { f ->
            OutcomeRow("Not saved", ok = false, lines = listOf(f.message), onDismiss = { model.acknowledge(f.token) }, modifier = at("failure-ok"))
        }
        done?.let { ResultBlock(it, friendly, at, onGame) { model.dismissResult() } }
        when (configState) {
            SetupConfig.Loading -> WorkProgress("Reading the library settings…")
            is SetupConfig.Failed -> {
                Note("Couldn't read the library settings: ${configState.message}", error = true)
                SettingsPanel { row { FocusRow(onClick = retry, modifier = at("retry")) { Line("Try again", "Read the settings from the card again") } } }
            }
            is SetupConfig.Loaded -> Unit
        }
        if (config != null) {
            if (config.errors.isNotEmpty()) {
                SectionTitle("Settings files Sortie can't use")
                config.errors.forEach { Note(it, error = true) }
                Note(
                    "Sortie leaves these files exactly as they are and won't save over them. Fix or move them on the card " +
                        "(keep a copy first), then come back here."
                )
            }
            if (!config.exists && config.errors.isEmpty()) {
                Note(
                    "Nothing is set up yet. Add a system: pick what kind of games it is, choose the folders that hold them and the " +
                        "emulator that starts them. You can add more systems and change everything later."
                )
            }
            SectionTitle("Systems")
            SettingsPanel {
                systems.forEach { def ->
                    row(key = def.id) {
                        val n = counts.firstOrNull { it.systemId == def.id }?.n ?: 0
                        val emulator = emulatorSummary(def, config, installed)
                        FocusRow(onClick = { onSystem(def) }, modifier = at("system:${def.id}")) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        def.label,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        plural(n, "game"),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (n == 0) MaterialTheme.colorScheme.onSurfaceVariant else Amber,
                                        modifier = Modifier.padding(start = 12.dp)
                                    )
                                }
                                Text(
                                    plural(def.roots.size, "folder") + " · " + emulator.first,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (emulator.second) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                row {
                    FocusRow(onClick = onAdd, modifier = at("add")) {
                        Line("Add a system…", "PlayStation 2, Game Boy Advance, arcade… or one you name yourself")
                    }
                }
            }
            SectionTitle("BIOS and save folders")
            SettingsPanel {
                row {
                    FocusRow(onClick = onSupport, modifier = at("support")) {
                        Line(
                            "BIOS and save folders",
                            "BIOS: ${config.biosDir?.let(friendly) ?: "not set"} · saves: " +
                                if (config.saves.isEmpty()) "none" else plural(config.saves.size, "folder")
                        )
                    }
                }
            }
            SectionTitle("Index")
            SettingsPanel { row { FocusRow(onClick = onReindex) { Line("Re-index every system", "Read all game folders again, e.g. after copying games") } } }
            InfoBlock("Where these settings live") {
                Note("${friendly(graph.sortieDir.path)} (${graph.sortieDir.path}).")
                Note("Systems you don't edit here, desktop-made ones included, stay exactly as they are.")
            }
        }
        Spacer(Modifier.height(8.dp))
        SettingsPanel { row { FocusRow(onClick = onDone) { Line("Done", "Back to Sortie") } } }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

private fun emulatorSummary(def: SystemDef, config: LibraryConfiguration, installed: Installed): Pair<String, Boolean> {
    val profiles = def.profiles.mapNotNull { profileDef(config, it) }
    if (profiles.isEmpty()) return "No emulator chosen: its games can't start yet" to true
    val first = profiles.firstOrNull { installed.available(it) } ?: return "No installed emulator (${profiles.joinToString(", ") { it.label }})" to true
    return "Starts with ${first.label}" to false
}

@Composable
private fun ResultBlock(r: SetupResult, friendly: (String) -> String, at: (String) -> Modifier, onGame: (String) -> Unit, onDismiss: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Slate).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(r.title, style = MaterialTheme.typography.titleMedium, color = Amber, modifier = Modifier.padding(horizontal = 8.dp))
        r.report?.let { rep ->
            Note(
                "Index: ${plural(rep.files, "file")} in ${plural(rep.systems, "system")}, ${rep.updated} new or changed, ${rep.removed} removed " +
                    "(${"%.1f".format(Locale.ROOT, rep.millis / 1000.0)} s)."
            )
        }
        if (r.systemId != null) {
            Note(
                if (r.gameCount == 0) "No games were indexed for this system." else "${plural(r.gameCount, "game")} indexed for this system.",
                error = r.gameCount == 0
            )
            r.folders.forEach { c ->
                val (text, problem) = folderStatus(c, anyType = false)
                Note("${friendly(c.path)}: $text", error = problem)
            }
            if (r.gameCount == 0) {
                Note(
                    "Games must sit directly in a chosen folder and end in one of the system's file types. Open the system to add " +
                        "subfolders or change the file types."
                )
            }
        }
        r.problems.forEach { Note(it, error = true) }
        if (r.games.isNotEmpty()) {
            Text("Try a launch", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp, top = 8.dp))
            Note(
                "Open a game and start it. Sortie can only tell that the emulator app opened; check that the game itself runs, " +
                    "then come back to Sortie."
            )
            r.games.forEach { g ->
                FocusRow(onClick = { onGame(g.id) }, modifier = at("game:${g.id}")) {
                    Column {
                        Text(displayTitle(g), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            g.fileName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        FocusRow(onClick = onDismiss, modifier = at("result-ok")) { Text("OK", style = MaterialTheme.typography.bodyLarge) }
    }
}

// ---------------------------------------------------------------------------------------------------------------------------
// Adding a system

@Composable
private fun AddSystemPage(config: LibraryConfiguration, installed: Installed, onPreset: (LibraryPreset) -> Unit, onCustom: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val first = remember { FocusRequester() }
    FocusOnShow(Unit, first)
    val configured = config.systems.map { it.id }.toSet()
    SetupPageFrame(hints = arrayOf("A" to "choose", "B" to "back")) {
        PageTitle("Add a system")
        Note(
            "Pick what kind of games they are. Sortie suggests file types and emulators; you choose the folders and can change " +
                "everything before saving. Sortie only starts emulators already installed; it never downloads emulators, BIOS files or games."
        )
        Spacer(Modifier.height(6.dp))
        SettingsPanel {
            LibraryPresets.systems.forEachIndexed { i, preset ->
                row(key = preset.system.id) {
                    val packaged = preset.profiles.filter { it.`package` != null }
                    val ready = packaged.filter { installed.has(it.`package`!!) }
                    val status = when {
                        preset.system.id in configured -> "Already in your library: opens its settings"
                        ready.isNotEmpty() -> {
                            val apps = ready.mapNotNull { it.`package` }.distinct().map(installed::label)
                            val coreApps = ready.filter(::usesCore).mapNotNull { it.`package` }.distinct().map(installed::label)
                            "Emulator app installed: ${apps.joinToString(", ")}" +
                                if (coreApps.isEmpty()) "" else " · cores are set up separately in ${coreApps.joinToString(", ")}"
                        }
                        packaged.isNotEmpty() -> "Not installed yet: ${packaged.joinToString(", ") { it.label }}"
                        else -> "Opens games with an app you choose"
                    }
                    FocusRow(onClick = { onPreset(preset) }, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier) {
                        Line(preset.system.label, status)
                    }
                }
            }
            row {
                FocusRow(onClick = onCustom, modifier = if (LibraryPresets.systems.isEmpty()) Modifier.focusRequester(first) else Modifier) {
                    Line("Something else…", "Name it yourself and choose its file types and emulator")
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------------
// Editing a system

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SystemEditor(
    graph: SortieApp.Graph,
    draft: SystemDraft,
    config: LibraryConfiguration?,
    installed: Installed,
    friendly: (String) -> String,
    error: String?,
    conflict: Boolean,
    working: SetupWork.Working?,
    focusKey: String?,
    onChange: (SystemDraft) -> Unit,
    onAddFolder: () -> Unit,
    onReplaceFolder: (String) -> Unit,
    onAddEmulator: () -> Unit,
    onSave: () -> Unit,
    onReload: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit
) {
    val busy = working != null
    BackHandler { if (!busy) onCancel() }
    val sys = draft.system
    val preset = presetOf(draft.presetId)
    val (exts, bad) = parseExtensions(draft.extensions)
    val extSet = remember(exts) { exts.toSet() }
    var folderSheet by remember { mutableStateOf<String?>(null) }
    var emulatorSheet by remember { mutableStateOf<String?>(null) }
    // never a text field (focusing one may raise the keyboard) and never Save (disabled until something changes)
    val target = focusKey?.takeIf { it == "add-folder" || it == "add-emulator" } ?: "add-folder"
    val first = remember { FocusRequester() }
    FocusOnShow(target, first)
    val at = { key: String -> if (key == target) Modifier.focusRequester(first) else Modifier }
    val isNew = draft.existingId == null
    val name = sys.label.ifBlank { if (isNew) "New system" else draft.initial.label }

    SetupPageFrame(hints = arrayOf("A" to "choose / type", "B" to "cancel")) {
        Text(
            name,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
        )
        Note(
            if (isNew) {
                "Nothing is saved until you choose Save."
            } else {
                "Changes apply when you save. The system keeps its identity, so its games keep their favourites, notes and play history."
            }
        )
        if (!isNew && config != null && config.systems.none { it.id == draft.existingId }) {
            Note("This system is no longer in the library on the card. Save adds it back as you see it here.", error = true)
        }

        SectionTitle("Name")
        SetupTextField(sys.label, { onChange(draft.copy(system = sys.copy(label = it))) }, "e.g. PlayStation 2", enabled = !busy)

        SectionTitle("Game folders")
        Note(
            "Sortie lists the game files that sit directly in each folder; it doesn't look inside subfolders, so add every folder " +
                "that holds games. If two folders of this system hold files with the same name, they show as one game."
        )
        Spacer(Modifier.height(4.dp))
        SettingsPanel {
            sys.roots.forEach { root ->
                row(key = root) {
                    val check by produceState<FolderCheck?>(null, root, extSet) {
                        value = withContext(Dispatchers.IO) { checkFolder(resolveFolder(graph, root), extSet) }
                    }
                    FocusRow(onClick = { if (!busy) folderSheet = root }) {
                        Column {
                            Text(friendly(root), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                resolveFolder(graph, root).path,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val status = check?.let { folderStatus(it, anyType = extSet.isEmpty()) }
                            Text(
                                status?.first ?: "Checking…",
                                style = MaterialTheme.typography.bodySmall,
                                color = when {
                                    status == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                    status.second -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }
            }
            row {
                FocusRow(onClick = { if (!busy) onAddFolder() }, modifier = at("add-folder")) {
                    Line("Add a folder…", if (sys.roots.isEmpty()) "Choose where this system's games are" else "Another folder with games for this system")
                }
            }
        }

        SectionTitle("File types")
        SetupTextField(draft.extensions, { onChange(draft.copy(extensions = it)) }, "e.g. iso, chd, cso", enabled = !busy, isError = bad != null)
        Note(
            if (bad != null) {
                "“$bad” isn't a file type. Use endings like iso or chd, separated by commas."
            } else if (exts.isEmpty()) {
                "Empty: every file in the folders is listed. Separate file types with commas to list only those."
            } else {
                "Only files ending in ${exts.joinToString(", ")} are listed."
            },
            error = bad != null
        )
        preset?.let { p ->
            val suggested = p.system.extensions
            if (suggested.isNotEmpty() && parseExtensions(suggested.joinToString(",")).first != exts) {
                SettingsPanel {
                    row {
                        FocusRow(onClick = { if (!busy) onChange(draft.copy(extensions = extensionsText(suggested))) }) {
                            Line("Use the suggested file types", suggested.joinToString(", "))
                        }
                    }
                }
            }
        }

        SectionTitle("Emulators")
        Note(
            "Sortie starts a game with the first installed emulator in this list, unless you chose a default for this system in " +
                "Settings or a game has its own Launch with… choice."
        )
        Spacer(Modifier.height(4.dp))
        SettingsPanel {
            sys.profiles.forEachIndexed { i, id ->
                row(key = id) {
                    val def = profileDef(config, id)
                    val (status, problem) = def?.let { profileStatus(it, installed) }
                        ?: ("unknown emulator: not in the library settings or Sortie's list" to true)
                    FocusRow(onClick = { if (!busy) emulatorSheet = id }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${i + 1}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Amber,
                                modifier = Modifier.padding(end = 14.dp)
                            )
                            Column {
                                Text(def?.label ?: id, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    status.replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            row {
                FocusRow(onClick = { if (!busy) onAddEmulator() }, modifier = at("add-emulator")) {
                    Line("Add an emulator…", "Choose from the emulators Sortie knows how to start")
                }
            }
        }
        if (sys.profiles.isEmpty()) {
            Note("Choose an emulator or Open with… before saving.", error = true)
        } else if (sys.profiles.mapNotNull { profileDef(config, it) }.none { installed.available(it) }) {
            Note("None of these is installed. Install one, then come back: Sortie checks again when you return.", error = true)
        }
        preset?.instructions?.takeIf { it.isNotBlank() }?.let { InfoBlock("Before the first game") { Note(it) } }

        DraftFooter(error = error, conflict = conflict, working = working, onReload = onReload)
        FlowRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GlowButton(
                if (busy) "Saving…" else "Save",
                modifier = at("save"),
                enabled = !busy && sys.profiles.isNotEmpty() && (draft.changed || isNew || error != null),
                primary = true,
                onClick = onSave
            )
            GlowButton("Cancel", enabled = !busy, onClick = onCancel)
            if (!isNew) GlowButton("Remove from library…", enabled = !busy, colour = MaterialTheme.colorScheme.error, onClick = onRemove)
        }
    }

    folderSheet?.let { root ->
        ContextSheet(
            friendly(root),
            listOf(
                SheetAction("Choose a different folder") { onReplaceFolder(root) },
                SheetAction("Remove this folder") { onChange(draft.copy(system = sys.copy(roots = sys.roots - root))) }
            )
        ) { folderSheet = null }
    }
    emulatorSheet?.let { id ->
        val i = sys.profiles.indexOf(id)
        val move = { to: Int ->
            val list = sys.profiles.toMutableList()
            list.add(to, list.removeAt(i))
            onChange(draft.copy(system = sys.copy(profiles = list)))
        }
        ContextSheet(
            profileDef(config, id)?.label ?: id,
            buildList {
                if (i > 0) add(SheetAction("Move up (try earlier)") { move(i - 1) })
                if (i in 0 until sys.profiles.lastIndex) add(SheetAction("Move down (try later)") { move(i + 1) })
                add(SheetAction("Remove from this system") { onChange(draft.copy(system = sys.copy(profiles = sys.profiles - id))) })
            }
        ) { emulatorSheet = null }
    }
}

@Composable
private fun EmulatorPicker(draft: SystemDraft, config: LibraryConfiguration?, installed: Installed, onPick: (String) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val chosen = draft.system.profiles.toSet()
    val suggested = presetOf(draft.presetId)?.profiles.orEmpty().filter { it.id !in chosen }
    val shown = HashSet<String>(chosen).apply { suggested.forEach { add(it.id) } }
    val library = config?.profiles.orEmpty().filter { it.id !in shown }.also { l -> l.forEach { shown += it.id } }
    val others = LibraryPresets.systems.flatMap { it.profiles }.distinctBy { it.id }.filter { it.id !in shown }
    // installed first; a card profile with the same id as a catalog one is the one shown (it is what launches)
    val order = { list: List<LaunchProfile> -> list.map { profileDef(config, it.id) ?: it }.sortedByDescending { installed.available(it) } }
    val groups = listOf(
        "Suggested for ${draft.system.label.ifBlank { "this system" }}" to order(suggested),
        "Already used in your library" to order(library),
        "Other emulators Sortie knows" to order(others)
    ).filter { it.second.isNotEmpty() }
    val first = remember { FocusRequester() }
    val back = remember { FocusRequester() }
    FocusOnShow(Unit, if (groups.isEmpty()) back else first)
    SetupPageFrame(hints = arrayOf("A" to "add", "B" to "back")) {
        PageTitle("Add an emulator")
        Note("Sortie only starts emulators already installed on this device; it never downloads emulators, BIOS files or games.")
        if (groups.isEmpty()) Note("Every emulator Sortie knows is already in this system's list.")
        groups.forEachIndexed { g, (title, profiles) ->
            SectionTitle(title)
            SettingsPanel {
                profiles.forEachIndexed { i, p ->
                    row(key = p.id) {
                        val (status, problem) = profileStatus(p, installed)
                        FocusRow(onClick = { onPick(p.id) }, modifier = if (g == 0 && i == 0) Modifier.focusRequester(first) else Modifier) {
                            Column {
                                Text(p.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    status.replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        SettingsPanel { row { FocusRow(onClick = onBack, modifier = Modifier.focusRequester(back)) { Line("Back", "Add nothing") } } }
    }
}

// ---------------------------------------------------------------------------------------------------------------------------
// BIOS and save folders

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SupportEditor(
    draft: SupportDraft,
    friendly: (String) -> String,
    error: String?,
    conflict: Boolean,
    working: SetupWork.Working?,
    focusKey: String?,
    onChange: (SupportDraft) -> Unit,
    onChooseBios: () -> Unit,
    onAddSave: () -> Unit,
    onReplaceSave: (String) -> Unit,
    onSave: () -> Unit,
    onReload: () -> Unit,
    onCancel: () -> Unit
) {
    val busy = working != null
    BackHandler { if (!busy) onCancel() }
    var biosSheet by remember { mutableStateOf(false) }
    var saveSheet by remember { mutableStateOf<String?>(null) }
    val target = focusKey?.takeIf { it == "bios" || it == "add-save" } ?: "bios"
    val first = remember { FocusRequester() }
    FocusOnShow(target, first)
    val at = { key: String -> if (key == target) Modifier.focusRequester(first) else Modifier }
    SetupPageFrame(hints = arrayOf("A" to "choose", "B" to "cancel")) {
        PageTitle("BIOS and save folders")
        Note("Both are optional. Nothing is saved until you choose Save.")
        SectionTitle("BIOS folder")
        Note(
            "Where your BIOS files are. Sortie checks for the files a system lists there and marks games whose BIOS is missing; " +
                "emulators keep their own BIOS settings. Sortie never downloads BIOS files."
        )
        Spacer(Modifier.height(4.dp))
        SettingsPanel {
            row {
                FocusRow(onClick = { if (!busy) biosSheet = true }, modifier = at("bios")) {
                    Line("BIOS folder", draft.biosDir?.let(friendly) ?: "Not set")
                }
            }
        }
        SectionTitle("Save folders")
        Note("Where emulators keep saves and save states. Sortie looks two folder levels deep for files named like a game and marks that game as started.")
        Spacer(Modifier.height(4.dp))
        SettingsPanel {
            draft.saves.forEach { path ->
                row(key = path) { FocusRow(onClick = { if (!busy) saveSheet = path }) { Line(friendly(path), path) } }
            }
            row { FocusRow(onClick = { if (!busy) onAddSave() }, modifier = at("add-save")) { Line("Add a save folder…", "e.g. RetroArch's saves folder") } }
        }
        DraftFooter(error = error, conflict = conflict, working = working, onReload = onReload)
        FlowRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GlowButton(if (busy) "Saving…" else "Save", enabled = !busy && (draft.changed || error != null), primary = true, onClick = onSave)
            GlowButton("Cancel", enabled = !busy, onClick = onCancel)
        }
    }
    if (biosSheet) {
        ContextSheet(
            "BIOS folder",
            buildList {
                add(SheetAction(if (draft.biosDir == null) "Choose a folder" else "Choose a different folder") { onChooseBios() })
                if (draft.biosDir != null) add(SheetAction("Don't use a BIOS folder") { onChange(draft.copy(biosDir = null)) })
            }
        ) { biosSheet = false }
    }
    saveSheet?.let { path ->
        ContextSheet(
            friendly(path),
            listOf(
                SheetAction("Choose a different folder") { onReplaceSave(path) },
                SheetAction("Remove this folder") { onChange(draft.copy(saves = draft.saves - path)) }
            )
        ) { saveSheet = null }
    }
}

// ---------------------------------------------------------------------------------------------------------------------------
// Shared pieces

/** The error or save progress under a draft, with Reload after a conflicting change on the card. */
@Composable
private fun DraftFooter(error: String?, conflict: Boolean, working: SetupWork.Working?, onReload: () -> Unit) {
    if (working != null) {
        Column(Modifier.padding(top = 12.dp)) { WorkProgress(working.step) }
    }
    if (error != null && working == null) {
        Column(Modifier.padding(top = 12.dp)) {
            Note(error, error = true)
            if (conflict) {
                SettingsPanel { row { FocusRow(onClick = onReload) { Line("Reload library settings", "Keeps your draft") } } }
            } else {
                Note("Your draft is kept: fix it and Save again, or Cancel to drop it.")
            }
        }
    }
}

/** A scrolling, left-aligned page column (readable width on the wide top screen) above the hint bar. */
@Composable
private fun SetupPageFrame(hints: Array<Pair<String, String>>, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Column(Modifier.widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
        }
        HintBar(*hints)
    }
}

/**
 * One-line text field a controller can pass through: up/down from a physical pad move the focus instead of the caret, A opens
 * the keyboard, Done closes it. The text lives in the screen's saved draft, so closing the keyboard never loses it.
 */
@Composable
private fun SetupTextField(value: String, onChange: (String) -> Unit, placeholder: String, enabled: Boolean = true, isError: Boolean = false) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        singleLine = true,
        isError = isError,
        placeholder = { Text(placeholder) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).onPreviewKeyEvent { e ->
            when {
                !isPhysicalPad(e) -> false
                e.key == Key.DirectionUp || e.key == Key.DirectionDown -> {
                    if (e.type == KeyEventType.KeyDown) {
                        keyboard?.hide()
                        focus.moveFocus(if (e.key == Key.DirectionUp) FocusDirection.Up else FocusDirection.Down)
                    }
                    true
                }
                e.key == Key.ButtonA || e.key == Key.DirectionCenter -> {
                    if (e.type == KeyEventType.KeyUp) keyboard?.show()
                    true
                }
                else -> false
            }
        }
    )
}

@Composable
private fun ConfirmDialog(title: String, body: String, keep: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val safe = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        // the safe choice holds the focus, so confirming takes a deliberate move down
        Column(
            Modifier.widthIn(max = 460.dp).clip(RoundedCornerShape(16.dp)).background(Slate).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 6.dp)
            )
            FocusRow(onClick = onDismiss, modifier = Modifier.focusRequester(safe)) { Text(keep, style = MaterialTheme.typography.bodyLarge) }
            val danger = MaterialTheme.colorScheme.error
            FocusRow(onClick = onConfirm, colour = danger) { Text(confirm, style = MaterialTheme.typography.bodyLarge, color = danger) }
            HintBar("A" to "choose", "B" to "keep")
        }
        FocusOnShow(Unit, safe)
    }
}

/** Keyboard input mode, then [requester] until it takes the focus (a touch-opened page starts in touch mode). */
@Composable
private fun FocusOnShow(key: Any?, requester: FocusRequester) {
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(key) {
        repeat(20) {
            @OptIn(ExperimentalComposeUiApi::class)
            inputMode.requestInputMode(InputMode.Keyboard)
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(40)
        }
    }
}
