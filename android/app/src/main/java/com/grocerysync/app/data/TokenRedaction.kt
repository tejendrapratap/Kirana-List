package com.grocerysync.app.data

import java.net.URLEncoder

/**
 * Keeps the secret token out of text the app shows (status line, banners, failed changes, Test connection).
 * The token can be typed or pasted but never viewed (user decision for 1.2). Code.gs never echoes it, but a
 * wrong URL can reach a server that puts the request into its error page or answer, and the app shows
 * parts of those. Pure, so it is unit-tested.
 */
internal object TokenRedaction {
    /** Stands in for the token in a message (see [redact]). */
    const val HIDDEN = "(hidden)"

    /** Shorter tokens are not redacted: replacing every "ab" in a message would garble it. */
    const val MIN_LENGTH = 4

    /**
     * [text] with every occurrence of [token] (trimmed) replaced by [HIDDEN]: as it is, and as it is written in a
     * URL, which is how a server that echoes the request shows it. The app sends the token as a query parameter
     * (OkHttp percent-encodes it, a space as %20) or in a JSON body; a form-encoded spelling (a space as +) is
     * covered too. Tokens made by generateToken are letters and digits only, so all spellings are the same.
     */
    fun redact(text: String, token: String): String {
        val secret = token.trim()
        if (secret.length < MIN_LENGTH) return text
        var result = text
        for (spelling in spellings(secret)) {
            if (spelling in result) result = result.replace(spelling, HIDDEN)
        }
        return result
    }

    /** [secret] as it is, form-encoded, and percent-encoded as in an OkHttp query (form encoding, but %20 for a space). */
    private fun spellings(secret: String): List<String> {
        val form = URLEncoder.encode(secret, Charsets.UTF_8.name())
        return listOf(secret, form, form.replace("+", "%20")).distinct()
    }
}
