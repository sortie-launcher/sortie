package io.github.esko997.sortie.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The bundled user manual (`assets/MANUAL.md`), rendered by a deliberately tiny subset of Markdown: `#`/`##` headings, `-`
 * bullets, paragraphs joined across soft line breaks, `**bold**` and `*italic*`. The grid takes the D-pad; the text scrolls.
 */
@Composable
fun ManualScreen() {
    val context = LocalContext.current
    val blocks by produceState(emptyList<Block>()) {
        value = withContext(Dispatchers.IO) { parse(context.assets.open("MANUAL.md").bufferedReader().readText()) }
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
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
        ) {
            items(blocks) { b ->
                when (b.kind) {
                    Kind.H1 -> Text(
                        b.text,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Kind.H2 -> Text(b.text, style = MaterialTheme.typography.titleLarge, color = Amber, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
                    Kind.BULLET -> Text(
                        inline("•  " + b.text),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)
                    )
                    Kind.PARA -> Text(inline(b.text), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 10.dp))
                }
            }
        }
        HintBar("D-pad" to "scroll", "B" to "back")
    }
}

private enum class Kind { H1, H2, BULLET, PARA }

private class Block(val kind: Kind, val text: String)

private fun parse(md: String): List<Block> {
    val out = ArrayList<Block>()
    val para = StringBuilder()
    fun flush() {
        if (para.isNotEmpty()) out += Block(Kind.PARA, para.toString())
        para.clear()
    }
    for (raw in md.lines()) {
        val line = raw.trim()
        when {
            line.isEmpty() -> flush()
            line.startsWith("## ") -> {
                flush()
                out += Block(Kind.H2, line.removePrefix("## "))
            }
            line.startsWith("# ") -> {
                flush()
                out += Block(Kind.H1, line.removePrefix("# "))
            }
            line.startsWith("- ") -> {
                flush()
                out += Block(Kind.BULLET, line.removePrefix("- "))
            }
            out.lastOrNull()?.kind == Kind.BULLET && para.isEmpty() && raw.startsWith("  ") -> // wrapped bullet continuation
                out[out.lastIndex] = Block(Kind.BULLET, out.last().text + " " + line)
            else -> {
                if (para.isNotEmpty()) para.append(' ')
                para.append(line)
            }
        }
    }
    flush()
    return out
}

/** `**bold**` and `*italic*` spans; anything else verbatim. */
private fun inline(text: String): AnnotatedString = buildAnnotatedString {
    val rx = Regex("""\*\*(.+?)\*\*|\*(.+?)\*""")
    var i = 0
    for (m in rx.findAll(text)) {
        append(text, i, m.range.first)
        val bold = m.groups[1]?.value
        if (bold != null) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(bold)
            }
        } else {
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(m.groups[2]!!.value) }
        }
        i = m.range.last + 1
    }
    append(text, i, text.length)
}
