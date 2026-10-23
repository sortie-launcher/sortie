package io.github.esko997.sortie

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/**
 * Confirms `ShortcutManager.requestPinShortcut` from other apps (DroidDeck's "add to home screen") while Sortie is the default
 * launcher: Android only offers pinning when HOME declares a CONFIRM_PIN_SHORTCUT activity. Translucent, no UI beyond a toast;
 * the pinned shortcut then shows up under Apps and can be favourited or matched by `apps: true` folders.
 */
class ShortcutPinActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = (application as SortieApp).graph.shortcuts.accept(intent)
        Toast.makeText(applicationContext, label?.let { "Added to Sortie: $it" } ?: "Sortie can only add app shortcuts", Toast.LENGTH_SHORT).show()
        finish()
    }
}
