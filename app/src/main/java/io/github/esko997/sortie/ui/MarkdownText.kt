package io.github.esko997.sortie.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import io.github.esko997.sortie.data.markdown.parseInline

private val LinkTarget = Color(0xFF9AA3AD)

/**
 * Inline Markdown (see [parseInline]) as styled text. A link is attribution, not navigation: its text is underlined and its
 * target follows in muted brackets, so a source stays readable offline and nothing on screen promises to open a browser.
 */
fun inlineText(text: String): AnnotatedString = buildAnnotatedString {
    for (run in parseInline(text)) {
        val link = run.link
        when {
            link != null -> {
                withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(run.text) }
                if (link != run.text) withStyle(SpanStyle(color = LinkTarget, fontSize = 0.85.em)) { append(" ($link)") }
            }
            run.bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(run.text) }
            run.italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(run.text) }
            else -> append(run.text)
        }
    }
}
