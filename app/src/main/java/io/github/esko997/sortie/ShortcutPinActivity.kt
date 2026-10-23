package io.github.esko997.sortie

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/**
 * Confirms `ShortcutManager.requestPinShortcut` from other apps (DroidDeck's "add to home screen") while Sortie is the default
 * launcher: Android only offers pinning when HOME declares a CONFIRM_PIN_SHORTCUT activity. Translucent, no UI beyond a toast;
 * pinning is the user's explicit act, so the shortcut is opted in to the Apps screen (`user.shortcuts`) and can then be
 * favourited, placed on home or matched by `apps: true` folders.
 */
class ShortcutPinActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as SortieApp).graph
        val entry = graph.shortcuts.accept(intent)?.also { graph.user.showShortcut(it.id, true) }
        Toast.makeText(applicationContext, entry?.let { "Added to Sortie: ${it.label}" } ?: "Sortie can only add app shortcuts", Toast.LENGTH_SHORT).show()
        finish()
    }
}
