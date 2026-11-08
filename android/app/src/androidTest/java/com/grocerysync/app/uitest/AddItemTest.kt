package com.grocerysync.app.uitest

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grocerysync.app.testing.FakeSheetServer.Need
import com.grocerysync.app.testing.GroceryUiTest
import com.grocerysync.app.testing.amountField
import com.grocerysync.app.testing.button
import com.grocerysync.app.testing.choice
import com.grocerysync.app.testing.gotIt
import com.grocerysync.app.testing.itemRow
import com.grocerysync.app.testing.nameField
import com.grocerysync.app.testing.stepperAmount
import com.grocerysync.app.testing.textField
import com.grocerysync.app.testing.toBuyRowWithAmount
import com.grocerysync.app.testing.unitPicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The + button: a yes / no item, and a quantity, which needs a unit (no default) before Save turns on. */
@RunWith(AndroidJUnit4::class)
class AddItemTest : GroceryUiTest() {

    @Test
    fun addsAYesNoItemThatIsNeeded() {
        launchConnected()

        addButton().performClick()
        waitForNode(button("Save"))
        node(choice("Yes / no")).assertIsSelected()
        node(choice("No")).assertIsSelected()
        node(nameField).performTextInput("Bananas")
        node(choice("Yes")).performClick()
        node(button("Save")).assertIsEnabled().performClick()

        // Needed, so it is on To buy (the page shown) at once, and reaches the sheet.
        waitForNode(gotIt("Bananas"))
        waitFor("Bananas on the sheet") { fake.item("Bananas") != null }
        val sent = fake.changesFromApp("Bananas").last()
        assertEquals("upsert", sent.op)
        assertEquals("boolean", sent.type)
        assertEquals(true, sent.value)
        assertEquals(ME, sent.device)
        assertEquals("applied", sent.status)
        assertEquals(Need.YesNo(true), fake.item("Bananas")!!.need)

        // All items says who added it.
        showAllItems()
        waitForNode(itemRow("Bananas", "Added by $ME"))
    }

    @Test
    fun aQuantityNeedsAUnitAndTakesOneOfYourOwn() {
        launchConnected()

        addButton().performClick()
        node(nameField).performTextInput("Coriander")
        node(choice("Quantity")).performClick()

        // No unit yet: the picker asks for one and Save stays off, also once an amount is set.
        node(unitPicker).assert(hasContentDescription("Unit") and hasStateDescription("Choose a unit to save"))
        node(button("Save")).assertIsNotEnabled()
        node(amountField).performTextReplacement("2")
        node(button("Save")).assertIsNotEnabled()

        // A standard unit turns Save on.
        node(unitPicker).performClick()
        waitForNode(button("kg")).performClick()
        waitForNode(unitPicker and hasContentDescription("Unit: kg"))
        node(button("Save")).assertIsEnabled()

        // Other... takes a unit of your own instead.
        node(unitPicker).performClick()
        waitForNode(button("Other...")).performClick()
        waitForNode(textField("Unit")).performTextInput("bunch")
        node(button("Use")).assertIsEnabled().performClick()
        waitForNode(unitPicker and hasContentDescription("Unit: bunch"))
        node(button("Save")).assertIsEnabled().performClick()

        waitForNode(toBuyRowWithAmount("Coriander", "2 bunch"))
        waitFor("Coriander on the sheet") { fake.item("Coriander") != null }
        val sent = fake.changesFromApp("Coriander").last()
        assertEquals("upsert", sent.op)
        assertEquals("quantity", sent.type)
        assertTrue("amount sent: ${sent.value}", sent.amountIs("2"))
        assertEquals("bunch", sent.unit)
        val stored = fake.item("Coriander")!!.need as Need.Quantity
        assertTrue(stored.amountIs("2"))
        assertEquals("bunch", stored.unit)

        showAllItems()
        waitForNode(stepperAmount("Coriander", "2 bunch"))
    }
}
