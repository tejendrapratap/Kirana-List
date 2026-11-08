package com.grocerysync.app.uitest

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grocerysync.app.data.Units
import com.grocerysync.app.testing.FakeSheetServer.Companion.quantity
import com.grocerysync.app.testing.FakeSheetServer.Companion.yesNo
import com.grocerysync.app.testing.FakeSheetServer.Need
import com.grocerysync.app.testing.GroceryUiTest
import com.grocerysync.app.testing.button
import com.grocerysync.app.testing.gotIt
import com.grocerysync.app.testing.textField
import com.grocerysync.app.testing.toBuyRowWithAmount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Many items at once (1.4): ⋮ > Paste a list, and a list shared into the app from another one (Share > Grocery
 * Sync). Nothing changes before the preview's button is tapped. The parser and the preview's rules are tested on the
 * JVM (PasteParserTest, PastePlanTest, ShareRoundTripTest).
 */
@RunWith(AndroidJUnit4::class)
class PasteListTest : GroceryUiTest() {

    @Test
    fun pastedLinesAddAndMarkItemsAfterThePreview() {
        fake.seed("Dahi", yesNo(false))
        launchConnected()

        chooseFromMenu("Paste a list")
        waitForNode(textField("List")).performTextInput("Rice 2 kg\nCurd")
        node(button("Next")).performClick()

        // What each line does: a new item, and another name of an item on the list.
        waitForNode(hasText("Add Rice - 2 kg"))
        waitForNode(hasText("Mark Dahi needed") and hasText("Curd → Dahi"))
        assertTrue(fake.sentChanges().isEmpty())
        node(button("Apply 2 changes")).performClick()

        waitForNode(hasText("Added 1 item, updated 1"))
        waitForNode(toBuyRowWithAmount("Rice", "2 kg"))
        waitForNode(gotIt("Dahi"))
        waitFor("both on the sheet") { fake.item("Rice") != null && fake.item("Dahi")?.need == Need.YesNo(true) }
        val rice = fake.item("Rice")!!.need as Need.Quantity
        assertTrue(rice.amountIs("2"))
        assertEquals(Units.KG, rice.unit)
        // Curd is another name of Dahi, so no item of that name.
        assertNull(fake.item("Curd"))
    }

    @Test
    fun aListSharedIntoTheAppOpensItsPreview() {
        fake.seed("Oil", quantity("1", Units.L))
        // As Share in the app writes it (on the other phone).
        launchConnected(sharedTextIntent("Grocery list (2 items)\n\n*Oil & ghee*\n• Ghee - 0.5 L\n• Oil - 1 L"))

        waitForNode(hasText("Add Ghee - 0.5 L"))
        waitForNode(hasText("Already needed: Oil"))
        assertTrue(fake.sentChanges().isEmpty())
        node(button("Add 1 item")).performClick()

        waitForNode(hasText("Added 1 item"))
        waitFor("Ghee on the sheet") { fake.item("Ghee") != null }
        val ghee = fake.item("Ghee")!!.need as Need.Quantity
        assertTrue(ghee.amountIs("0.5"))
        assertEquals(Units.L, ghee.unit)
    }

    @Test
    fun aListSharedBeforeSetupIsNotRead() {
        launchApp(sharedTextIntent("Rice 2 kg"))

        waitForNode(hasText("Set up Grocery Sync first, then share the list again."))
        assertNoNode(hasText("Add Rice - 2 kg"))
    }

    @Test
    fun pastingBeforeSetupSaysToConnectFirst() {
        launchApp()
        waitForNode(hasText("Not connected"))

        // Until it has the family's list, every line would be new and could replace their item of that name.
        chooseFromMenu("Paste a list")
        waitForNode(hasText("Connect to your sheet in Settings first, so items already on it aren't replaced."))
        assertNoNode(textField("List"))
    }
}
