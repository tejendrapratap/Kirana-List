package com.grocerysync.app.ui

import com.grocerysync.app.data.TokenRedaction
import com.grocerysync.app.text.Texts

/** Which secret token Save and Test connection in Settings use. */
enum class TokenSource {
    /** Typed or pasted into the Secret token field just now. */
    TYPED,

    /** From a pasted setup code: kept aside, hidden, until Save. */
    SETUP_CODE,

    /** The saved token; the field is empty. */
    SAVED,

    /** No token yet. */
    NONE,
}

/**
 * Rules of the Secret token field. The token can be typed or pasted but never viewed, copied or shared
 * (user decision for 1.2), so the field only ever holds a NEW token: the saved token and a setup code's
 * token are never put into it. Leaving the field empty keeps the hidden one.
 *
 * Precedence: a token typed into the field, else the one from a pasted setup code, else the saved one.
 * Pure (no Android or Compose), so it is unit-tested. Only [choose] returns a token, and only for Save
 * and Test connection; nothing here returns a token for display.
 *
 * A hidden token only goes to the URL it came with ([sameUrl]): the saved token to the saved Web app URL, a
 * setup code's token to the code's URL. Callers pass a hidden token only while the URL field still holds that
 * URL, so a token nobody can see is never sent to (and echoed back by) a URL typed in afterwards; for a new
 * URL the token has to be typed or pasted again (SettingsTexts.tokenOtherUrl).
 */
internal object TokenEntry {
    /** Stands in for the token in a message shown on screen (see [redact]). */
    const val HIDDEN = TokenRedaction.HIDDEN

    /** Shorter tokens are not redacted: replacing every "ab" in a message would garble it. */
    const val MIN_REDACT_LENGTH = TokenRedaction.MIN_LENGTH

    /** [typed]: the field's text; whitespace only counts as empty, like the trimmed token [choose] returns. */
    fun source(typed: String, setupCodeToken: String?, hasSavedToken: Boolean): TokenSource = when {
        typed.isNotBlank() -> TokenSource.TYPED
        !setupCodeToken.isNullOrBlank() -> TokenSource.SETUP_CODE
        hasSavedToken -> TokenSource.SAVED
        else -> TokenSource.NONE
    }

    /** The token Save and Test connection send, trimmed; "" when there is none. Never for display. */
    fun choose(typed: String, setupCodeToken: String?, savedToken: String): String =
        when (source(typed, setupCodeToken, savedToken.isNotBlank())) {
            TokenSource.TYPED -> typed.trim()
            TokenSource.SETUP_CODE -> setupCodeToken.orEmpty().trim()
            TokenSource.SAVED -> savedToken.trim()
            TokenSource.NONE -> ""
        }

    /**
     * The field's supporting text, in the language shown: says which token will be used, never the token itself.
     * [hiddenTokenForOtherUrl]: a token is saved (or came with a setup code) for another URL than the one in the
     * field, and isn't used. It reads Texts.current, so a derivedStateOf that calls it follows a change of language.
     */
    fun supportingText(
        typed: String,
        setupCodeToken: String?,
        hasSavedToken: Boolean,
        hiddenTokenForOtherUrl: Boolean = false,
    ): String {
        val t = Texts.current.settings
        return when (source(typed, setupCodeToken, hasSavedToken)) {
            TokenSource.TYPED -> when {
                !setupCodeToken.isNullOrBlank() -> t.tokenReplacesSetupCode
                hasSavedToken -> t.tokenReplacesSaved
                else -> t.tokenNew
            }
            TokenSource.SETUP_CODE -> t.tokenFromSetupCode
            TokenSource.SAVED -> t.tokenSaved
            TokenSource.NONE -> if (hiddenTokenForOtherUrl) t.tokenOtherUrl else t.tokenNew
        }
    }

    /**
     * True when the URL field ([fieldUrl]) still holds the URL a hidden token came with ([tokenUrl]: the saved
     * URL, or a setup code's), ignoring spaces around them; false when there is none.
     */
    fun sameUrl(tokenUrl: String?, fieldUrl: String): Boolean =
        tokenUrl != null && tokenUrl.isNotBlank() && tokenUrl.trim() == fieldUrl.trim()

    /** True when a token the field doesn't show is in use (the field then shows a lock). */
    fun usesHiddenToken(source: TokenSource): Boolean =
        source == TokenSource.SAVED || source == TokenSource.SETUP_CODE

    /**
     * [text] with every occurrence of [token] replaced by [HIDDEN], for messages shown on screen. The
     * script never echoes the token, but a wrong URL can reach a server that puts the request into its
     * error page, and Test connection shows parts of such pages. Same rule as the sync messages
     * ([SheetsClient][com.grocerysync.app.sync.SheetsClient] redacts every failure it returns).
     */
    fun redact(text: String, token: String): String = TokenRedaction.redact(text, token)
}
