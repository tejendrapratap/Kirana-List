package com.grocerysync.app.sync

import com.grocerysync.app.data.ListLogic
import com.grocerysync.app.data.ServerSnapshot
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Result of one call to the web app. */
sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>

    /** [kind] decides retrying; [source] says whether anything answered and how far the problem reaches. */
    data class Failure(val kind: FailureKind, val message: String, val source: FailureSource) : ApiResult<Nothing> {
        /** False when no HTTP response arrived at all (no network, timeout, dropped connection). */
        val serverAnswered: Boolean get() = source != FailureSource.NO_RESPONSE
    }
}

enum class FailureKind {
    /** Temporary (no network, Google hiccup, script busy): retry with backoff. */
    OFFLINE,

    /** Needs the user to fix something (URL, token, deployment): stop retrying. */
    ERROR,
}

/**
 * Where a failure comes from. Decides whether a failed push still lets the pull run (the list keeps
 * updating while changes can't be saved) and how soon a failed push is worth a warning.
 */
enum class FailureSource {
    /** No HTTP response at all: no network, DNS failure, timeout, dropped connection. */
    NO_RESPONSE,

    /** Google answered, but the web app can't be used (URL, token, deployment access, another script): reads fail too. */
    SETUP,

    /**
     * Google answered with a problem that is often momentary: sheet busy, HTTP 5xx/429, cut-off or garbled
     * body, or the script reporting Google-side trouble (a Sheets time-out, a quota, "try again", ...).
     */
    GLITCH,

    /** The script answered this request with an error of its own ("internal: ...", bad_request, an Apps Script error page). */
    SCRIPT,
}

/** [serverVersion]: the protocol version of the script that answered ([Protocol.versionOf]). */
data class ListResult(
    val revision: String,
    val serverTime: Long,
    val unchanged: Boolean,
    val snapshot: ServerSnapshot?,
    val serverVersion: Int,
)

enum class ChangeStatus { APPLIED, STALE, DUPLICATE, ERROR }

data class ChangeResult(val id: String, val status: ChangeStatus, val error: String?)

/** [serverVersion]: the protocol version of the script that answered ([Protocol.versionOf]). */
data class ApplyResult(
    val results: List<ChangeResult>,
    val serverTime: Long,
    val snapshot: ServerSnapshot,
    val serverVersion: Int,
)

/**
 * Android-free interpretation of web app responses. Apps Script can't set HTTP status codes, so
 * success or failure is read from the JSON body; HTTP codes only come from Google's front end.
 */
object Protocol {
    const val APP_ID = "grocery-sync"
    const val MAX_CHANGES_PER_REQUEST = 100

    /**
     * Protocol 3: amounts with decimals and units. The app asks for it with `v=3` (GET) and `"protocol":3`
     * (POST); a script that speaks it answers `"version":3`.
     */
    const val VERSION = ListLogic.PROTOCOL_VERSION

    /** What a script that doesn't say its version speaks: whole-number quantities, no units. */
    const val LEGACY_VERSION = 2

    /** Query parameter that tells the script which protocol the app speaks. */
    const val VERSION_PARAM = "v"

    /**
     * Shown while the sheet runs a script older than protocol 3. Changes wait on the phone until it is
     * updated: an old script would refuse amounts with units or decimals, or lose the units.
     */
    const val MSG_SCRIPT_OUTDATED =
        "The sheet's script is out of date. To use units, paste the latest Code.gs into Apps Script and deploy a new version."

    /** Ends the messages of transient failures. */
    const val SAVED_ON_PHONE = "Changes are saved on this phone and will sync automatically."

