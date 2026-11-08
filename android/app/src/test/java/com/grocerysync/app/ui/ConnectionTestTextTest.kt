package com.grocerysync.app.ui

import com.grocerysync.app.data.UrlCheck
import com.grocerysync.app.sync.Protocol
import com.grocerysync.app.text.AppTexts
import com.grocerysync.app.text.HindiSettingsTexts
import com.grocerysync.app.ui.ConnectionTest.Failure.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** What Settings says about a Test connection, built where it is shown, in the language shown. */
class ConnectionTestTextTest {

    private val english = AppTexts.English
    private val hindi = AppTexts.Hindi
    private val invalid = UrlCheck.Invalid("That isn't a valid URL")

    @Test
    fun englishIsAsBefore() {
        val noToken = ConnectionTest.Failure(Reason.NO_TOKEN)
        assertEquals("Enter the secret token first.", connectionFailureText(noToken, invalid, english))
        assertEquals(invalid.message, connectionFailureText(ConnectionTest.Failure(Reason.INVALID_URL), invalid, english))
        val notFound = ConnectionTest.Failure(Reason.NO_ANSWER, Protocol.MSG_NOT_FOUND)
        assertEquals(Protocol.MSG_NOT_FOUND, connectionFailureText(notFound, UrlCheck.Ok, english))
        assertEquals(
            Protocol.MSG_SCRIPT_OUTDATED + " Until then the list syncs, but changes wait on this phone.",
            scriptOutdatedText(english),
        )
    }

    @Test
    fun hindiTranslatesTheSyncLayersMessages() {
        assertEquals(
            HindiSettingsTexts.enterTokenFirst,
            connectionFailureText(ConnectionTest.Failure(Reason.NO_TOKEN), UrlCheck.Ok, hindi),
        )
        assertEquals(
            "वेब ऐप नहीं मिला (404)। URL जाँचें, या स्क्रिप्ट को Web app के रूप में डिप्लॉय करें।",
            connectionFailureText(ConnectionTest.Failure(Reason.NO_ANSWER, Protocol.MSG_NOT_FOUND), UrlCheck.Ok, hindi),
        )
        // The script's own words (here with the token redacted) stay as they are inside the Hindi sentence.
        assertEquals(
            "शीट की स्क्रिप्ट ने गड़बड़ी बताई: Bad request ?token=(hidden).",
            connectionFailureText(
                ConnectionTest.Failure(Reason.NO_ANSWER, Protocol.SCRIPT_ERROR_PREFIX + "Bad request ?token=(hidden)."),
                UrlCheck.Ok,
                hindi,
            ),
        )
        // An answer the app doesn't know is shown as it came.
        assertEquals(
            "Quota exceeded",
            connectionFailureText(ConnectionTest.Failure(Reason.NO_ANSWER, "Quota exceeded"), UrlCheck.Ok, hindi),
        )
    }

    @Test
    fun anOldScriptsWarningIsAllHindi() {
        val warning = scriptOutdatedText(hindi)
        assertEquals(
            "शीट की स्क्रिप्ट पुरानी है। यूनिट इस्तेमाल करने के लिए Apps Script में सबसे नई Code.gs पेस्ट करें और " +
                "नया वर्ज़न डिप्लॉय करें। तब तक लिस्ट सिंक होती रहेगी, पर बदलाव इस फ़ोन पर रुके रहेंगे।",
            warning,
        )
        assertFalse(warning, warning.contains("out of date"))
    }

    @Test
    fun anInvalidUrlFailureSaysNothingOnceTheUrlIsValid() {
        // Can't happen (a new URL clears the result), but then there is nothing true to say.
        assertNull(connectionFailureText(ConnectionTest.Failure(Reason.INVALID_URL), UrlCheck.Ok, english))
    }
}
