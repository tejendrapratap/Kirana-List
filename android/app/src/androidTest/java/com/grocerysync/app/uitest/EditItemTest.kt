package com.grocerysync.app.uitest

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grocerysync.app.data.Units
import com.grocerysync.app.testing.FakeSheetServer.Companion.quantity
import com.grocerysync.app.testing.FakeSheetServer.Companion.yesNo
import com.grocerysync.app.testing.FakeSheetServer.Need
import com.grocerysync.app.testing.GroceryUiTest
import com.grocerysync.app.testing.amountField
import com.grocerysync.app.testing.button
import com.grocerysync.app.testing.itemRow
import com.grocerysync.app.testing.nameField
import com.grocerysync.app.testing.stepperAmount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Tapping an item: rename it and change its amount; delete it, then Undo. */
@RunWith(AndroidJUnit4::class)
class EditItemTest : GroceryUiTest() {

    @Test
    fun editsNameAndAmount() {
        fake.seed("Rice", quantity("1", Units.KG))
        val addedAt = fake.item("Rice")!!.addedAt
        launchConnected()
        showAllItems()

        node(itemRow("Rice")).performClick()
        waitForNode(hasText("Edit item"))
        node(nameField).performTextReplacement("Basmati rice")
        node(amountField).performTextReplacement("2.5")
        node(button("Save")).performClick()

        waitForNode(stepperAmount("Basmati rice", "2.5 kg"))
        waitForNoNode(itemRow("Rice"))
        waitFor("the rename on the sheet") { fake.item("Basmati rice") != null && fake.item("Rice") == null }

        // A rename is a delete of the old name and the new name with the new amount, keeping when it was added.
        assertEquals("delete", fake.changesFromApp("Rice").last().op)
        val renamed = fake.changesFromApp("Basmati rice").last()
        assertEquals("upsert", renamed.op)
        assertEquals("quantity", renamed.type)
        assertTrue("amount sent: ${renamed.value}", renamed.amountIs("2.5"))
        assertEquals(Units.KG, renamed.unit)
        assertEquals(addedAt, renamed.addedAt)
        val stored = fake.item("Basmati rice")!!
        assertEquals(addedAt, stored.addedAt)
        assertTrue((stored.need as Need.Quantity).amountIs("2.5"))
        // So it reads as changed, not as just added.
        waitForNode(itemRow("Basmati rice", "Updated by $ME"))
    }

    @Test
    fun deletesAndUndoesTheDelete() {
        fake.seed("Jam", yesNo(false))
        fake.seed("Tea", yesNo(false))
        val addedAt = fake.item("Jam")!!.addedAt
        launchConnected()
        showAllItems()

        node(itemRow("Jam")).performClick()
        waitForNode(hasText("Edit item"))
        node(button("Delete")).performClick()
        // The confirmation is a second dialog with its own Delete button.
        val question = hasText("Delete \"Jam\"?")
        waitForNode(question)
        node(button("Delete") and hasAnyAncestor(hasAnyDescendant(question))).performClick()

        waitForNoNode(itemRow("Jam"))
        waitForNode(hasText("Deleted Jam"))
        withUiClockStopped {
            waitFor("Jam deleted on the sheet") { fake.item("Jam") == null }
        }
        assertEquals("delete", fake.changesFromApp("Jam").last().op)
        assertEquals(ME, fake.changesFromApp("Jam").last().device)

        node(button("Undo")).performClick()
        waitForNode(itemRow("Jam"))
        waitFor("Jam back on the sheet") { fake.item("Jam") != null }
        val restored = fake.changesFromApp("Jam").last()
        assertEquals("upsert", restored.op)
        assertEquals(false, restored.value)
        // Undo brings back the item as it was, including when it was added: it reads "Updated by".
        assertEquals(addedAt, restored.addedAt)
        assertEquals(addedAt, fake.item("Jam")!!.addedAt)
        assertEquals(Need.YesNo(false), fake.item("Jam")!!.need)
        waitForNode(itemRow("Jam", "Updated by $ME"))
    }
}
