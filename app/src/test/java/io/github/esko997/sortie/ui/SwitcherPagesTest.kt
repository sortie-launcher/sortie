package io.github.esko997.sortie.ui

import io.github.esko997.sortie.ui.SwitcherPages.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitcherPagesTest {
    private val top = 0
    private val bottom = 4

    @Test
    fun eachWindowHasItsOwnPage() {
        val pages = SwitcherPages()
        assertEquals(Page.APPS, pages.page(top))
        pages.openPerf(bottom) // the touch window's chip
        assertEquals(Page.PERF, pages.page(bottom))
        assertEquals(Page.APPS, pages.page(top))
        assertEquals(mapOf(bottom to Page.PERF), pages.state.value)
    }

    @Test
    fun bLeavesPerfFirstThenClosesTheSwitcher() {
        val pages = SwitcherPages()
        pages.openPerf(top)
        assertTrue("B on the Perf page goes back to the app list", pages.back(top))
        assertEquals(Page.APPS, pages.page(top))
        assertFalse("B on the app list closes the switcher", pages.back(top))
    }

    @Test
    fun shoulderButtonsToggle() {
        val pages = SwitcherPages()
        pages.toggle(top)
        assertEquals(Page.PERF, pages.page(top))
        pages.toggle(top)
        assertEquals(Page.APPS, pages.page(top))
    }

    @Test
    fun theAppListGetsItsFocusBackAfterPerf() {
        val pages = SwitcherPages()
        assertNull(pages.returnSpot(top))
        pages.focused(top, CardSpot(row = top, task = 11))
        pages.focused(top, CardSpot(row = bottom, task = 42)) // D-pad down to the bottom screen's row
        pages.openPerf(top)
        pages.back(top)
        assertEquals(CardSpot(bottom, 42), pages.returnSpot(top))
        assertNull(pages.returnSpot(bottom)) // the other window's list is its own
    }

    @Test
    fun aNewSummonsStartsOnTheAppList() {
        val pages = SwitcherPages()
        pages.focused(top, CardSpot(top, 11))
        pages.openPerf(top)
        pages.openPerf(bottom)
        pages.reset()
        assertEquals(emptyMap<Int, Page>(), pages.state.value)
        assertNull(pages.returnSpot(top))
    }
}