    const val MSG_OFFLINE = "Can't reach Google right now. $SAVED_ON_PHONE"
    const val MSG_GOOGLE_TROUBLE = "Google Sheets isn't responding properly right now. $SAVED_ON_PHONE"
    const val MSG_BUSY = "The sheet is busy with other changes. $SAVED_ON_PHONE"
    private const val HINT_ACCESS =
        "In Apps Script use Deploy > Manage deployments and set 'Who has access' to 'Anyone'."
    const val MSG_HTML = "Google returned a web page instead of data. $HINT_ACCESS"
    const val MSG_NO_DOGET =
        "The web app doesn't run the Kirana List script (Code.gs). Paste Code.gs into Apps Script and deploy a new version."
    const val MSG_ACCESS_DENIED = "Google refused access to the web app. $HINT_ACCESS"
    const val MSG_NOT_FOUND = "Web app not found (404). Check the URL, or deploy the script as a Web app."
    const val MSG_DEPLOYMENT_GONE =
        "Google can't open this web app (\"Sorry, unable to open the file at this time\"): the deployment doesn't exist any more. " +
            "In Apps Script open Deploy > Manage deployments, copy the Web app URL (ending in /exec) again and paste it in Settings."
    const val MSG_UNAUTHORIZED = "The secret token doesn't match SECRET_TOKEN in the script."
    const val MSG_NOT_CONFIGURED =
        "The script still has SECRET_TOKEN = 'CHANGE_ME'. Set your own token in Code.gs and deploy a new version."
    const val MSG_INVALID_URL = "The web app URL isn't valid. Check it in Settings."
    const val MSG_NOT_OUR_SCRIPT =
        "This web app isn't the Kirana List script (Code.gs). Paste the latest Code.gs into Apps Script and deploy a new version."

    /** Starts the message of a top-level "internal: <detail>" answer; the detail follows. */
    const val SCRIPT_ERROR_PREFIX = "The sheet's script reported an error: "

    /** Starts the message of an Apps Script error page; the page's error text follows. */
    const val SCRIPT_FAILED_PREFIX = "The sheet's script failed: "

    /** Per-change error code of a change the sheet itself refused (e.g. a data validation rule). */
    const val SHEET_REJECTED = "sheet_rejected"

    private const val MSG_SHEET_REFUSED = "The sheet refused this change"
    private const val HINT_VALIDATION = "Remove or relax that rule in the sheet (Data > Data validation), then tap Retry."
    private const val HINT_PROTECTION =
        "Remove that protection in the sheet (Data > Protect sheets and ranges), then tap Retry."

    private const val MAX_DETAIL_LENGTH = 200
    private const val MAX_CHANGE_ERROR_LENGTH = 300
    private const val MAX_PAGE_TEXT_LENGTH = 150
    private const val MAX_TITLE_LENGTH = 80

    /**
     * Classifies an HTTP response and returns the parsed JSON body when the script answered `ok: true`.
     * [finalHost] is the host that served the response after redirects (accounts.google.com = sign-in page).
     */
    fun interpretResponse(code: Int, contentType: String?, body: String, finalHost: String? = null): ApiResult<JSONObject> {
        val text = body.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        when {
            code == 401 || code == 403 -> return actionNeeded(MSG_ACCESS_DENIED)
            code == 404 -> return actionNeeded(if (isMissingFilePage(text.lowercase())) MSG_DEPLOYMENT_GONE else MSG_NOT_FOUND)
            code == 408 || code == 429 || code >= 500 ->
                return appsScriptErrorText(text)?.let { offline(SCRIPT_FAILED_PREFIX + it, scriptErrorSource(it)) }
                    ?: offline(MSG_GOOGLE_TROUBLE)
            code !in 200..299 -> return actionNeeded("Google answered with HTTP $code. Check the web app URL in Settings.")
        }
        val isHtml = contentType?.contains("text/html", ignoreCase = true) == true
        if (isHtml || !text.startsWith("{")) return interpretPage(text, finalHost)
        val json = try {
            JSONObject(text)
        } catch (e: JSONException) {
            // Truncated or garbled transfer: worth retrying.
            return offline(MSG_GOOGLE_TROUBLE)
        }
        return when (val ok = json.opt("ok")) {
            true -> ApiResult.Success(json)
            false -> mapServerError(json.opt("error") as? String)
            else -> if (ok == null) actionNeeded(MSG_NOT_OUR_SCRIPT) else offline(MSG_GOOGLE_TROUBLE)
        }
    }

