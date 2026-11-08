package com.grocerysync.app.ui

import com.grocerysync.app.data.Amount
import com.grocerysync.app.data.GroceryItem
import com.grocerysync.app.data.ItemKeys
import com.grocerysync.app.data.ItemValue
import com.grocerysync.app.data.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The list screen's pages: the pager after a rotation or process death in the middle of a page change
 * ([pageToSettleOnRestore]), when a page's list goes to the top for the search ([SearchScroll]), and what each
 * page shows with and without a search ([pageContent]).
 */
class ListPagesTest {

    private val toBuy = ListFilter.TO_BUY.page
    private val all = ListFilter.ALL.page

    private fun item(name: String, needed: Boolean = false) = GroceryItem(
        key = ItemKeys.normalize(name),
        name = name,
        value = ItemValue.BoolValue(needed),
        updatedAt = 1_000L,
        updatedBy = "Home",
        pendingSync = false,
    )

    private val milk = item("Milk", needed = true)
    private val rice = GroceryItem(
        key = "rice",
        name = "Rice",
        value = ItemValue.Quantity(Amount.whole(2L), Units.KG),
        updatedAt = 1_000L,
        updatedBy = "Home",
        pendingSync = false,
    )
    private val basmati = item("Basmati rice", needed = true)
    private val riceFlour = item("Rice flour")
    private val coffee = item("Coffee")
    private val coffeeBeans = item("coffee beans")
    private val bread = item("Bread")

    /** As in [ListUiState]: To buy holds the needed items by name, All items those first, then the rest. */
    private val neededItems = listOf(basmati, milk, rice)
    private val everyItem = neededItems + listOf(bread, coffee, coffeeBeans, riceFlour)

    private fun content(
        page: ListFilter,
        query: String,
        toBuyItems: List<GroceryItem> = neededItems,
        allItems: List<GroceryItem> = everyItem,
        loaded: Boolean = true,
    ) = pageContent(page, loaded, toBuyItems, allItems, query, Locale.ENGLISH)

    private fun rows(vararg items: GroceryItem) = PageContent.Rows(items.toList())

    private fun toBuyNoMatch(action: NoMatchAction) = PageContent.NoMatch(ItemSearch.NO_TO_BUY_MATCH_TEXT, action)

    private fun allNoMatch(action: NoMatchAction) = PageContent.NoMatch(ItemSearch.NO_MATCH_TEXT, action)

    @Test
    fun pagesFollowTheSegmentedRow() {
        assertEquals(0, toBuy)
        assertEquals(1, all)
        assertEquals(ListFilter.TO_BUY, filterOfPage(0))
        assertEquals(ListFilter.ALL, filterOfPage(1))
    }

    @Test
    fun aPagerAtRestIsLeftAlone() {
        assertNull(pageToSettleOnRestore(toBuy, 0f))
        assertNull(pageToSettleOnRestore(all, 0f))
    }

    @Test
    fun anUnfinishedSwipeOrTapGoesBackToTheCurrentPage() {
        // Rotated with All items 70% in view (current page All items) or 30% (still To buy). An open search
        // stays open on that page.
        assertEquals(all, pageToSettleOnRestore(all, -0.3f))
        assertEquals(toBuy, pageToSettleOnRestore(toBuy, 0.3f))
        assertEquals(toBuy, pageToSettleOnRestore(toBuy, 0.5f))
    }

    @Test
    fun typingMovesThePageTypedOnToTheTopWithEachNewQuery() {
        val scroll = SearchScroll()
        assertTrue(scroll.takeScrollToTop(ListFilter.ALL, "e"))
        assertTrue(scroll.takeScrollToTop(ListFilter.ALL, "eg"))
        // The same query again (the page composed again, live updates, a letter past the limit): it stays put.
        assertFalse(scroll.takeScrollToTop(ListFilter.ALL, "eg"))
        assertEquals("eg", scroll.query)
        assertEquals(setOf(ListFilter.ALL), scroll.pagesAtTop)
    }

    @Test
    fun theOtherPageGoesToTheTopOnlyOnceShownDuringTheSearch() {
        // The review's case: To buy scrolled halfway down; "eggs" typed on All items, then X, then To buy shown.
        val scroll = SearchScroll()
        assertTrue(scroll.takeScrollToTop(ListFilter.ALL, "eggs"))
        assertFalse(scroll.takeScrollToTop(ListFilter.ALL, "")) // X: All items, now without a search
        assertFalse(scroll.takeScrollToTop(ListFilter.TO_BUY, "")) // To buy keeps its place
        // The other way round: a search typed and closed on To buy leaves All items alone.
        assertTrue(scroll.takeScrollToTop(ListFilter.TO_BUY, "rice"))
        assertFalse(scroll.takeScrollToTop(ListFilter.TO_BUY, ""))
        assertFalse(scroll.takeScrollToTop(ListFilter.ALL, ""))
        assertTrue(scroll.pagesAtTop.isEmpty())
    }

