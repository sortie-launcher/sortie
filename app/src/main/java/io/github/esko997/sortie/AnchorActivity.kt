package io.github.esko997.sortie

import android.app.Activity
import android.os.Bundle

/**
 * Does nothing but exist for one frame on a chosen display. Android hands key focus to the display an activity was last started
 * on, so starting this here and finishing at once leaves the controller with whatever is on top of that display (the game).
 * Transparent, no history, never in recents (manifest).
 */
class AnchorActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