    /** Maps the `error` string of an `ok: false` body. */
    fun mapServerError(serverError: String?): ApiResult.Failure {
        val code = serverError.orEmpty().substringBefore(':').trim()
        val detail = cleanText(serverError.orEmpty().substringAfter(':', ""), MAX_DETAIL_LENGTH)
        return when (code) {
            "unauthorized" -> actionNeeded(MSG_UNAUTHORIZED)
            "server_not_configured" -> actionNeeded(MSG_NOT_CONFIGURED)
            "busy" -> offline(MSG_BUSY)
            // The script's own words (e.g. a sheet's data validation message), still retried. Google-side
            // trouble it reports this way (a Sheets time-out, ...) is often gone by the next try.
            "internal" -> {
                val words = detail.replace(DOCUMENT_ID, "").trim()
                if (words.isEmpty()) offline(MSG_GOOGLE_TROUBLE)
                else offline(SCRIPT_ERROR_PREFIX + asSentence(words), scriptErrorSource(words))
            }
            "bad_request" -> actionNeeded(
                "The script rejected the request" + (if (detail.isNotEmpty()) " ($detail)" else "") +
                    ". Make sure the sheet runs the latest Code.gs and deploy a new version.",
                FailureSource.SCRIPT,
            )
            else -> actionNeeded(
                "The script reported an error" + (if (serverError.isNullOrBlank()) "" else ": ${serverError.take(MAX_DETAIL_LENGTH)}") +
                    ". Make sure the sheet runs the latest Code.gs.",
                FailureSource.SCRIPT,
            )
        }
    }

    /**
     * Text stored for a change the server answered with `status: "error"`. "sheet_rejected: <detail>"
     * (the sheet refused the write) becomes an explanation with what to do; other texts are kept.
     */
    fun changeErrorText(serverError: String?): String {
        val error = serverError?.trim()?.takeIf { it.isNotEmpty() } ?: return ListLogic.DEFAULT_FAILURE
        if (error.substringBefore(':').trim() != SHEET_REJECTED) return error
        val detail = cleanText(error.substringAfter(':', ""), MAX_CHANGE_ERROR_LENGTH)
        val hint = if (detail.contains("protect", ignoreCase = true)) HINT_PROTECTION else HINT_VALIDATION
        return if (detail.isEmpty()) "$MSG_SHEET_REFUSED. $hint" else "$MSG_SHEET_REFUSED: ${asSentence(detail)} $hint"
    }

    /** The script's own error text inside a message made for "internal: <detail>" or an Apps Script error page. */
    fun scriptErrorDetail(message: String?): String? = when {
        message == null -> null
        message.startsWith(SCRIPT_ERROR_PREFIX) -> message.removePrefix(SCRIPT_ERROR_PREFIX)
        message.startsWith(SCRIPT_FAILED_PREFIX) -> message.removePrefix(SCRIPT_FAILED_PREFIX)
        else -> null
    }

    /** Ping answer: the spreadsheet name, or a failure when the URL serves some other script. */
    fun parsePing(json: JSONObject): ApiResult<String> {
        if (json.opt("app") != APP_ID) return actionNeeded(MSG_NOT_OUR_SCRIPT)
        return ApiResult.Success(json.optStringOrNull("spreadsheet").orEmpty())
    }

    /**
     * The protocol version an answer comes from: its "version" (a number, or a number in text), else
     * [LEGACY_VERSION]: scripts before protocol 3 only put a version in ping answers.
     */
    fun versionOf(json: JSONObject): Int = when (val version = json.opt("version")) {
        is Number -> version.toInt()
        is String -> version.trim().toIntOrNull()
        else -> null
    } ?: LEGACY_VERSION

    /** True for a script that takes protocol 3 changes (amounts with decimals and units); null = not known yet. */
    fun takesUnits(serverVersion: Int?): Boolean = serverVersion != null && serverVersion >= VERSION

    /** True when a script answered and it is older than protocol 3. */
    fun isOutdated(serverVersion: Int?): Boolean = serverVersion != null && serverVersion < VERSION

    fun parseList(json: JSONObject): ApiResult<ListResult> {
        val revision = json.optStringOrNull("revision")?.takeIf { it.isNotBlank() } ?: return malformed()
        val serverTime = json.optLongOrZero("serverTime")
        val version = versionOf(json)
        if (json.optBoolean("unchanged", false)) {
            return ApiResult.Success(ListResult(revision, serverTime, unchanged = true, snapshot = null, serverVersion = version))
        }
        val items = json.opt("items") as? JSONArray ?: return malformed()
        val snapshot = ServerSnapshot(revision, json.optStringOrNull("spreadsheet"), ListLogic.parseItems(items))
        return ApiResult.Success(ListResult(revision, serverTime, unchanged = false, snapshot = snapshot, serverVersion = version))
    }

