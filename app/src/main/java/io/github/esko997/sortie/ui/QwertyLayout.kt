package io.github.esko997.sortie.ui

import android.view.KeyEvent

/** One key of Sortie's QWERTY keyboard; [width] in key units, 15 to a row. */
internal sealed class QwertyKey(val width: Float) {
    /** Types [base], or [shifted] while Shift is on. */
    class Character(val base: Char, val shifted: Char, width: Float = 1f) : QwertyKey(width)

    /** Sends Android's key [code], as a hardware keyboard would; [repeats] while held (Delete, the arrows). */
    class Command(val label: String, val name: String, val code: Int, width: Float, val repeats: Boolean = false) : QwertyKey(width)

    class Shift(width: Float) : QwertyKey(width)
}

/** Shift is off, on for the next character, or locked on until tapped again. */
internal enum class ShiftMode { OFF, ONCE, LOCKED }

/** What a key press sends to the other screen. */
internal sealed interface QwertySend {
    data class Text(val text: String) : QwertySend

    data class Key(val code: Int) : QwertySend
}

/**
 * The US English layout: every printable ASCII character, Space, Delete, Enter, Tab, Escape, Home, End and the arrows. Pure, so
 * what each key types is checked on the JVM.
 */
internal object Qwerty {
    val ROWS: List<List<QwertyKey>> = listOf(
        characters("`1234567890-=", "~!@#$%^&*()_+") + QwertyKey.Command("Delete", "Delete", KeyEvent.KEYCODE_DEL, 2f, repeats = true),
        listOf(QwertyKey.Command("Tab", "Tab", KeyEvent.KEYCODE_TAB, 1.5f)) + characters("qwertyuiop[]", "QWERTYUIOP{}") +
            QwertyKey.Character('\\', '|', 1.5f),
        listOf(QwertyKey.Command("Esc", "Escape", KeyEvent.KEYCODE_ESCAPE, 1.75f)) + characters("asdfghjkl;'", "ASDFGHJKL:\"") +
            QwertyKey.Command("Enter", "Enter", KeyEvent.KEYCODE_ENTER, 2.25f),
        listOf(QwertyKey.Shift(2.25f)) + characters("zxcvbnm,./", "ZXCVBNM<>?") + QwertyKey.Shift(2.75f),
        listOf(
            QwertyKey.Command("Home", "Home", KeyEvent.KEYCODE_MOVE_HOME, 1.5f),
            QwertyKey.Command("End", "End", KeyEvent.KEYCODE_MOVE_END, 1.5f),
            QwertyKey.Character(' ', ' ', 8f),
            QwertyKey.Command("←", "Left arrow", KeyEvent.KEYCODE_DPAD_LEFT, 1f, repeats = true),
            QwertyKey.Command("↑", "Up arrow", KeyEvent.KEYCODE_DPAD_UP, 1f, repeats = true),
            QwertyKey.Command("↓", "Down arrow", KeyEvent.KEYCODE_DPAD_DOWN, 1f, repeats = true),
            QwertyKey.Command("→", "Right arrow", KeyEvent.KEYCODE_DPAD_RIGHT, 1f, repeats = true)
        )
    )

    private fun characters(base: String, shifted: String): List<QwertyKey> = base.indices.map { QwertyKey.Character(base[it], shifted[it]) }

    fun character(key: QwertyKey.Character, shift: ShiftMode): Char = if (shift == ShiftMode.OFF) key.base else key.shifted

    fun label(key: QwertyKey, shift: ShiftMode): String = when (key) {
        is QwertyKey.Character -> if (key.base == ' ') "Space" else character(key, shift).toString()
        is QwertyKey.Command -> key.label
        is QwertyKey.Shift -> "Shift"
    }

    /** What screen readers say for [key]: punctuation by name, a capital as "capital A". */
    fun name(key: QwertyKey, shift: ShiftMode): String = when (key) {
        is QwertyKey.Character -> spokenChar(character(key, shift))
        is QwertyKey.Command -> key.name
        is QwertyKey.Shift -> "Shift"
    }

    /** A tap on Shift: off, on for one character, locked, off again. */
    fun tapShift(shift: ShiftMode): ShiftMode = when (shift) {
        ShiftMode.OFF -> ShiftMode.ONCE
        ShiftMode.ONCE -> ShiftMode.LOCKED
        ShiftMode.LOCKED -> ShiftMode.OFF
    }

    /** What pressing [key] sends, and Shift afterwards (a one-character Shift is used up by a character). Shift itself sends nothing. */
    fun press(key: QwertyKey, shift: ShiftMode): Pair<QwertySend?, ShiftMode> = when (key) {
        is QwertyKey.Character -> QwertySend.Text(character(key, shift).toString()) to (if (shift == ShiftMode.ONCE) ShiftMode.OFF else shift)
        is QwertyKey.Command -> QwertySend.Key(key.code) to shift
        is QwertyKey.Shift -> null to tapShift(shift)
    }
}

/** Spoken names for the touch keyboard's printable keys. */
private fun spokenChar(c: Char): String = when (c) {
    in 'A'..'Z' -> "capital $c"
    ' ' -> "space"
    '!' -> "exclamation mark"
    '"' -> "quotation mark"
    '#' -> "number sign"
    '$' -> "dollar sign"
    '%' -> "percent"
    '&' -> "ampersand"
    '\'' -> "apostrophe"
    '(' -> "left parenthesis"
    ')' -> "right parenthesis"
    '*' -> "asterisk"
    '+' -> "plus"
    ',' -> "comma"
    '-' -> "hyphen"
    '.' -> "period"
    '/' -> "slash"
    ':' -> "colon"
    ';' -> "semicolon"
    '<' -> "less than"
    '=' -> "equals"
    '>' -> "greater than"
    '?' -> "question mark"
    '@' -> "at sign"
    '[' -> "left bracket"
    '\\' -> "backslash"
    ']' -> "right bracket"
    '^' -> "caret"
    '_' -> "underscore"
    '`' -> "grave accent"
    '{' -> "left brace"
    '|' -> "vertical bar"
    '}' -> "right brace"
    '~' -> "tilde"
    else -> c.toString()
}
