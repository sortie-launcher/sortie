package io.github.esko997.sortie.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun DiagnosticsScreen(vm: SortieViewModel) {
    val index by vm.index.collectAsState()
    val log by vm.launchLog.collectAsState()
    val snap by vm.manifests.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
        item {
            Text("Diagnostics", style = MaterialTheme.typography.titleLarge)
            Row(Modifier.padding(vertical = 8.dp)) {
                Button(onClick = { vm.reindex(force = true) }, enabled = !index.running) { Text(if (index.running) "Indexing…" else "Re-index everything") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { vm.reloadManifests() }) { Text("Reload manifests") }
            }
            Text(
                "Manifests: ${snap.systems.size} systems · ${snap.profiles.size} profiles · ${snap.folders.size} folders · guide ${snap.guide.size}",
                style = MaterialTheme.typography.bodySmall
            )
            snap.errors.forEach { Text("! $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            index.report?.let { r ->
                Text("Last index: ${r.files} files, ${r.updated} updated, ${r.removed} removed, ${r.millis} ms", style = MaterialTheme.typography.bodySmall)
                r.problems.forEach { Text("! $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            val shizuku by vm.shizuku.collectAsState()
            val moveResult by vm.moveResult.collectAsState()
            val secondary = snap.device.screens.count > 1
            SectionTitle("Screens (Shizuku)")
            Text("Shizuku: ${shizuku.name.lowercase().replace('_', ' ')}", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.shizukuRefresh() }) { Text("Refresh") }
                OutlinedButton(onClick = { vm.shizukuRequest() }) { Text("Request permission") }
                if (secondary) {
                    OutlinedButton(onClick = { vm.moveTopApp(0, 4) }) { Text("Top app → bottom") }
                    OutlinedButton(onClick = { vm.moveTopApp(4, 0) }) { Text("Bottom app → top") }
                }
            }
            if (moveResult.isNotBlank()) Text(moveResult, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionTitle("Launch log (newest first)")
        }
        items(log) { e ->
            Column(Modifier.padding(vertical = 6.dp)) {
                Text("${e.at}  ${e.gameId}  ${if (e.launched) "✓" else "✗"}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                e.attempts.forEach { a ->
                    Text("  ${a.profileId}: ${a.outcome}", style = MaterialTheme.typography.bodySmall)
                    if (a.command.isNotBlank()) {
                        Text(
                            "    adb shell ${a.command}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Sortie needs access to the library card", style = MaterialTheme.typography.titleLarge)
        Text("Grant \"All files access\" in Android settings, or over adb:", style = MaterialTheme.typography.bodyMedium)
        Text(
            "adb shell appops set ${ctx.packageName} MANAGE_EXTERNAL_STORAGE allow",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = {
            ctx.startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:${ctx.packageName}")
                )
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }) { Text("Open settings") }
    }
}
