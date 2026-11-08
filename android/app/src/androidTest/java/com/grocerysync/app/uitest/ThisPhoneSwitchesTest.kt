package com.grocerysync.app.uitest

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.grocerysync.app.sync.SyncScheduler
import com.grocerysync.app.testing.AppState
import com.grocerysync.app.testing.GroceryUiTest
import com.grocerysync.app.text.EnglishSettingsTexts
import com.grocerysync.app.text.HindiSettingsTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Settings > This phone: the Simple mode switch and the switch for change notifications, each saved as soon as it is
 * switched; the notifications switch also plans and cancels the background check.
 */
@RunWith(AndroidJUnit4::class)
class ThisPhoneSwitchesTest : GroceryUiTest() {

    /** A switch row by its title (the line under it is part of the same row). */
    private fun switchRow(title: String): SemanticsMatcher = isToggleable() and hasText(title)

    private val simpleMode = switchRow("Simple mode")
    private val notifySwitch = switchRow("Notify me when someone adds to To buy")

    @Test
    fun simpleModeIsSavedAsSoonAsItIsSwitched() {
        launchApp()
        openSettings()
        waitForNode(isHeading() and hasText("This phone"))
        node(simpleMode)
            .performScrollTo()
            .assertIsOff()
            .assert(hasText("Bigger text and buttons. Shows only To buy, and Undo stays on screen."))

        node(simpleMode).performClick()
        node(simpleMode).assertIsOn()
        assertTrue(AppState.container.phoneSettings.current().simpleMode)

        node(simpleMode).performClick()
        node(simpleMode).assertIsOff()
        assertFalse(AppState.container.phoneSettings.current().simpleMode)
    }

    @Test
    fun theNotificationsSwitchPlansAndCancelsTheBackgroundCheck() {
        // Allowed beforehand, so turning the switch on doesn't bring up Android's permission dialog.
        allowNotifications()
        launchConnected()
        waitFor("the background check to be planned") { listCheckPlanned() }
        // Not answered yet (AppState.reset answers it), so the switch below is seen to answer it.
        AppState.container.phoneSettings.setNotifyOfferDone(false)
        openSettings()

        waitForNode(notifySwitch)
            .performScrollTo()
            .assertIsOn()
            .assert(hasText("Checks about every 15 minutes while the app is closed."))
        // Notifications are allowed: nothing says they are blocked.
        assertEquals(0, count(hasText(EnglishSettingsTexts.notificationsBlocked)))

        node(notifySwitch).performClick()
        node(notifySwitch).assertIsOff()
        assertFalse(AppState.container.phoneSettings.current().notifyChanges)
        waitFor("the background check to be cancelled") { !listCheckPlanned() }
        // Choosing here answers the list's offer too.
        assertTrue(AppState.container.phoneSettings.current().notifyOfferDone)

        node(notifySwitch).performClick()
        node(notifySwitch).assertIsOn()
        assertTrue(AppState.container.phoneSettings.current().notifyChanges)
        waitFor("the background check to be planned again") { listCheckPlanned() }
        assertEquals(0, count(hasText(EnglishSettingsTexts.notificationsBlocked)))
    }

    @Test
    fun theSwitchesSpeakHindi() {
        launchApp()
        openSettings()
        node(isSelectable() and hasText("हिन्दी") and !hasText(EnglishSettingsTexts.phonesLanguage))
            .performScrollTo()
            .performClick()
        waitForNode(isHeading() and hasText(HindiSettingsTexts.thisPhone))
        node(switchRow(HindiSettingsTexts.simpleMode))
            .performScrollTo()
            .assert(hasText(HindiSettingsTexts.simpleModeHelp))
        node(switchRow(HindiSettingsTexts.notifyChanges))
            .performScrollTo()
            .assert(hasText(HindiSettingsTexts.notifyChangesHelp))
    }

    /** True while a run of the background check is planned (WorkManager's unique work, not finished). */
    private fun listCheckPlanned(): Boolean =
        WorkManager.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
            .getWorkInfosForUniqueWork(SyncScheduler.LIST_CHECK_WORK)
            .get(10, TimeUnit.SECONDS)
            .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }

    /**
     * Grants POST_NOTIFICATIONS on Android 13+ (granting doesn't restart the app; taking a permission away would end
     * the test's process, so no test does that). Below Android 13 notifications are allowed by default.
     */
    private fun allowNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }
}
