package com.grocerysync.app.uitest

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.grocerysync.app.notify.ChangeNotifier
import com.grocerysync.app.testing.GroceryUiTest
import com.grocerysync.app.testing.tab
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tapping the change notification ("Mummy added 2 items to To buy") opens the app on To buy, wherever it was left:
 * on Settings, or on the All items page. The intent is the notification's own ([ChangeNotifier.openAppIntent]), sent
 * while the app is open on top of its task, so it reaches the running activity as a tap on the notification does.
 */
@RunWith(AndroidJUnit4::class)
class NotificationTapTest : GroceryUiTest() {

    private val settingsShown = isHeading() and hasText("This phone")

    private fun tapNotification() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.startActivity(ChangeNotifier.openAppIntent(context))
    }

    @Test
    fun leftOnSettingsItOpensToBuy() {
        launchConnected()
        showAllItems()
        openSettings()
        waitForNode(settingsShown)

        tapNotification()
        waitForNode(tab("To buy") and isSelected())
        waitForNoNode(settingsShown)
    }

    @Test
    fun leftOnAllItemsItOpensToBuy() {
        launchConnected()
        showAllItems()

        tapNotification()
        waitForNode(tab("To buy") and isSelected())
    }

    @Test
    fun eachTapSwitchesPagesOnlyOnce() {
        launchConnected()
        showAllItems()
        tapNotification()
        waitForNode(tab("To buy") and isSelected())

        // Back on All items, a trip to Settings and back doesn't take the same tap again.
        showAllItems()
        openSettings()
        waitForNode(settingsShown)
        node(hasContentDescription("Back")).performClick()
        waitForNoNode(settingsShown)
        waitForNode(tab("All items") and isSelected())
    }

    @Test
    fun aTapAfterTheAppWasRecreatedOnSettingsStillOpensToBuy() {
        launchConnected()
        showAllItems()
        tapNotification()
        waitForNode(tab("To buy") and isSelected())

        // Recreated while Settings is shown (turned sideways, dark mode, a new Language): the list screen isn't drawn
        // then, and keeps the tap it took last.
        showAllItems()
        openSettings()
        waitForNode(settingsShown)
        recreateApp()
        waitForNode(settingsShown)

        tapNotification()
        waitForNode(tab("To buy") and isSelected())
        waitForNoNode(settingsShown)
    }
}
