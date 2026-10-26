package io.github.esko997.sortie.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp
import io.github.esko997.sortie.data.markdown.MdBlock
import io.github.esko997.sortie.data.markdown.parseMarkdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The bundled user manual (`assets/MANUAL.md`), rendered by the shared Markdown subset ([parseMarkdown], [MarkdownBlock]); the
 * manual itself uses `#`/`##`/`###` headings, `-` bullets, paragraphs joined across soft line breaks, `**bold**` and `*italic*`.
 * The D-pad scrolls the text.
 */
@Composable
fun ManualScreen() {
    val context = LocalContext.current
    val blocks by produceState(emptyList<MdBlock>()) {
        value = withContext(Dispatchers.IO) { parseMarkdown(context.assets.open("MANUAL.md").bufferedReader().readText()) }
    }
    val scrollState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(Unit) {
        repeat(20) {
            @OptIn(ExperimentalComposeUiApi::class)
            inputMode.requestInputMode(InputMode.Keyboard)
            if (runCatching { focusRequester.requestFocus() }.isSuccess) return@LaunchedEffect
            kotlinx.coroutines.delay(40)
        }
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionDown -> {
                            scope.launch { scrollState.scrollBy(160f) }
                            true
                        }
                        Key.DirectionUp -> {
                            scope.launch { scrollState.scrollBy(-160f) }
                            true
                        }
                        Key.PageDown -> {
                            scope.launch { scrollState.scrollBy(500f) }
                            true
                        }
                        Key.PageUp -> {
                            scope.launch { scrollState.scrollBy(-500f) }
                            true
                        }
                        else -> false
                    }
                },
            state = scrollState,
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
            // the same measure and block styles as a reference card's text, so long lines stay readable on the wide screen
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val measure = Modifier.widthIn(max = MarkdownMeasure).fillMaxWidth()
            items(blocks) { MarkdownBlock(it, measure) }
        }
        HintBar("D-pad" to "scroll", "B" to "back")
    }
}
