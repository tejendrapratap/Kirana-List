package com.grocerysync.app.uitest

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grocerysync.app.data.Units
import com.grocerysync.app.testing.FakeSheetServer.Companion.quantity
import com.grocerysync.app.testing.FakeSheetServer.Companion.yesNo
import com.grocerysync.app.testing.FakeSheetServer.Need
import com.grocerysync.app.testing.GroceryUiTest
import com.grocerysync.app.testing.button
import com.grocerysync.app.testing.gotIt
import com.grocerysync.app.testing.hasContentDescriptionMatching
import com.grocerysync.app.testing.needSwitch
import com.grocerysync.app.testing.searchField
import com.grocerysync.app.testing.stepButton
import com.grocerysync.app.testing.stepperAmount
import com.grocerysync.app.testing.tab
import com.grocerysync.app.testing.toBuyRowWithAmount
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Ticked today" under To buy: the items this phone took off To buy today, closed until tapped, each with Put back;
 * the Undo of a tick takes it off again, and it is hidden while a search is open.
 */
@RunWith(AndroidJUnit4::class)
class TickedTodayTest : GroceryUiTest() {

    /** The section's header, a heading that TalkBack reads with its count: "Ticked today, 2 items". */
    private fun tickedToday(count: Int): SemanticsMatcher =
        isHeading() and hasContentDescription("Ticked today, $count ${if (count == 1) "item" else "items"}")

    private val anyTickedToday: SemanticsMatcher = isHeading() and hasContentDescriptionMatching(Regex("Ticked today, \\d+ items?"))

    /** The Put back button of [name], as TalkBack reads it. */
    private fun putBack(name: String): SemanticsMatcher = hasContentDescription("Put back $name") and hasClickAction()

    private fun state(text: String): SemanticsMatcher = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text)

    @Test
    fun gotItGoesUnderTickedTodayAndPutBackBringsItBack() {
        fake.seed("Glue", quantity("2", Units.PCS))
        fake.seed("Stapler", yesNo(true))
        launchConnected()
        waitForNode(toBuyRowWithAmount("Glue", "2 pcs"))
        assertNoNode(anyTickedToday)

        node(gotIt("Glue")).performClick()
        waitForNoNode(gotIt("Glue"))
        // Closed at first: only the heading, with its count.
        waitForNode(tickedToday(1) and state("Collapsed"))
        assertNoNode(putBack("Glue"))
        assertEquals("Show items", node(tickedToday(1)).fetchSemanticsNode().config[SemanticsActions.OnClick].label)

        node(tickedToday(1)).performClick()
        waitForNode(tickedToday(1) and state("Expanded"))
        waitForNode(putBack("Glue"))
        // The amount Put back gives it again.
        waitForNode(hasContentDescription("Quantity 2 pcs"))

        node(putBack("Glue")).performClick()
        waitForNode(toBuyRowWithAmount("Glue", "2 pcs"))
        waitForNoNode(anyTickedToday)
        waitFor("2 on the sheet again") { (fake.item("Glue")?.need as? Need.Quantity)?.amountIs("2") == true }
    }

    @Test
    fun theUndoOfATickTakesItOffTickedToday() {
        fake.seed("Stapler", yesNo(true))
        launchConnected()

        node(gotIt("Stapler")).performClick()
        waitForNode(tickedToday(1))
        node(button("Undo")).performClick()
        waitForNode(gotIt("Stapler"))
        waitForNoNode(anyTickedToday)
    }

    @Test
    fun theNeedSwitchAndStepsDownToZeroTickItemsOffToo() {
        fake.seed("Stapler", yesNo(true))
        fake.seed("Glue", quantity("2", Units.PCS))
        launchConnected()
        showAllItems()

        node(needSwitch("Stapler")).performClick()
        waitForNode(needSwitch("Stapler") and isOff())
        repeat(2) { node(stepButton("Glue", "1 pcs", more = false)).performClick() }
        waitForNode(stepperAmount("Glue", "0 pcs"))

        node(tab("To buy")).performClick()
        waitForNode(tab("To buy") and isSelected())
        node(tickedToday(2)).performClick()
        waitForNode(putBack("Stapler"))
        // The two steps were one change: Glue goes back to the amount from before them.
        node(putBack("Glue")).performClick()
        waitForNode(toBuyRowWithAmount("Glue", "2 pcs"))
        waitForNode(tickedToday(1))
    }

    @Test
    fun itIsHiddenWhileASearchIsOpen() {
        fake.seed("Stapler", yesNo(true))
        fake.seed("Glue", quantity("1", Units.PCS))
        launchConnected()

        node(gotIt("Stapler")).performClick()
        waitForNode(tickedToday(1))
        searchButton().performClick()
        waitForNode(searchField)
        waitForNoNode(anyTickedToday)

        node(hasContentDescription("Close search")).performClick()
        waitForNode(tickedToday(1))
    }
}
