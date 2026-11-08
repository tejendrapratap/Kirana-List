package com.grocerysync.app.uitest

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grocerysync.app.data.Units
import com.grocerysync.app.testing.FakeSheetServer.Companion.quantity
import com.grocerysync.app.testing.FakeSheetServer.Companion.yesNo
import com.grocerysync.app.testing.GroceryUiTest
import com.grocerysync.app.testing.button
import com.grocerysync.app.testing.gotIt
import com.grocerysync.app.testing.itemRow
import com.grocerysync.app.testing.nameField
import com.grocerysync.app.testing.pager
import com.grocerysync.app.testing.searchField
import com.grocerysync.app.testing.tab
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Search on the page shown (1.3): the app bar's icon opens it on To buy or All items without switching pages.
 * To buy searches the needed items, All items every item; switching pages keeps the query. When nothing to buy
 * matches, To buy offers the matching items that aren't needed ("Show n in All items"), or Add when nothing on
 * the list matches at all.
 */
@RunWith(AndroidJUnit4::class)
class SearchTest : GroceryUiTest() {

    private val noToBuyMatch = hasText("No items to buy match your search")
    private val noMatch = hasText("No items match your search")
    private val closeSearch = hasContentDescription("Close search") and hasClickAction()

    @Test
    fun searchesThePageShownAndSwitchingPagesKeepsTheQuery() {
        fake.seed("Basmati rice", yesNo(true))
        fake.seed("Brown rice", yesNo(false))
        fake.seed("Milk", quantity("1", Units.L))
        launchConnected()
        node(tab("To buy")).assertIsSelected()

        // The icon opens the field on the page shown: To buy stays.
        searchButton().performClick()
        waitForNode(searchField).performTextInput("rice")
        node(tab("To buy")).assertIsSelected()

        // To buy: the needed items that match (Brown rice isn't needed, Milk doesn't match).
        waitForNode(gotIt("Basmati rice"))
        waitForNoNode(itemRow("Milk"))
        waitForNoNode(itemRow("Brown rice"))

        // All items (tapped): every item that matches; the field keeps the query.
        showAllItems()
        node(searchField).assert(hasText("rice"))
        waitForNode(itemRow("Basmati rice"))
        waitForNode(itemRow("Brown rice"))
        waitForNoNode(itemRow("Milk"))

        // Every word, in any order.
        node(searchField).performTextReplacement("rice basmati")
        waitForNoNode(itemRow("Brown rice"))
        waitForNode(itemRow("Basmati rice"))

        // Swiping back to To buy keeps the query too.
        node(pager).performTouchInput { swipeRight() }
        waitForNode(tab("To buy") and isSelected())
        node(searchField).assert(hasText("rice basmati"))
        waitForNode(gotIt("Basmati rice"))
        waitForNoNode(itemRow("Milk"))

        // The X closes the search on the page shown, which then shows all its items again.
        node(closeSearch).performClick()
        waitForNoNode(searchField)
        waitForNode(gotIt("Milk"))
        node(tab("To buy")).assertIsSelected()
    }

    @Test
    fun toBuyOffersTheMatchingItemsThatAreNotNeeded() {
        fake.seed("Basmati rice", yesNo(false))
        fake.seed("Brown rice", yesNo(false))
        fake.seed("Crème fraîche", yesNo(false))
        fake.seed("Milk", quantity("1", Units.L))
        launchConnected()

        searchButton().performClick()
        waitForNode(searchField).performTextInput("rice")

        // Nothing to buy matches, but two items that aren't needed do.
        waitForNode(noToBuyMatch)
        waitForNoNode(itemRow("Milk"))
        waitForNoNode(button("Add \"Rice\" to the list"))
        waitForNode(button("Show 2 in All items")).performClick()

        // All items, with the same query: both are there.
        waitForNode(tab("All items") and isSelected())
        node(searchField).assert(hasText("rice"))
        waitForNode(itemRow("Basmati rice"))
        waitForNode(itemRow("Brown rice"))
        waitForNoNode(itemRow("Milk"))

        // Accents don't count; back on To buy the row counts the matches of the new query.
        node(searchField).performTextReplacement("creme")
        waitForNode(itemRow("Crème fraîche"))
        node(tab("To buy")).performClick()
        waitForNode(tab("To buy") and isSelected())
        waitForNode(noToBuyMatch)
        waitForNode(button("Show 1 in All items"))
    }

    @Test
    fun nothingOnTheListMatchesOffersToAddIt() {
        fake.seed("Milk", quantity("1", Units.L))
        fake.seed("Brown rice", yesNo(false))
        launchConnected()

        // To buy: no item matches anywhere ("oat" is in no name), so it offers to add one.
        searchButton().performClick()
        waitForNode(searchField).performTextInput("oat milk")
        waitForNode(noToBuyMatch)
        waitForNoNode(itemRow("Milk"))
        waitForNoNode(hasText("Show", substring = true) and hasClickAction())
        waitForNode(button("Add \"Oat milk\" to the list")).performClick()

        // Add opens the Add dialog with the name filled in; Cancel keeps the search.
        waitForNode(hasText("Add item"))
        node(nameField).assert(hasText("Oat milk"))
        node(button("Cancel")).performClick()
        waitForNoNode(nameField)
        node(searchField).assert(hasText("oat milk"))

        // All items says the same, in its own words.
        showAllItems()
        waitForNode(noMatch)
        waitForNode(button("Add \"Oat milk\" to the list"))
        waitForNoNode(itemRow("Milk"))

        // The X closes the search and shows everything again.
        node(closeSearch).performClick()
        waitForNoNode(searchField)
        waitForNode(itemRow("Brown rice"))
        waitForNode(itemRow("Milk"))
        node(tab("All items")).assertIsSelected()
        assertTrue(fake.changesFromApp("Oat milk").isEmpty())
    }
}
