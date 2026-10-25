package io.github.esko997.sortie.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.Display
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import io.github.esko997.sortie.BuiltinApp
import io.github.esko997.sortie.BuiltinApps
import io.github.esko997.sortie.R
import io.github.esko997.sortie.data.magazine.ReaderLocation

/** The built-in's icon, for Apps and the app pickers. */
fun BuiltinApp.icon(context: Context): Drawable? = context.getDrawable(
    when (this) {
        BuiltinApp.MAGAZINES -> R.drawable.ic_builtin_magazines
        BuiltinApp.PERFORMANCE -> R.drawable.ic_builtin_performance
    }
)

/** What the built-in is, under its name in the Apps list. */
val BuiltinApp.summary: String
    get() = when (this) {
        BuiltinApp.MAGAZINES -> "Offline magazines and your scrapbook"
        BuiltinApp.PERFORMANCE -> "Live load, clocks, memory and temperatures"
    }

/**
 * Starts [app] on [displayId] (or brings the running one there with its place kept); says so when Android refused. [opened]
 * sends the magazine reader to an article.
 */
fun openBuiltin(context: Context, app: BuiltinApp, displayId: Int, opened: ReaderLocation? = null): Boolean =
    BuiltinApps.open(context, app, displayId, opened).also { ok ->
        if (!ok) Toast.makeText(context, "Couldn't open ${app.label}", Toast.LENGTH_SHORT).show()
    }

/**
 * Opening a built-in from a screen of Sortie's navigation, which runs on one display: with one screen it opens here at once;
 * with two, a small sheet asks for this screen or the other one. Pickers that already know their screen call [openBuiltin].
 */
@Composable
fun rememberBuiltinOpener(): (BuiltinApp) -> Unit {
    val context = LocalContext.current
    val view = LocalView.current
    var choosing by remember { mutableStateOf<BuiltinApp?>(null) }
    // read at the press, never from when the screen was composed: the navigation may have moved to the other screen since
    val here = { view.display?.displayId ?: Display.DEFAULT_DISPLAY }
    choosing?.let { app ->
        val other = BuiltinApps.otherDisplay(context, here())
        ContextSheet(
            "Open ${app.label}",
            listOfNotNull(
                SheetAction("Open here") { openBuiltin(context, app, here()) },
                other?.let { SheetAction("Open on the other screen") { openBuiltin(context, app, it) } }
            )
        ) { choosing = null }
    }
    return remember<(BuiltinApp) -> Unit>(context, view) {
        { app ->
            if (BuiltinApps.otherDisplay(context, here()) == null) {
                openBuiltin(context, app, here())
            } else {
                choosing = app
            }
        }
    }
}
