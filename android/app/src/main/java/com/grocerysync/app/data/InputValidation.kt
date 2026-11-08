package com.grocerysync.app.data

import com.grocerysync.app.text.Texts
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

sealed interface UrlCheck {
    data object Ok : UrlCheck
    data class Warning(val message: String) : UrlCheck
    data class Invalid(val message: String) : UrlCheck
}

/**
 * User input rules; mirror the per-change validation in Code.gs so the server never rejects what the UI allowed.
 *
 * The messages are in the language shown ([Texts.current], ItemTexts): each `ERROR_*` and `URL_*` text is read
 * from there whenever it is used, so [validateName] and [validateWebAppUrl] give the current language's message.
 * Code that only asks whether a value is valid (a message or null) doesn't depend on the language.
 */
object InputValidation {
    const val MAX_NAME_LENGTH = 100

    /** Largest amount (the whole-number part; up to [Amount.DECIMALS] decimals are allowed below it). */
    const val MAX_QUANTITY = 999_999L

    val ERROR_NAME_EMPTY: String get() = Texts.current.item.nameEmpty
    val ERROR_NAME_TOO_LONG: String get() = Texts.current.item.nameTooLong(MAX_NAME_LENGTH)
    val ERROR_NAME_LINE_BREAK: String get() = Texts.current.item.nameLineBreak

    val URL_DEV_WARNING: String get() = Texts.current.item.urlDevWarning
    val URL_HTTP_WARNING: String get() = Texts.current.item.urlHttpWarning
    val URL_NOT_APPS_SCRIPT_WARNING: String get() = Texts.current.item.urlNotAppsScriptWarning
    val URL_EMPTY: String get() = Texts.current.item.urlEmpty
    val URL_BAD_SCHEME: String get() = Texts.current.item.urlBadScheme
    val URL_UNPARSEABLE: String get() = Texts.current.item.urlUnparseable

    private const val APPS_SCRIPT_HOST = "script.google.com"

    /** Returns null when [raw] is a usable item name, else a short message for the text field. */
    fun validateName(raw: String): String? {
        val name = ItemKeys.clean(raw)
        return when {
            name.isEmpty() -> ERROR_NAME_EMPTY
            name.any { it == '\n' || it == '\r' || it == '\u2028' || it == '\u2029' } -> ERROR_NAME_LINE_BREAK
            name.length > MAX_NAME_LENGTH -> ERROR_NAME_TOO_LONG
            else -> null
        }
    }

    /**
     * Parses an amount typed by the user: ASCII digits with at most one decimal separator ('.' or ',', so
     * "1,5" is 1.5) and at most [Amount.DECIMALS] decimals, surrounding spaces allowed; 0..[MAX_QUANTITY].
     */
    fun parseAmount(raw: String): Amount? = Amount.parse(raw)

    /** Shown under the amount field while it can't be read. */
    val ERROR_AMOUNT: String get() = Texts.current.item.amountError(MAX_QUANTITY, Amount.DECIMALS)

    /** Cleans a unit typed by the user the way the sheet stores it (see [Units.normalize]). */
    fun cleanUnit(raw: String): String = Units.normalize(raw)

    fun validateWebAppUrl(raw: String): UrlCheck {
        val text = raw.trim()
        if (text.isEmpty()) return UrlCheck.Invalid(URL_EMPTY)

        val isHttps = text.startsWith("https://", ignoreCase = true)
        val isHttp = text.startsWith("http://", ignoreCase = true)
        if (!isHttps && !isHttp) {
            return UrlCheck.Invalid(if (text.contains("://")) URL_BAD_SCHEME else URL_UNPARSEABLE)
        }
        val url = text.toHttpUrlOrNull() ?: return UrlCheck.Invalid(URL_UNPARSEABLE)
        if (url.host.isEmpty()) return UrlCheck.Invalid(URL_UNPARSEABLE)

        val segments = url.pathSegments
        return when {
            segments.lastOrNull { it.isNotEmpty() } == "dev" -> UrlCheck.Warning(URL_DEV_WARNING)
            !url.isHttps -> UrlCheck.Warning(URL_HTTP_WARNING)
            !isAppsScriptExecUrl(url.host, segments) -> UrlCheck.Warning(URL_NOT_APPS_SCRIPT_WARNING)
            else -> UrlCheck.Ok
        }
    }

    /**
     * Accepts `/macros/s/<id>/exec` and the Google Workspace form `/a/macros/<domain>/s/<id>/exec`.
     */
    private fun isAppsScriptExecUrl(host: String, segments: List<String>): Boolean {
        if (!host.equals(APPS_SCRIPT_HOST, ignoreCase = true)) return false
        val rest = when {
            segments.size == 4 && segments[0] == "macros" -> segments.drop(1)
            segments.size == 6 && segments[0] == "a" && segments[1] == "macros" && segments[2].isNotEmpty() ->
                segments.drop(3)
            else -> return false
        }
        return rest[0] == "s" && rest[1].isNotEmpty() && rest[2] == "exec"
    }
}
