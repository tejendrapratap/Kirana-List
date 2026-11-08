package com.grocerysync.app.uitest

import android.os.Build
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.grocerysync.app.notify.hasNotificationPermission
import com.grocerysync.app.testing.AppState
import com.grocerysync.app.testing.FakeSheetServer.Companion.yesNo
import com.grocerysync.app.testing.GroceryUiTest
import com.grocerysync.app.testing.button
import com.grocerysync.app.testing.itemRow
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The list's one-time offer of change notifications (NotifyOffer, first in the banner header above the list): shown
 * while notifications are on, the phone is set up, Android 13+ hasn't given the app the permission and the offer
 * wasn't answered; "Not now" puts it away for good, in Simple mode too. AppState.reset counts the offer as answered,
 * so each test here that wants it makes it unanswered first.
 *
 * Whether Android has given the permission can't be chosen by a test (taking it away ends the test's process).
 * ThisPhoneSwitchesTest grants it, and Android keeps a grant until it is taken back (reinstalling the app keeps it),
 * so tools/e2e/uitest.sh takes it back before each run. The tests that need the card on screen are skipped while it
 * is granted, and below Android 13: within one run this class must come before ThisPhoneSwitchesTest, as its name
 * does (and as it does when both are named with --class in that order). "Turn on" opens Android's permission dialog,
 * which only Android's own screens can answer, so its button is checked on the card alone
 * (NotificationCardsTest.theOffersButtons); NotifyOfferPlacementTest checks where the card sits on any emulator.
 */
@RunWith(AndroidJUnit4::class)
class NotifyOfferOnListTest : GroceryUiTest() {

    private val offer: SemanticsMatcher = hasText("Get a notification when someone adds to To buy?")

    /** Skips the test unless the offer can show on this emulator: Android 13+ without POST_NOTIFICATIONS. */
    private fun assumeTheOfferCanShow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(
            "the offer needs Android 13+ without the notification permission",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission(context),
        )
    }

    @Test
    fun theOfferSitsAboveTheListUntilNotNow() {
        assumeTheOfferCanShow()
        fake.seed("Stapler", yesNo(true))
        AppState.container.phoneSettings.setNotifyOfferDone(false)
        launchConnected()

        val card = waitForNode(offer).assertIsDisplayed().getUnclippedBoundsInRoot()
        val row = waitForNode(itemRow("Stapler")).getUnclippedBoundsInRoot()
        assertTrue("The offer ($card) should be above the rows ($row)", card.bottom <= row.top)

        node(button("Not now")).performClick()
        waitForNoNode(offer)
        val phone = AppState.container.phoneSettings.current()
        assertTrue("Not now should answer the offer", phone.notifyOfferDone)
        // Only the offer is answered: notifications stay on (Settings > This phone turns them off).
        assertTrue("Not now should leave notifications on", phone.notifyChanges)
        // Gone from All items too, where it was offered as well.
        showAllItems()
        assertNoNode(offer)
    }

    @Test
    fun simpleModeOffersItToo() {
        assumeTheOfferCanShow()
        fake.seed("Stapler", yesNo(true))
        AppState.container.phoneSettings.setNotifyOfferDone(false)
        AppState.container.phoneSettings.setSimpleMode(true)
        val before = System.currentTimeMillis()
        AppState.connect(fake.url, fake.token, ME)
        launchApp()
        waitFor("the first sync with the fake") { AppState.syncedSince(before) }

        waitForNode(offer).assertIsDisplayed()
        node(button("Not now")).performClick()
        waitForNoNode(offer)
        assertTrue(AppState.container.phoneSettings.current().notifyOfferDone)
    }

    @Test
    fun anAnsweredOfferIsNotShown() {
        // AppState.reset answered it, as "Turn on", "Not now" or the switch in Settings does.
        fake.seed("Stapler", yesNo(true))
        launchConnected()

        waitForNode(itemRow("Stapler"))
        assertNoNode(offer)
    }

    @Test
    fun itIsNotOfferedWhileNotificationsAreOff() {
        fake.seed("Stapler", yesNo(true))
        AppState.container.phoneSettings.setNotifyOfferDone(false)
        AppState.container.phoneSettings.setNotifyChanges(false)
        launchConnected()

        waitForNode(itemRow("Stapler"))
        assertNoNode(offer)
    }

    @Test
    fun itIsNotOfferedBeforeTheSheetIsSetUp() {
        AppState.container.phoneSettings.setNotifyOfferDone(false)
        launchApp()

        // The setup banner shows the way first; notifications would have nothing to check yet.
        waitForNode(hasText("Connect your Google Sheet"))
        assertNoNode(offer)
    }
}
