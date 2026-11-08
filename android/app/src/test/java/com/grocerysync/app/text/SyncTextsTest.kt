package com.grocerysync.app.text

import com.grocerysync.app.data.ListLogic
import com.grocerysync.app.sync.ApiResult
import com.grocerysync.app.sync.LiveSync
import com.grocerysync.app.sync.Protocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Every message the sync layer can show has its Hindi: the constants (found by reflection, so a new one can't be
 * forgotten), and the messages Protocol composes around the script's own words, made here through Protocol itself.
 */
class SyncTextsTest {

    private val hindi = HindiSyncTexts

    /** Every static String field of [type] whose name starts with MSG_, private ones included, by "Class.FIELD". */
    private fun messageConstants(type: Class<*>): Map<String, String> =
        type.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java && it.name.startsWith("MSG_") }
            .associate { field ->
                field.isAccessible = true
                "${type.simpleName}.${field.name}" to field.get(null) as String
            }

    /** [translated] is all Hindi around [kept] (the script's own words, which stay as they are). */
    private fun assertHindi(english: String, translated: String?, kept: String = "") {
        assertNotEquals("not translated: $english", english, translated)
        translated!!
        assertTrue(translated, translated.any { it in 'ऀ'..'ॿ' })
        assertTrue("\"$kept\" kept in: $translated", translated.contains(kept))
        val rest = translated.replace(kept, "")
        for (part in ENGLISH_PARTS) assertFalse("\"$part\" left in: $translated", rest.contains(part))
    }

    private fun failureMessage(result: ApiResult<*>): String = (result as ApiResult.Failure).message

    /** The message for an `ok: false` answer with [error] (none when null). */
    private fun serverError(error: String?): String {
        val body = JSONObject().put("ok", false)
        if (error != null) body.put("error", error)
        return failureMessage(Protocol.interpretResponse(200, "application/json", body.toString()))
    }

    private fun page(html: String, code: Int = 200): String =
        failureMessage(Protocol.interpretResponse(code, "text/html; charset=utf-8", html))

    @Test
    fun everyMessageConstantHasHindi() {
        val protocol = messageConstants(Protocol::class.java)
        val liveSync = messageConstants(LiveSync::class.java)
        assertTrue("Protocol's MSG_ constants: ${protocol.keys}", protocol.size >= 14)
        assertEquals(listOf("LiveSync.MSG_NOT_CONFIGURED"), liveSync.keys.toList())
        val others = mapOf(
            "Protocol.SAVED_ON_PHONE" to Protocol.SAVED_ON_PHONE,
            "ListLogic.DEFAULT_FAILURE" to ListLogic.DEFAULT_FAILURE,
        )
        for ((name, english) in protocol + liveSync + others) {
            val translated = hindi.message(english)
            assertNotEquals(name, english, translated)
            assertHindi(english, translated)
        }
    }

    @Test
    fun partsOfMessagesTranslateToo() {
        // The push warning shows the reason without "Changes are saved on this phone ...", which it says itself.
        for (message in listOf(Protocol.MSG_OFFLINE, Protocol.MSG_GOOGLE_TROUBLE, Protocol.MSG_BUSY)) {
            val cause = message.removeSuffix(Protocol.SAVED_ON_PHONE).trim()
            assertNotEquals(message, cause)
            assertHindi(cause, hindi.message(cause))
            assertEquals("${hindi.message(cause)} ${hindi.message(Protocol.SAVED_ON_PHONE)}", hindi.message(message))
        }
        // The list screen shows the first sentence of MSG_SCRIPT_OUTDATED in bold.
        val title = "The sheet's script is out of date."
        assertTrue(Protocol.MSG_SCRIPT_OUTDATED.startsWith("$title "))
        assertEquals("शीट की स्क्रिप्ट पुरानी है।", hindi.message(title))
        assertTrue(hindi.message(Protocol.MSG_SCRIPT_OUTDATED)!!.startsWith("शीट की स्क्रिप्ट पुरानी है। "))
    }

    @Test
    fun theScriptsOwnWordsStayInside() {
        val internal = serverError("internal: Exception: Range not found")
        assertTrue(internal.startsWith(Protocol.SCRIPT_ERROR_PREFIX))
        assertHindi(internal, hindi.message(internal), kept = "Exception: Range not found.")

        val errorPage = page("<html><head><title>Error</title></head><body><div>TypeError: x is undefined</div></body></html>")
        assertTrue(errorPage.startsWith(Protocol.SCRIPT_FAILED_PREFIX))
        assertHindi(errorPage, hindi.message(errorPage), kept = "TypeError: x is undefined")

        val badRequest = serverError("bad_request: unknown action")
        assertHindi(badRequest, hindi.message(badRequest), kept = "(unknown action)")
        val bareBadRequest = serverError("bad_request")
        assertHindi(bareBadRequest, hindi.message(bareBadRequest))

        val unknown = serverError("something_new: line one\nline two")
        assertHindi(unknown, hindi.message(unknown), kept = "something_new: line one\nline two")
        val missing = serverError(null)
        assertHindi(missing, hindi.message(missing))

        val http = failureMessage(Protocol.interpretResponse(418, "text/html", ""))
        assertHindi(http, hindi.message(http), kept = "HTTP 418")

        val titled = page("<html><head><title>Web Page Blocked</title></head><body>No.</body></html>")
        assertHindi(titled, hindi.message(titled), kept = "(\"Web Page Blocked\")")
    }

    @Test
    fun refusedChangesSayWhatToDo() {
        val rule = Protocol.changeErrorText("sheet_rejected: Choose Boolean or Integer.")
        assertHindi(rule, hindi.message(rule), kept = "Choose Boolean or Integer.")
        val protectedCell = Protocol.changeErrorText("sheet_rejected: You are trying to edit a protected cell or object.")
        assertHindi(protectedCell, hindi.message(protectedCell), kept = "You are trying to edit a protected cell or object.")
        val bare = Protocol.changeErrorText("sheet_rejected:")
        assertHindi(bare, hindi.message(bare))
        // "Tap Retry" names the Retry button as it is shown in Hindi.
        assertTrue(hindi.message(bare)!!.contains("\"${HindiCommonTexts.retry}\""))
        assertTrue(hindi.message(rule)!!.contains("\"${HindiCommonTexts.retry}\""))
        // Not "फिर "फिर से कोशिश करें"": the word for "then" isn't the one the button's name starts with.
        assertFalse(hindi.message(bare)!!.contains("फिर \"फिर"))
        assertTrue(hindi.message(bare)!!.endsWith("इसके बाद \"${HindiCommonTexts.retry}\" दबाएँ।"))
    }

    @Test
    fun anythingElseComesBackUnchanged() {
        assertNull(hindi.message(null))
        // A per-change error the script sent as it is, and a known sentence followed by something unknown.
        assertEquals("name: too long", hindi.message("name: too long"))
        val halfKnown = "Can't reach Google right now. Something else happened."
        assertEquals(halfKnown, hindi.message(halfKnown))
        assertEquals("", hindi.message(""))
    }

    @Test
    fun englishShowsTheMessagesAsTheyAre() {
        val messages = messageConstants(Protocol::class.java).values + serverError("internal: Boom") + "anything"
        for (message in messages) assertSame(message, EnglishSyncTexts.message(message))
        assertNull(EnglishSyncTexts.message(null))
        assertSame(EnglishSyncTexts, AppTexts.English.sync)
        assertSame(HindiSyncTexts, AppTexts.Hindi.sync)
    }

    private companion object {
        /** Bits of the English messages that a full translation leaves out (the script's own words aside). */
        val ENGLISH_PARTS = listOf(
            "The sheet", "The script", "The web app", "This web app", "Make sure", "Google answered",
            "Google returned", "Google refused", "Google can't", "Can't reach", "Changes are saved", "Remove ",
            "then tap", "Check the", "Add the web app",
        )
    }
}
