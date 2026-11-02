package io.github.esko997.sortie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A–Z by the first character of a case-folded sort key; digits, symbols and other scripts are "#". */
fun initialOf(sortKey: String): Char = sortKey.trimStart().firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' } ?: '#'

/**
 * The [initialOf] of each item, or null when jumping by letter makes no sense: the letters A–Z do not run in one direction
 * (the list is sorted by something other than its title) or there is only one letter. "#" is ignored for the order: by code
 * point, digits and most symbols sort before the letters and accented or non-Latin titles after them.
 */
fun initials(sortKeys: List<String>): List<Char>? {
    val letters = sortKeys.map(::initialOf)
    val az = letters.filter { it != '#' }.zipWithNext()
    val ordered = az.all { (a, b) -> a <= b } || az.all { (a, b) -> a >= b }
    return letters.takeIf { ordered && it.distinct().size > 1 }
}

/**
 * The index to focus when jumping from [from] (-1: no item of [letters] has the focus) by [step]: 1 is the first item of the
 * next letter, -1 the first item of the previous one, or of the first letter from inside it. Null when there is none.
 */
fun letterJumpTarget(letters: List<Char>, from: Int, step: Int): Int? {
    if (from !in letters.indices) return if (step > 0 && letters.isNotEmpty()) 0 else null
    fun runStart(i: Int): Int {
        var s = i
        while (s > 0 && letters[s - 1] == letters[i]) s--
        return s
    }
    if (step > 0) return (from + 1 until letters.size).firstOrNull { letters[it] != letters[from] }
    val start = runStart(from)
    return when {
        start > 0 -> runStart(start - 1)
        from > 0 -> 0
        else -> null
    }
}

/**
 * D-pad Left/Right on a browse list in list view, where they otherwise do nothing: jump to the previous/next starting letter
 * (see [letterJump]) and show it a moment in the list's middle ([LetterFlash]). Moves the focus the way Back restores it
 * ([GridMemory.focusRemembered]), so the target is scrolled to the top when it is off screen.
 */
@Stable
class LetterJump internal constructor(private val memory: GridMemory, private val state: LazyGridState, private val scope: CoroutineScope) {
    internal var letter by mutableStateOf<Char?>(null)
    internal var jumps by mutableIntStateOf(0)

    internal fun jump(letters: List<Char>, keys: List<Any>, first: Int, step: Int) {
        // a leading folder or system row, or nothing focused yet, is "before the first item"
        val from = ((memory.focused?.let { keys.indexOf(it) } ?: -1) - first).coerceAtLeast(-1)
        val target = letterJumpTarget(letters, from, step) ?: return
        memory.focused = keys.getOrNull(first + target) as? String ?: return
        memory.holding = false // the item leaving the focus no longer is [GridMemory.focused], so it would not clear this
        letter = letters[target]
        jumps++
        scope.launch { memory.focusRemembered(state, keys) }
    }
}

@Composable
fun rememberLetterJump(memory: GridMemory, state: LazyGridState): LetterJump {
    val scope = rememberCoroutineScope()
    return remember(memory, state, scope) { LetterJump(memory, state, scope) }
}

/**
 * Letter jumping on a list whose grid [keys] (every item in order) hold, from index [first], the items [letters] describes
 * ([initials]); null [letters] (grid view, or not in title order) leaves Left/Right alone.
 */
fun Modifier.letterJump(jump: LetterJump, letters: List<Char>?, keys: List<Any>, first: Int = 0): Modifier = if (letters == null) {
    this
} else {
    onPreviewKeyEvent { e ->
        val step = when (e.key) {
            Key.DirectionRight -> 1
            Key.DirectionLeft -> -1
            else -> return@onPreviewKeyEvent false
        }
        // held, it keeps stepping at the key-repeat rate
        if (e.type == KeyEventType.KeyDown) jump.jump(letters, keys, first, step)
        true
    }
}

/** The letter of the last jump, large in the middle of the list for a moment. */
@Composable
fun BoxScope.LetterFlash(jump: LetterJump) {
    val letter = jump.letter ?: return
    LaunchedEffect(jump.jumps) {
        delay(700)
        jump.letter = null
    }
    Text(
        letter.toString(),
        style = MaterialTheme.typography.displayMedium,
        color = Accent,
        modifier = Modifier
            .align(Alignment.Center)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f), RoundedCornerShape(16.dp))
            .padding(horizontal = 32.dp, vertical = 12.dp)
    )
}
