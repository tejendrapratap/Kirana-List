package com.grocerysync.app.uitest

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
import com.grocerysync.app.testing.AppState
import com.grocerysync.app.testing.GroceryUiTest
import com.grocerysync.app.text.EnglishSettingsTexts
import com.grocerysync.app.text.HindiSettingsTexts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Settings > This phone: the Simple mode switch, saved as soon as it is switched. */
@RunWith(AndroidJUnit4::class)
class ThisPhoneSwitchesTest : GroceryUiTest() {

    /** A switch row by its title (the line under it is part of the same row). */
    private fun switchRow(title: String): SemanticsMatcher = isToggleable() and hasText(title)

    private val simpleMode = switchRow("Simple mode")

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
    fun theSwitchSpeaksHindi() {
        launchApp()
        openSettings()
        node(isSelectable() and hasText("हिन्दी") and !hasText(EnglishSettingsTexts.phonesLanguage))
            .performScrollTo()
            .performClick()
        waitForNode(isHeading() and hasText(HindiSettingsTexts.thisPhone))
        node(switchRow(HindiSettingsTexts.simpleMode))
            .performScrollTo()
            .assert(hasText(HindiSettingsTexts.simpleModeHelp))
    }
}
