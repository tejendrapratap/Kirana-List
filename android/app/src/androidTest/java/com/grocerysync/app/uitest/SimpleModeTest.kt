package com.grocerysync.app.uitest

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grocerysync.app.data.Units
import com.grocerysync.app.testing.AppState
import com.grocerysync.app.testing.FakeSheetServer.Companion.quantity
import com.grocerysync.app.testing.FakeSheetServer.Companion.yesNo
import com.grocerysync.app.testing.FakeSheetServer.Need
import com.grocerysync.app.testing.GroceryUiTest
import com.grocerysync.app.testing.button
import com.grocerysync.app.testing.choice
import com.grocerysync.app.testing.gotIt
import com.grocerysync.app.testing.hasTextMatching
import com.grocerysync.app.testing.itemRow
import com.grocerysync.app.testing.nameField
import com.grocerysync.app.testing.pager
import com.grocerysync.app.testing.searchField
import com.grocerysync.app.testing.tab
import com.grocerysync.app.testing.toBuyRowWithAmount
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Simple mode (Settings > This phone): the list screen shows only To buy, with a big labelled "Got it" button on each
 * row, one big Add item button, Share and a ⋮ menu with Settings only; Undo stays until it is tapped or closed, and
 * Ticked today is open. Turning it off (or on) shows on the list at once. Simple mode is turned on here through the
 * app's PhoneSettingsStore, as Settings' switch does, so these tests don't depend on that screen's words.
 * SimpleModeLargeTextTest checks the screen at 200% text.
 */
@RunWith(AndroidJUnit4::class)
class SimpleModeTest : GroceryUiTest() {

    /** Simple mode's "Got it" button of [name]: labelled, and read by TalkBack with the name ("Got it: Rice"). */
    private fun gotItButton(name: String): SemanticsMatcher = gotIt(name) and hasText("Got it") and hasClickAction()

    /** Either page's tab of the usual screen: "To buy (2)", "All items (3)". */
    private val anyTab: SemanticsMatcher = isSelectable() and hasTextMatching(Regex("(To buy|All items) \\(\\d+\\)"))

    /** The X that closes a snackbar in Simple mode. */
    private val closeSnackbar: SemanticsMatcher = hasContentDescription("Close") and hasClickAction()

    /** Ticked today's header, a heading that TalkBack reads with its count. */
    private fun tickedToday(count: Int): SemanticsMatcher =
        isHeading() and hasContentDescription("Ticked today, $count ${if (count == 1) "item" else "items"}")

