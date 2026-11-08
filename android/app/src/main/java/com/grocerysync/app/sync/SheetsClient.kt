package com.grocerysync.app.sync

import com.grocerysync.app.data.ListLogic
import com.grocerysync.app.data.OutboxOp
import com.grocerysync.app.data.TokenRedaction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

sealed interface PingResult {
    /** [scriptVersion]: the protocol version the sheet's script speaks ([Protocol.versionOf]). */
    data class Ok(val spreadsheetName: String, val scriptVersion: Int = Protocol.VERSION) : PingResult {
        /** The script is older than protocol 3: the app can read the list but holds its changes. */
        val scriptOutdated: Boolean get() = Protocol.isOutdated(scriptVersion)
    }

    data class Failure(val message: String) : PingResult
}

/**
 * HTTP client for the Apps Script web app.
 *
 * `/exec` answers with a 302 to script.googleusercontent.com; OkHttp follows it with a GET (as
 * Google expects, also after a POST) and the final 200 carries the script output. Calls never
 * throw (except for coroutine cancellation): every problem becomes an [ApiResult.Failure].
 * The token travels in the URL or body only; it is never logged, and every text returned here that the
 * app may show (failure messages, per-change errors) has it replaced ([TokenRedaction]): a wrong URL can
 * reach a server that echoes the request into its error page.
 */