    fun parseApply(json: JSONObject): ApiResult<ApplyResult> {
        val revision = json.optStringOrNull("revision")?.takeIf { it.isNotBlank() } ?: return malformed()
        val items = json.opt("items") as? JSONArray ?: return malformed()
        val resultsJson = json.opt("results") as? JSONArray ?: return malformed()
        val results = ArrayList<ChangeResult>(resultsJson.length())
        for (i in 0 until resultsJson.length()) {
            val entry = resultsJson.opt(i) as? JSONObject ?: continue
            val id = entry.optStringOrNull("id") ?: continue
            val status = when (entry.optStringOrNull("status")) {
                "applied" -> ChangeStatus.APPLIED
                "stale" -> ChangeStatus.STALE
                "duplicate" -> ChangeStatus.DUPLICATE
                "error" -> ChangeStatus.ERROR
                else -> continue // unknown status: the op stays pending and is retried
            }
            val error = if (status == ChangeStatus.ERROR) changeErrorText(entry.optStringOrNull("error")) else null
            results += ChangeResult(id, status, error)
        }
        val snapshot = ServerSnapshot(revision, json.optStringOrNull("spreadsheet"), ListLogic.parseItems(items))
        return ApiResult.Success(ApplyResult(results, json.optLongOrZero("serverTime"), snapshot, versionOf(json)))
    }

    /** No HTTP response at all (no network, timeout, dropped connection): retry later. */
    fun offline() = ApiResult.Failure(FailureKind.OFFLINE, MSG_OFFLINE, FailureSource.NO_RESPONSE)

    /** Google or the script answered with a problem worth retrying. */
    fun offline(message: String, source: FailureSource = FailureSource.GLITCH) =
        ApiResult.Failure(FailureKind.OFFLINE, message, source)

    fun actionNeeded(message: String, source: FailureSource = FailureSource.SETUP) =
        ApiResult.Failure(FailureKind.ERROR, message, source)

    /** `ok: true` with an unexpected shape: most likely a cut-off response, so retry. */
    private fun malformed() = offline(MSG_GOOGLE_TROUBLE)

    // ---- Web pages instead of JSON -----------------------------------------------------------

    /**
     * Google answered HTTP 200 with something that isn't JSON. Tells apart the pages Google serves for
     * the usual problems (checked in this order, since a Drive error page can also link to sign-in):
     * - Apps Script "Script function not found: doGet": the URL runs another script.
     * - Drive "Sorry, unable to open the file at this time." / "...file you have requested does not
     *   exist": the deployment was deleted (or the URL is cut short).
     * - Apps Script error page (`<title>Error</title>`, Apps Script logo, the error in a `<div>`): the
     *   script crashed outside its own error handling; its text is shown.
     * - Google sign-in / "You need access": 'Who has access' isn't 'Anyone'.
     * - Anything else: generic text naming the page title.
     */
    private fun interpretPage(page: String, finalHost: String?): ApiResult.Failure {
        val lower = page.lowercase()
        val scriptError = appsScriptErrorText(page)
        return when {
            "script function not found" in lower -> actionNeeded(MSG_NO_DOGET)
            isMissingFilePage(lower) -> actionNeeded(MSG_DEPLOYMENT_GONE)
            scriptError != null -> {
                val message = SCRIPT_FAILED_PREFIX + scriptError
                if (isTransientScriptError(scriptError)) offline(message, FailureSource.GLITCH)
                else actionNeeded(message, FailureSource.SCRIPT)
            }
            isSignInPage(lower, finalHost) -> actionNeeded(MSG_HTML)
            else -> actionNeeded(htmlTitle(page)?.let { "Google returned a web page (\"$it\") instead of data. $HINT_ACCESS" } ?: MSG_HTML)
        }
    }

    private fun isMissingFilePage(lower: String): Boolean =
        "unable to open the file" in lower || "file you have requested does not exist" in lower

    private fun isSignInPage(lower: String, finalHost: String?): Boolean =
        finalHost.equals(GOOGLE_ACCOUNTS_HOST, ignoreCase = true) || SIGN_IN_MARKERS.any { it in lower }

