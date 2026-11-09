package com.grocerysync.app.uitest

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grocerysync.app.testing.AppState
import com.grocerysync.app.testing.GroceryUiTest
import com.grocerysync.app.testing.hasInputText
import com.grocerysync.app.testing.textField
import com.grocerysync.app.text.AppLanguage
import com.grocerysync.app.text.HindiItemTexts
import com.grocerysync.app.text.HindiSettingsTexts
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings > This phone > Language: choosing a language shows the app in it at once (the activity is recreated),
 * Settings stays open and what was typed there but not saved stays. The emulator's own language is English.
 */
@RunWith(AndroidJUnit4::class)
class LanguageTest : GroceryUiTest() {

    /** The "Phone's language" row, in either language. It also names the language it means ("English"). */
    private val phoneRow: SemanticsMatcher = isSelectable() and (hasText("Phone's language") or hasText("फ़ोन की भाषा"))

    /** The row of the language called [name], not the "Phone's language" row that may name it too. */
    private fun languageRow(name: String): SemanticsMatcher = isSelectable() and hasText(name) and !phoneRow

    private val english = languageRow("English")
    private val hindi = languageRow("हिन्दी")

    /** The Your name field by what was typed into it, whatever its label says in the language shown. */
    private val typedName = hasSetTextAction() and hasInputText("Dadi")

    @Test
    fun choosingALanguageShowsItAndKeepsSettingsOpen() {
        launchApp()
        openSettings()
        waitForNode(isHeading() and hasText("This phone"))
        node(phoneRow).performScrollTo().assertIsSelected()
        // The phone's language (English) is named under it.
        node(phoneRow).assert(hasText("Phone's language")).assert(hasText("English"))
        node(textField("Your name")).performTextInput("Dadi")

        node(hindi).performScrollTo().performClick()
        waitForNode(isHeading() and hasText("यह फ़ोन"))
        node(hindi).performScrollTo().assertIsSelected()
        node(phoneRow).assert(hasText("फ़ोन की भाषा")).assertIsNotSelected()
        node(typedName).performScrollTo()
        assertEquals(AppLanguage.HINDI, AppState.container.phoneSettings.current().language)

        node(english).performScrollTo().performClick()
        waitForNode(isHeading() and hasText("This phone"))
        node(english).performScrollTo().assertIsSelected()
        node(typedName).performScrollTo()
        assertEquals(AppLanguage.ENGLISH, AppState.container.phoneSettings.current().language)

        // Back to the phone's language: English stays (no recreate needed), the choice is saved.
        node(phoneRow).performScrollTo().performClick()
        waitForNode(phoneRow and isSelected())
        assertEquals(AppLanguage.PHONE, AppState.container.phoneSettings.current().language)
    }

    @Test
    fun theUrlFieldsErrorIsSaidInTheLanguageChosen() {
        launchApp()
        openSettings()
        node(hindi).performScrollTo().performClick()
        waitForNode(isHeading() and hasText("यह फ़ोन"))
        node(textField(HindiSettingsTexts.webAppUrl)).performScrollTo().performTextInput("abc")
        // TalkBack reads the field's own message, not Material's "Invalid input" in the phone's language.
        waitForNode(
            textField(HindiSettingsTexts.webAppUrl) and
                SemanticsMatcher.expectValue(SemanticsProperties.Error, HindiItemTexts.urlUnparseable),
        )
    }
}
