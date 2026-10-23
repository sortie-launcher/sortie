package io.github.esko997.sortie

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import io.github.esko997.sortie.ui.Selection
import kotlinx.coroutines.launch

/**
 * Companion panel on the DEFAULT display while Sortie's navigation runs on a secondary one (there is no "secondary home" for
 * the default display, so this is an ordinary task there). Closed when Sortie swaps back or sends a game to this screen.
 */
class CompanionActivity : CompanionHost() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch { Selection.closeCompanion.collect { finishAndRemoveTask() } }
    }
}