    /** The error text of an Apps Script error page, or null when [page] isn't one. */
    private fun appsScriptErrorText(page: String): String? {
        val isAppsScriptPage = APPS_SCRIPT_MARKER.containsMatchIn(page) ||
            htmlTitle(page).equals("Error", ignoreCase = true)
        if (!isAppsScriptPage) return null
        return cleanText(pageText(page), MAX_PAGE_TEXT_LENGTH).takeIf { it.isNotEmpty() }
    }

    private fun htmlTitle(page: String): String? =
        TITLE.find(page)?.groupValues?.get(1)
            ?.let { cleanText(decodeEntities(it.replace(TAG, " ")), MAX_TITLE_LENGTH) }
            ?.takeIf { it.isNotEmpty() }

    /** Visible text of a page: without head, scripts, styles, comments and tags. */
    private fun pageText(page: String): String = decodeEntities(page.replace(HIDDEN_PARTS, " ").replace(TAG, " "))

    private fun decodeEntities(text: String): String = text
        .replace(NUMERIC_ENTITY) { match ->
            val ref = match.groupValues[1]
            val codePoint = if (ref.startsWith("x", ignoreCase = true)) ref.substring(1).toIntOrNull(16) else ref.toIntOrNull()
            codePoint?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: match.value
        }
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")

    /** One line, at most [maxLength] characters (cut with "..."). */
    private fun cleanText(text: String, maxLength: Int): String {
        val clean = text.replace(WHITESPACE, " ").trim()
        return if (clean.length <= maxLength) clean else clean.take(maxLength - 3).trimEnd() + "..."
    }

    private fun asSentence(text: String): String = if (text.last() in ".!?") text else "$text."

    private const val GOOGLE_ACCOUNTS_HOST = "accounts.google.com"
    private val SIGN_IN_MARKERS = listOf(
        "accounts.google.com", "servicelogin", "signin", "sign in", "sign-in",
        "you need access", "you need permission", "request access",
    )
    private val WHITESPACE = Regex("""\s+""")
    private val TAG = Regex("""<[^>]*>""")
    private val TITLE = Regex("""<title[^>]*>(.*?)</title\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val HIDDEN_PARTS = Regex(
        """<(head|script|style|noscript)\b[^>]*>.*?</\1\s*>|<!--.*?-->""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val NUMERIC_ENTITY = Regex("""&#([xX][0-9a-fA-F]+|[0-9]+);""")

    /** Apps Script error pages show its logo and favicon from ssl.gstatic.com/docs/script/images/. */
    private val APPS_SCRIPT_MARKER = Regex("""docs/script/images/|alt\s*=\s*["']Google Apps Script["']""", RegexOption.IGNORE_CASE)

    /**
     * Apps Script runtime errors that usually go away by themselves (quotas, time-outs, Google-side errors),
     * e.g. "Service Spreadsheets timed out while accessing document with id ...", "Too many simultaneous
     * invocations: Spreadsheets", "We're sorry, a server error occurred. Please wait a bit and try again.",
     * "Unexpected error while getting the method or property ...". Code.gs uses the same list.
     */
    private val TRANSIENT_SCRIPT_ERROR = Regex(
        listOf(
            "try again", "please wait", "timed out", "time-?out", "too many", "unavailable", "rate limit", "quota",
            "maximum execution time", "simultaneous invocations", "server error", "service error", "internal error",
            "backend error", "failed while accessing", "temporar", "unexpected error", "unknown error",
        ).joinToString("|"),
        RegexOption.IGNORE_CASE,
    )

    /** "... with id 1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms": the spreadsheet's id says nothing to the user. */
    private val DOCUMENT_ID = Regex("""\s+with id\s+[\w-]{10,}""", RegexOption.IGNORE_CASE)

    private fun isTransientScriptError(text: String): Boolean = TRANSIENT_SCRIPT_ERROR.containsMatchIn(text)

    /** Google-side trouble the script reports is often momentary; anything else is the script's (or sheet's) own error. */
    private fun scriptErrorSource(text: String): FailureSource =
        if (isTransientScriptError(text)) FailureSource.GLITCH else FailureSource.SCRIPT

    private fun JSONObject.optStringOrNull(name: String): String? = opt(name) as? String

    private fun JSONObject.optLongOrZero(name: String): Long = (opt(name) as? Number)?.toLong() ?: 0L
}
