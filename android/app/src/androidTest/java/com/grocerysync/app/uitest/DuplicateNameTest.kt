package com.grocerysync.app.uitest

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grocerysync.app.data.Units
import com.grocerysync.app.testing.FakeSheetServer.Companion.quantity
import com.grocerysync.app.testing.FakeSheetServer.Companion.yesNo
import com.grocerysync.app.testing.GroceryUiTest
import com.grocerysync.app.testing.amountField
import com.grocerysync.app.testing.button
import com.grocerysync.app.testing.itemRow
import com.grocerysync.app.testing.nameField
import com.grocerysync.app.testing.unitPicker
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Names are unique (capitals and spaces don't count): the dialogs block a name another item has. */
@RunWith(AndroidJUnit4::class)
class DuplicateNameTest : GroceryUiTest() {

    @Test
    fun addingANameOnTheListOffersToOpenThatItem() {
        fake.seed("Rice", quantity("1", Units.KG))
        launchConnected()

        addButton().performClick()
        waitForNode(nameField).performTextInput("  rice ")
        waitForNode(hasText("\"Rice\" is already on the list"))
        node(button("Save")).assertIsNotEnabled()

        node(button("Open \"Rice\"")).performClick()
        waitForNode(hasText("Edit item"))
        node(nameField).assert(hasText("Rice"))
        node(amountField).assert(hasText("1"))
        node(unitPicker).assert(hasContentDescription("Unit: kg"))
        node(button("Cancel")).performClick()
        waitForNoNode(nameField)

        assertTrue(fake.changesFromApp("Rice").isEmpty())
    }

    @Test
    fun renamingToAnotherItemsNameIsBlocked() {
        fake.seed("Rice", quantity("1", Units.KG))
        fake.seed("Bread", yesNo(false))
        launchConnected()
        showAllItems()

        node(itemRow("Bread")).performClick()
        waitForNode(hasText("Edit item"))
        node(nameField).performTextReplacement("RICE")
        waitForNode(hasText("Another item is already called \"Rice\""))
        node(button("Save")).assertIsNotEnabled()
        // No Open button when editing; changing only the capitals of its own name is fine.
        waitForNoNode(button("Open \"Rice\""))
        node(nameField).performTextReplacement("BREAD")
        node(button("Save")).assertIsEnabled()
        node(button("Cancel")).performClick()
        waitForNoNode(nameField)

        assertTrue(fake.changesFromApp("Rice").isEmpty())
        assertTrue(fake.changesFromApp("Bread").isEmpty())
    }
}
