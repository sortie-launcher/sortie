package io.github.esko997.sortie.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.esko997.sortie.data.journal.JournalAttachment
import io.github.esko997.sortie.data.journal.JournalMedia
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where an attachment's file stands: still being checked, absent from this card, or present. */
internal sealed interface AttachmentFile {
    data object Checking : AttachmentFile
    data object Missing : AttachmentFile
    data class Found(val file: File) : AttachmentFile
}

/** Resolves and stats [path] off the main thread (the card may be slow). */
@Composable
internal fun rememberAttachmentFile(media: JournalMedia, path: String): State<AttachmentFile> = produceState<AttachmentFile>(AttachmentFile.Checking, path) {
    value = withContext(Dispatchers.IO) { media.file(path)?.takeIf { it.isFile }?.let { AttachmentFile.Found(it) } ?: AttachmentFile.Missing }
}

/** A focusable 16:9 thumbnail; A or a tap opens it. A missing file says so instead of drawing an empty box. */
@Composable
internal fun AttachmentThumb(attachment: JournalAttachment, media: JournalMedia, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var held by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    val state by rememberAttachmentFile(media, attachment.path)
    Box(
        modifier
            .size(160.dp, 90.dp)
            .confirmHeld { held = it }
            .focusGlow(focused, shape, pressed = held)
            .clip(shape)
            .background(Slate)
            .border(1.dp, Hairline, shape)
            .clickable(interactionSource = interaction, indication = PressVeil, role = Role.Image, onClick = onOpen)
            .semantics { contentDescription = "Screenshot ${attachment.name}".trim() },
        contentAlignment = Alignment.Center
    ) {
        when (val s = state) {
            is AttachmentFile.Found -> CoverImage(s.file.path, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            AttachmentFile.Missing -> Text(
                "File missing",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(6.dp)
            )
            AttachmentFile.Checking -> Unit
        }
    }
}

/**
 * [attachments] full screen, fitted to the display, starting at [start]; pinch, double tap, A and L2/R2 zoom, the D-pad pans a
 * zoomed picture and otherwise pages (see [ZoomableImage]). B or Close leaves. The on-screen buttons are touch-only so the D-pad
 * never has to leave the picture.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun JournalViewer(attachments: List<JournalAttachment>, start: Int, media: JournalMedia, onClose: () -> Unit) {
    if (attachments.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    var index by rememberSaveable { mutableIntStateOf(start.coerceIn(0, attachments.lastIndex)) }
    index = index.coerceIn(0, attachments.lastIndex)
    val a = attachments[index]
    val state by rememberAttachmentFile(media, a.path)
    val focus = remember { FocusRequester() }
    val found = state is AttachmentFile.Found
    // the focus target moves between the picture and the fallback below when a file turns out missing
    LaunchedEffect(found) { runCatching { focus.requestFocus() } }
    BackHandler(onBack = onClose)
    val page = { delta: Int -> index = (index + delta).coerceIn(0, attachments.lastIndex) }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(
                    // without a picture to zoom, left/right still page (ZoomableImage handles keys itself when there is one)
                    if (found) {
                        Modifier
                    } else {
                        Modifier.focusRequester(focus).onPreviewKeyEvent { e ->
                            val delta = when (e.key) {
                                Key.DirectionLeft -> -1
                                Key.DirectionRight -> 1
                                else -> return@onPreviewKeyEvent false
                            }
                            if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) page(delta)
                            true
                        }.focusable()
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            when (val s = state) {
                is AttachmentFile.Found -> ZoomableImage(
                    s.file,
                    description = "Screenshot ${a.name}".trim(),
                    modifier = Modifier.fillMaxSize(),
                    controller = true,
                    focusRequester = focus,
                    onStep = page
                )
                AttachmentFile.Missing -> Text(
                    "This screenshot's file is not on this card (${a.path}). Screenshots stay on the card they were attached on; " +
                        "backups hold the note but not the picture.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(24.dp)
                )
                AttachmentFile.Checking -> Unit
            }
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val size = if (a.width > 0 && a.height > 0) "${a.width}×${a.height}" else null
            Text(
                listOfNotNull("${index + 1} of ${attachments.size}", a.name.ifBlank { null }, size).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.CenterVertically)
            )
            val touchOnly = Modifier.focusProperties { canFocus = false }
            OutlinedButton(onClick = { page(-1) }, enabled = index > 0, modifier = touchOnly) { Text("Previous") }
            OutlinedButton(onClick = { page(1) }, enabled = index < attachments.lastIndex, modifier = touchOnly) { Text("Next") }
            OutlinedButton(onClick = onClose, modifier = touchOnly) { Text("Close") }
        }
        HintBar("◀ ▶" to "previous / next", "A" to "zoom", "L2 / R2" to "zoom out / in", "B" to "close")
    }
}
