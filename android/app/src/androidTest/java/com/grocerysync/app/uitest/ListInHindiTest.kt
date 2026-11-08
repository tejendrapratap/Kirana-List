package com.grocerysync.app.uitest

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.grocerysync.app.R
import com.grocerysync.app.data.Units
import com.grocerysync.app.testing.AppState
import com.grocerysync.app.testing.FakeSheetServer.Companion.quantity
import com.grocerysync.app.testing.FakeSheetServer.Companion.yesNo
import com.grocerysync.app.testing.GroceryUiTest
import com.grocerysync.app.testing.button
import com.grocerysync.app.testing.hasTextMatching
import com.grocerysync.app.testing.tab
import com.grocerysync.app.text.AppLanguage
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The list screen with the Language choice set to Hindi (1.6): the app bar, the pages, the status line, section
 * headers, rows, the Undo snackbar and the ⋮ menu are in Hindi; item names and amounts stay as they are.
 */
@RunWith(AndroidJUnit4::class)
class ListInHindiTest : GroceryUiTest() {

    @Test
    fun theListScreenIsInHindi() {
        fake.seed("Dahi", yesNo(true))
        fake.seed("Atta", quantity("10", Units.KG))
        fake.seed("Coffee", yesNo(false))
        AppState.container.phoneSettings.setLanguage(AppLanguage.HINDI)
        val before = System.currentTimeMillis()
        AppState.connect(fake.url, fake.token, ME)
        launchApp()
        waitFor("the first sync with the fake") { AppState.syncedSince(before) }

        // The app's name in Hindi, unless the build has a name of its own (the qa build's "Kirana QA").
        val appName = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.app_name)
        waitForNode(hasText(if (appName == "Kirana List") "किराना लिस्ट" else appName))
        waitForNode(tab("ख़रीदना है", 2)).assertIsSelected()
        node(tab("सारा सामान", 3)).assertIsDisplayed()
        waitForNode(hasTextMatching(Regex("लाइव( - .+ अपडेट हुई)?")))
        waitForNode(isHeading() and hasContentDescription("दूध, अंडे और ब्रेड, 1 चीज़"))
        // The amount's badge, as TalkBack reads it.
        node(hasContentDescription("मात्रा 10 kg")).assertIsDisplayed()

        // Got it, and its Undo.
        val gotDahi = hasContentDescription("ले लिया: Dahi")
        node(gotDahi).performClick()
        waitForNode(hasText("Dahi ले लिया"))
        waitForNode(button("पहले जैसा करें")).performClick()
        waitForNode(gotDahi)
        waitForNode(tab("ख़रीदना है", 2))

        // The ⋮ menu.
        compose.onNodeWithContentDescription("और विकल्प").performClick()
        waitForNode(button("आम सामान जोड़ें")).assertIsDisplayed()
        node(button("लिस्ट पेस्ट करें")).assertIsDisplayed()
        node(button("सेक्शन के हिसाब से दिखाएँ")).assertIsDisplayed()
        node(button("सेटिंग्स")).assertIsDisplayed()
    }
}
