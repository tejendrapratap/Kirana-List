package com.grocerysync.app.uitest

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grocerysync.app.data.ShopSection
import com.grocerysync.app.data.Units
import com.grocerysync.app.testing.AppState
import com.grocerysync.app.testing.FakeSheetServer.Companion.quantity
import com.grocerysync.app.testing.FakeSheetServer.Companion.yesNo
import com.grocerysync.app.testing.GroceryUiTest
import com.grocerysync.app.testing.anySectionHeader
import com.grocerysync.app.testing.button
import com.grocerysync.app.testing.gotIt
import com.grocerysync.app.testing.itemRow
import com.grocerysync.app.testing.searchField
import com.grocerysync.app.testing.sectionHeader
import com.grocerysync.app.testing.sectionPicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * To buy by shop section (1.4): a header per section in walking order, flat results while searching, and moving an
 * item to another section on this phone only (nothing goes to the sheet).
 */
@RunWith(AndroidJUnit4::class)
class ToBuySectionsTest : GroceryUiTest() {

    @Test
    fun toBuyIsGroupedInWalkingOrder() {
        fake.seed("Milk", quantity("2", Units.L))
        fake.seed("Onion", quantity("1", Units.KG))
        fake.seed("Gadget", yesNo(true))
        fake.seed("Curd", yesNo(true))
        fake.seed("Rice", yesNo(false))
        launchConnected()

        val sabzi = sectionHeader("Sabzi & fruit", 1)
        val dairy = sectionHeader("Dairy, eggs & bread", 2)
        val other = sectionHeader("Other", 1)
        waitForNode(other)
        // Rice isn't needed, so its section has no header.
        assertNoNode(sectionHeader("Atta, rice & dal"))

        // Walking order, each item under its section (Curd is Dahi), A to Z inside a section.
        val fromTop = listOf(sabzi, itemRow("Onion"), dairy, itemRow("Curd"), itemRow("Milk"), other, itemRow("Gadget"))
        assertEquals(fromTop.map { it.description }, fromTop.sortedBy(::top).map { it.description })

        // Searching shows the results without headers; closing the search brings them back.
        searchButton().performClick()
        waitForNode(searchField).performTextInput("milk")
        waitForNoNode(anySectionHeader)
        waitForNode(gotIt("Milk"))
        node(hasContentDescription("Close search") and hasClickAction()).performClick()
        waitForNode(dairy)
    }

    @Test
    fun anItemMovesToAnotherSectionOnThisPhoneOnly() {
        fake.seed("Gadget", yesNo(true))
        fake.seed("Milk", quantity("2", Units.L))
        launchConnected()
        waitForNode(sectionHeader("Other", 1))

        node(itemRow("Gadget")).performClick()
        waitForNode(hasText("Edit item"))
        waitForNode(hasText("This phone only"))
        node(sectionPicker).assert(hasContentDescription("Shop section: Other")).performClick()
        waitForNode(button("Cleaning & household")).performClick()
        waitForNode(sectionPicker and hasContentDescription("Shop section: Cleaning & household"))
        node(button("Save")).performClick()

        waitForNode(sectionHeader("Cleaning & household", 1))
        waitForNoNode(sectionHeader("Other"))
        assertEquals(ShopSection.CLEANING_HOUSEHOLD, AppState.container.shopSections.current().chosen["gadget"])
        // Nothing about it went to the sheet.
        assertTrue(fake.changesFromApp("Gadget").isEmpty())

        // Back to the dictionary's section: the choice is forgotten.
        node(itemRow("Gadget")).performClick()
        waitForNode(sectionPicker and hasContentDescription("Shop section: Cleaning & household")).performClick()
        waitForNode(button("Other")).performClick()
        node(button("Save")).performClick()
        waitForNode(sectionHeader("Other", 1))
        assertTrue(AppState.container.shopSections.current().chosen.isEmpty())
    }

    /** Where the node matching [matcher] starts, from the top of the screen. */
    private fun top(matcher: SemanticsMatcher): Float = node(matcher).fetchSemanticsNode().boundsInRoot.top
}
