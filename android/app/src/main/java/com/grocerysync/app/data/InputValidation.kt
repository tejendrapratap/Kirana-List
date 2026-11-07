package com.grocerysync.app.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

sealed interface UrlCheck {
    data object Ok : UrlCheck
    data class Warning(val message: String) : UrlCheck
    data class Invalid(val message: String) : UrlCheck
}

/** User input rules; mirror the per-change validation in Code.gs so the server never rejects what the UI allowed. */
object InputValidation {
    const val MAX_NAME_LENGTH = 100

    /** Largest amount (the whole-number part; up to [Amount.DECIMALS] decimals are allowed below it). */
    const val MAX_QUANTITY = 999_999L

    const val ERROR_NAME_EMPTY = "Enter an item name"
    const val ERROR_NAME_TOO_LONG = "Keep it under 100 characters"
    const val ERROR_NAME_LINE_BREAK = "Item name can't contain line breaks"

    const val URL_DEV_WARNING =
        "This is a test (/dev) URL. Use the Web app URL ending in /exec from Deploy > Manage deployments."
    const val URL_HTTP_WARNING = "Not secure - only use http:// for local testing."
    const val URL_NOT_APPS_SCRIPT_WARNING =
        "This doesn't look like an Apps Script web app URL (it should end in /exec)."
    const val URL_EMPTY = "Paste the Web app URL from Apps Script"
    const val URL_BAD_SCHEME = "The URL must start with https://"
    const val URL_UNPARSEABLE = "That isn't a valid URL"

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
    const val ERROR_AMOUNT = "Enter an amount from 0 to 999999, up to 3 decimals"

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
