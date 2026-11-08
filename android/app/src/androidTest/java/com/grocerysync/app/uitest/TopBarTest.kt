package com.grocerysync.app.uitest

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grocerysync.app.data.Units
import com.grocerysync.app.testing.AppState
import com.grocerysync.app.testing.FakeSheetServer.Companion.quantity
import com.grocerysync.app.testing.FakeSheetServer.Companion.yesNo
import com.grocerysync.app.testing.GroceryUiTest
import com.grocerysync.app.testing.anySectionHeader
import com.grocerysync.app.testing.button
import com.grocerysync.app.testing.gotIt
import com.grocerysync.app.testing.sectionHeader
import com.grocerysync.app.testing.textField
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The list's app bar: the ⋮ menu (More options) and what its choices open. Share is in ShareTest, "Paste a list" in
 * PasteListTest.
 */
@RunWith(AndroidJUnit4::class)
class TopBarTest : GroceryUiTest() {

    @Test
    fun theMenuHoldsTheListChoicesAndSettings() {
        // Not an empty list, which has an "Add common items" button of its own.
        fake.seed("Coffee", yesNo(false))
        launchConnected()
        moreOptionsButton().performClick()
        waitForNode(button("Add common items")).assertIsDisplayed()
        node(button("Paste a list")).assertIsDisplayed()
        // A tick, on unless this phone turned it off; TalkBack reads it as a checkbox.
        node(button("Group by shop section")).assertIsDisplayed().assert(isOn())

        node(button("Settings")).performClick()
        waitForNode(textField("Secret token"))
    }

    @Test
    fun addCommonItemsInTheMenuOpensTheSets() {
        fake.seed("Coffee", yesNo(false))
        launchConnected()

        chooseFromMenu("Add common items")
        // Everyday first, everything ticked but meat, fish and eggs (adding them is in AddCommonItemsTest).
        val onion = isToggleable() and hasText("Onion")
        waitForNode(onion).assertIsOn()
        node(hasContentDescription("Close") and hasClickAction()).performClick()
        waitForNoNode(onion)
    }

    @Test
    fun groupByShopSectionInTheMenuTurnsTheSectionsOffAndOn() {
        fake.seed("Milk", quantity("2", Units.L))
        fake.seed("Gadget", yesNo(true))
        launchConnected()
        waitForNode(sectionHeader("Dairy, eggs & bread", 1))

        // Off: To buy A to Z, without headers, on this phone only.
        chooseFromMenu("Group by shop section")
        waitForNoNode(anySectionHeader)
        waitForNode(gotIt("Gadget"))
        assertFalse(AppState.container.shopSections.current().groupBySection)

        // The tick shows it, and turns it back on.
        moreOptionsButton().performClick()
        waitForNode(button("Group by shop section")).assert(isOff()).performClick()
        waitForNode(sectionHeader("Other", 1))
        assertTrue(AppState.container.shopSections.current().groupBySection)
        assertTrue(fake.sentChanges().isEmpty())
    }
}
