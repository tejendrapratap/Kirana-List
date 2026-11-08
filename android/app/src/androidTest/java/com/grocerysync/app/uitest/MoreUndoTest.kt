package com.grocerysync.app.uitest

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grocerysync.app.data.Amount
import com.grocerysync.app.data.ItemValue
import com.grocerysync.app.data.Units
import com.grocerysync.app.testing.AppState
import com.grocerysync.app.testing.FakeSheetServer
import com.grocerysync.app.testing.FakeSheetServer.Companion.quantity
import com.grocerysync.app.testing.FakeSheetServer.Companion.yesNo
import com.grocerysync.app.testing.FakeSheetServer.Need
import com.grocerysync.app.testing.GroceryUiTest
import com.grocerysync.app.testing.amountField
import com.grocerysync.app.testing.button
import com.grocerysync.app.testing.gotIt
import com.grocerysync.app.testing.itemRow
import com.grocerysync.app.testing.nameField
import com.grocerysync.app.testing.needSwitch
import com.grocerysync.app.testing.stepButton
import com.grocerysync.app.testing.stepperAmount
import com.grocerysync.app.testing.toBuyRowWithAmount
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Undo after - / + (steps of one item in a row are one Undo), the Need switch and an Edit dialog save; and an Undo
 * that finds the item changed on the other phone leaves it as it is and says so.
 */
@RunWith(AndroidJUnit4::class)
class MoreUndoTest : GroceryUiTest() {

    private fun FakeSheetServer.quantityOf(name: String): Need.Quantity? = item(name)?.need as? Need.Quantity

    @Test
    fun stepsOfOneItemAreUndoneTogether() {
        fake.seed("Glue", quantity("1", Units.PCS))
        launchConnected()
        showAllItems()
        waitForNode(stepperAmount("Glue", "1 pcs"))

        node(stepButton("Glue", "1 pcs", more = true)).performClick()
        waitForNode(hasText("Glue: 1 pcs → 2 pcs"))
        node(stepButton("Glue", "1 pcs", more = true)).performClick()
        // One snackbar for both steps, from the amount before the first.
        waitForNode(hasText("Glue: 1 pcs → 3 pcs"))
        withUiClockStopped {
            waitFor("3 on the sheet") { fake.quantityOf("Glue")?.amountIs("3") == true }
        }

        node(button("Undo")).performClick()
        waitForNode(stepperAmount("Glue", "1 pcs"))
        waitFor("1 on the sheet again") { fake.quantityOf("Glue")?.amountIs("1") == true }
        assertNoNode(hasText("Glue: 1 pcs → 3 pcs"))
    }

    @Test
    fun theNeedSwitchCanBeUndone() {
        fake.seed("Stapler", yesNo(false))
        launchConnected()
        showAllItems()

        node(needSwitch("Stapler")).performClick()
        waitForNode(hasText("Stapler: needed"))
        withUiClockStopped {
            waitFor("Stapler needed on the sheet") { fake.item("Stapler")?.need == Need.YesNo(true) }
        }

        node(button("Undo")).performClick()
        waitForNode(needSwitch("Stapler") and isOff())
        waitFor("Stapler not needed on the sheet again") { fake.item("Stapler")?.need == Need.YesNo(false) }

        node(needSwitch("Stapler")).performClick()
        waitForNode(needSwitch("Stapler") and isOn())
        waitForNode(hasText("Stapler: needed"))
    }

    @Test
    fun anEditIsUndoneWithItsNameAndAmount() {
        fake.seed("Notebook", quantity("1", Units.PCS))
        launchConnected()
        showAllItems()

        node(itemRow("Notebook")).performClick()
        waitForNode(hasText("Edit item"))
        node(nameField).performTextReplacement("Diary")
        node(amountField).performTextReplacement("2")
        node(button("Save")).performClick()

        waitForNode(hasText("Changed Notebook, now called Diary"))
        waitForNode(stepperAmount("Diary", "2 pcs"))
        withUiClockStopped {
            waitFor("the rename on the sheet") { fake.item("Diary") != null && fake.item("Notebook") == null }
        }

        node(button("Undo")).performClick()
        waitForNode(stepperAmount("Notebook", "1 pcs"))
        waitForNoNode(itemRow("Diary"))
        waitFor("Notebook back on the sheet") {
            fake.item("Diary") == null && fake.quantityOf("Notebook")?.amountIs("1") == true
        }
    }

    @Test
    fun anEditOfTheAmountOnlySaysChanged() {
        fake.seed("Pens", quantity("2", Units.PCS))
        launchConnected()
        showAllItems()

        node(itemRow("Pens")).performClick()
        waitForNode(hasText("Edit item"))
        node(amountField).performTextReplacement("5")
        node(button("Save")).performClick()
        waitForNode(hasText("Changed Pens"))
        waitForNode(stepperAmount("Pens", "5 pcs"))

        node(button("Undo")).performClick()
        waitForNode(stepperAmount("Pens", "2 pcs"))
        waitFor("2 on the sheet") { fake.quantityOf("Pens")?.amountIs("2") == true }
    }

    @Test
    fun anUndoKeepsAChangeMadeOnTheOtherPhoneMeanwhile() {
        fake.seed("Bulbs", quantity("2", Units.PCS))
        launchConnected()
        waitForNode(toBuyRowWithAmount("Bulbs", "2 pcs"))

        node(gotIt("Bulbs")).performClick()
        waitForNode(hasText("Got Bulbs"))
        withUiClockStopped {
            waitFor("Bulbs at 0 on the sheet") { fake.quantityOf("Bulbs")?.amountIs("0") == true }
            // Home needs 4 now; the app's next poll brings it.
            fake.upsertAs("Home", "Bulbs", quantity("4", Units.PCS))
            waitFor("Home's 4 on this phone") { appHas("Bulbs", ItemValue.Quantity(Amount.whole(4L), Units.PCS)) }
        }

        node(button("Undo")).performClick()
        waitForNode(hasText("\"Bulbs\" was changed on another phone"))
        waitForNode(toBuyRowWithAmount("Bulbs", "4 pcs"))
        assertTrue("Home's 4 kept on the sheet", fake.quantityOf("Bulbs")?.amountIs("4") == true)
    }

    /** The app's own list (its database with its waiting changes) has [name] with [value]. */
    private fun appHas(name: String, value: ItemValue): Boolean = runBlocking {
        AppState.container.repository.items.first().any { it.name == name && it.value == value }
    }
}
