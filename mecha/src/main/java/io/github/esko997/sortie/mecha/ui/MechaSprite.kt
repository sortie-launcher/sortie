package io.github.esko997.sortie.mecha.ui

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.esko997.sortie.mecha.MechaFinish
import io.github.esko997.sortie.mecha.MechaPaint
import io.github.esko997.sortie.mecha.MechaPaintScheme
import io.github.esko997.sortie.mecha.MechaPart
import io.github.esko997.sortie.mecha.MechaSlot
import io.github.esko997.sortie.ui.displayLitFlow
import io.github.esko997.sortie.ui.shownFlow
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class MechaPose { IDLE, WALK, SLEEP, CELEBRATE }

/** One authored frame is 64x64 sprite pixels; a sheet holds eight of them side by side (see the art contract). */
internal const val MECHA_FRAME = 64
private const val SHEET_FRAMES = 8

/** Sprite pixels reserved above the frame: the art can reach row 0 and a cheer hops up to this far. */
internal const val MECHA_HEADROOM = 3

/** Legs first, head last: arms overlap the body's shoulders and the head sits over the neck. */
private val DRAW_ORDER = arrayOf(MechaSlot.LEGS, MechaSlot.BODY, MechaSlot.ARMS, MechaSlot.HEAD)

/**
 * A pose's loop: [frames] index the sheet, [bob] lifts the assembled mech by whole sprite pixels (negative is up; parts never
 * bob on their own), each step lasts [stepMs]. [still] is the frame shown while motion is off.
 */
private class PoseLoop(val stepMs: Long, val frames: IntArray, val bob: IntArray, val still: Int)

private val LOOPS = mapOf(
    // a slow breath with one blink per cycle
    MechaPose.IDLE to PoseLoop(
        200,
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, -1, -1, -1, -1, -1, -1, -1, -1),
        0
    ),
    // contact, passing (lifted), contact, passing
    MechaPose.WALK to PoseLoop(120, intArrayOf(2, 3, 4, 5), intArrayOf(0, -1, 0, -1), 0),
    MechaPose.SLEEP to PoseLoop(900, intArrayOf(6, 6), intArrayOf(0, 1), 6),
    // a little hop with the arms up
    MechaPose.CELEBRATE to PoseLoop(130, intArrayOf(7, 7, 7, 7, 0, 0), intArrayOf(0, -2, -3, -2, 0, 0), 7)
)

/** Frame index of this pose's loop at [step] (wrapping). */
internal fun MechaPose.frameAt(step: Int): Int = LOOPS.getValue(this).let { it.frames[step.mod(it.frames.size)] }

/** Whole sprite pixels the assembled mech is lifted at [step] (negative is up). */
internal fun MechaPose.bobAt(step: Int): Int = LOOPS.getValue(this).let { it.bob[step.mod(it.bob.size)] }

internal val MechaPose.steps: Int get() = LOOPS.getValue(this).frames.size
internal val MechaPose.stepMs: Long get() = LOOPS.getValue(this).stepMs

/** The frame shown while nothing moves. */
internal val MechaPose.stillFrame: Int get() = LOOPS.getValue(this).still

/** A pose sheet or vertically stacked material phases. Drawing only selects a source rectangle. */
@Immutable
internal class MechaSheet(val image: ImageBitmap, val phases: Int)

/**
 * A process-wide 12 MiB LRU, not a catalog × paints × phases matrix. The mutex serializes off-main work, bounding
 * transient raster memory and avoiding duplicate decoding. Evicted images are not recycled while visible sprites own
 * them; no completed Deferred retains an evicted image. Original Standard keeps the original bitmap and dimensions.
 */
internal object MechaSheets {
    private data class Key(val path: String, val paint: MechaPaint)

