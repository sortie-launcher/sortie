package io.github.esko997.sortie.ui

import android.widget.ImageView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.esko997.sortie.data.apps.AppEntry

@Composable
fun AppsScreen(vm: SortieViewModel) {
    val apps by vm.apps.collectAsState()
    val user by vm.user.collectAsState()
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item { Text("Apps · ${apps.size}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp)) }
            items(apps, key = {
                it.pkg
            }) { app ->
                AppRow(app, favourite = app.id in user.favourites, onLaunch = {
                    vm.launchApp(app)
                }, onFavourite = { vm.toggleFavourite(app.id) }, icon = { vm.appIcon(app.pkg) })
            }
        }
        HintBar("A" to "open", "hold A" to "favourite", "B" to "back")
    }
}

@Composable
fun AppRow(app: AppEntry, favourite: Boolean, onLaunch: () -> Unit, onFavourite: () -> Unit, icon: () -> android.graphics.drawable.Drawable?) {
    FocusRow(onClick = onLaunch, onLongClick = onFavourite) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AndroidView(factory = { ImageView(it) }, modifier = Modifier.size(40.dp)) { it.setImageDrawable(icon()) }
            Column(Modifier.padding(start = 12.dp)) {
                Text((if (favourite) "★ " else "") + app.label, style = MaterialTheme.typography.titleMedium)
                Text(app.pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
