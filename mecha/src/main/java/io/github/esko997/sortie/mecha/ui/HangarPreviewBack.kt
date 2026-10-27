package io.github.esko997.sortie.mecha.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf

/** Dialog Back first clears the innermost local preview, never writes companion state. */
internal class HangarPreviewBackStack {
    private val handlers = mutableListOf<() -> Unit>()

    fun add(handler: () -> Unit) {
        handlers.add(handler)
    }
    fun remove(handler: () -> Unit) {
        handlers.remove(handler)
    }
    fun cancel(): Boolean {
        val handler = handlers.lastOrNull() ?: return false
        handler()
        return true
    }
}

internal val LocalHangarPreviewBack = staticCompositionLocalOf<HangarPreviewBackStack?> { null }

@Composable
internal fun HangarPreviewBack(enabled: Boolean, onCancel: () -> Unit) {
    val stack = LocalHangarPreviewBack.current
    val cancel = rememberUpdatedState(onCancel)
    DisposableEffect(stack, enabled) {
        val handler = { cancel.value() }
        if (enabled) stack?.add(handler)
        onDispose { if (enabled) stack?.remove(handler) }
    }
}