    private fun state(text: String): SemanticsMatcher = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text)

    /** Turns Simple mode on, connects the app to [fake] as [ME] and opens it, until the first sync is done. */
    private fun launchInSimpleMode() {
        AppState.container.phoneSettings.setSimpleMode(true)
        val before = System.currentTimeMillis()
        AppState.connect(fake.url, fake.token, ME)
        launchApp()
        waitFor("the first sync with the fake") { AppState.syncedSince(before) }
    }

    @Test
    fun onlyToBuyWithBigButtons() {
        fake.seed("Glue", quantity("2", Units.PCS))
        fake.seed("Stapler", yesNo(true))
        fake.seed("Coffee", yesNo(false))
        launchInSimpleMode()

        waitForNode(gotItButton("Glue")).assertIsDisplayed()
        node(gotItButton("Stapler")).assertIsDisplayed()
        // The amount stays on the row.
        node(toBuyRowWithAmount("Glue", "2 pcs")).assertIsDisplayed()
        // Taller rows.
        assertTrue(node(itemRow("Stapler")).getUnclippedBoundsInRoot().height >= 64.dp)
        // No pages: no tabs, no pager to swipe, and nothing that isn't needed.
        assertNoNode(anyTab)
        assertNoNode(pager)
        assertNoNode(itemRow("Coffee"))
        // One big Add item button, wider than tall (the label is next to the +).
        val add = addButton().assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("Add item is ${add.width} x ${add.height}", add.height >= 64.dp && add.width > add.height)
        // The app bar: Share and the ⋮ menu, no Search.
        assertNoNode(hasContentDescription("Search items"))
        shareButton().assertIsDisplayed()
    }

    @Test
    fun theMenuHoldsOnlySettings() {
        fake.seed("Stapler", yesNo(true))
        launchInSimpleMode()
        waitForNode(gotItButton("Stapler"))

        moreOptionsButton().performClick()
        waitForNode(button("Settings")).assertIsDisplayed()
        assertNoNode(button("Add common items"))
        assertNoNode(button("Paste a list"))
        assertNoNode(hasText("Group by shop section"))

        node(button("Settings")).performClick()
        waitForNode(isHeading() and hasText("This phone"))
    }

    @Test
    fun gotItTicksOffAndItsUndoWaitsToBeTapped() {
        fake.seed("Stapler", yesNo(true))
        launchInSimpleMode()

        waitForNode(gotItButton("Stapler")).performClick()
        waitForNode(hasText("Got Stapler"))
        waitFor("Stapler not needed on the sheet") { fake.item("Stapler")?.need == Need.YesNo(false) }
        // Long after the usual snackbar (4 s) and even a long one (10 s) would have gone, Undo is still there.
        compose.mainClock.advanceTimeBy(15_000L)
        node(hasText("Got Stapler")).assertIsDisplayed()
        node(closeSnackbar).assertIsDisplayed()
        // Ticked today is open from the start in Simple mode.
        waitForNode(tickedToday(1) and state("Expanded"))
        waitForNode(hasContentDescription("Put back Stapler") and hasClickAction())

        node(button("Undo")).performClick()
        waitForNode(gotItButton("Stapler"))
        waitForNoNode(hasText("Got Stapler"))
        waitFor("Stapler needed on the sheet again") { fake.item("Stapler")?.need == Need.YesNo(true) }
    }

    @Test
    fun theXClosesTheUndoAndTheItemStaysTicked() {
        fake.seed("Stapler", yesNo(true))
        fake.seed("Glue", quantity("1", Units.PCS))
        launchInSimpleMode()

        waitForNode(gotItButton("Stapler")).performClick()
        waitForNode(hasText("Got Stapler"))
        node(closeSnackbar).performClick()
        waitForNoNode(hasText("Got Stapler"))
        assertNoNode(gotItButton("Stapler"))
        node(gotItButton("Glue")).assertIsDisplayed()
        waitFor("Stapler not needed on the sheet") { fake.item("Stapler")?.need == Need.YesNo(false) }
    }

    @Test
    fun tappingARowStillOpensItsEditDialog() {
        fake.seed("Stapler", yesNo(true))
        launchInSimpleMode()

        waitForNode(itemRow("Stapler")).performClick()
        waitForNode(nameField)
        // The dialog's own buttons are another screen's; Back closes it like any dialog.
        Espresso.pressBack()
        waitForNoNode(nameField)
        node(gotItButton("Stapler")).assertIsDisplayed()
    }

    @Test
    fun anEmptyToBuyPointsToTheAddItemButton() {
        fake.seed("Coffee", yesNo(false))
        launchInSimpleMode()

        waitForNode(hasText("Everything is ticked off. Tap Add item when you need something."))
        // No "Add common items" here, as in the menu.
        assertNoNode(button("Add common items"))
        addButton().performClick()
        waitForNode(nameField)
    }

    @Test
    fun aNewItemStartsAsNeeded() {
        fake.seed("Coffee", yesNo(false))
        launchInSimpleMode()

        waitForNode(hasText("Everything is ticked off. Tap Add item when you need something."))
        addButton().performClick()
        waitForNode(nameField).performTextInput("Stapler")
        // Only To buy is shown, so a new item starts at "Need to buy? Yes": saved as it is, it stays in sight.
        node(choice("Yes / no")).assertIsSelected()
        node(choice("Yes")).assertIsSelected()
        node(button("Save")).performClick()

        waitForNode(gotItButton("Stapler")).assertIsDisplayed()
        waitFor("Stapler needed on the sheet") { fake.item("Stapler")?.need == Need.YesNo(true) }
    }

    @Test
    fun turningItOffInSettingsBringsThePagesBack() {
        fake.seed("Stapler", yesNo(true))
        fake.seed("Coffee", yesNo(false))
        launchInSimpleMode()
        waitForNode(gotItButton("Stapler"))

        openSettings()
        waitForNode(isHeading() and hasText("This phone"))
        AppState.container.phoneSettings.setSimpleMode(false)
        node(hasContentDescription("Back") and hasClickAction()).performClick()

        // The usual screen again: both pages, Search, and the small checkbox instead of the button.
        waitForNode(tab("To buy", 1))
        node(tab("All items", 2)).assertIsDisplayed()
        searchButton().assertIsDisplayed()
        waitForNode(gotIt("Stapler"))
        assertNoNode(gotItButton("Stapler"))

        // And on again while the list is shown.
        AppState.container.phoneSettings.setSimpleMode(true)
        waitForNode(gotItButton("Stapler"))
        waitForNoNode(anyTab)
    }

    @Test
    fun anOpenSearchClosesWhenSimpleModeIsTurnedOn() {
        fake.seed("Stapler", yesNo(true))
        fake.seed("Glue", quantity("1", Units.PCS))
        launchConnected()

        searchButton().performClick()
        waitForNode(searchField).performTextInput("stap")
        waitForNoNode(itemRow("Glue"))

        AppState.container.phoneSettings.setSimpleMode(true)
        // Simple mode has no search, so its field goes and every item to buy shows again.
        waitForNoNode(searchField)
        waitForNode(gotItButton("Glue"))
        node(gotItButton("Stapler")).assertIsDisplayed()
    }
}
