package com.grocerysync.app.uitest

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grocerysync.app.data.Units
import com.grocerysync.app.testing.FakeSheetServer.Companion.quantity
import com.grocerysync.app.testing.FakeSheetServer.Companion.yesNo
import com.grocerysync.app.testing.FakeSheetServer.Need
import com.grocerysync.app.testing.GroceryUiTest
import com.grocerysync.app.testing.button
import com.grocerysync.app.testing.gotIt
import com.grocerysync.app.testing.tab
import com.grocerysync.app.testing.toBuyRowWithAmount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Shopping on To buy: "Got it" takes an item off (a quantity keeps its unit), Undo puts it back. */
@RunWith(AndroidJUnit4::class)
class ToBuyTest : GroceryUiTest() {

    @Test
    fun gotItAndUndo() {
        fake.seed("Milk", quantity("2", Units.L))
        fake.seed("Bread", yesNo(true))
        fake.seed("Coffee", yesNo(false))
        launchConnected()

        // To buy is the first page: the needed items, a quantity with its amount and unit.
        node(tab("To buy", 2)).assertIsSelected()
        waitForNode(toBuyRowWithAmount("Milk", "2 L"))
        waitForNode(gotIt("Bread"))
        waitForNoNode(gotIt("Coffee"))

        node(gotIt("Milk")).performClick()
        waitForNoNode(gotIt("Milk"))
        waitForNode(hasText("Got Milk"))
        waitForNode(tab("To buy", 1))
        // The snackbar (and its Undo) stays up while the change goes out.
        withUiClockStopped {
            waitFor("Milk at 0 L on the sheet") { (fake.item("Milk")?.need as? Need.Quantity)?.amountIs("0") == true }
        }
        val gotIt = fake.changesFromApp("Milk").last()
        assertEquals("quantity", gotIt.type)
        assertTrue("amount sent: ${gotIt.value}", gotIt.amountIs("0"))
        assertEquals(Units.L, gotIt.unit)

        node(button("Undo")).performClick()
        waitForNode(toBuyRowWithAmount("Milk", "2 L"))
        waitForNode(tab("To buy", 2))
        waitFor("Milk back at 2 L on the sheet") { (fake.item("Milk")?.need as? Need.Quantity)?.amountIs("2") == true }
        assertEquals(Units.L, (fake.item("Milk")!!.need as Need.Quantity).unit)

        // A yes / no item is just not needed any more.
        node(gotIt("Bread")).performClick()
        waitForNoNode(gotIt("Bread"))
        waitFor("Bread not needed on the sheet") { fake.item("Bread")?.need == Need.YesNo(false) }
    }
}
