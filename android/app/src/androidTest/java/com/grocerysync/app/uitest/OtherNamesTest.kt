package com.grocerysync.app.uitest

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grocerysync.app.data.Units
import com.grocerysync.app.testing.FakeSheetServer.Companion.quantity
import com.grocerysync.app.testing.FakeSheetServer.Companion.yesNo
import com.grocerysync.app.testing.GroceryUiTest
import com.grocerysync.app.testing.button
import com.grocerysync.app.testing.gotIt
import com.grocerysync.app.testing.itemRow
import com.grocerysync.app.testing.nameField
import com.grocerysync.app.testing.searchField
import com.grocerysync.app.testing.tab
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hindi / English names (1.4): search on either page finds an item by another name of it in the built-in
 * dictionary, and adding (or renaming an item to) another name of an item on the list warns ("Similar") without
 * blocking Save.
 */
@RunWith(AndroidJUnit4::class)
class OtherNamesTest : GroceryUiTest() {

    @Test
    fun searchFindsAnItemByItsOtherName() {
        fake.seed("Dahi", yesNo(true))
        fake.seed("Onion", quantity("1", Units.KG))
        fake.seed("Cinnamon", yesNo(true))
        launchConnected()

        searchButton().performClick()
        waitForNode(searchField).performTextInput("curd")
        waitForNode(gotIt("Dahi"))
        waitForNoNode(itemRow("Onion"))

        // Devanagari finds it too.
        node(searchField).performTextReplacement("प्याज") // प्याज
        waitForNode(gotIt("Onion"))
        waitForNoNode(itemRow("Dahi"))

        // Only at word starts: "dal" doesn't find Cinnamon (Dalchini), so it offers to add Dal.
        node(searchField).performTextReplacement("dal")
        waitForNode(button("Add \"Dal\" to the list"))
        waitForNoNode(itemRow("Cinnamon"))
    }

    @Test
    fun addingAnotherNameOfAnItemWarnsAndOffersToOpenIt() {
        fake.seed("Dahi", yesNo(false))
        launchConnected()

        addButton().performClick()
        waitForNode(nameField).performTextInput("Curd")
        waitForNode(hasText("Similar: \"Dahi\" is already on the list"))
        node(button("Save")).assertIsEnabled()

        node(button("Open \"Dahi\"")).performClick()
        waitForNode(hasText("Edit item"))
        node(nameField).assert(hasText("Dahi"))
        node(button("Cancel")).performClick()
        waitForNoNode(nameField)

        assertTrue(fake.changesFromApp("Dahi").isEmpty())
        assertTrue(fake.changesFromApp("Curd").isEmpty())
    }

    @Test
    fun theWarningDoesntBlockSaving() {
        fake.seed("Dahi", yesNo(false))
        launchConnected()

        addButton().performClick()
        waitForNode(nameField).performTextInput("Curd")
        waitForNode(hasText("Similar: \"Dahi\" is already on the list"))
        node(button("Save")).assertIsEnabled().performClick()

        waitForNoNode(nameField)
        waitFor("Curd on the sheet") { fake.item("Curd") != null }
        assertTrue(fake.item("Dahi") != null)
    }

    @Test
    fun allItemsFindsAnItemByItsOtherNameToo() {
        fake.seed("Dahi", yesNo(false))
        fake.seed("Kishmish", yesNo(false))
        fake.seed("Milk", quantity("1", Units.L))
        launchConnected()

        // Dahi isn't needed: To buy offers it in All items, which finds it by "curd" too.
        searchButton().performClick()
        waitForNode(searchField).performTextInput("curd")
        waitForNode(hasText("No items to buy match your search"))
        waitForNode(button("Show 1 in All items")).performClick()
        waitForNode(tab("All items") and isSelected())
        waitForNode(itemRow("Dahi"))
        waitForNoNode(itemRow("Milk"))
        waitForNoNode(itemRow("Kishmish"))

        node(searchField).performTextReplacement("raisins")
        waitForNode(itemRow("Kishmish"))
        waitForNoNode(itemRow("Dahi"))
    }

    @Test
    fun renamingToAnotherNameOfAnItemWarnsButSaves() {
        fake.seed("Dahi", yesNo(false))
        fake.seed("Bread", yesNo(false))
        launchConnected()
        showAllItems()

        node(itemRow("Bread")).performClick()
        waitForNode(hasText("Edit item"))
        node(nameField).performTextReplacement("Curd")
        waitForNode(hasText("Similar: \"Dahi\" is already on the list"))
        // Editing offers no Open button (as for a name on the list); Save stays on.
        assertNoNode(button("Open \"Dahi\""))
        node(button("Save")).assertIsEnabled().performClick()

        waitForNoNode(nameField)
        waitForNode(itemRow("Curd"))
        waitFor("the rename on the sheet") { fake.item("Curd") != null && fake.item("Bread") == null }
        assertTrue(fake.item("Dahi") != null)
        assertTrue(fake.changesFromApp("Dahi").isEmpty())
    }
}
