package com.grocerysync.app.uitest

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hindi / English names (1.4): search finds an item by another name of it in the built-in dictionary, and adding
 * another name of an item on the list warns ("Similar") without blocking Save.
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
}
