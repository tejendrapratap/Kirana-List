package com.grocerysync.app.uitest

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grocerysync.app.testing.FakeSheetServer.Companion.yesNo
import com.grocerysync.app.testing.GroceryUiTest
import com.grocerysync.app.testing.button
import com.grocerysync.app.testing.tab
import com.grocerysync.app.testing.textField
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The list's app bar: Share with nothing to buy, and the ⋮ menu (More options). Sharing a list opens Android's
 * share sheet, another app's screen, so only the text it sends is tested (ShareListTest, on the JVM).
 */
@RunWith(AndroidJUnit4::class)
class TopBarTest : GroceryUiTest() {

    @Test
    fun shareWithNothingToBuySaysSo() {
        fake.seed("Coffee", yesNo(false))
        launchConnected()
        // To buy's empty state says it already; the snackbar says it a second time.
        waitForNode(tab("To buy", 0))
        waitForNode(hasText(NOTHING_TO_BUY))

        shareButton().performClick()
        waitFor("the snackbar \"$NOTHING_TO_BUY\"") { count(hasText(NOTHING_TO_BUY)) == 2 }
    }

    @Test
    fun theMenuHoldsTheListChoicesAndSettings() {
        launchConnected()
        moreOptionsButton().performClick()
        waitForNode(button("Add common items")).assertIsDisplayed()
        node(button("Paste a list")).assertIsDisplayed()
        // A tick, on unless this phone turned it off; TalkBack reads it as a checkbox.
        node(button("Group by shop section")).assertIsDisplayed().assert(isOn())

        node(button("Settings")).performClick()
        waitForNode(textField("Secret token"))
    }

    private companion object {
        const val NOTHING_TO_BUY = "Nothing to buy right now"
    }
}
