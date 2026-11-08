package com.grocerysync.app.ui

import com.grocerysync.app.text.AppTexts
import com.grocerysync.app.text.EnglishSettingsTexts
import com.grocerysync.app.text.HindiSettingsTexts
import com.grocerysync.app.text.withTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenEntryTest {

    private val saved = "saved-token-AAAA"
    private val code = "code-token-BBBB"
    private val typed = "typed-token-CCCC"

    @Test
    fun blankFieldKeepsTheSavedToken() {
        assertEquals(TokenSource.SAVED, TokenEntry.source("", null, hasSavedToken = true))
        assertEquals(saved, TokenEntry.choose("", null, saved))
        assertEquals(saved, TokenEntry.choose("   ", null, saved))
        assertEquals(EnglishSettingsTexts.tokenSaved, TokenEntry.supportingText("", null, hasSavedToken = true))
    }

    @Test
    fun aTypedTokenReplacesTheSavedOne() {
        assertEquals(TokenSource.TYPED, TokenEntry.source(typed, null, hasSavedToken = true))
        assertEquals(typed, TokenEntry.choose("  $typed  ", null, saved))
        assertEquals(EnglishSettingsTexts.tokenReplacesSaved, TokenEntry.supportingText(typed, null, hasSavedToken = true))
    }

    @Test
    fun aPastedSetupCodeIsUsedWhileTheFieldIsEmpty() {
        assertEquals(TokenSource.SETUP_CODE, TokenEntry.source("", code, hasSavedToken = true))
        assertEquals(code, TokenEntry.choose("", code, saved))
        assertEquals(code, TokenEntry.choose("", code, ""))
        assertEquals(EnglishSettingsTexts.tokenFromSetupCode, TokenEntry.supportingText("", code, hasSavedToken = false))
    }

    @Test
    fun typingWinsOverTheSetupCodeAndClearingFallsBackToIt() {
        assertEquals(typed, TokenEntry.choose(typed, code, saved))
        assertEquals(EnglishSettingsTexts.tokenReplacesSetupCode, TokenEntry.supportingText(typed, code, hasSavedToken = true))
        assertEquals(code, TokenEntry.choose("", code, saved))
    }

    @Test
    fun noTokenAtAll() {
        assertEquals(TokenSource.NONE, TokenEntry.source("", null, hasSavedToken = false))
        assertEquals(TokenSource.NONE, TokenEntry.source(" ", " ", hasSavedToken = false))
        assertEquals("", TokenEntry.choose("", null, ""))
        assertEquals("", TokenEntry.choose(" ", "", "  "))
        assertEquals(EnglishSettingsTexts.tokenNew, TokenEntry.supportingText("", null, hasSavedToken = false))
        assertEquals(EnglishSettingsTexts.tokenNew, TokenEntry.supportingText(typed, null, hasSavedToken = false))
    }

    @Test
    fun aLockShowsOnlyForAHiddenToken() {
        assertTrue(TokenEntry.usesHiddenToken(TokenSource.SAVED))
        assertTrue(TokenEntry.usesHiddenToken(TokenSource.SETUP_CODE))
        assertFalse(TokenEntry.usesHiddenToken(TokenSource.TYPED))
        assertFalse(TokenEntry.usesHiddenToken(TokenSource.NONE))
    }

    @Test
    fun supportingTextsNeverContainAToken() {
        for (field in listOf("", " ", typed)) {
            for (setupCode in listOf(null, "", code)) {
                for (hasSaved in listOf(false, true)) {
                    val text = TokenEntry.supportingText(field, setupCode, hasSaved)
                    for (secret in listOf(saved, code, typed)) assertFalse(text, text.contains(secret))
                }
            }
        }
    }

    @Test
    fun pasteHelpNoLongerSaysSomeoneSharedIt() {
        assertEquals("Have a setup message? Copy it, then tap Paste setup code.", EnglishSettingsTexts.pasteSetupHelp)
        assertFalse(EnglishSettingsTexts.pasteSetupHelp.contains("share", ignoreCase = true))
        assertFalse(HindiSettingsTexts.pasteSetupHelp, HindiSettingsTexts.pasteSetupHelp.contains("शेयर"))
    }

    @Test
    fun supportingTextsAreInTheLanguageShown() {
        val hindi = HindiSettingsTexts
        withTexts(AppTexts.Hindi) {
            assertEquals(hindi.tokenSaved, TokenEntry.supportingText("", null, hasSavedToken = true))
            assertEquals(hindi.tokenReplacesSaved, TokenEntry.supportingText(typed, null, hasSavedToken = true))
            assertEquals(hindi.tokenFromSetupCode, TokenEntry.supportingText("", code, hasSavedToken = false))
            assertEquals(hindi.tokenReplacesSetupCode, TokenEntry.supportingText(typed, code, hasSavedToken = true))
            assertEquals(hindi.tokenNew, TokenEntry.supportingText("", null, hasSavedToken = false))
            assertEquals(
                hindi.tokenOtherUrl,
                TokenEntry.supportingText("", null, hasSavedToken = false, hiddenTokenForOtherUrl = true),
            )
        }
    }

    @Test
    fun redactReplacesTheTokenInMessages() {
        val message = "Google returned a web page (\"Bad request ?token=$saved&v=3 $saved\") instead of data."
        val redacted = TokenEntry.redact(message, " $saved ")
        assertFalse(redacted, redacted.contains(saved))
        assertEquals(
            "Google returned a web page (\"Bad request ?token=(hidden)&v=3 (hidden)\") instead of data.",
            redacted,
        )
    }

    @Test
    fun aHiddenTokenOnlyGoesToItsOwnUrl() {
        val saved = "https://script.google.com/macros/s/A/exec"
        assertTrue(TokenEntry.sameUrl(saved, saved))
        assertTrue(TokenEntry.sameUrl(saved, "  $saved "))
        assertFalse(TokenEntry.sameUrl(saved, "https://webhook.site/abc"))
        assertFalse(TokenEntry.sameUrl(null, saved))
        assertFalse(TokenEntry.sameUrl("", ""))
        // The view model passes no hidden token for another URL; the help says why the field needs one.
        assertEquals(EnglishSettingsTexts.tokenOtherUrl, TokenEntry.supportingText("", null, hasSavedToken = false, hiddenTokenForOtherUrl = true))
        assertEquals(EnglishSettingsTexts.tokenNew, TokenEntry.supportingText(typed, null, hasSavedToken = false, hiddenTokenForOtherUrl = true))
        assertFalse(EnglishSettingsTexts.tokenOtherUrl.contains(this.saved))
    }

    @Test
    fun redactAlsoReplacesTheTokenAsWrittenInAUrl() {
        val token = "my+long/secret=token &more"
        // As OkHttp writes it in the query (a space as %20), and form-encoded (a space as +).
        val echoed = "denied: action=ping&token=my%2Blong%2Fsecret%3Dtoken%20%26more&v=3 or my%2Blong%2Fsecret%3Dtoken+%26more"
        assertEquals("denied: action=ping&token=(hidden)&v=3 or (hidden)", TokenEntry.redact(echoed, token))
        assertEquals("raw (hidden)", TokenEntry.redact("raw $token", token))
    }

    @Test
    fun redactLeavesMessagesAloneForVeryShortOrNoTokens() {
        val message = "The secret token doesn't match SECRET_TOKEN in the script."
        assertEquals(message, TokenEntry.redact(message, "e"))
        assertEquals(message, TokenEntry.redact(message, "  "))
        assertEquals(message, TokenEntry.redact(message, saved))
    }
}
