package io.github.esko997.sortie.ui

import android.app.Activity
import android.graphics.drawable.Drawable
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.esko997.sortie.data.apps.ShortcutCreator

/**
 * Installed apps plus launcher shortcuts other apps handed to Sortie (pinned via ShortcutPinActivity, or pulled here with
 * "Add shortcut from…", which starts an app's `ACTION_CREATE_SHORTCUT` activity and accepts the pin request it returns).
 */
@Composable
fun AppsScreen(vm: SortieViewModel) {
    val apps by vm.apps.collectAsState()
    val allShortcuts by vm.shortcuts.collectAsState()
    val user by vm.user.collectAsState()
    val context = LocalContext.current
    val shortcuts = remember(allShortcuts) { allShortcuts.filter { !it.manifestOnly } }
    val appLabels = remember(apps) { apps.associate { it.pkg to it.label } }
    var showCreators by rememberSaveable { mutableStateOf(false) }
    val creators by produceState(emptyList<ShortcutCreator>(), showCreators) { if (showCreators) value = vm.shortcutCreators() }
    val createShortcut = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val label = vm.acceptShortcut(r.data)
        val message = label?.let { "Added to Sortie: $it" } ?: "That app returned an old-style shortcut; Sortie can't use it"
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        if (label != null) showCreators = false
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item { Text("Apps · ${apps.size}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp)) }
            item(key = "add-shortcut") {
                FocusRow(onClick = { showCreators = !showCreators }) {
                    Text(
                        (if (showCreators) "▾ " else "▸ ") + "Add shortcut from…",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
            if (showCreators) {
                items(creators, key = { "create:" + it.component.flattenToShortString() }) { c ->
                    LauncherRow(c.label, c.component.packageName, favourite = false, onClick = {
                        runCatching { createShortcut.launch(vm.createShortcutIntent(c)) }
                            .onFailure { Toast.makeText(context, "Can't open ${c.label}: ${it.message}", Toast.LENGTH_SHORT).show() }
                    }) { vm.appIcon(c.component.packageName) }
                }
            }
            if (shortcuts.isNotEmpty()) {
                item { SectionTitle("Shortcuts · ${shortcuts.size}") }
                items(shortcuts, key = { it.id }) { s ->
                    LauncherRow(
                        s.label,
                        "shortcut · ${appLabels[s.pkg] ?: s.pkg}",
                        favourite = s.id in user.favourites,
                        onClick = { vm.launchShortcut(s) },
                        onLongClick = { vm.toggleFavourite(s.id) }
                    ) { vm.shortcutIcon(s) }
                }
                item { SectionTitle("Apps") }
            }
            items(apps, key = { it.id }) { app ->
                LauncherRow(
                    app.label,
                    app.pkg,
                    favourite = app.id in user.favourites,
                    onClick = { vm.launchApp(app) },
                    onLongClick = { vm.toggleFavourite(app.id) }
                ) { vm.appIcon(app.pkg) }
            }
        }
        HintBar("A" to "open", "hold A" to "favourite", "B" to "back")
    }
}

/**
 * Row for an app, shortcut or shortcut source: icon, label (★ when favourited), subtitle. [tall] puts the icon in a cover-sized
 * slot so the row lines up with GameRow in mixed folder lists.
 */
@Composable
fun LauncherRow(
    label: String,
    subtitle: String,
    favourite: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    minutes: Int? = null,
    tall: Boolean = false,
    icon: () -> Drawable?
) {
    FocusRow(onClick = onClick, onLongClick = onLongClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (tall) {
                Box(Modifier.size(56.dp, 72.dp).clip(RoundedCornerShape(6.dp)).background(Slate), contentAlignment = Alignment.Center) {
                    IconView(icon, 44)
                }
            } else {
                IconView(icon, 40)
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text((if (favourite) "★ " else "") + label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if ((minutes ?: 0) > 0) Row { Badge("${minutes}m played", Color(0xFF14532D)) }
            }
        }
    }
}

@Composable
private fun IconView(icon: () -> Drawable?, size: Int) {
    AndroidView(factory = { ImageView(it) }, modifier = Modifier.size(size.dp)) { it.setImageDrawable(icon()) }
}
