package com.grocerysync.app.data

import org.json.JSONException
import org.json.JSONObject
import java.util.Base64

/**
 * Reads the setup code inside a setup message, so a phone can be set up with Settings > Paste setup code.
 * Format: [PREFIX] + base64url (padding optional) of the UTF-8 JSON `{"u": web app URL, "t": token}`.
 *
 * Since 1.2 the app only reads codes: it never makes one, because the token must not leave the app
 * (user decision). Setup messages made by "Share setup with another phone" in 1.1 and earlier keep working.
 */
object SetupCode {
    const val PREFIX = "grocerysync-setup:v1:"

    /** Finds the first decodable code anywhere in [text]; returns (url, token) or null. */
    fun decode(text: String): Pair<String, String>? {
        var from = 0
        while (true) {
            val start = text.indexOf(PREFIX, from)
            if (start < 0) return null
            val payloadStart = start + PREFIX.length
            var end = payloadStart
            while (end < text.length && isBase64UrlChar(text[end])) end++
            decodePayload(text.substring(payloadStart, end))?.let { return it }
            from = payloadStart
        }
    }

    private fun decodePayload(payload: String): Pair<String, String>? {
        val unpadded = payload.trimEnd('=')
        if (unpadded.isEmpty()) return null
        return try {
            val json = JSONObject(String(Base64.getUrlDecoder().decode(unpadded), Charsets.UTF_8))
            val url = (json.opt("u") as? String)?.trim().orEmpty()
            val token = (json.opt("t") as? String)?.trim().orEmpty()
            if (url.isEmpty() || token.isEmpty()) null else url to token
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: JSONException) {
            null
        }
    }

    private fun isBase64UrlChar(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '='
}