    private val ready = object : LruCache<Key, MechaSheet>(12 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: MechaSheet): Int = value.image.width * value.image.height * 4
    }
    private val decoding = Mutex()

    fun peek(path: String, paint: MechaPaint): MechaSheet? = ready.get(Key(path, paint))

    suspend fun get(assets: AssetManager, part: MechaPart, paint: MechaPaint): MechaSheet {
        val key = Key(part.asset, paint)
        ready.get(key)?.let { return it }
        return withContext(Dispatchers.Default) {
            decoding.withLock {
                ready.get(key) ?: decode(assets, part, paint).also { ready.put(key, it) }
            }
        }
    }

    private fun decode(assets: AssetManager, part: MechaPart, paint: MechaPaint): MechaSheet {
        val bitmap = decodeBitmap(assets, part.asset)
        if (paint.scheme == MechaPaintScheme.ORIGINAL && paint.finish == MechaFinish.PLAIN) {
            bitmap.prepareToDraw()
            return MechaSheet(bitmap.asImageBitmap(), 1)
        }
        val phases = if (paint.finish == MechaFinish.PLAIN) 1 else MECHA_MATERIAL_PHASES
        val material = try {
            val source = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(source, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val mask = decodeBitmap(assets, part.paintAsset, premultiplied = false)
            val roles = try {
                IntArray(source.size).also { mask.getPixels(it, 0, mask.width, 0, 0, mask.width, mask.height) }
            } finally {
                mask.recycle()
            }
            val pixels = mechaMaterialPixels(source, roles, bitmap.width, bitmap.height, paint)
            Bitmap.createBitmap(pixels, bitmap.width, bitmap.height * phases, Bitmap.Config.ARGB_8888)
        } finally {
            bitmap.recycle()
        }
        material.prepareToDraw()
        return MechaSheet(material.asImageBitmap(), phases)
    }

    private fun decodeBitmap(assets: AssetManager, path: String, premultiplied: Boolean = true): Bitmap {
        val options = BitmapFactory.Options().apply {
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = premultiplied
        }
        val bitmap = assets.open(path).use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IllegalStateException("$path is not a readable PNG")
        if (bitmap.width != MECHA_FRAME * SHEET_FRAMES || bitmap.height != MECHA_FRAME) {
            val dimensions = "${bitmap.width}x${bitmap.height}"
            bitmap.recycle()
            error("$path is $dimensions, expected ${MECHA_FRAME * SHEET_FRAMES}x$MECHA_FRAME")
        }
        return bitmap
    }
}

@Immutable
internal sealed interface SpriteLoad {
    data object Loading : SpriteLoad
    class Ready(val layers: List<MechaSheet>) : SpriteLoad
    class Failed(val reason: String) : SpriteLoad
}

/** The equipped parts in draw order, each resolved against the catalog (null: that slot's part is missing). */
@Composable
private fun rememberLayers(parts: List<MechaPart>, equipped: Map<MechaSlot, String>): List<Pair<MechaSlot, MechaPart?>> = remember(parts, equipped) {
    DRAW_ORDER.map { slot -> slot to equipped[slot]?.let { id -> parts.firstOrNull { it.id == id && it.slot == slot } } }
}

/** The equipped parts' sheets from [MechaSheets]: ready at once when already decoded, else after an off-main decode. */
@Composable
internal fun rememberSpriteLoad(parts: List<MechaPart>, equipped: Map<MechaSlot, String>, paints: Map<MechaSlot, MechaPaint> = emptyMap()): SpriteLoad =
    spriteLoad(rememberLayers(parts, equipped), paints)

