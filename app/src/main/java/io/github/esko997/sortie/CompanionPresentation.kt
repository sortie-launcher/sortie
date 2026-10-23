package io.github.esko997.sortie

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.github.esko997.sortie.ui.Selection
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Companion panel on a secondary display while Sortie runs on the default one. */
class CompanionPresentation(context: Context, display: Display, private val owner: LifecycleOwner) : Presentation(context, display) {
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = CompanionView(context)
        setContentView(view.root)
        job = owner.lifecycleScope.launch { Selection.panel().collect(view::show) }
    }

    override fun onStop() {
        job?.cancel()
        super.onStop()
    }
}
