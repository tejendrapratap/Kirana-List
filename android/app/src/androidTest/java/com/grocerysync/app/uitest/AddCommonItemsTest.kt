package com.grocerysync.app.uitest

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grocerysync.app.data.Units
import com.grocerysync.app.testing.FakeSheetServer.Companion.HOME
import com.grocerysync.app.testing.FakeSheetServer.Companion.yesNo
import com.grocerysync.app.testing.FakeSheetServer.Need
import com.grocerysync.app.testing.GroceryUiTest
import com.grocerysync.app.testing.button
import com.grocerysync.app.testing.choice
import com.grocerysync.app.testing.itemRow
import com.grocerysync.app.testing.needSwitch
import com.grocerysync.app.testing.tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** "Add common items" from the empty list: tick items of a built-in set and add them to All items, not needed. */
@RunWith(AndroidJUnit4::class)
class AddCommonItemsTest : GroceryUiTest() {

    /** A row of the dialog: the whole row is its checkbox. */
    private fun starterRow(name: String): SemanticsMatcher = isToggleable() and hasText(name)

    @Test
    fun addsTheTickedItemsOfASetNotNeeded() {
        launchConnected()

        waitForNode(button("Add common items")).performClick()
        // Everyday first, everything ticked.
        waitForNode(starterRow("Onion")).assertIsOn()
        node(choice("Vrat / fasting")).performClick()
        waitForNode(starterRow("Potato")).assertIsOn()

        node(button("Select none")).performClick()
        waitForNode(button("Add items")).assertIsNotEnabled()
        node(starterRow("Potato")).assertIsOff().performClick()
        node(starterRow("Hari mirch")).performClick()
        node(button("Add 2 items")).assertIsEnabled().performClick()

        waitForNode(hasText("Added 2 items to All items"))
        waitForNode(tab("All items", 2))
        waitForNode(tab("To buy", 0))
        waitFor("both on the sheet") { fake.item("Potato") != null && fake.item("Hari mirch") != null }
        val potato = fake.changesFromApp("Potato").last()
        assertEquals("quantity", potato.type)
        assertTrue("amount sent: ${potato.value}", potato.amountIs("0"))
        assertEquals(Units.KG, potato.unit)
        val chilli = fake.changesFromApp("Hari mirch").last()
        assertEquals("boolean", chilli.type)
        assertEquals(false, chilli.value)
        // Nothing else of the set was sent.
        assertEquals(setOf("Potato", "Hari mirch"), fake.sentChanges().map { it.name }.toSet())

        showAllItems()
        waitForNode(itemRow("Potato"))
        node(needSwitch("Hari mirch")).assertIsOff()
    }

    @Test
    fun notOfferedBeforeThePhoneIsConnected() {
        launchApp()
        // Until it has the family's list, common items could replace items of the same name on the sheet.
        waitForNode(hasText("Nothing to buy right now"))
        assertNoNode(button("Add common items"))
    }

    @Test
    fun anItemOnTheListUnderAnotherNameCantBeTicked() {
        launchConnected()

        waitForNode(button("Add common items")).performClick()
        waitForNode(choice("Diwali puja")).performClick()
        waitForNode(starterRow("Nariyal")).assertIsOn()

        // The other phone adds it as "Coconut" while the dialog is open.
        assertEquals("applied", fake.upsertAs(HOME, "Coconut", yesNo(true)))
        waitForNode(starterRow("Nariyal") and hasText("On the list as Coconut"), SYNC_TIMEOUT_MS)
            .assertIsOff()
            .assertIsNotEnabled()

        node(button("Select none")).performClick()
        node(starterRow("Ghee")).performClick()
        node(button("Add 1 item")).performClick()

        waitForNode(hasText("Added 1 item to All items"))
        waitFor("Ghee on the sheet") { fake.item("Ghee") != null }
        val ghee = fake.item("Ghee")!!.need as Need.Quantity
        assertTrue(ghee.amountIs("0"))
        assertEquals(Units.L, ghee.unit)
        assertNull(fake.item("Nariyal"))
        assertTrue(fake.changesFromApp("Coconut").isEmpty())
    }
}