@Composable
private fun spriteLoad(layers: List<Pair<MechaSlot, MechaPart?>>, paints: Map<MechaSlot, MechaPaint>): SpriteLoad {
    val context = LocalContext.current
    val initial = remember(layers, paints) { initialSpriteLoad(layers, paints) }
    val load by produceState(initial, layers, paints) {
        val missing = layers.firstOrNull { it.second == null }
        value = if (missing != null) {
            SpriteLoad.Failed("No ${missing.first.label().lowercase()} part")
        } else {
            try {
                SpriteLoad.Ready(layers.map { (slot, part) -> MechaSheets.get(context.assets, part!!, paints[slot] ?: ORIGINAL_PAINT) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SpriteLoad.Failed(e.message ?: "Sprite sheet unreadable")
            }
        }
    }
    return load
}

private val FRAME_RIGS = Array(SHEET_FRAMES) { MechaRig(it) }

internal fun DrawScope.drawMechaFrame(sheets: List<MechaSheet>, frame: Int, x: Int, frameTop: Int, scale: Int, materialPhase: Int = 0) =
    drawMechaFrame(sheets, FRAME_RIGS[frame], x, frameTop, scale, materialPhase)

/**
 * Draws [rig] of every layer in [sheets] (in [DRAW_ORDER]), [scale]× with nearest-neighbour sampling, frame row 0 at
 * [frameTop], box left [x]; each part shifted by its rig offset in whole sprite pixels, so it stays crisp.
 */
internal fun DrawScope.drawMechaFrame(sheets: List<MechaSheet>, rig: MechaRig, x: Int, frameTop: Int, scale: Int, materialPhase: Int = 0) {
    val side = MECHA_FRAME * scale
    for (i in sheets.indices) {
        val sheet = sheets[i]
        val slot = DRAW_ORDER[i]
        drawImage(
            sheet.image,
            srcOffset = IntOffset(rig.frame * MECHA_FRAME, (materialPhase % sheet.phases) * MECHA_FRAME),
            srcSize = IntSize(MECHA_FRAME, MECHA_FRAME),
            dstOffset = IntOffset(x + rig.dx(slot) * scale, frameTop + rig.dy(slot) * scale),
            dstSize = IntSize(side, side),
            filterQuality = FilterQuality.None
        )
    }
}

/**
 * One drawn pose: [frame] of every sheet with each part layer shifted by whole sprite pixels (positive right and down).
 * Procedural animation on top of the authored frames: a breath lifts the upper body, a glance moves the head, a dangle
 * stretches and swings the legs, a strain hunches the upper body, sitting tucks the legs up.
 */
@Immutable
internal data class MechaRig(
    val frame: Int,
    val legsX: Int = 0,
    val legsY: Int = 0,
    val bodyX: Int = 0,
    val bodyY: Int = 0,
    val armsX: Int = 0,
    val armsY: Int = 0,
    val headX: Int = 0,
    val headY: Int = 0
) {
    fun dx(slot: MechaSlot): Int = when (slot) {
        MechaSlot.LEGS -> legsX
        MechaSlot.BODY -> bodyX
        MechaSlot.ARMS -> armsX
        MechaSlot.HEAD -> headX
    }

    fun dy(slot: MechaSlot): Int = when (slot) {
        MechaSlot.LEGS -> legsY
        MechaSlot.BODY -> bodyY
        MechaSlot.ARMS -> armsY
        MechaSlot.HEAD -> headY
    }

    /** Body, arms and head moved together by [dx], [dy] more; the legs stay. */
    fun upper(dx: Int, dy: Int): MechaRig =
        copy(bodyX = bodyX + dx, bodyY = bodyY + dy, armsX = armsX + dx, armsY = armsY + dy, headX = headX + dx, headY = headY + dy)
}

/** A small pixel glyph ('X' = filled), drawn [scale]× with its top-left at ([left], [top]) in window-local pixels. */
internal class MechaGlyph(rows: List<String>) {
    val width = rows.maxOf { it.length }
    val height = rows.size
    private val cells: List<Pair<Int, Int>> = rows.flatMapIndexed { y, row -> row.mapIndexedNotNull { x, c -> if (c == 'X') x to y else null } }

    fun draw(scope: DrawScope, color: Color, left: Int, top: Int, scale: Int) {
        val px = Size(scale.toFloat(), scale.toFloat())
        for ((x, y) in cells) scope.drawRect(color, Offset((left + x * scale).toFloat(), (top + y * scale).toFloat()), px)
    }

    companion object {
        val HEART = MechaGlyph(listOf(".XX.XX.", "XXXXXXX", "XXXXXXX", ".XXXXX.", "..XXX..", "...X..."))
        val ZED = MechaGlyph(listOf("XXXX", "..X.", ".X..", "XXXX"))
    }
}

/**
 * The equipped parts drawn as one mech. Each part is a 64px frame from its sheet, drawn at the largest whole multiple that fits
 * the bounds with nearest-neighbour sampling, centred on whole device pixels, so every sprite pixel stays a crisp square.
 *
 * Animation: one frame counter, read only while drawing, so a tick redraws this sprite without recomposing anything. The ticker
 * runs only with [motion], visible ready sheets, a RESUMED lifecycle and a lit display. Sheets come from [MechaSheets].
 * A missing equipped part or an unreadable sheet says so in text instead of drawing an empty box.
 */
@Composable
internal fun MechaSprite(
    parts: List<MechaPart>,
    equipped: Map<MechaSlot, String>,
    pose: MechaPose,
    motion: Boolean,
    modifier: Modifier = Modifier,
    paints: Map<MechaSlot, MechaPaint> = emptyMap()
) {
    val layers = rememberLayers(parts, equipped)
    val description = remember(layers) {
        "Mech with " + layers.reversed().joinToString(", ") { (slot, part) -> part?.name ?: "no ${slot.label().lowercase()}" }
    }
    val load = spriteLoad(layers, paints)
    val loop = LOOPS.getValue(pose)
    val tick = remember { mutableIntStateOf(0) }
    var visible by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val androidView = LocalView.current
    val ready = load is SpriteLoad.Ready
    LaunchedEffect(pose, motion, lifecycle, androidView, visible, ready) {
        tick.intValue = 0
        if (!motion || !visible || !ready) return@LaunchedEffect
        whileShown(shownFlow(lifecycle, displayLitFlow(androidView))) {
            while (true) {
                delay(loop.stepMs)
                // Keep a full material cycle even for poses whose authored loop has only two steps.
                tick.intValue = (tick.intValue + 1) % (loop.frames.size * MECHA_MATERIAL_PHASES * 40)
            }
        }
    }
    when (load) {
        is SpriteLoad.Failed -> Box(modifier.semantics { contentDescription = "Mech can't be drawn: ${load.reason}" }, contentAlignment = Alignment.Center) {
            Text(load.reason, style = MaterialTheme.typography.labelSmall, color = Color(0xFFF87171), textAlign = TextAlign.Center)
        }
        SpriteLoad.Loading -> Spacer(modifier.semantics { contentDescription = "Loading mech" })
        is SpriteLoad.Ready -> {
            val sheets = load.layers
            Spacer(
                modifier
                    .semantics { contentDescription = description }
                    .onGloballyPositioned { coordinates ->
                        val bounds = coordinates.boundsInWindow()
                        visible = bounds.width > 0f && bounds.height > 0f
                    }
                    // the art reaches the frame's top row, so a hop needs the headroom the box reserves; never paint outside it
                    .clipToBounds()
                    .drawBehind {
                        // the counter may still belong to the previous pose for the one frame before the ticker restarts
                        val step = if (motion) tick.intValue % loop.frames.size else -1
                        val frame = if (step < 0) loop.still else loop.frames[step]
                        val bob = if (step < 0) 0 else loop.bob[step]
                        val scale = max(1, minOf(size.width.toInt() / MECHA_FRAME, size.height.toInt() / (MECHA_FRAME + MECHA_HEADROOM)))
                        val side = MECHA_FRAME * scale
                        // centred across, standing on the bottom edge: the feet keep their floor and a hop rises into the headroom
                        val x = ((size.width - side) / 2).roundToInt()
                        val materialPhase = mechaMaterialPhase(tick.intValue * loop.stepMs, motion)
                        drawMechaFrame(sheets, frame, x, size.height.toInt() - side + bob * scale, scale, materialPhase)
                    }
            )
        }
    }
}

private val ORIGINAL_PAINT = MechaPaint()

private fun initialSpriteLoad(layers: List<Pair<MechaSlot, MechaPart?>>, paints: Map<MechaSlot, MechaPaint>): SpriteLoad {
    val cached = layers.map { (slot, part) ->
        part?.let { MechaSheets.peek(it.asset, paints[slot] ?: ORIGINAL_PAINT) } ?: return SpriteLoad.Loading
    }
    return SpriteLoad.Ready(cached)
}

/**
 * The box for a [MechaSprite] whose 64px frame is drawn close to [target] tall: a whole multiple of the frame across, plus
 * [MECHA_HEADROOM] sprite pixels above it for hops, so the sprite fills it exactly on this screen's density.
 */
@Composable
internal fun mechaSize(target: Dp): DpSize {
    val density = LocalDensity.current
    return remember(target, density) {
        with(density) {
            val scale = max(1, (target.toPx() / MECHA_FRAME).roundToInt())
            DpSize((MECHA_FRAME * scale).toDp(), ((MECHA_FRAME + MECHA_HEADROOM) * scale).toDp())
        }
    }
}

/** The largest frame height of at most [limit] whose whole-multiple sprite still fits [available] across, for [mechaSize]. */
@Composable
internal fun mechaFit(available: Dp, limit: Dp): Dp {
    val density = LocalDensity.current
    return remember(available, limit, density) {
        with(density) {
            val scale = max(1, (minOf(available, limit).toPx() / MECHA_FRAME).toInt())
            (MECHA_FRAME * scale).toDp()
        }
    }
}

/** A [MechaSprite] sized with [mechaSize]. */
@Composable
internal fun MechaFigure(
    parts: List<MechaPart>,
    equipped: Map<MechaSlot, String>,
    pose: MechaPose,
    motion: Boolean,
    target: Dp,
    modifier: Modifier = Modifier,
    paints: Map<MechaSlot, MechaPaint> = emptyMap()
) {
    MechaSprite(parts, equipped, pose, motion, modifier.size(mechaSize(target)), paints)
}

internal fun MechaSlot.label(): String = when (this) {
    MechaSlot.HEAD -> "Head"
    MechaSlot.BODY -> "Body"
    MechaSlot.ARMS -> "Arms"
    MechaSlot.LEGS -> "Legs"
}
