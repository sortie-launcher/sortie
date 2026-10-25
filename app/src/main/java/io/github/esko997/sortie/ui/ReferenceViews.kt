package io.github.esko997.sortie.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.size.Scale
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.data.markdown.MdBlock
import io.github.esko997.sortie.data.markdown.parseMarkdown
import io.github.esko997.sortie.data.reference.ReferenceCard
import io.github.esko997.sortie.data.reference.ReferenceCards
import io.github.esko997.sortie.data.reference.ReferenceFiles
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** [gameId]'s reference cards, live: an edit or import on either screen shows at once. */
@Composable
fun rememberReferenceCards(graph: SortieApp.Graph, gameId: String): List<ReferenceCard> {
    val flow = remember(gameId) { graph.user.data.map { it.referenceCards[gameId].orEmpty() }.distinctUntilChanged() }
    return flow.collectAsState(graph.user.data.value.referenceCards[gameId].orEmpty()).value
}

/**
 * Which card the companion shows for each game and whether its reference view is open. Process-wide, not composition state:
 * the switcher, the launcher, the magazine panel or browsing another game replace the companion's content for a while and must
 * give it back exactly as it was. A new play session of the game opens on Now playing again, on the card last shown.
 */
object ReferenceSelection {
    data class Pick(val cardId: String? = null, val open: Boolean = false, val session: Long = 0)

    private val picks = MutableStateFlow<Map<String, Pick>>(emptyMap())

    @Composable
    fun pick(gameId: String, session: Long): Pick {
        val all by picks.collectAsState()
        val p = all[gameId] ?: return Pick(session = session)
        return if (p.session == session) p else p.copy(open = false, session = session)
    }

    fun show(gameId: String, session: Long, cardId: String?, open: Boolean = true) = picks.update { it + (gameId to Pick(cardId, open, session)) }
}

/** The touch entry on Now playing; only composed while the game has cards. */
@Composable
fun ReferenceEntryButton(count: Int, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Amber.copy(alpha = 0.12f), contentColor = Amber)
    ) {
        Text(if (count == 1) "Reference card" else "Reference cards · $count")
    }
}

/**
 * The companion's reference view while a game is played: close, previous/next and the card, all touch. Nothing here requests
 * focus or keeps the controller ([io.github.esko997.sortie.CompanionHost] hands it back to the game after every touch). The
 * top row sits between the battery and bell corners and the card stops above the dock dot and '+', so every corner control
 * stays reachable.
 */
@Composable
fun ReferenceCompanion(
    graph: SortieApp.Graph,
    cards: List<ReferenceCard>,
    card: ReferenceCard,
    status: String,
    onShow: (ReferenceCard) -> Unit,
    onClose: () -> Unit
) {
    val index = cards.indexOfFirst { it.id == card.id }
    Column(Modifier.fillMaxSize().padding(top = 16.dp, bottom = 68.dp)) {
        Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 68.dp), verticalAlignment = Alignment.CenterVertically) {
            TouchChip("Close", onClick = onClose)
            TouchChip("‹", enabled = cards.size > 1, onClickLabel = "Previous card") { ReferenceCards.neighbour(cards, card.id, -1)?.let(onShow) }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(card.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${index + 1} of ${cards.size} · $status",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TouchChip("›", enabled = cards.size > 1, onClickLabel = "Next card") { ReferenceCards.neighbour(cards, card.id, 1)?.let(onShow) }
        }
        ReferenceCardBody(card, graph.sortieDir, Modifier.weight(1f).fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 16.dp))
    }
}

@Composable
private fun TouchChip(text: String, enabled: Boolean = true, onClickLabel: String? = null, onClick: () -> Unit) {
    Box(
        Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Slate.copy(alpha = if (enabled) 0.9f else 0.4f))
            .clickable(enabled = enabled, onClickLabel = onClickLabel ?: text, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (enabled) Amber else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = if (text.length == 1) 22.sp else 15.sp)
    }
}