    @Test
    fun eachPageGoesToTheTopOncePerQuery() {
        val scroll = SearchScroll()
        scroll.takeScrollToTop(ListFilter.ALL, "eggs")
        // To buy shown while "eggs" is searched (tap, swipe, or back from "Show n in All items"): its results
        // start at the top, once; switching back and forth keeps each page's place within its results.
        assertTrue(scroll.takeScrollToTop(ListFilter.TO_BUY, "eggs"))
        assertFalse(scroll.takeScrollToTop(ListFilter.ALL, "eggs"))
        assertFalse(scroll.takeScrollToTop(ListFilter.TO_BUY, "eggs"))
        // A new query: To buy (typed on) at once, All items again once it is shown.
        assertTrue(scroll.takeScrollToTop(ListFilter.TO_BUY, "eggs w"))
        assertEquals(setOf(ListFilter.TO_BUY), scroll.pagesAtTop)
        assertTrue(scroll.takeScrollToTop(ListFilter.ALL, "eggs w"))
        assertEquals(ListFilter.entries.toSet(), scroll.pagesAtTop)
    }

    @Test
    fun closingOrEmptyingTheSearchMovesNoListAndStartsOver() {
        val scroll = SearchScroll()
        for (query in listOf("", "  ", " ' ")) {
            for (page in ListFilter.entries) assertFalse(query, scroll.takeScrollToTop(page, query))
        }
        assertTrue(scroll.pagesAtTop.isEmpty())
        // Both pages went to the top for "eggs"; the field emptied (or the search closed), then "eggs" typed
        // again: a new search, so each page's results start at the top again.
        scroll.takeScrollToTop(ListFilter.ALL, "eggs")
        scroll.takeScrollToTop(ListFilter.TO_BUY, "eggs")
        assertFalse(scroll.takeScrollToTop(ListFilter.TO_BUY, ""))
        assertTrue(scroll.pagesAtTop.isEmpty())
        assertTrue(scroll.takeScrollToTop(ListFilter.TO_BUY, "eggs"))
        assertTrue(scroll.takeScrollToTop(ListFilter.ALL, "eggs"))
    }

    @Test
    fun aRestoredSearchScrollMovesNoPageAgain() {
        // Rotated (or the process died) after "eggs" was typed on All items, before To buy was shown.
        val before = SearchScroll()
        before.takeScrollToTop(ListFilter.ALL, "eggs")
        val restored = SearchScroll(before.query, before.pagesAtTop)
        assertFalse(restored.takeScrollToTop(ListFilter.ALL, "eggs"))
        assertTrue(restored.takeScrollToTop(ListFilter.TO_BUY, "eggs"))
        // What is saved is a copy: later changes don't reach it.
        val saved = restored.pagesAtTop
        restored.takeScrollToTop(ListFilter.ALL, "")
        assertEquals(ListFilter.entries.toSet(), saved)
    }

    @Test
    fun nothingShowsBeforeTheListWasRead() {
        for (page in ListFilter.entries) {
            for (query in listOf("", "rice", "corriander")) {
                assertSame(PageContent.NotLoaded, content(page, query, emptyList(), emptyList(), loaded = false))
            }
        }
    }

    @Test
    fun withoutASearchEachPageShowsItsListAsItIs() {
        // "" is a closed search; a query of only spaces or apostrophes is no search either.
        for (query in listOf("", "  ", " ' ")) {
            val toBuyRows = content(ListFilter.TO_BUY, query) as PageContent.Rows
            val allRows = content(ListFilter.ALL, query) as PageContent.Rows
            assertSame(neededItems, toBuyRows.items)
            assertSame(everyItem, allRows.items)
        }
    }

    @Test
    fun withoutASearchAnEmptyPageShowsItsEmptyState() {
        // Nothing needed: To buy is empty, All items isn't.
        assertSame(PageContent.Empty, content(ListFilter.TO_BUY, "", toBuyItems = emptyList(), allItems = listOf(bread)))
        assertEquals(rows(bread), content(ListFilter.ALL, "", toBuyItems = emptyList(), allItems = listOf(bread)))
        for (page in ListFilter.entries) {
            assertSame(PageContent.Empty, content(page, "", emptyList(), emptyList()))
        }
    }

