package com.grocerysync.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Since 1.2 the app only reads setup codes (Paste setup code); the codes here are made the way
 * "Share setup with another phone" made them in 1.1 and earlier, so the messages people kept still work.
 */
class SetupCodeTest {

    private val url = "https://script.google.com/macros/s/AKfycb-x_9/exec?lang=de&q=a%20b"
    private val token = "s3cr3t/+=?&\"'\\ wörd 🛒 "

    @Test
    fun decodesCodesMadeByVersion11() {
        // Exactly what a 1.1 phone produced (Android's org.json escapes '/' as "\/"), and the same JSON unescaped.
        val expected = "https://script.google.com/macros/s/AKfycbTEST123/exec" to "test-token-1.1"
        assertEquals(expected, SetupCode.decode(V11_CODE_ANDROID_JSON))
        assertEquals(expected, SetupCode.decode(V11_CODE_PLAIN_JSON))
    }

    @Test
    fun decodesTheWholeSetupMessageOfVersion11() {
        val message = "Join our grocery list in Grocery Sync:\n" +
            "1. Install the Grocery Sync app.\n" +
            "2. Copy this whole message.\n" +
            "3. In the app open Settings > Paste setup code, enter your name, tap Save.\n" +
            "\n" +
            V11_CODE_ANDROID_JSON
        assertEquals("https://script.google.com/macros/s/AKfycbTEST123/exec" to "test-token-1.1", SetupCode.decode(message))
    }

    @Test
    fun theAppNoLongerMakesSetupCodes() {
        // The token must not leave the app, so there is nothing that encodes it any more.
        val methods = SetupCode::class.java.declaredMethods.map { it.name }
        assertTrue(methods.contains("decode"))
        assertFalse(methods.toString(), methods.any { it.contains("encode", ignoreCase = true) })
    }

    @Test
    fun roundTripsSpecialCharacters() {
        val code = encodeLikeVersion11(url, token)
        assertEquals(url to token.trim(), SetupCode.decode(code))
    }

    @Test
    fun roundTripsTokenWithoutSurroundingSpaces() {
        assertEquals(url to "abc123", SetupCode.decode(encodeLikeVersion11(url, "abc123")))
    }

    @Test
    fun acceptsPaddedBase64() {
        val json = """{"u":"https://x/exec","t":"tok"}"""
        val padded = Base64.getUrlEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        assertTrue(padded.endsWith("="))
        assertEquals("https://x/exec" to "tok", SetupCode.decode(SetupCode.PREFIX + padded))
    }

    @Test
    fun decodesFromALongerChatMessage() {
        val message = "Join our grocery list in Grocery Sync:\n" +
            "1. Install the Grocery Sync app.\n" +
            "2. Copy this whole message.\n\n" +
            encodeLikeVersion11(url, "tok") + "\nSee you later!"
        assertEquals(url to "tok", SetupCode.decode(message))
    }

    @Test
    fun decodesWhenFollowedByPunctuation() {
        val message = "Code: " + encodeLikeVersion11(url, "tok") + ". Thanks"
        assertEquals(url to "tok", SetupCode.decode(message))
    }

    @Test
    fun skipsACorruptCodeBeforeAValidOne() {
        val message = SetupCode.PREFIX + "%%% and then " + encodeLikeVersion11(url, "tok")
        assertEquals(url to "tok", SetupCode.decode(message))
    }

    @Test
    fun corruptBase64ReturnsNull() {
        assertNull(SetupCode.decode(SetupCode.PREFIX + "!!!!"))
        assertNull(SetupCode.decode(SetupCode.PREFIX + "a"))
        assertNull(SetupCode.decode(SetupCode.PREFIX))
        val truncated = encodeLikeVersion11(url, token).dropLast(7)
        assertNull(SetupCode.decode(truncated))
    }

    @Test
    fun nonJsonPayloadReturnsNull() {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("hello".toByteArray())
        assertNull(SetupCode.decode(SetupCode.PREFIX + payload))
    }

    @Test
    fun missingFieldsReturnNull() {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"u":"https://x/exec"}""".toByteArray())
        assertNull(SetupCode.decode(SetupCode.PREFIX + payload))
        val blankToken = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"u":"https://x/exec","t":"  "}""".toByteArray())
        assertNull(SetupCode.decode(SetupCode.PREFIX + blankToken))
    }

    @Test
    fun missingPrefixReturnsNull() {
        val code = encodeLikeVersion11(url, token)
        assertNull(SetupCode.decode(code.removePrefix(SetupCode.PREFIX)))
        assertNull(SetupCode.decode(code.replace("v1", "v2")))
        assertNull(SetupCode.decode(""))
        assertNull(SetupCode.decode("Just a normal message"))
    }

    companion object {
        /** `{"u":"https:\/\/script.google.com\/macros\/s\/AKfycbTEST123\/exec","t":"test-token-1.1"}` */
        const val V11_CODE_ANDROID_JSON = "grocerysync-setup:v1:" +
            "eyJ1IjoiaHR0cHM6XC9cL3NjcmlwdC5nb29nbGUuY29tXC9tYWNyb3NcL3NcL0FLZnljYlRFU1QxMjNcL2V4ZWMiLCJ0IjoidGVzdC10b2tlbi0xLjEifQ"

        /** `{"u":"https://script.google.com/macros/s/AKfycbTEST123/exec","t":"test-token-1.1"}` */
        const val V11_CODE_PLAIN_JSON = "grocerysync-setup:v1:" +
            "eyJ1IjoiaHR0cHM6Ly9zY3JpcHQuZ29vZ2xlLmNvbS9tYWNyb3Mvcy9BS2Z5Y2JURVNUMTIzL2V4ZWMiLCJ0IjoidGVzdC10b2tlbi0xLjEifQ"

        /** The encoder of 1.1 (SetupCode.encode, removed from the app in 1.2), for test messages only. */
        fun encodeLikeVersion11(webAppUrl: String, token: String): String {
            val json = JSONObject().put("u", webAppUrl).put("t", token).toString()
            return SetupCode.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))
        }
    }
}
