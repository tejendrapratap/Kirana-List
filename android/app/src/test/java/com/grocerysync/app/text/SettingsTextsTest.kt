package com.grocerysync.app.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Settings screen's texts: English exactly as before 1.6, Hindi complete and built from the shared words. */
class SettingsTextsTest {

    private val english = EnglishSettingsTexts
    private val hindi = HindiSettingsTexts

    @Test
    fun englishIsWhatSettingsSaidBefore() {
        assertEquals("Back", english.back)
        assertEquals("You", english.you)
        assertEquals("Your name", english.yourName)
        assertEquals("Shown to the other person as \"Added by\" or \"Updated by\".", english.yourNameHelp)
        assertEquals("This phone", english.thisPhone)
        assertEquals("Google Sheet", english.googleSheet)
        assertEquals("Paste setup code", english.pasteSetupCode)
        assertEquals("Have a setup message? Copy it, then tap Paste setup code.", english.pasteSetupHelp)
        assertEquals("Web app URL", english.webAppUrl)
        assertEquals("From Deploy > Manage deployments in Apps Script (ends in /exec).", english.webAppUrlHelp)
        assertEquals("Secret token", english.secretToken)
        assertEquals("Type or paste the SECRET_TOKEN from Code.gs. The app never shows it.", english.tokenNew)
        assertEquals("Saved and hidden. Type or paste a new token only to replace it.", english.tokenSaved)
        assertEquals("Token from the setup code, hidden. Tap Save to use it.", english.tokenFromSetupCode)
        assertEquals("Replaces the saved token when you tap Save.", english.tokenReplacesSaved)
        assertEquals("Used instead of the token from the setup code.", english.tokenReplacesSetupCode)
        assertEquals(
            "New web app URL: type or paste its token. A hidden token is only sent to its own URL.",
            english.tokenOtherUrl,
        )
        assertEquals("Test connection", english.testConnection)
        assertEquals("Testing connection...", english.testingConnection)
        assertEquals("Connected to Groceries", english.connectedTo("Groceries"))
        assertEquals("Enter the secret token first.", english.enterTokenFirst)
        assertEquals("No setup code found on the clipboard", english.noSetupCode)
        assertEquals("Setup code pasted - tap Save", english.setupCodePasted)
        assertEquals("How to connect a Google Sheet", english.helpTitle)
        assertEquals(
            listOf(
                "Open your sheet > Extensions > Apps Script, paste Code.gs.",
                "Set SECRET_TOKEN to your own secret.",
                "Deploy > New deployment > Web app; Execute as: Me; Who has access: Anyone.",
                "Copy the Web app URL (ends in /exec) here.",
            ),
            english.helpSteps,
        )
        assertEquals(
            listOf("Expand", "Collapse", "Expanded", "Collapsed"),
            listOf(english.expand, english.collapse, english.expanded, english.collapsed),
        )
        assertEquals("Version 1.2", english.version("1.2"))
    }

    @Test
    fun everyFixedTextHasItsOwnHindi() {
        val getters = SettingsTexts::class.java.methods.filter {
            it.parameterCount == 0 && it.returnType == String::class.java
        }
        assertTrue(getters.map { it.name }.toString(), getters.size >= 20)
        for (getter in getters) {
            val inHindi = getter.invoke(hindi) as String
            assertNotEquals(getter.name, getter.invoke(english), inHindi)
            assertTrue("${getter.name}: $inHindi", inHindi.any { it in 'ऀ'..'ॿ' })
        }
        assertEquals(english.helpSteps.size, hindi.helpSteps.size)
        for (step in hindi.helpSteps) assertTrue(step, step.any { it in 'ऀ'..'ॿ' })
    }

    @Test
    fun hindiTextsWithParts() {
        assertEquals("Groceries से जुड़ गया", hindi.connectedTo("Groceries"))
        assertEquals("वर्ज़न 1.6", hindi.version("1.6"))
        assertEquals(
            "शीट की स्क्रिप्ट पुरानी है। तब तक लिस्ट सिंक होती रहेगी, पर बदलाव इस फ़ोन पर रुके रहेंगे।",
            hindi.scriptOutdated("शीट की स्क्रिप्ट पुरानी है।"),
        )
        assertEquals(
            "The sheet's script is out of date. Until then the list syncs, but changes wait on this phone.",
            english.scriptOutdated("The sheet's script is out of date."),
        )
    }

    @Test
    fun yourNameHelpQuotesWhatTheRowsSay() {
        // English names the forms without a name, as since 1.2; Hindi, where the name comes first, keeps its place.
        assertTrue(english.yourNameHelp.contains("\"${EnglishCommonTexts.addedBy("").trim()}\""))
        assertTrue(english.yourNameHelp.contains("\"${EnglishCommonTexts.updatedBy("").trim()}\""))
        assertEquals("दूसरे फ़ोन पर ऐसे दिखेगा: \"… ने जोड़ा\" या \"… ने बदला\"।", hindi.yourNameHelp)
        assertTrue(hindi.yourNameHelp.contains(HindiCommonTexts.addedBy("…")))
        assertTrue(hindi.yourNameHelp.contains(HindiCommonTexts.updatedBy("…")))
    }

    @Test
    fun buttonsAreNamedAsTheyAreShown() {
        // "Tap Save", "tap Paste setup code": the button's own text, in each language.
        assertTrue(hindi.pasteSetupHelp.contains("\"${hindi.pasteSetupCode}\""))
        for (text in listOf(hindi.tokenFromSetupCode, hindi.tokenReplacesSaved, hindi.setupCodePasted)) {
            assertTrue(text, text.contains("\"${HindiCommonTexts.save}\""))
        }
        for (text in listOf(english.tokenFromSetupCode, english.tokenReplacesSaved, english.setupCodePasted)) {
            assertTrue(text, text.contains(EnglishCommonTexts.save))
        }
        assertEquals("सेटअप कोड पेस्ट हो गया - \"सेव करें\" दबाएँ", hindi.setupCodePasted)
    }

    @Test
    fun thisPhonesSwitches() {
        assertEquals("Simple mode", english.simpleMode)
        assertEquals("Bigger text and buttons. Shows only To buy, and Undo stays on screen.", english.simpleModeHelp)
        assertEquals("Notify me when someone adds to To buy", english.notifyChanges)
        assertEquals("Checks about every 15 minutes while the app is closed", english.notifyChangesHelp)
        assertEquals("Notifications are blocked for this app.", english.notificationsBlocked)
        assertEquals("Allow in Android settings", english.allowNotifications)

        assertEquals("आसान मोड", hindi.simpleMode)
        // The page and the button are named as they are shown, quoted.
        assertEquals(
            "बड़े अक्षर और बड़े बटन। सिर्फ़ \"ख़रीदना है\" दिखेगा, और \"पहले जैसा करें\" स्क्रीन पर बना रहेगा।",
            hindi.simpleModeHelp,
        )
        assertTrue(hindi.simpleModeHelp.contains("\"${HindiCommonTexts.toBuy}\""))
        assertTrue(hindi.simpleModeHelp.contains("\"${HindiCommonTexts.undo}\""))
        assertEquals("कोई \"ख़रीदना है\" में कुछ जोड़े, तो मुझे सूचना दें", hindi.notifyChanges)
        assertTrue(hindi.notifyChangesHelp.contains("15"))
    }

    @Test
    fun theCurrentTextsAreTheLanguageShown() {
        assertEquals("Test connection", Texts.current.settings.testConnection)
        assertEquals("कनेक्शन जाँचें", withTexts(AppTexts.Hindi) { Texts.current.settings.testConnection })
    }
}
