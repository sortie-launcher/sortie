package io.github.esko997.sortie.ui

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** A mounted storage volume as Android reports it: its user-facing name and its real directory. */
internal data class VolumeDir(val label: String, val dir: File)

/** Mounted volumes with a real filesystem directory (internal storage, SD cards, USB drives), from [StorageManager]. */
internal fun storageVolumes(context: Context): List<VolumeDir> {
    val sm = context.getSystemService(StorageManager::class.java) ?: return emptyList()
    return sm.storageVolumes.mapNotNull { v ->
        val dir = v.directory ?: return@mapNotNull null
        if (v.state != Environment.MEDIA_MOUNTED && v.state != Environment.MEDIA_MOUNTED_READ_ONLY) return@mapNotNull null
        VolumeDir(v.getDescription(context), dir)
    }
}

/** "/storage/ABCD-1234/roms/ps2" → "SD card › roms › ps2"; a path on no mounted volume stays as it is. */
internal fun friendlyPath(volumes: List<VolumeDir>, path: String): String {
    val v = volumeOf(volumes, path) ?: return path
    val rel = path.removePrefix(v.dir.path).trim('/')
    return if (rel.isEmpty()) v.label else v.label + " › " + rel.replace("/", " › ")
}

private fun volumeOf(volumes: List<VolumeDir>, path: String): VolumeDir? =
    volumes.filter { path == it.dir.path || path.startsWith(it.dir.path + "/") }.maxByOrNull { it.dir.path.length }

private sealed interface Listing {
    data object Loading : Listing

    data class Unreadable(val missing: Boolean) : Listing

    data class Ready(val dirs: List<File>, val files: Int, val matching: Int) : Listing
}

private fun list(path: String, extensions: Set<String>?): Listing {
    val dir = File(path)
    if (!dir.isDirectory) return Listing.Unreadable(missing = true)
    val all = dir.listFiles() ?: return Listing.Unreadable(missing = false)
    val dirs = ArrayList<File>()
    var files = 0
    var matching = 0
    for (f in all) {
        if (f.name.startsWith(".")) continue
        if (f.isDirectory) {
            dirs += f
        } else {
            files++
            if (extensions != null && matches(f.name, extensions)) matching++
        }
    }
    dirs.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    return Listing.Ready(dirs, files, matching)
}

/**
 * Picks a real, readable directory on a mounted volume (All files access lets Sortie read them; storage-framework documents are
 * not paths and are never offered). [path] null = the volume list. Navigation is hoisted ([onNavigate]) so the caller keeps the
 * place across the X swap and recreation; [cameFrom] is the child just left, which takes the focus again. B goes up a level,
 * leaving the volume list closes. [extensions]: count this system's game files in the open folder (null: count every file).
 * [chosen] holds paths already picked, which "Use this folder" then says.
 */
