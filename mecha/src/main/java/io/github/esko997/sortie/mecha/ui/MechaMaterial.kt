package io.github.esko997.sortie.mecha.ui

import io.github.esko997.sortie.mecha.MechaFinish
import io.github.esko997.sortie.mecha.MechaPaint
import io.github.esko997.sortie.mecha.MechaPaintScheme
import kotlin.math.abs

/** Eight restrained material phases share the authored pose sheet's horizontal frame coordinates. */
internal const val MECHA_MATERIAL_PHASES = 8
private const val MATERIAL_STEP_MS = 400L

internal fun mechaMaterialPhase(elapsedMs: Long, motion: Boolean): Int = if (motion) ((elapsedMs / MATERIAL_STEP_MS) % MECHA_MATERIAL_PHASES).toInt() else 0

/**
 * Pure atlas construction, called off-main once per cached asset/paint. Role masks, rather than source-colour
 * guesses, distinguish the two authored pigments from eyes, outlines and mechanical details.
 */
internal fun mechaMaterialPixels(source: IntArray, mask: IntArray, width: Int, height: Int, paint: MechaPaint): IntArray {
    require(width == MECHA_FRAME * 8 && height == MECHA_FRAME && source.size == width * height)
    require(mask.size == source.size) { "Paint mask dimensions differ from the source" }
    for (index in source.indices) {
        val pixel = mask[index]
        require(pixel ushr 24 == source[index] ushr 24) { "Paint mask alpha differs at pixel $index" }
        require((pixel ushr 16 and 255) in 0..2 && (pixel ushr 8 and 255) in 0..3 && (pixel and 255) in 0..1) {
            "Invalid paint role, tone or Original eligibility at pixel $index"
        }
        require(pixel ushr 24 != 0 || pixel == 0) { "Transparent paint mask pixel $index is not zero" }
    }
    val original = paint.scheme == MechaPaintScheme.ORIGINAL
    if (original && paint.finish == MechaFinish.PLAIN) return source
    val phases = if (paint.finish == MechaFinish.PLAIN) 1 else MECHA_MATERIAL_PHASES
    val primary = if (original) null else ramp(paint.scheme.primary)
    val secondary = if (original) null else ramp(paint.scheme.secondary)
    val result = IntArray(source.size * phases)
    for (phase in 0 until phases) {
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val pixel = source[index]
                val roles = mask[index]
                val role = roles ushr 16 and 255
                val eligible = if (original) roles and 255 == 1 else role != 0
                var painted = pixel
                if (eligible && pixel ushr 24 != 0) {
                    if (!original) {
                        val tone = roles ushr 8 and 255
                        painted = (pixel and -0x1000000) or ((if (role == 1) primary!![tone] else secondary!![tone]) and 0xFFFFFF)
                    }
                    if (paint.finish != MechaFinish.PLAIN) {
                        painted = (pixel and -0x1000000) or (coat(painted, x % MECHA_FRAME, y, phase, paint.finish, original) and 0xFFFFFF)
                    }
                }
                result[phase * source.size + index] = painted
            }
        }
    }
    return result
}

/** Shadow is 64% pigment; light/glint mix in 34%/64% white. Base is the exact purchased swatch. */
private fun ramp(swatch: Int): IntArray {
    val r = swatch ushr 16 and 255
    val g = swatch ushr 8 and 255
    val b = swatch and 255
    return intArrayOf(
        rgb(r * 64 / 100, g * 64 / 100, b * 64 / 100),
        swatch,
        rgb(r + (255 - r) * 34 / 100, g + (255 - g) * 34 / 100, b + (255 - b) * 34 / 100),
        rgb(r + (255 - r) * 64 / 100, g + (255 - g) * 64 / 100, b + (255 - b) * 64 / 100)
    )
}

private fun coat(pixel: Int, x: Int, y: Int, phase: Int, finish: MechaFinish, original: Boolean): Int {
    val r = pixel shr 16 and 255
    val g = pixel shr 8 and 255
    val b = pixel and 255
    val light = (r * 54 + g * 183 + b * 19) / 256
    return if (finish == MechaFinish.ALLOY) {
        // Brushed cool metal, with a narrow diagonal glint travelling once per 3.2 seconds.
        val distance = abs(x + y / 2 - (phase * 12 - 6))
        val glint = if (distance <= 2) 30 else if (distance <= 4) 12 else 0
        val brush = if (y % 4 == 0) -5 else 0
        val base = light + brush + glint
        if (original) {
            rgb((base * 7 + r) / 8 - 6, (base * 7 + g) / 8 + 2, (base * 7 + b) / 8 + 10)
        } else {
            // Coloured metal keeps its purchased pigment; only Original retains the historical silver coating.
            rgb((r * 3 + light) / 4 + brush + glint - 3, (g * 3 + light) / 4 + brush + glint, (b * 3 + light) / 4 + brush + glint + 3)
        }
    } else {
        // A translucent prismatic film: retain three quarters of the authored pigment and all its shading.
        val tint = when (((x + y / 2 + phase * 8) / 16) % 4) {
            0 -> 0xA4DCEB
            1 -> 0xB8ACE8
            2 -> 0xE8AECF
            else -> 0xAADBC5
        }
        rgb(
            (r * 3 + (tint shr 16 and 255)) / 4,
            (g * 3 + (tint shr 8 and 255)) / 4,
            (b * 3 + (tint and 255)) / 4
        )
    }
}

private fun rgb(r: Int, g: Int, b: Int): Int = (255 shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
