package com.grocerysync.app.uitest

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grocerysync.app.testing.button
import com.grocerysync.app.text.AppTexts
import com.grocerysync.app.text.HindiNotifyTexts
import com.grocerysync.app.text.HindiSettingsTexts
import com.grocerysync.app.text.Texts
import com.grocerysync.app.ui.NotifyChangesSetting
import com.grocerysync.app.ui.components.NotifyOfferCard
import com.grocerysync.app.ui.theme.GrocerySyncTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the notifications switch shows when Android blocks the app's notifications, and the list's offer card: shown
 * on their own (not in the app), because whether Android allows notifications can't be chosen by a test (taking the
 * permission away ends the test's process).
 */
@RunWith(AndroidJUnit4::class)
class NotificationCardsTest {

    @get:Rule
    val compose = createComposeRule()

    private val blockedLine = hasText("Notifications are blocked for this app.")
    private val allowButton = button("Allow in Android settings")
    private val notifySwitch = isToggleable() and hasText("Notify me when someone adds to To buy")

    @After
    fun backToEnglish() {
        Texts.current = AppTexts.English
    }

    @Test
    fun blockedNotificationsSayHowToAllowThem() {
        var on by mutableStateOf(true)
        var allowTaps = 0
        compose.setContent {
            GrocerySyncTheme {
                NotifyChangesSetting(on = on, blocked = on, onChange = { on = it }, onAllow = { allowTaps++ })
            }
        }
        compose.onNode(notifySwitch).assertIsOn()
        compose.onNode(blockedLine).assertIsDisplayed()
        compose.onNode(allowButton).performClick()
        assertEquals(1, allowTaps)

        // Turned off, there is nothing to allow.
        compose.onNode(notifySwitch).performClick()
        compose.onNode(notifySwitch).assertIsOff()
        compose.onNode(blockedLine).assertDoesNotExist()
        compose.onNode(allowButton).assertDoesNotExist()
    }

    @Test
    fun allowedNotificationsShowOnlyTheSwitch() {
        compose.setContent {
            GrocerySyncTheme {
                NotifyChangesSetting(on = true, blocked = false, onChange = {}, onAllow = {})
            }
        }
        compose.onNode(notifySwitch).assertIsOn()
        compose.onNode(hasText("Checks about every 15 minutes while the app is closed")).assertIsDisplayed()
        compose.onNode(blockedLine).assertDoesNotExist()
        compose.onNode(allowButton).assertDoesNotExist()
    }

    @Test
    fun theOffersButtons() {
        var turnOn = 0
        var notNow = 0
        compose.setContent {
            GrocerySyncTheme {
                NotifyOfferCard(onTurnOn = { turnOn++ }, onNotNow = { notNow++ })
            }
        }
        compose.onNode(hasText("Get a notification when someone adds to To buy?")).assertIsDisplayed()
        compose.onNode(button("Turn on")).performClick()
        assertEquals(1 to 0, turnOn to notNow)
        compose.onNode(button("Not now")).performClick()
        assertEquals(1 to 1, turnOn to notNow)
    }

    @Test
    fun inHindi() {
        Texts.current = AppTexts.Hindi
        compose.setContent {
            GrocerySyncTheme {
                Column {
                    NotifyChangesSetting(on = true, blocked = true, onChange = {}, onAllow = {})
                    NotifyOfferCard(onTurnOn = {}, onNotNow = {})
                }
            }
        }
        compose.onNode(isToggleable() and hasText(HindiSettingsTexts.notifyChanges)).assertIsOn()
        compose.onNode(hasText(HindiSettingsTexts.notificationsBlocked)).assertIsDisplayed()
        compose.onNode(button(HindiSettingsTexts.allowNotifications)).assertIsDisplayed()
        compose.onNode(hasText(HindiNotifyTexts.offerQuestion)).assertIsDisplayed()
        compose.onNode(button(HindiNotifyTexts.turnOn)).assertIsDisplayed()
        compose.onNode(button(HindiNotifyTexts.notNow)).assertIsDisplayed()
    }
}