@Composable
internal fun FolderBrowser(
    title: String,
    explanation: String,
    path: String?,
    cameFrom: String?,
    extensions: Set<String>?,
    chosen: Set<String>,
    onNavigate: (path: String?, cameFrom: String?) -> Unit,
    onUse: (String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val volumes = remember { storageVolumes(context) }
    val volume = path?.let { volumeOf(volumes, it) }
    // up: the parent, except at a volume's top, which goes back to the volume list (Android hides what lies above it)
    val parent: String? = path?.let { p -> if (volume != null && p == volume.dir.path) null else File(p).parentFile?.path }
    BackHandler { if (path == null) onClose() else onNavigate(parent, path) }
    // the listing remembers its folder: until the new one is read, the old folder's rows must not be shown or focused
    val loaded by produceState<Pair<String?, Listing>>(null to Listing.Loading, path, extensions) {
        value = path to if (path == null) Listing.Ready(emptyList(), 0, 0) else withContext(Dispatchers.IO) { list(path, extensions) }
    }
    val listing = if (loaded.first == path) loaded.second else Listing.Loading
    // rows: [0] use / volumes, [1] up, then the subfolders; the volume list is the volumes only
    val rows: List<BrowserRow> = when {
        path == null -> volumes.map { BrowserRow.Volume(it) }
        else -> buildList {
            add(BrowserRow.Use)
            add(BrowserRow.Up)
            (listing as? Listing.Ready)?.dirs?.forEach { add(BrowserRow.Child(it)) }
        }
    }
    val focusIndex = when {
        listing is Listing.Loading -> -1
        cameFrom != null -> rows.indexOfFirst { row ->
            (row is BrowserRow.Child && row.dir.path == cameFrom) || (row is BrowserRow.Volume && row.volume.dir.path == cameFrom)
        }
            .takeIf { it >= 0 } ?: 0
        else -> 0
    }
    val listState = rememberLazyListState()
    val inputMode = LocalInputModeManager.current
    val target = remember { FocusRequester() }
    LaunchedEffect(path, focusIndex) {
        if (focusIndex < 0 || rows.isEmpty()) return@LaunchedEffect
        listState.scrollToItem(focusIndex)
        repeat(20) {
            @OptIn(ExperimentalComposeUiApi::class)
            inputMode.requestInputMode(InputMode.Keyboard)
            if (runCatching { target.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(40)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                GlowButton("Cancel", onClick = onClose)
            }
            Text(
                explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                if (path == null) "Storage" else friendlyPath(volumes, path),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Amber,
                modifier = Modifier.padding(top = 10.dp)
            )
            if (path != null) Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                summary(path, listing, extensions, volumes.isEmpty()),
                style = MaterialTheme.typography.bodySmall,
                color = if (listing is Listing.Unreadable || (path == null && volumes.isEmpty())) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )
        }
        LazyColumn(
            Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            itemsIndexed(rows, key = { _, r -> r.key }) { i, row ->
                val mod = if (i == focusIndex) Modifier.focusRequester(target) else Modifier
                when (row) {
                    is BrowserRow.Volume -> FocusRow(onClick = { onNavigate(row.volume.dir.path, null) }, modifier = mod) {
                        Line(row.volume.label, row.volume.dir.path)
                    }
                    BrowserRow.Use -> {
                        val readable = listing is Listing.Ready
                        val already = path != null && path in chosen
                        FocusRow(onClick = { if (readable && path != null && !already) onUse(path) }, modifier = mod) {
                            Line(
                                when {
                                    already -> "Already chosen"
                                    readable -> "Use this folder"
                                    else -> "This folder can't be used"
                                },
                                path?.let { friendlyPath(volumes, it) }.orEmpty()
                            )
                        }
                    }
                    BrowserRow.Up -> FocusRow(onClick = { onNavigate(parent, path) }, modifier = mod) {
                        Line("Up one level", parent?.let { friendlyPath(volumes, it) } ?: "Storage volumes")
                    }
                    is BrowserRow.Child -> FocusRow(onClick = { onNavigate(row.dir.path, null) }, modifier = mod) {
                        Text(row.dir.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        HintBar("A" to "open / choose", "B" to "up a level")
    }
}

private sealed interface BrowserRow {
    val key: String

    data class Volume(val volume: VolumeDir) : BrowserRow {
        override val key get() = "v:" + volume.dir.path
    }

    data object Use : BrowserRow {
        override val key get() = "use"
    }

    data object Up : BrowserRow {
        override val key get() = "up"
    }

    data class Child(val dir: File) : BrowserRow {
        override val key get() = "d:" + dir.path
    }
}

private fun summary(path: String?, listing: Listing, extensions: Set<String>?, noVolumes: Boolean): String = when {
    path == null && noVolumes -> "Android reports no mounted storage. Insert or mount the card, then open this again."
    path == null -> "Choose where to look."
    else -> when (listing) {
        Listing.Loading -> "Reading…"
        is Listing.Unreadable -> if (listing.missing) {
            "This folder is gone (the card may have been removed). Go up and choose another."
        } else {
            "Android doesn't let Sortie read this folder (Android/data and Android/obb belong to other apps). Choose another."
        }
        is Listing.Ready -> {
            val sub = if (listing.dirs.isEmpty()) "no subfolders" else plural(listing.dirs.size, "subfolder")
            when {
                extensions == null -> "${plural(listing.files, "file")} directly here, $sub."
                extensions.isEmpty() -> "${plural(listing.files, "file")} directly here (this system lists every file), $sub."
                else -> "${plural(listing.matching, "game file")} of this system's types directly here, $sub."
            }
        }
    }
}
