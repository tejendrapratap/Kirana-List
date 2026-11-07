package com.grocerysync.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pager after a rotation or process death in the middle of a page change ([pageToSettleOnRestore]). */
class ListPagesTest {

    private val toBuy = ListFilter.TO_BUY.page
    private val all = ListFilter.ALL.page

    @Test
    fun pagesFollowTheSegmentedRow() {
        assertEquals(0, toBuy)
        assertEquals(1, all)
        assertEquals(ListFilter.TO_BUY, filterOfPage(0))
        assertEquals(ListFilter.ALL, filterOfPage(1))
    }

    @Test
    fun aPagerAtRestIsLeftAlone() {
        assertNull(pageToSettleOnRestore(toBuy, 0f, searchOpen = false))
        assertNull(pageToSettleOnRestore(all, 0f, searchOpen = false))
        assertNull(pageToSettleOnRestore(all, 0f, searchOpen = true))
    }

    @Test
    fun anUnfinishedSwipeOrTapGoesBackToTheCurrentPage() {
        // Rotated with All items 70% in view (current page All items) or 30% (still To buy).
        assertEquals(all, pageToSettleOnRestore(all, -0.3f, searchOpen = false))
        assertEquals(toBuy, pageToSettleOnRestore(toBuy, 0.3f, searchOpen = false))
        assertEquals(toBuy, pageToSettleOnRestore(toBuy, 0.5f, searchOpen = false))
    }

    @Test
    fun aSearchBeingOpenedFromToBuyIsKeptOnAllItems() {
        // Search icon on To buy, rotated during the page animation (or before it started): not closed.
        assertEquals(all, pageToSettleOnRestore(toBuy, 0.3f, searchOpen = true))
        assertEquals(all, pageToSettleOnRestore(toBuy, 0f, searchOpen = true))
        // A swipe away from All items with the search open, not finished: back to All items, search kept.
        assertEquals(all, pageToSettleOnRestore(all, -0.2f, searchOpen = true))
    }
}