class SheetsClient(
    private val http: OkHttpClient = defaultHttpClient(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * For apply: the script may first wait up to 30 s for its lock (another phone's apply), then apply the
     * changes one at a time when the sheet refuses some. A shorter wait would report a working connection
     * as offline and stop the list from updating. Same connection pool and dispatcher as [http].
     */
    private val applyHttp: OkHttpClient by lazy {
        http.newBuilder()
            .readTimeout(atLeast(http.readTimeoutMillis, APPLY_READ_TIMEOUT_MS), TimeUnit.MILLISECONDS)
            .callTimeout(atLeast(http.callTimeoutMillis, APPLY_CALL_TIMEOUT_MS), TimeUnit.MILLISECONDS)
            .build()
    }


    suspend fun ping(webAppUrl: String, token: String): PingResult {
        val base = webAppUrl.trim().toHttpUrlOrNull() ?: return PingResult.Failure(Protocol.MSG_INVALID_URL)
        val url = base.withQuery("action" to "ping", "token" to token.trim(), VERSION_QUERY)
        val result = get(url, token) { json ->
            when (val ping = Protocol.parsePing(json)) {
                is ApiResult.Success -> ApiResult.Success(PingResult.Ok(ping.value, Protocol.versionOf(json)))
                is ApiResult.Failure -> ping
            }
        }
        return when (result) {
            is ApiResult.Success -> result.value
            is ApiResult.Failure -> PingResult.Failure(TokenRedaction.redact(result.message, token))
        }
    }

    suspend fun list(webAppUrl: String, token: String, since: String?): ApiResult<ListResult> {
        val base = webAppUrl.trim().toHttpUrlOrNull() ?: return Protocol.actionNeeded(Protocol.MSG_INVALID_URL)
        val params = buildList {
            add("action" to "list")
            add("token" to token)
            add(VERSION_QUERY)
            if (!since.isNullOrBlank()) add("since" to since)
        }
        return get(base.withQuery(*params.toTypedArray()), token) { Protocol.parseList(it) }.withoutToken(token)
    }

    /** Sends up to [Protocol.MAX_CHANGES_PER_REQUEST] changes in one `apply` request (protocol 3, see [ListLogic.buildApplyRequest]). */
    suspend fun apply(webAppUrl: String, token: String, device: String, changes: List<OutboxOp>): ApiResult<ApplyResult> {
        require(changes.size in 1..Protocol.MAX_CHANGES_PER_REQUEST) { "1..${Protocol.MAX_CHANGES_PER_REQUEST} changes per request" }
        val url = webAppUrl.trim().toHttpUrlOrNull() ?: return Protocol.actionNeeded(Protocol.MSG_INVALID_URL)
        val body = withContext(io) { ListLogic.buildApplyRequest(token, device, changes) }
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .build()
        return when (val result = call(request, applyHttp, token) { Protocol.parseApply(it) }) {
            is ApiResult.Failure -> result.withoutToken(token)
            is ApiResult.Success -> {
                val results = result.value.results
                val clean = results.map { change ->
                    val error = change.error ?: return@map change
                    val redacted = TokenRedaction.redact(error, token)
                    if (redacted == error) change else change.copy(error = redacted)
                }
                if (clean == results) result else ApiResult.Success(result.value.copy(results = clean))
            }
        }
    }

    /** A failure's message with the token replaced (see the class comment); a success as it is. */
    private fun <T> ApiResult<T>.withoutToken(token: String): ApiResult<T> = when (this) {
        is ApiResult.Success -> this
        is ApiResult.Failure -> {
            val clean = TokenRedaction.redact(message, token)
            if (clean == message) this else copy(message = clean)
        }
    }

    private suspend fun <T> get(url: HttpUrl, token: String, parse: (JSONObject) -> ApiResult<T>): ApiResult<T> {
        val request = Request.Builder().url(url).get().header("Accept", "application/json").build()
        return call(request, http, token, parse)
    }

    /**
     * Sends [request] and reads the answer. The token is taken out of the whole answer first ([TokenRedaction]),
     * before anything reads it: the messages made from it are cut short (a page title, an error), and a cut
     * could otherwise land inside an echoed token and leave its first part in view. That also covers what a
     * success shows, such as the spreadsheet name ("Connected to ..." in Settings).
     */
    private suspend fun <T> call(request: Request, client: OkHttpClient, token: String, parse: (JSONObject) -> ApiResult<T>): ApiResult<T> {
        val outcome = execute(request, client)
        return withContext(io) {
            when (outcome) {
                // No response at all: unlike a failure Google answered, a pull would not get through either.
                is HttpOutcome.NetworkFailure -> Protocol.offline()
                is HttpOutcome.Received -> {
                    val body = TokenRedaction.redact(outcome.body, token)
                    when (val result = Protocol.interpretResponse(outcome.code, outcome.contentType, body, outcome.finalHost)) {
                        is ApiResult.Success -> parse(result.value)
                        is ApiResult.Failure -> result
                    }
                }
            }
        }
    }

    /** Runs the call on OkHttp's dispatcher; cancelling the coroutine cancels the HTTP call. */
    private suspend fun execute(request: Request, client: OkHttpClient): HttpOutcome = suspendCancellableCoroutine { cont ->
        val call = try {
            client.newCall(request)
        } catch (e: RuntimeException) {
            cont.resume(HttpOutcome.NetworkFailure(IOException("Could not create call", e)))
            return@suspendCancellableCoroutine
        }
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resume(HttpOutcome.NetworkFailure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                val outcome = try {
                    response.use {
                        HttpOutcome.Received(it.code, it.header("Content-Type"), it.body?.string().orEmpty(), it.request.url.host)
                    }
                } catch (e: IOException) {
                    HttpOutcome.NetworkFailure(e)
                }
                if (cont.isActive) cont.resume(outcome)
            }
        })
    }

    private sealed interface HttpOutcome {
        /** [finalHost] served the response after redirects (accounts.google.com for a sign-in page). */
        class Received(val code: Int, val contentType: String?, val body: String, val finalHost: String) : HttpOutcome
        class NetworkFailure(val cause: IOException) : HttpOutcome
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** `v=3`: the app speaks protocol 3, so the script answers with units and decimals. */
        private val VERSION_QUERY = Protocol.VERSION_PARAM to Protocol.VERSION.toString()

        /** Apply's waits (see applyHttp); Code.gs answers within about 45 s even after waiting for its lock. */
        private const val APPLY_READ_TIMEOUT_MS = 60_000L
        private const val APPLY_CALL_TIMEOUT_MS = 75_000L

        /** [current] raised to [min]; 0 (no time-out) stays 0. */
        private fun atLeast(current: Int, min: Long): Long = if (current == 0) 0L else maxOf(current.toLong(), min)

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()

        private fun HttpUrl.withQuery(vararg params: Pair<String, String>): HttpUrl =
            newBuilder().apply { params.forEach { (name, value) -> setQueryParameter(name, value) } }.build()
    }
}
