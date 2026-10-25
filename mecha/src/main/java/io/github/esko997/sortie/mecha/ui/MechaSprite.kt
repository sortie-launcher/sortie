package io.github.esko997.sortie.mecha.ui

import android.content.res.AssetManager
import android.graphics.BitmapFactory
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.esko997.sortie.mecha.MechaPart
import io.github.esko997.sortie.mecha.MechaSlot
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay

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

/**
 * Decoded sprite sheets, shared by every sprite in the process. Each sheet is decoded once on an IO thread (24 sheets of
 * 512x64 ARGB are ~3 MB) and uploaded ahead of the first draw; drawing never touches the assets again. A failed decode is
 * forgotten so a later composition retries it.
 */
private object MechaSheets {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ready = ConcurrentHashMap<String, ImageBitmap>()
    private val pending = ConcurrentHashMap<String, Deferred<ImageBitmap>>()

    fun peek(path: String): ImageBitmap? = ready[path]

    suspend fun get(assets: AssetManager, path: String): ImageBitmap {
        ready[path]?.let { return it }
        val job = pending.computeIfAbsent(path) { scope.async { decode(assets, path).also { ready[path] = it } } }
        return try {
            job.await()
        } catch (e: CancellationException) {
            throw e // the sprite left composition; the decode carries on for the next one
        } catch (e: Exception) {
            pending.remove(path, job)
            throw e
        }
    }

    private fun decode(assets: AssetManager, path: String): ImageBitmap {
        val options = BitmapFactory.Options().apply {
            inScaled = false
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        val bitmap = assets.open(path).use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IllegalStateException("$path is not a readable PNG")
        check(bitmap.width == MECHA_FRAME * SHEET_FRAMES && bitmap.height == MECHA_FRAME) {
            "$path is ${bitmap.width}x${bitmap.height}, expected ${MECHA_FRAME * SHEET_FRAMES}x$MECHA_FRAME"
        }
        bitmap.prepareToDraw()
        return bitmap.asImageBitmap()
    }
}

@Immutable
internal sealed interface SpriteLoad {
    data object Loading : SpriteLoad
    class Ready(val layers: List<ImageBitmap>) : SpriteLoad
    class Failed(val reason: String) : SpriteLoad
}

/** The equipped parts in draw order, each resolved against the catalog (null: that slot's part is missing). */
@Composable
private fun rememberLayers(parts: List<MechaPart>, equipped: Map<MechaSlot, String>): List<Pair<MechaSlot, MechaPart?>> = remember(parts, equipped) {
    DRAW_ORDER.map { slot -> slot to equipped[slot]?.let { id -> parts.firstOrNull { it.id == id && it.slot == slot } } }
}

/** The equipped parts' sheets from [MechaSheets]: ready at once when already decoded, else after an off-main decode. */
@Composable
internal fun rememberSpriteLoad(parts: List<MechaPart>, equipped: Map<MechaSlot, String>): SpriteLoad = spriteLoad(rememberLayers(parts, equipped))

@Composable
private fun spriteLoad(layers: List<Pair<MechaSlot, MechaPart?>>): SpriteLoad {
    val context = LocalContext.current
    val load by produceState(initialSpriteLoad(layers), layers) {
        val missing = layers.firstOrNull { it.second == null }
        value = if (missing != null) {
            SpriteLoad.Failed("No ${missing.first.label().lowercase()} part")
        } else {
            try {
                SpriteLoad.Ready(layers.map { MechaSheets.get(context.assets, it.second!!.asset) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SpriteLoad.Failed(e.message ?: "Sprite sheet unreadable")
            }
        }
    }
    return load
}

internal fun DrawScope.drawMechaFrame(sheets: List<ImageBitmap>, frame: Int, x: Int, frameTop: Int, scale: Int) =
    drawMechaFrame(sheets, MechaRig(frame), x, frameTop, scale)

/**
 * Draws [rig] of every layer in [sheets] (in [DRAW_ORDER]), [scale]× with nearest-neighbour sampling, frame row 0 at
 * [frameTop], box left [x]; each part shifted by its rig offset in whole sprite pixels, so it stays crisp.
 */
internal fun DrawScope.drawMechaFrame(sheets: List<ImageBitmap>, rig: MechaRig, x: Int, frameTop: Int, scale: Int) {
    val side = MECHA_FRAME * scale
    sheets.forEachIndexed { i, sheet ->
        val slot = DRAW_ORDER.getOrElse(i) { MechaSlot.HEAD }
        drawImage(
            sheet,
            srcOffset = IntOffset(rig.frame * MECHA_FRAME, 0),
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
 * runs only while [motion] is on, the sprite is composed and its lifecycle is RESUMED. Sheets come from [MechaSheets].
 * A missing equipped part or an unreadable sheet says so in text instead of drawing an empty box.
 */
@Composable
internal fun MechaSprite(parts: List<MechaPart>, equipped: Map<MechaSlot, String>, pose: MechaPose, motion: Boolean, modifier: Modifier = Modifier) {
    val layers = rememberLayers(parts, equipped)
    val description = remember(layers) {
        "Mech with " + layers.reversed().joinToString(", ") { (slot, part) -> part?.name ?: "no ${slot.label().lowercase()}" }
    }
    val load = spriteLoad(layers)
    val loop = LOOPS.getValue(pose)
    val tick = remember { mutableIntStateOf(0) }
    var visible by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(pose, motion, lifecycle, visible) {
        tick.intValue = 0
        if (!motion || !visible) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(loop.stepMs)
                tick.intValue = (tick.intValue + 1) % loop.frames.size
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
                        drawMechaFrame(sheets, frame, x, size.height.toInt() - side + bob * scale, scale)
                    }
            )
        }
    }
}

private fun initialSpriteLoad(layers: List<Pair<MechaSlot, MechaPart?>>): SpriteLoad {
    val cached = layers.map { it.second?.let { p -> MechaSheets.peek(p.asset) } ?: return SpriteLoad.Loading }
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
    modifier: Modifier = Modifier
) {
    MechaSprite(parts, equipped, pose, motion, modifier.size(mechaSize(target)))
}

internal fun MechaSlot.label(): String = when (this) {
    MechaSlot.HEAD -> "Head"
    MechaSlot.BODY -> "Body"
    MechaSlot.ARMS -> "Arms"
    MechaSlot.LEGS -> "Legs"
}