/**
 * A card's content: an image card's image (pan/zoom) with its notes beneath, or a text card's Markdown. With [controller] the
 * content takes the D-pad focus through [focusRequester]: it scrolls or pans, and left/right at rest call [onStep].
 */
@Composable
fun ReferenceCardBody(
    card: ReferenceCard,
    sortieDir: File,
    modifier: Modifier = Modifier,
    controller: Boolean = false,
    focusRequester: FocusRequester? = null,
    onStep: ((Int) -> Unit)? = null
) {
    if (card.image == null) {
        ReferenceMarkdown(card.body, modifier, controller, focusRequester, onStep)
        return
    }
    Column(modifier) {
        val file = ReferenceFiles.resolve(sortieDir, card.image)
        val imageModifier = Modifier.weight(1f).fillMaxWidth()
        if (file == null || !file.isFile) {
            MissingImage("The image for this card is missing (${card.image}). Delete the card, or import the image again.", imageModifier)
        } else {
            ZoomableImage(file, card.title, imageModifier, controller, focusRequester, onStep)
        }
        if (card.body.isNotBlank()) {
            Box(Modifier.fillMaxWidth().heightIn(max = 140.dp).padding(top = 8.dp).verticalScroll(rememberScrollState())) {
                Column { MarkdownBlocks(remember(card.body) { parseMarkdown(card.body) }) }
            }
        }
    }
}

@Composable
private fun MissingImage(message: String, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(24.dp)
        )
    }
}

/** True for the key-down of a fresh press (not a hold's repeat). */
private fun KeyEvent.freshDown(): Boolean = type == KeyEventType.KeyDown && nativeKeyEvent.repeatCount == 0

