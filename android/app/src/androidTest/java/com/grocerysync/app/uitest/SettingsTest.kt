package com.grocerysync.app.uitest

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grocerysync.app.data.SyncSettings
import com.grocerysync.app.testing.AppState
import com.grocerysync.app.testing.FakeSheetServer.Companion.yesNo
import com.grocerysync.app.testing.GroceryUiTest
import com.grocerysync.app.testing.button
import com.grocerysync.app.testing.gotIt
import com.grocerysync.app.testing.hasInputText
import com.grocerysync.app.testing.hasWord
import com.grocerysync.app.testing.isPasswordField
import com.grocerysync.app.testing.liveStatus
import com.grocerysync.app.testing.mentions
import com.grocerysync.app.testing.showsText
import com.grocerysync.app.testing.tab
import com.grocerysync.app.testing.textField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** The Settings screen: connecting through it, and the secret token that is never shown. */
@RunWith(AndroidJUnit4::class)
class SettingsTest : GroceryUiTest() {

    private val tokenField = textField("Secret token")

    @Test
    fun connectsThroughTheSettingsScreen() {
        // Answer like Google does (302 to an echo URL), which the app follows.
        fake.googleRedirects = true
        fake.seed("Coffee", yesNo(true))
        launchApp()
        waitForNode(hasText("Not connected"))

        node(button("Set up")).performClick()
        waitForNode(textField("Web app URL"))
        node(tokenField).assert(hasText("Type or paste the SECRET_TOKEN from Code.gs. The app never shows it."))
        node(textField("Your name")).performTextInput(ME)
        node(textField("Web app URL")).performTextInput(fake.url)
        node(tokenField).performTextInput(fake.token)

        // The keyboard is up and the screen scrolls: bring each button into view first, or the tap lands outside
        // the visible part of the screen and does nothing.
        node(button("Test connection")).performScrollTo().assertIsDisplayed().performClick()
        waitForNode(hasText("Connected to ${fake.spreadsheetName}"), SYNC_TIMEOUT_MS)
        assertEquals(1, fake.pingRequests)

        node(button("Save")).performScrollTo().assertIsDisplayed().performClick()
        // Back on the list, which syncs at once and is named after the sheet.
        waitForNode(gotIt("Coffee"), SYNC_TIMEOUT_MS)
        waitForNode(liveStatus)
        waitForNode(hasText(fake.spreadsheetName))
        assertEquals(SyncSettings(fake.url, fake.token, ME), AppState.settings())
    }

    @Test
    fun theSavedTokenIsNeverShownAndTheVersionIs() {
        launchConnected()
        settingsButton().performClick()
        waitForNode(tokenField)
        // Let the cross-fade from the list finish, so only Settings is on screen.
        waitForNoNode(tab("To buy"))

        // The field is empty and says a token is saved; nothing on the screen holds the token.
        node(tokenField)
            .assert(hasInputText(""))
            .assert(isPasswordField)
            .assert(hasText("Saved and hidden. Type or paste a new token only to replace it."))
        assertNoNode(mentions(fake.token))
        // No Show button for it, and no Share setup any more.
        assertEquals(0, count(hasWord("Show")))
        assertEquals(0, count(hasWord("Share")))

        // The version line at the bottom names the installed version.
        val versionName = AppState.versionName()
        assertFalse("versionName of the app", versionName.isNullOrBlank())
        node(hasText("Version $versionName")).performScrollTo().assertIsDisplayed()

        // A new token typed in is masked and would replace the saved one; leaving without Save keeps the saved one.
        // (The field holds what was typed, as its input text; what it shows are dots.)
        node(tokenField).performTextInput("another-secret-token")
        node(tokenField)
            .assert(hasInputText("another-secret-token"))
            .assert(hasText("Replaces the saved token when you tap Save."))
        assertNoNode(showsText("another-secret-token"))
        assertNoNode(mentions(fake.token))
        node(hasContentDescription("Back") and hasClickAction()).performClick()
        waitForNode(tab("To buy"))
        assertEquals(fake.token, AppState.settings().token)
    }
}
