package com.grocerysync.app.ui

import com.grocerysync.app.data.Amount
import com.grocerysync.app.data.InputValidation
import com.grocerysync.app.data.ItemValue
import com.grocerysync.app.data.UrlCheck
import com.grocerysync.app.data.Units
import com.grocerysync.app.text.AppTexts
import com.grocerysync.app.text.UseTextsRule
import com.grocerysync.app.text.withTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * The item dialog's texts in Hindi, as its functions make them: the name checks, the duplicate and similar-item
 * messages (with names quoted and shortened as in English), the unit prompt, the "changed on another phone" notice
 * and the web app URL checks Settings shows. Item names, units and amounts stay as they are.
 */
class ItemTextsHindiTest {

    @get:Rule
    val hindi = UseTextsRule(AppTexts.Hindi)

    @Test
    fun nameChecks() {
        assertEquals("सामान का नाम लिखें", InputValidation.validateName("  "))
        assertEquals("100 अक्षरों से छोटा रखें", InputValidation.validateName("a".repeat(101)))
        assertEquals("नाम एक ही लाइन में लिखें", InputValidation.validateName("Milk\nBread"))
        assertNull(InputValidation.validateName("दूध"))
    }

    @Test
    fun duplicateAndSimilarNames() {
        assertEquals("\"Rice\" पहले से लिस्ट में है", alreadyOnListText("Rice"))
        assertEquals("\"Rice\" नाम का दूसरा सामान पहले से है", anotherItemCalledText("Rice"))
        assertEquals("\"Rice\" खोलें", openItemText("Rice"))
        assertEquals("मिलता-जुलता \"दही\" पहले से लिस्ट में है", similarItemText("दही"))
        // A long name is cut as in English: the button quotes a shorter one.
        val long = "Basmati rice extra long grain premium quality from the hills"
        assertEquals("\"Basmati rice extra long grain premium...\" पहले से लिस्ट में है", alreadyOnListText(long))
        assertEquals("\"Basmati rice extra lo...\" खोलें", openItemText(long))
    }

    @Test
    fun amountAndUnit() {
        assertEquals("0 से 999999 तक की मात्रा लिखें, दशमलव के बाद 3 अंक तक", InputValidation.ERROR_AMOUNT)
        assertEquals("यूनिट चुनें", UNIT_PROMPT)
        assertEquals("यूनिट", unitPickerText("", promptInPicker = false))
        assertEquals("चुनें", unitPickerText("", promptInPicker = true))
        // A chosen unit is data, in any language.
        assertEquals(Units.KG, unitPickerText(Units.KG, promptInPicker = true))
        assertEquals("0.5 kg", stepDescription(Units.KG))
    }

    @Test
    fun changedOnAnotherPhone() {
        val keep = "अपना बदलाव रखने के लिए सेव करें।"
        assertEquals("दूसरे फ़ोन पर बदला गया: चाहिए। $keep", changedElsewhereText(ItemValue.BoolValue(true)))
        assertEquals("दूसरे फ़ोन पर बदला गया: नहीं चाहिए। $keep", changedElsewhereText(ItemValue.BoolValue(false)))
        assertEquals(
            "दूसरे फ़ोन पर बदला गया: 1.5 kg। $keep",
            changedElsewhereText(ItemValue.Quantity(Amount(1_500L), Units.KG)),
        )
        assertEquals("दूसरे फ़ोन पर बदला गया: × 2। $keep", changedElsewhereText(ItemValue.Quantity.of(2)))
    }

    @Test
    fun webAppUrlChecks() {
        assertEquals(
            UrlCheck.Warning(
                "यह टेस्ट (/dev) URL है। Deploy > Manage deployments से वह Web app URL लें जो /exec पर ख़त्म होता है।",
            ),
            InputValidation.validateWebAppUrl("https://script.google.com/macros/s/AKfycbx/dev"),
        )
        assertEquals(
            UrlCheck.Warning("सुरक्षित नहीं - http:// सिर्फ़ लोकल टेस्टिंग के लिए इस्तेमाल करें।"),
            InputValidation.validateWebAppUrl("http://example.com/macros/s/test/exec"),
        )
        assertEquals(
            UrlCheck.Warning("यह Apps Script के वेब ऐप का URL नहीं लगता (यह /exec पर ख़त्म होना चाहिए)।"),
            InputValidation.validateWebAppUrl("https://example.com/exec"),
        )
        assertEquals(UrlCheck.Invalid("Apps Script से Web app URL पेस्ट करें"), InputValidation.validateWebAppUrl(" "))
        assertEquals(
            UrlCheck.Invalid("URL https:// से शुरू होना चाहिए"),
            InputValidation.validateWebAppUrl("ftp://script.google.com/macros/s/x/exec"),
        )
        assertEquals(UrlCheck.Invalid("यह सही URL नहीं है"), InputValidation.validateWebAppUrl("not a url"))
        assertEquals(UrlCheck.Ok, InputValidation.validateWebAppUrl("https://script.google.com/macros/s/AKfycbx/exec"))
    }

    @Test
    fun theTextsFollowTheLanguageShown() {
        // Read each time they are used, so the same checks made after a switch back to English say it in English.
        val dev = "https://script.google.com/macros/s/AKfycbx/dev"
        assertEquals("सामान का नाम लिखें", InputValidation.validateName(""))
        withTexts(AppTexts.English) {
            assertEquals("Enter an item name", InputValidation.validateName(""))
            assertEquals(
                UrlCheck.Warning(
                    "This is a test (/dev) URL. Use the Web app URL ending in /exec from Deploy > Manage deployments.",
                ),
                InputValidation.validateWebAppUrl(dev),
            )
            assertEquals("Choose a unit", UNIT_PROMPT)
            assertEquals(
                "Changed on another phone to needed. Save to use your value instead.",
                changedElsewhereText(ItemValue.BoolValue(true)),
            )
        }
    }
}