/** Text card content in Sortie's Markdown subset, touch-scrollable; with [controller] the D-pad scrolls it. */
@Composable
private fun ReferenceMarkdown(body: String, modifier: Modifier, controller: Boolean, focusRequester: FocusRequester?, onStep: ((Int) -> Unit)?) {
    val blocks = remember(body) { parseMarkdown(body) }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val keys = if (!controller) {
        Modifier
    } else {
        Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusGlow(focused, RoundedCornerShape(8.dp), focusedScale = 1f)
            .onPreviewKeyEvent { e ->
                when (e.key) {
                    Key.DirectionDown -> {
                        if (!state.canScrollForward) return@onPreviewKeyEvent false
                        if (e.type == KeyEventType.KeyDown) scope.launch { state.scrollBy(240f) }
                        true
                    }
                    // at the top the focus moves up to the card's buttons
                    Key.DirectionUp -> {
                        if (!state.canScrollBackward) return@onPreviewKeyEvent false
                        if (e.type == KeyEventType.KeyDown) scope.launch { state.scrollBy(-240f) }
                        true
                    }
                    Key.DirectionLeft, Key.DirectionRight -> {
                        val step = onStep ?: return@onPreviewKeyEvent false
                        if (e.freshDown()) step(if (e.key == Key.DirectionLeft) -1 else 1)
                        true
                    }
                    else -> false
                }
            }
            .focusable(interactionSource = interaction)
    }
    LazyColumn(modifier.then(keys), state = state, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
        if (blocks.isEmpty()) {
            item { Text("This card is empty.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(blocks) { MarkdownBlock(it) }
    }
}

@Composable
private fun MarkdownBlocks(blocks: List<MdBlock>) {
    blocks.forEach { MarkdownBlock(it) }
}

/** The manual's rendering of the shared subset; a card's image lines show their caption (only imported images are shown). */
@Composable
private fun MarkdownBlock(b: MdBlock) {
    val body = MaterialTheme.typography.bodyLarge
    when (b) {
        is MdBlock.Heading -> when (b.level) {
            1 -> Text(
                b.text,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
            )
            2 -> Text(b.text, style = MaterialTheme.typography.titleLarge, color = Amber, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            else -> Text(
                b.text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
            )
        }
        is MdBlock.Paragraph -> Text(inlineText(b.text), style = body, modifier = Modifier.padding(bottom = 10.dp))
        is MdBlock.Bullet -> Row(Modifier.padding(start = 4.dp, bottom = 6.dp)) {
            Text("•", style = body, color = Amber, modifier = Modifier.width(18.dp))
            Text(inlineText(b.text), style = body)
        }
        is MdBlock.Quote -> Row(Modifier.padding(vertical = 4.dp).height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(Amber))
            Text(inlineText(b.text), style = body, fontStyle = FontStyle.Italic, modifier = Modifier.padding(start = 12.dp, bottom = 6.dp))
        }
        is MdBlock.Image -> Text(
            "Image not included: " + b.caption.ifBlank { b.src },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 10.dp)
        )
        MdBlock.Rule -> Box(Modifier.padding(vertical = 10.dp).fillMaxWidth().height(1.dp).background(Hairline))
    }
}

/**
 * A local image fitted to the box, pinch-zoomed and dragged by touch, double tap toggling zoom. The decode keeps up to
 * [io.github.esko997.sortie.data.reference.ReferenceLimits.DISPLAY_MAX_PIXELS] (see [ReferenceCards.displaySize]) so a map
 * stays legible when zoomed, and zoom reaches at least 3 screen pixels per image pixel; magnified pixel art is drawn unsmoothed.
 * With [controller]: D-pad pans while zoomed (left/right at rest call [onStep]), A toggles zoom, R2/L2 zoom in/out. [onTap]: a
 * single tap (not a double tap or a drag).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ZoomableImage(
    file: File,
    description: String,
    modifier: Modifier = Modifier,
    controller: Boolean = false,
    focusRequester: FocusRequester? = null,
    onStep: ((Int) -> Unit)? = null,
    onTap: (() -> Unit)? = null
) {
    val bounds by produceState<IntSize?>(null, file) {
        value = withContext(Dispatchers.IO) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, o)
            IntSize(max(0, o.outWidth), max(0, o.outHeight))
        }
    }
    val b = bounds
    if (b == null) {
        Box(modifier)
        return
    }
    var failed by remember(file) { mutableStateOf(false) }
    if (b.width == 0 || b.height == 0 || failed) {
        MissingImage("${file.name} could not be decoded. Remove it and import the image again.", modifier)
        return
    }
    val (dw, dh) = remember(b) { ReferenceCards.displaySize(b.width, b.height) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember(file) { mutableFloatStateOf(1f) }
    var offset by remember(file) { mutableStateOf(Offset.Zero) }
    val currentTap by rememberUpdatedState(onTap)
    val tap = { currentTap?.invoke() }
    val aspect = dw.toFloat() / dh
    val fw = if (box.width == 0) 0f else min(box.width.toFloat(), box.height * aspect)
    val fh = fw / aspect
    val maxScale = if (fw > 0f) (3f * dw / fw).coerceIn(4f, 32f) else 4f

    fun clamp(o: Offset, s: Float): Offset {
        val mx = max(0f, (fw * s - box.width) / 2f)
        val my = max(0f, (fh * s - box.height) / 2f)
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    // keeps the image point under [focus] (relative to the box centre) where it is
    fun zoomTo(target: Float, focus: Offset) {
        val s = target.coerceIn(1f, maxScale)
        val k = s / scale
        offset = clamp(focus * (1 - k) + offset * k, s)
        scale = s
    }

    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    if (controller && focusRequester != null) {
        val inputMode = LocalInputModeManager.current
        // The focus target does not exist until the asynchronous bounds read above completes.
        // Request here, not in the parent viewer while it still contains the loading box.
        LaunchedEffect(file, focusRequester) {
            repeat(20) {
                if (focused) return@LaunchedEffect
                inputMode.requestInputMode(InputMode.Keyboard)
                runCatching { focusRequester.requestFocus() }
                delay(40)
            }
        }
    }
    val keys = if (!controller) {
        Modifier
    } else {
        Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusGlow(focused, RoundedCornerShape(8.dp), focusedScale = 1f)
            .onPreviewKeyEvent { e ->
                val zoomed = scale > 1.01f
                when (e.key) {
                    Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown -> {
                        if (!zoomed) {
                            val step = onStep
                            if (step == null || e.key == Key.DirectionUp || e.key == Key.DirectionDown) return@onPreviewKeyEvent false
                            if (e.freshDown()) step(if (e.key == Key.DirectionLeft) -1 else 1)
                            return@onPreviewKeyEvent true
                        }
                        if (e.type == KeyEventType.KeyDown) {
                            val dx = box.width * 0.2f
                            val dy = box.height * 0.2f
                            // the D-pad moves the view: pressing right shows what lies to the right
                            val pan = when (e.key) {
                                Key.DirectionLeft -> Offset(dx, 0f)
                                Key.DirectionRight -> Offset(-dx, 0f)
                                Key.DirectionUp -> Offset(0f, dy)
                                else -> Offset(0f, -dy)
                            }
                            offset = clamp(offset + pan, scale)
                        }
                        true
                    }
                    Key.ButtonA, Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (e.freshDown()) {
                            if (zoomed) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                zoomTo(2.5f, Offset.Zero)
                            }
                        }
                        true
                    }
                    Key.ButtonR2, Key.ButtonL2 -> {
                        if (e.type == KeyEventType.KeyDown) zoomTo(scale * if (e.key == Key.ButtonR2) 1.5f else 1 / 1.5f, Offset.Zero)
                        true
                    }
                    else -> false
                }
            }
            .focusable(interactionSource = interaction)
    }
    val context = LocalPlatformContext.current
    val request = remember(file, dw, dh) {
        ImageRequest.Builder(context)
            .data(file)
            .size(dw, dh)
            .scale(Scale.FIT)
            .precision(Precision.EXACT)
            .allowHardware(true)
            .build()
    }
    Box(
        modifier
            .then(keys)
            .clipToBounds()
            .onSizeChanged { box = it }
            .pointerInput(file, fw, fh, maxScale) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    zoomTo(scale * zoom, centroid - Offset(box.width / 2f, box.height / 2f))
                    offset = clamp(offset + pan, scale)
                }
            }
            .pointerInput(file, fw, fh, maxScale, onTap != null) {
                detectTapGestures(
                    onTap = if (onTap == null) null else { _ -> tap() },
                    onDoubleTap = { p ->
                        if (scale > 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            zoomTo(3f, p - Offset(box.width / 2f, box.height / 2f))
                        }
                    }
                )
            }
    ) {
        // past 1.5 screen pixels per image pixel, smoothing only blurs pixel-art maps
        val magnified = fw > 0f && scale * fw / dw > 1.5f
        AsyncImage(
            model = request,
            contentDescription = description,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
            onState = { if (it is AsyncImagePainter.State.Error) failed = true },
            contentScale = ContentScale.Fit,
            filterQuality = if (magnified) FilterQuality.None else FilterQuality.Low
        )
        if (scale > 1.01f) {
            Text(
                "%.1f×".format(scale),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(Ink.copy(alpha = 0.7f))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

/** Small preview of an image card for lists; nothing when the file is missing (the row says so). */
@Composable
fun ReferenceThumbnail(card: ReferenceCard, sortieDir: File, modifier: Modifier = Modifier) {
    Box(modifier.size(72.dp, 56.dp).clip(RoundedCornerShape(6.dp)).background(Slate), contentAlignment = Alignment.Center) {
        val file = ReferenceFiles.resolve(sortieDir, card.image)
        if (card.image == null) {
            Text("¶", color = Amber, fontSize = 24.sp)
        } else if (file != null && file.isFile) {
            CoverImage(file.path, Modifier.size(72.dp, 56.dp), ContentScale.Crop)
        } else {
            Text("?", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 20.sp)
        }
    }
}
