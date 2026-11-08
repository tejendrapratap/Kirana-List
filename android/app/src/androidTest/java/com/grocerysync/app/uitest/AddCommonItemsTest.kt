package com.grocerysync.app.uitest

import android.os.Build
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.grocerysync.app.data.Units
import com.grocerysync.app.testing.DialogWindowState
import com.grocerysync.app.testing.FakeSheetServer.Companion.HOME
import com.grocerysync.app.testing.FakeSheetServer.Companion.quantity
import com.grocerysync.app.testing.FakeSheetServer.Companion.yesNo
import com.grocerysync.app.testing.FakeSheetServer.Need
import com.grocerysync.app.testing.GroceryUiTest
import com.grocerysync.app.testing.button
import com.grocerysync.app.testing.choice
import com.grocerysync.app.testing.darkTheme
import com.grocerysync.app.testing.itemRow
import com.grocerysync.app.testing.needSwitch
import com.grocerysync.app.testing.openDialogWindow
import com.grocerysync.app.testing.tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Add common items", from the empty list or the ⋮ menu: tick items of a built-in set and add them to All items, not
 * needed; Undo takes them back.
 */
@RunWith(AndroidJUnit4::class)
class AddCommonItemsTest : GroceryUiTest() {

    /** A row of the dialog: the whole row is its checkbox. */
    private fun starterRow(name: String): SemanticsMatcher = isToggleable() and hasText(name)

    /** The dialog's list of rows, which scrolls (only the rows on screen exist). */
    private val starterList: SemanticsMatcher = hasScrollToIndexAction() and hasAnyAncestor(isDialog())

    @Test
    fun addsTheTickedItemsOfASetNotNeeded() {
        launchConnected()

        waitForNode(button("Add common items")).performClick()
        // Everyday first, everything ticked but meat, fish and eggs.
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
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q) // WindowInspector
    fun theDialogCoversTheWholeScreenWithReadableSystemBars() {
        launchConnected()

        waitForNode(button("Add common items")).performClick()
        waitForNode(starterRow("Onion"))
        // Edge to edge (1.5): the green app bar under the status bar (light icons in both themes) and the bottom bar
        // under the navigation bar, whose buttons follow the theme. In 1.4 the dialog stopped above the gesture bar,
        // and the dimmed list behind showed through there.
        val expected = DialogWindowState(lightStatusBars = false, lightNavigationBars = !darkTheme, edgeToEdge = true)
        waitFor("$expected") { openDialogWindow() == expected }
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

    @Test
    fun itemsOnTheListUnderOtherNamesAreShownAndUndoTakesTheAddedOnesBack() {
        // The family writes Rice as Chawal and Oil as Tel.
        fake.seed("Chawal", yesNo(false))
        fake.seed("Tel", quantity("1", Units.L))
        launchConnected()

        chooseFromMenu("Add common items")
        waitForNode(choice("Monthly kirana")).performClick()
        // The set starts with Atta, rice & dal.
        waitForNode(starterRow("Rice") and hasText("On the list as Chawal")).assertIsOff().assertIsNotEnabled()
        node(starterRow("Atta")).assertIsOn()

        node(button("Select none")).performClick()
        waitForNode(button("Add items")).assertIsNotEnabled()
        node(starterRow("Atta")).assertIsOff().performClick()
        // Oil & ghee is further down.
        node(starterList).performScrollToNode(starterRow("Oil"))
        node(starterRow("Oil") and hasText("On the list as Tel")).assertIsOff().assertIsNotEnabled()
        node(starterList).performScrollToNode(starterRow("Ghee"))
        node(starterRow("Ghee")).assertIsOff().performClick()
        node(button("Add 2 items")).assertIsEnabled().performClick()

        // Both are on All items, not needed, and on the sheet; Undo (in the snackbar) is kept up meanwhile.
        waitForNode(hasText("Added 2 items to All items"))
        withUiClockStopped {
            waitFor("both on the sheet") { fake.item("Atta") != null && fake.item("Ghee") != null }
        }
        val atta = fake.item("Atta")!!.need as Need.Quantity
        assertTrue(atta.amountIs("0"))
        assertEquals(Units.KG, atta.unit)
        val ghee = fake.item("Ghee")!!.need as Need.Quantity
        assertTrue(ghee.amountIs("0"))
        assertEquals(Units.L, ghee.unit)
        waitForNode(tab("All items", 4))
        node(tab("To buy", 1)).assertExists()

        node(button("Undo")).performClick()
        waitForNode(tab("All items", 2))
        waitFor("both deleted on the sheet") { fake.item("Atta") == null && fake.item("Ghee") == null }
        assertEquals("delete", fake.changesFromApp("Atta").last().op)
        assertEquals("delete", fake.changesFromApp("Ghee").last().op)
        // The family's own items were never touched.
        assertTrue(fake.changesFromApp("Chawal").isEmpty())
        assertTrue(fake.changesFromApp("Tel").isEmpty())
        assertNull(fake.item("Rice"))
        assertNull(fake.item("Oil"))
    }
}
