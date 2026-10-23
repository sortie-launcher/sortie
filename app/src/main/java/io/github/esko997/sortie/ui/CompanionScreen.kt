package io.github.esko997.sortie.ui

import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.shell.TaskSwitcher
import java.io.File

/**
 * What the other screen shows: the selected game's art and hook (or a rotating hero when nothing is selected), and, while the
 * switcher is open, the apps running on this very display as a touch carousel. The hosting window is not focusable, so every
 * control here is touch.
 */
@Composable
fun CompanionScreen(displayId: Int) {
    val graph = (LocalContext.current.applicationContext as SortieApp).graph
    val switcher by graph.switcher.state.collectAsState()
    val game by Selection.panel().collectAsState(null)
    Surface(Modifier.fillMaxSize(), color = Ink) {
        if (switcher.open) {
            TouchSwitcher(graph.switcher, switcher, displayId)
        } else {
            Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val art = game?.screenshot ?: game?.cover
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (art != null) {
                        AsyncImage(
                            model = File(art),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                val g = game
                Text(
                    g?.title ?: "Sortie",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp)
                )
                Text(
                    g?.hook ?: listOfNotNull(g?.year?.toString(), g?.developer, g?.genre).joinToString(" · "),
                    color = Amber,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
                )
            }
        }
    }
}

/** This display's running apps; tap raises one here, the button sends it to the other screen. Empty screen offers the others. */
@Composable
private fun TouchSwitcher(switcher: TaskSwitcher, state: TaskSwitcher.State, displayId: Int) {
    val here = state.on(displayId)
    val elsewhere = state.entries.filter { it.task.displayId != displayId }
    val bringing = here.isEmpty()
    val shown = if (bringing) elsewhere else here
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            when {
                !state.ready -> "Switcher needs Shizuku"
                state.entries.isEmpty() -> "Nothing running"
                bringing -> "This screen is free · tap an app to bring it here"
                else -> "On this screen"
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(shown, key = { it.task.id }) { e ->
                Column(
                    Modifier.width(150.dp).clip(RoundedCornerShape(12.dp)).background(Slate).clickable(enabled = !state.busy) {
                        if (bringing) switcher.moveTo(e, displayId) else switcher.bringToFront(e)
                    }.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AndroidView(factory = { ImageView(it) }, modifier = Modifier.size(64.dp)) { it.setImageDrawable(e.icon) }
                    Text(e.label, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                    val other = switcher.other(displayId)
                    if (!bringing && other != null) {
                        OutlinedButton(onClick = { switcher.moveTo(e, other) }, enabled = !state.busy) {
                            Text("Other screen")
                        }
                    }
                }
            }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { switcher.anchor(displayId) }, enabled = state.entries.isNotEmpty()) { Text("Controller here") }
            OutlinedButton(onClick = { switcher.close() }) { Text("Close") }
        }
    }
}
