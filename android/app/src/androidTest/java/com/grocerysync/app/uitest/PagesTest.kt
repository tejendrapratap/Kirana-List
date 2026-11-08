package com.grocerysync.app.uitest

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grocerysync.app.testing.FakeSheetServer.Companion.yesNo
import com.grocerysync.app.testing.GroceryUiTest
import com.grocerysync.app.testing.gotIt
import com.grocerysync.app.testing.needSwitch
import com.grocerysync.app.testing.pager
import com.grocerysync.app.testing.tab
import org.junit.Test
import org.junit.runner.RunWith

/** To buy and All items are two pages: swipe between them or tap the row above; the row follows the page. */
@RunWith(AndroidJUnit4::class)
class PagesTest : GroceryUiTest() {

    @Test
    fun swipingSwitchesPagesAndTheRowFollows() {
        fake.seed("Bread", yesNo(true))
        fake.seed("Coffee", yesNo(false))
        launchConnected()
        node(tab("To buy", 1)).assertIsSelected()
        node(tab("All items", 2)).assertIsNotSelected()
        waitForNode(gotIt("Bread"))

        node(pager).performTouchInput { swipeLeft() }
        waitForNode(tab("All items") and isSelected())
        node(tab("To buy")).assertIsNotSelected()
        waitForNode(needSwitch("Coffee"))
        waitForNoNode(gotIt("Bread"))

        node(pager).performTouchInput { swipeRight() }
        waitForNode(tab("To buy") and isSelected())
        node(tab("All items")).assertIsNotSelected()
        waitForNode(gotIt("Bread"))
        waitForNoNode(needSwitch("Coffee"))

        // Tapping the row switches too.
        node(tab("All items")).performClick()
        waitForNode(tab("All items") and isSelected())
        waitForNode(needSwitch("Coffee"))
    }
}
