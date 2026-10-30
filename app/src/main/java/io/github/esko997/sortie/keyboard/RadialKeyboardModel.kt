package io.github.esko997.sortie.keyboard

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2

/** What the keyboard view (touch) or the service (controller) asks the service to do. */
enum class KeyboardAction { COMMIT, BACKSPACE, SPACE, ENTER, HIDE, NEXT_IME, TOGGLE_SHIFT, TOGGLE_SYMBOLS }

/**
 * Selection state of the radial keyboard. Pure Kotlin so the stick geometry can be tested on the JVM.
 *
 * The left stick picks one of eight groups. The right stick picks a character on the cardinals or an editing action on the
 * diagonals; sector 0 is north, clockwise. Axes use Android's convention: x grows rightwards, y downwards.
 * A selection latches when the stick returns to neutral, so the user can let go and then commit.
 *
 * Layers: letters, letters + shift (upper case), symbols (digits and common punctuation), symbols + shift (every ASCII
 * punctuation mark). Shift and symbols are each the OR of a latched touch toggle and a physical hold (trigger).
 * All label lists are built once; reading them never allocates.
 */
class RadialKeyboardModel {
    var selectedGroup: Int = NONE
        private set
    var selectedSector: Int = NONE
        private set
    val selectedIndex: Int get() = if (selectedSector >= 0 && selectedSector % 2 == 0) selectedSector / 2 else NONE
    val selectedAction: KeyboardAction? get() = RIGHT_ACTIONS.getOrNull(selectedSector)

    /** Touch toggles; [shifted] and [symbols] also include the physical holds. */
    var shiftLatched: Boolean = false
        private set
    var symbolsLatched: Boolean = false
        private set
    private var shiftHeld = false
    private var symbolsHeld = false

    val shifted: Boolean get() = shiftLatched || shiftHeld
    val symbols: Boolean get() = symbolsLatched || symbolsHeld

    private val layer: Int get() = (if (symbols) 2 else 0) + (if (shifted) 1 else 0)

    /** Eight labels; label g is group g's four characters in slot order (N, E, S, W). */
    val groupLabels: List<String> get() = GROUP_LABELS[layer]

    /** The selected group's four characters, or four empty strings when no group is selected. */
    val characterLabels: List<String> get() = if (selectedGroup == NONE) BLANK else CHARACTERS[layer][selectedGroup]

    /** What a commit would type now; empty when nothing is selected. */
    val selectedCharacter: String
        get() = if (selectedGroup == NONE || selectedIndex == NONE) "" else CHARACTERS[layer][selectedGroup][selectedIndex]

    val layerLabel: String get() = LAYER_LABELS[layer]

    private val left = StickTracker(GROUPS, GROUP_HYSTERESIS)
    private val right = StickTracker(RIGHT_SECTORS, GROUP_HYSTERESIS)

    fun selectGroup(index: Int): Boolean {
        if (index !in 0 until GROUPS || index == selectedGroup) return false
        selectedGroup = index
        return true
    }

    fun selectCharacter(index: Int): Boolean = index in 0 until SLOTS && selectRightSector(index * 2)

    fun selectRightSector(sector: Int): Boolean {
        if (sector !in 0 until RIGHT_SECTORS || sector == selectedSector) return false
        selectedSector = sector
        return true
    }

    fun updateLeftStick(x: Float, y: Float): Boolean = selectGroup(left.update(x, y, selectedGroup))

    fun updateRightStick(x: Float, y: Float): Boolean = selectRightSector(right.update(x, y, selectedSector))

    fun setShiftHeld(held: Boolean): Boolean {
        val before = shifted
        shiftHeld = held
        return shifted != before
    }

    fun setSymbolsHeld(held: Boolean): Boolean {
        val before = symbols
        symbolsHeld = held
        return symbols != before
    }

    /** Always a visible change: the toggle's own state flips even when a hold keeps the layer the same. */
    fun toggleShift(): Boolean {
        shiftLatched = !shiftLatched
        return true
    }

    fun toggleSymbols(): Boolean {
        symbolsLatched = !symbolsLatched
        return true
    }

    /** Fresh state for a new editor; [numeric] starts on the symbols layer (digits) for number, phone and date fields. */
    fun reset(numeric: Boolean = false) {
        selectedGroup = NONE
        selectedSector = NONE
        shiftLatched = false
        symbolsLatched = numeric
        shiftHeld = false
        symbolsHeld = false
        left.reset()
        right.reset()
    }

