package com.grocerysync.app.uitest

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grocerysync.app.data.Units
import com.grocerysync.app.testing.FakeSheetServer
import com.grocerysync.app.testing.FakeSheetServer.Companion.quantity
import com.grocerysync.app.testing.FakeSheetServer.Companion.yesNo
import com.grocerysync.app.testing.FakeSheetServer.Need
import com.grocerysync.app.testing.GroceryUiTest
import com.grocerysync.app.testing.gotIt
import com.grocerysync.app.testing.needSwitch
import com.grocerysync.app.testing.stepButton
import com.grocerysync.app.testing.stepperAmount
import com.grocerysync.app.testing.tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The controls on All items rows: the Need switch of a yes / no item, - and + of a quantity. */
@RunWith(AndroidJUnit4::class)
class AllItemsControlsTest : GroceryUiTest() {

    private fun FakeSheetServer.quantityOf(name: String): Need.Quantity? = item(name)?.need as? Need.Quantity

    @Test
    fun needSwitchSendsTheChange() {
        fake.seed("Bread", yesNo(false))
        launchConnected()
        showAllItems()

        node(needSwitch("Bread")).assertIsOff().performClick()
        waitForNode(needSwitch("Bread") and isOn())
        waitFor("Bread needed on the sheet") { fake.item("Bread")?.need == Need.YesNo(true) }
        val sent = fake.changesFromApp("Bread").last()
        assertEquals("upsert", sent.op)
        assertEquals("boolean", sent.type)
        assertEquals(true, sent.value)
        assertEquals(ME, fake.item("Bread")!!.updatedBy)
        waitForNode(tab("To buy", 1))

        node(needSwitch("Bread")).performClick()
        waitForNode(needSwitch("Bread") and isOff())
        waitFor("Bread not needed on the sheet") { fake.item("Bread")?.need == Need.YesNo(false) }
        waitForNode(tab("To buy", 0))
    }

    @Test
    fun stepperChangesTheAmountByTheUnitsStep() {
        fake.seed("Rice", quantity("1", Units.KG))
        fake.seed("Eggs", quantity("6", Units.PCS))
        launchConnected()
        showAllItems()
        waitForNode(stepperAmount("Rice", "1 kg"))

        // kg steps by 0.5.
        node(stepButton("Rice", "0.5 kg", more = true)).performClick()
        waitForNode(stepperAmount("Rice", "1.5 kg"))
        waitFor("1.5 kg on the sheet") { fake.quantityOf("Rice")?.amountIs("1.5") == true }
        val up = fake.changesFromApp("Rice").last()
        assertEquals("quantity", up.type)
        assertTrue("amount sent: ${up.value}", up.amountIs("1.5"))
        assertEquals(Units.KG, up.unit)

        // Down to 0 (not needed); - then stops there.
        repeat(3) { node(stepButton("Rice", "0.5 kg", more = false)).performClick() }
        waitForNode(stepperAmount("Rice", "0 kg"))
        node(stepButton("Rice", "0.5 kg", more = false)).assertIsNotEnabled()
        waitFor("0 kg on the sheet") { fake.quantityOf("Rice")?.amountIs("0") == true }
        assertEquals(Units.KG, fake.quantityOf("Rice")!!.unit)

        // pcs steps by 1.
        node(stepButton("Eggs", "1 pcs", more = true)).assertIsEnabled().performClick()
        waitForNode(stepperAmount("Eggs", "7 pcs"))
        waitFor("7 eggs on the sheet") { fake.quantityOf("Eggs")?.amountIs("7") == true }
        // Rice is not needed any more; Eggs still is.
        node(tab("To buy", 1)).assertExists()
        showToBuyAndCheck()
    }

    private fun showToBuyAndCheck() {
        node(tab("To buy")).performClick()
        waitForNode(gotIt("Eggs"))
        waitForNoNode(gotIt("Rice"))
    }
}
