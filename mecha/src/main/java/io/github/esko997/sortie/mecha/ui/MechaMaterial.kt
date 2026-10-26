package io.github.esko997.sortie.mecha.ui

import io.github.esko997.sortie.mecha.MechaFinish
import io.github.esko997.sortie.mecha.MechaSlot
import kotlin.math.abs

/** Eight restrained material phases share the authored pose sheet's horizontal frame coordinates. */
internal const val MECHA_MATERIAL_PHASES = 8
private const val MATERIAL_STEP_MS = 400L

internal fun mechaMaterialPhase(elapsedMs: Long, motion: Boolean): Int = if (motion) ((elapsedMs / MATERIAL_STEP_MS) % MECHA_MATERIAL_PHASES).toInt() else 0

/**
 * Pure pixel transform used by the actual bitmap decoder, never by the draw loop. The plain path keeps the original
 * array, including transparent RGB. Finished phases stack vertically; each pose uses the same local x so its finish
 * cannot jump when the authored pose changes. No resampling, alpha changes or expansion of the silhouette.
 */
internal fun mechaMaterialPixels(source: IntArray, width: Int, height: Int, slot: MechaSlot, finish: MechaFinish): IntArray {
    require(width == MECHA_FRAME * 8 && height == MECHA_FRAME && source.size == width * height)
    if (finish == MechaFinish.PLAIN) return source
    val result = IntArray(source.size * MECHA_MATERIAL_PHASES)
    for (phase in 0 until MECHA_MATERIAL_PHASES) {
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val pixel = source[index]
                val localX = x % MECHA_FRAME
                result[phase * source.size + index] = if (protectedPixel(pixel, slot, localX, y)) {
                    pixel
                } else {
                    coat(pixel, localX, y, phase, finish)
                }
            }
        }
    }
    return result
}

private fun protectedPixel(pixel: Int, slot: MechaSlot, x: Int, y: Int): Boolean {
    if (pixel ushr 24 != 255) return true
    // All seven authored families share this mechanical ramp. Keep outlines, joints, rivets and steel intact.
    when (pixel and 0xFFFFFF) {
        0x18232D, 0x344650, 0x768A91, 0xA6B5B1, 0xD7DFD5 -> return true
    }
    // The authored eyes/lids move within this face window; their palette is deliberately family-specific.
    if (slot == MechaSlot.HEAD && x in 18..45 && y in 12..23) return true
    return maxOf(pixel shr 16 and 255, pixel shr 8 and 255, pixel and 255) < 96
}

private fun coat(pixel: Int, x: Int, y: Int, phase: Int, finish: MechaFinish): Int {
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
        rgb((base * 7 + r) / 8 - 6, (base * 7 + g) / 8 + 2, (base * 7 + b) / 8 + 10)
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