    /**
     * Sector picker with a radial dead zone and angular hysteresis. A push past [ENGAGE] picks the nearest sector; while the
     * stick stays out (above [RELEASE]) the current sector is kept until the angle leaves it by more than [hysteresis], so a
     * stick resting on a boundary does not flicker. Inside the dead zone nothing changes: the selection latches.
     */
    private class StickTracker(private val sectors: Int, private val hysteresis: Double) {
        private val width = 2 * PI / sectors
        private var engaged = false

        /** The sector to select, or [current] (possibly [NONE]) to keep the selection. */
        fun update(x: Float, y: Float, current: Int): Int {
            if (x.isNaN() || y.isNaN()) return current
            val r2 = x * x + y * y
            if (engaged && r2 < RELEASE * RELEASE) engaged = false
            if (r2 < ENGAGE * ENGAGE) return current
            val angle = atan2(x.toDouble(), -y.toDouble()).let { if (it < 0) it + 2 * PI else it }
            val fresh = !engaged
            engaged = true
            if (!fresh && current in 0 until sectors && distance(angle, current * width) <= width / 2 + hysteresis) return current
            return ((angle + width / 2) / width).toInt() % sectors
        }

        fun reset() {
            engaged = false
        }

        private fun distance(a: Double, b: Double): Double {
            val d = abs(a - b) % (2 * PI)
            return if (d > PI) 2 * PI - d else d
        }
    }

    companion object {
        const val NONE = -1
        const val GROUPS = 8
        const val SLOTS = 4
        const val RIGHT_SECTORS = 8

        /** Stick magnitude that selects, and the lower magnitude that counts as back at neutral. */
        const val ENGAGE = 0.5f
        const val RELEASE = 0.3f
        private const val GROUP_HYSTERESIS = 8 * PI / 180 // sector half-width is 22.5 degrees

        private val RIGHT_ACTIONS = arrayOf(
            null,
            KeyboardAction.BACKSPACE,
            null,
            KeyboardAction.SPACE,
            null,
            KeyboardAction.TOGGLE_SYMBOLS,
            null,
            KeyboardAction.TOGGLE_SHIFT
        )

        /** Thirty-two characters per layer: eight groups of four, groups clockwise from north, slots N, E, S, W. */
        private val LAYOUTS = arrayOf(
            "abcdefghijklmnopqrstuvwxyz,.?!'-",
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ,.?!'-",
            "1234567890.,-+=/():;!?'\"@#$%&*_~",
            "()[]{}<>+-*/=%^~\\|_`@#$&:;\"'!?,."
        )
        private val LAYER_LABELS = listOf("abc", "ABC", "123", "#+=")
        private val BLANK = List(SLOTS) { "" }
        private val GROUP_LABELS: List<List<String>> = LAYOUTS.map { layout -> List(GROUPS) { g -> layout.substring(g * SLOTS, g * SLOTS + SLOTS) } }
        private val CHARACTERS: List<List<List<String>>> = LAYOUTS.map { layout ->
            List(GROUPS) { g -> List(SLOTS) { s -> layout[g * SLOTS + s].toString() } }
        }
    }
}

/**
 * UTF-16 units to delete when the editor cannot delete by code point. An incomplete surrogate at the cursor returns zero:
 * the service delegates to the editor's DEL handling rather than risking deleting half of a character.
 */
internal fun backspaceLength(before: CharSequence?): Int {
    if (before.isNullOrEmpty()) return 0
    val n = before.length
    val last = before[n - 1]
    return when {
        Character.isLowSurrogate(last) -> if (n >= 2 && Character.isHighSurrogate(before[n - 2])) 2 else 0
        Character.isHighSurrogate(last) -> 0
        else -> 1
    }
}

/** Where a D-pad press sends the cursor: one character left or right, or the start or end of the text. */
internal enum class CursorMove { LEFT, RIGHT, START, END }

/**
 * Cursor position after [move] in [text] whose selection runs between [selStart] and [selEnd], in either order. Left and right
 * step one code point, never into a surrogate pair, and stay put at the edge; a non-empty selection collapses to that side
 * instead. Returns -1 for a left or right move when the selection is unknown or outside [text].
 */
internal fun cursorTarget(text: CharSequence, selStart: Int, selEnd: Int, move: CursorMove): Int {
    val lo = minOf(selStart, selEnd)
    val hi = maxOf(selStart, selEnd)
    val known = lo >= 0 && hi <= text.length
    return when (move) {
        CursorMove.START -> 0
        CursorMove.END -> text.length
        CursorMove.LEFT -> if (!known) -1 else if (lo != hi || lo == 0) lo else Character.offsetByCodePoints(text, lo, -1)
        CursorMove.RIGHT -> if (!known) -1 else if (lo != hi || hi == text.length) hi else Character.offsetByCodePoints(text, hi, 1)
    }
}
