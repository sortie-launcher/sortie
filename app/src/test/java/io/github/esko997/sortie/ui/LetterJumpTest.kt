package io.github.esko997.sortie.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LetterJumpTest {
    @Test
    fun initialsAreLettersOrHash() {
        assertEquals('M', initialOf("mario kart"))
        assertEquals('Z', initialOf("  zelda"))
        assertEquals('#', initialOf("1942"))
        assertEquals('#', initialOf("ōkami"))
        assertEquals('#', initialOf(""))
    }

    @Test
    fun titleOrderEitherWayJumpsAndOtherOrdersDoNot() {
        // "#" sorts before the letters (digits) and after them (accented titles) by code point
        val sorted = listOf("1942", "alpha", "astro", "bomber", "zelda", "ōkami")
        assertEquals(listOf('#', 'A', 'A', 'B', 'Z', '#'), initials(sorted))
        assertEquals(listOf('Z', 'B', 'A'), initials(listOf("zelda", "bomber", "alpha")))
        assertNull("recently played", initials(listOf("bomber", "alpha", "zelda")))
        assertNull("one letter", initials(listOf("alpha", "astro")))
        assertNull(initials(emptyList()))
    }

    @Test
    fun rightGoesToTheNextLetterAndLeftToThePreviousOne() {
        val letters = listOf('#', 'A', 'A', 'B', 'D', 'D', 'D')
        assertEquals(0, letterJumpTarget(letters, -1, 1)) // from a folder row above the games
        assertEquals(1, letterJumpTarget(letters, 0, 1))
        assertEquals(3, letterJumpTarget(letters, 1, 1))
        assertEquals(3, letterJumpTarget(letters, 2, 1))
        assertEquals(4, letterJumpTarget(letters, 3, 1))
        assertNull("last letter", letterJumpTarget(letters, 5, 1))

        assertEquals(3, letterJumpTarget(letters, 6, -1))
        assertEquals(1, letterJumpTarget(letters, 3, -1))
        assertEquals(0, letterJumpTarget(letters, 2, -1))
        assertEquals("inside the first letter: its top", 0, letterJumpTarget(listOf('A', 'A', 'B'), 1, -1))
        assertNull("already at the top", letterJumpTarget(letters, 0, -1))
        assertNull(letterJumpTarget(letters, -1, -1))
        assertNull(letterJumpTarget(emptyList(), -1, 1))
    }
}
