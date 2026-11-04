package io.github.esko997.sortie.ui

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QwertyLayoutTest {
    private val characters = Qwerty.ROWS.flatten().filterIsInstance<QwertyKey.Character>()

    private fun key(base: Char) = characters.single { it.base == base }

    @Test
    fun everyPrintableAsciiCharacterIsTypedLiterally() {
        val typed = mutableSetOf<Char>()
        for (shift in ShiftMode.entries) {
            for (k in characters) {
                val (sent, _) = Qwerty.press(k, shift)
                val text = (sent as QwertySend.Text).text
                assertEquals("one character per key", 1, text.length)
                typed += text[0]
            }
        }
        assertEquals((' '..'~').toSet(), typed)
        // the characters shells and format strings treat specially arrive as themselves
        assertEquals(QwertySend.Text("%"), Qwerty.press(key('5'), ShiftMode.ONCE).first)
        assertEquals(QwertySend.Text("\""), Qwerty.press(key('\''), ShiftMode.ONCE).first)
        assertEquals(QwertySend.Text("'"), Qwerty.press(key('\''), ShiftMode.OFF).first)
        assertEquals(QwertySend.Text("\\"), Qwerty.press(key('\\'), ShiftMode.OFF).first)
        assertEquals(QwertySend.Text("`"), Qwerty.press(key('`'), ShiftMode.OFF).first)
    }

    @Test
    fun shiftLastsOneCharacterUnlessLocked() {
        var shift = Qwerty.tapShift(ShiftMode.OFF)
        assertEquals(ShiftMode.ONCE, shift)
        val (a, after) = Qwerty.press(key('a'), shift)
        assertEquals(QwertySend.Text("A"), a)
        assertEquals(ShiftMode.OFF, after)

        shift = Qwerty.tapShift(Qwerty.tapShift(ShiftMode.OFF))
        assertEquals(ShiftMode.LOCKED, shift)
        val (b, locked) = Qwerty.press(key('b'), shift)
        assertEquals(QwertySend.Text("B"), b)
        assertEquals(ShiftMode.LOCKED, locked)
        assertEquals(ShiftMode.OFF, Qwerty.tapShift(locked))

        val shiftKey = Qwerty.ROWS.flatten().first { it is QwertyKey.Shift }
        val (nothing, on) = Qwerty.press(shiftKey, ShiftMode.OFF)
        assertNull("Shift itself sends nothing", nothing)
        assertEquals(ShiftMode.ONCE, on)
        // a command keeps a one-character Shift for the character after it
        val left = Qwerty.ROWS.flatten().filterIsInstance<QwertyKey.Command>().single { it.code == KeyEvent.KEYCODE_DPAD_LEFT }
        assertEquals(QwertySend.Key(KeyEvent.KEYCODE_DPAD_LEFT) to ShiftMode.ONCE, Qwerty.press(left, ShiftMode.ONCE))
    }
}