    @Test
    fun toBuySearchesOnlyTheNeededItems() {
        // Rice flour matches too, but isn't needed: only All items shows it.
        assertEquals(rows(basmati, rice), content(ListFilter.TO_BUY, "rice"))
        assertEquals(rows(basmati, rice, riceFlour), content(ListFilter.ALL, "rice"))
        // Same rules as on All items: every word, in any order; capitals and spaces don't count.
        assertEquals(rows(basmati), content(ListFilter.TO_BUY, "  RICE   basmati "))
        assertEquals(rows(milk), content(ListFilter.TO_BUY, "mil"))
    }

    @Test
    fun searchResultsAreAToZOnBothPages() {
        val almondMilk = item("Almond milk")
        val apples = item("apples", needed = true)
        val needed = listOf(apples, milk)
        val every = needed + listOf(almondMilk, bread)
        assertEquals(rows(apples, milk), content(ListFilter.TO_BUY, "l", needed, every))
        // Not needed first: Almond milk (not needed) comes before Milk (needed).
        assertEquals(rows(almondMilk, milk), content(ListFilter.ALL, "milk", needed, every))
    }

    @Test
    fun toBuyWithNoNeededMatchOffersTheMatchesUnderAllItems() {
        assertEquals(toBuyNoMatch(NoMatchAction.ShowInAllItems(2)), content(ListFilter.TO_BUY, "coffee"))
        assertEquals(toBuyNoMatch(NoMatchAction.ShowInAllItems(1)), content(ListFilter.TO_BUY, "flour"))
        assertEquals(toBuyNoMatch(NoMatchAction.ShowInAllItems(1)), content(ListFilter.TO_BUY, "beans coffee"))
        // All items, with the same query, shows them.
        assertEquals(rows(coffee, coffeeBeans), content(ListFilter.ALL, "coffee"))
        assertEquals(rows(riceFlour), content(ListFilter.ALL, "flour"))
    }

    @Test
    fun aSearchOnAnEmptyToBuySaysNoMatchNotNothingToBuy() {
        val nothingNeeded = listOf(bread, coffee)
        assertEquals(
            toBuyNoMatch(NoMatchAction.ShowInAllItems(1)),
            content(ListFilter.TO_BUY, "bread", toBuyItems = emptyList(), allItems = nothingNeeded),
        )
        assertEquals(
            toBuyNoMatch(NoMatchAction.Add("Rice")),
            content(ListFilter.TO_BUY, "rice", toBuyItems = emptyList(), allItems = nothingNeeded),
        )
    }

    @Test
    fun nothingMatchingAnywhereOffersToAddTheQuery() {
        assertEquals(toBuyNoMatch(NoMatchAction.Add("Corriander")), content(ListFilter.TO_BUY, "corriander"))
        assertEquals(allNoMatch(NoMatchAction.Add("Corriander")), content(ListFilter.ALL, "corriander"))
        assertEquals(allNoMatch(NoMatchAction.Add("Oat milk")), content(ListFilter.ALL, "  oat   milk "))
        // Also on an empty list.
        for (page in ListFilter.entries) {
            val shown = content(page, "tea", emptyList(), emptyList()) as PageContent.NoMatch
            assertEquals(NoMatchAction.Add("Tea"), shown.action)
        }
    }

    @Test
    fun allItemsNeverOffersToShowAllItems() {
        for (query in listOf("corriander", "x", "rice milk")) {
            val shown = content(ListFilter.ALL, query) as PageContent.NoMatch
            assertTrue(query, shown.action is NoMatchAction.Add)
        }
    }

    @Test
    fun theSuggestedNameUsesThePhonesLanguage() {
        val turkish = Locale.forLanguageTag("tr")
        val shown = pageContent(ListFilter.TO_BUY, true, emptyList(), emptyList(), "irmik", turkish)
        assertEquals(toBuyNoMatch(NoMatchAction.Add("\u0130rmik")), shown)
    }

    @Test
    fun noMatchTexts() {
        assertEquals("No items to buy match your search", ItemSearch.NO_TO_BUY_MATCH_TEXT)
        assertEquals("No items match your search", ItemSearch.NO_MATCH_TEXT)
        assertEquals("Show 1 in All items", NoMatchAction.ShowInAllItems(1).text)
        assertEquals("Show 2 in All items", NoMatchAction.ShowInAllItems(2).text)
        assertEquals("Add \"Corriander\" to the list", NoMatchAction.Add("Corriander").text)
    }
}
