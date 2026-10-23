package io.github.esko997.sortie

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import io.github.esko997.sortie.ui.Selection
import kotlinx.coroutines.launch

/**
 * Companion panel on the DEFAULT display while Sortie's navigation runs on a secondary one (a Presentation cannot target the
 * default display). Lives in its own task so it never steals focus from the navigation screen; closed when Sortie swaps back.
 */
class CompanionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = CompanionView(this)
        setContentView(view.root)
        lifecycleScope.launch { Selection.current.collect(view::show) }
        lifecycleScope.launch { Selection.closeCompanion.collect { finishAndRemoveTask() } }
    }
}
