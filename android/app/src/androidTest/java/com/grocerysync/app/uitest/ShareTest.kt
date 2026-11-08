package com.grocerysync.app.uitest

import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.grocerysync.app.MainActivity
import com.grocerysync.app.data.ItemKeys
import com.grocerysync.app.data.ShopSection
import com.grocerysync.app.data.Units
import com.grocerysync.app.testing.AppState
import com.grocerysync.app.testing.FakeSheetServer.Companion.quantity
import com.grocerysync.app.testing.FakeSheetServer.Companion.yesNo
import com.grocerysync.app.testing.GroceryUiTest
import com.grocerysync.app.testing.ShareSheetCatcher
import com.grocerysync.app.testing.anySectionHeader
import com.grocerysync.app.testing.gotIt
import com.grocerysync.app.testing.searchField
import com.grocerysync.app.testing.sectionHeader
import com.grocerysync.app.testing.tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Share in the app bar (1.4): the To buy list as text, through Android's share sheet, which leaves this app out
 * (1.5). The sheet itself is another app's screen, so [ShareSheetCatcher] keeps it from opening and the test reads
 * the text it was given. How the text
 * is built is tested on the JVM too (ShareListTest, ShareRoundTripTest).
 */
@RunWith(AndroidJUnit4::class)
class ShareTest : GroceryUiTest() {

    @Test
    fun sharesEveryNeededItemUnderItsShopSectionWhateverTheSearchShows() {
        fake.seed("Onion", quantity("1", Units.KG))
        fake.seed("Milk", quantity("1.5", Units.L))
        fake.seed("Dahi", yesNo(true))
        fake.seed("Atta", quantity("10", Units.KG))
        fake.seed("Gadget", yesNo(true))
        fake.seed("Rice", yesNo(false))
        // This phone keeps Gadget with the cleaning things (the Edit dialog's Shop section): Share does too.
        AppState.container.shopSections.setSection(ItemKeys.normalize("Gadget"), ShopSection.CLEANING_HOUSEHOLD)
        launchConnected()
        waitForNode(sectionHeader("Cleaning & household", 1))

        // A search that shows only Milk: Share still sends every needed item, and Rice, not needed, is left out.
        searchButton().performClick()
        waitForNode(searchField).performTextInput("milk")
        waitForNoNode(gotIt("Dahi"))

        ShareSheetCatcher().use { shareSheet ->
            shareButton().performClick()
            waitFor("the share sheet") { shareSheet.count == 1 }
            val shared = shareSheet.lastShared()
            // The sheet never offers this app itself (it takes shared lists: Paste a list).
            assertEquals(
                listOf(ComponentName(InstrumentationRegistry.getInstrumentation().targetContext, MainActivity::class.java)),
                shareSheet.lastExcluded(),
            )
            assertEquals(Intent.ACTION_SEND, shared.action)
            assertEquals("text/plain", shared.type)
            assertEquals(
                lines(
                    "Grocery list (5 items)",
                    "",
                    "*Sabzi & fruit*",
                    "• Onion - 1 kg",
                    "",
                    "*Dairy, eggs & bread*",
                    "• Dahi",
                    "• Milk - 1.5 L",
                    "",
                    "*Atta, rice & dal*",
                    "• Atta - 10 kg",
                    "",
                    "*Cleaning & household*",
                    "• Gadget",
                ),
                shared.getStringExtra(Intent.EXTRA_TEXT),
            )
        }
        // The search is as it was, and sharing changed nothing on the list.
        node(searchField).assertExists()
        waitForNode(gotIt("Milk"))
        assertTrue(fake.sentChanges().isEmpty())
    }

    @Test
    fun sharesAToZWhenGroupingIsOff() {
        fake.seed("Milk", quantity("1.5", Units.L))
        fake.seed("Dahi", yesNo(true))
        fake.seed("Atta", quantity("10", Units.KG))
        fake.seed("Gadget", yesNo(true))
        launchConnected()

        chooseFromMenu("Group by shop section")
        waitForNoNode(anySectionHeader)

        ShareSheetCatcher().use { shareSheet ->
            shareButton().performClick()
            waitFor("the share sheet") { shareSheet.count == 1 }
            assertEquals(
                lines("Grocery list (4 items)", "", "• Atta - 10 kg", "• Dahi", "• Gadget", "• Milk - 1.5 L"),
                shareSheet.lastShared().getStringExtra(Intent.EXTRA_TEXT),
            )
        }
    }

    @Test
    fun nothingToBuySaysSoInsteadOfOpeningTheShareSheet() {
        fake.seed("Coffee", yesNo(false))
        launchConnected()
        // To buy's empty state says it already; the snackbar says it a second time.
        waitForNode(tab("To buy", 0))
        waitForNode(hasText(NOTHING_TO_BUY))

        ShareSheetCatcher().use { shareSheet ->
            shareButton().performClick()
            waitFor("the snackbar \"$NOTHING_TO_BUY\"") { count(hasText(NOTHING_TO_BUY)) == 2 }
            assertEquals(0, shareSheet.count)
        }
    }

    private fun lines(vararg lines: String) = lines.joinToString("\n")

    private companion object {
        const val NOTHING_TO_BUY = "Nothing to buy right now"
    }
}
