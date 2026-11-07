package com.grocerysync.app.sync

import com.grocerysync.app.data.ItemValue.BoolValue
import com.grocerysync.app.data.ItemValue.Quantity
import com.grocerysync.app.data.OpStatus
import com.grocerysync.app.data.OpType
import com.grocerysync.app.data.OutboxOp
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class SheetsClientTest {

    private lateinit var server: MockWebServer
    private lateinit var url: String
    private val client = SheetsClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        url = server.url("/macros/s/DEPLOYMENT/exec").toString()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String, code: Int = 200) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    private fun html(body: String, code: Int = 200) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "text/html; charset=utf-8")
        .setBody(body)

    private val listBody = """
        {"ok":true,"revision":"r1","serverTime":1762500009000,"spreadsheet":"Groceries",
         "items":[{"name":"Milk","type":"integer","value":2,"updatedAt":1762500000000,"updatedBy":"Home"}]}
    """.trimIndent()

    private val changes = listOf(
        OutboxOp(1, "id-1", OpType.UPSERT, "milk", "Milk", Quantity.of(2), 1_762_500_000_000L, OpStatus.PENDING),
        OutboxOp(2, "id-2", OpType.UPSERT, "bread", "Bread", BoolValue(true), 1_762_500_000_100L, OpStatus.PENDING),
        OutboxOp(3, "id-3", OpType.DELETE, "jam", "Jam", null, 1_762_500_000_200L, OpStatus.PENDING),
    )

    // ---- time-outs ----

    @Test
    fun applyWaitsLongerThanReadsForTheScript() = runTest {
        // The script may wait for its lock and apply changes one at a time: slower than a read may be.
        val impatient = SheetsClient(OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).build())
        val slow = 1_200L
        server.enqueue(json(listBody).setHeadersDelay(slow, TimeUnit.MILLISECONDS))
        assertEquals(Protocol.offline(), impatient.list(url, "secret", null))

        server.enqueue(
            json("""{"ok":true,"results":[],"revision":"r2","serverTime":1,"spreadsheet":"Groceries","items":[]}""")
                .setHeadersDelay(slow, TimeUnit.MILLISECONDS),
        )
        assertTrue(impatient.apply(url, "secret", "Home", changes) is ApiResult.Success)
    }

    // ---- ping ----

    @Test
    fun pingOk() = runTest {
        server.enqueue(json("""{"ok":true,"app":"grocery-sync","version":2,"spreadsheet":"Groceries"}"""))

        // Changed with units: the result says which protocol the script speaks (2 = out of date).
        val ping = client.ping(url, " secret ")
        assertEquals(PingResult.Ok("Groceries", scriptVersion = 2), ping)
        assertTrue((ping as PingResult.Ok).scriptOutdated)

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        assertEquals("/macros/s/DEPLOYMENT/exec", request.requestUrl!!.encodedPath)
        assertEquals("ping", request.requestUrl!!.queryParameter("action"))
        assertEquals("secret", request.requestUrl!!.queryParameter("token"))
        assertEquals("3", request.requestUrl!!.queryParameter("v"))
    }

    @Test
    fun pingUnauthorized() = runTest {
        server.enqueue(json("""{"ok":false,"error":"unauthorized"}"""))
        assertEquals(PingResult.Failure(Protocol.MSG_UNAUTHORIZED), client.ping(url, "wrong"))
    }

    @Test
    fun pingNotConfigured() = runTest {
        server.enqueue(json("""{"ok":false,"error":"server_not_configured"}"""))
        assertEquals(PingResult.Failure(Protocol.MSG_NOT_CONFIGURED), client.ping(url, "t"))
    }

    @Test
    fun pingSignInPage() = runTest {
        server.enqueue(html("<!DOCTYPE html><html><body>Sign in - Google Accounts</body></html>"))
        assertEquals(PingResult.Failure(Protocol.MSG_HTML), client.ping(url, "t"))
    }

    @Test
    fun pingInvalidUrl() = runTest {
        assertEquals(PingResult.Failure(Protocol.MSG_INVALID_URL), client.ping("not a url", "t"))
        assertEquals(0, server.requestCount)
    }

    // ---- list ----

    @Test
    fun listSendsSinceAndParsesItems() = runTest {
        server.enqueue(json(listBody))

        val result = (client.list(url, "tok", since = "r0") as ApiResult.Success).value
        assertEquals("r1", result.revision)
        assertEquals("Milk", result.snapshot!!.items.single().name)

        val requestUrl = server.takeRequest(5, TimeUnit.SECONDS)!!.requestUrl!!
        assertEquals("list", requestUrl.queryParameter("action"))
        assertEquals("tok", requestUrl.queryParameter("token"))
        assertEquals("r0", requestUrl.queryParameter("since"))
        assertEquals("3", requestUrl.queryParameter("v"))
    }

    @Test
    fun listWithoutRevisionOmitsSince() = runTest {
        server.enqueue(json(listBody))
        client.list(url, "tok", since = null)
        assertNull(server.takeRequest(5, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("since"))
    }

    @Test
    fun listUnchanged() = runTest {
        server.enqueue(json("""{"ok":true,"revision":"r1","serverTime":5,"unchanged":true}"""))
        val result = (client.list(url, "tok", since = "r1") as ApiResult.Success).value
        assertTrue(result.unchanged)
        assertNull(result.snapshot)
    }

    @Test
    fun specialCharactersInTokenAreEncoded() = runTest {
        server.enqueue(json(listBody))
        client.list(url, "a&b=c d/é", since = null)
        assertEquals("a&b=c d/é", server.takeRequest(5, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("token"))
    }

    // ---- apply ----

    @Test
    fun applyFollowsRedirectWithGet() = runTest {
        val echo = server.url("/macros/echo?user_content_key=abc123&lib=xyz")
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", echo.toString()))
        server.enqueue(
            json(
                """
                {"ok":true,"results":[{"id":"id-1","status":"applied"},{"id":"id-2","status":"stale"},
                  {"id":"id-3","status":"duplicate"}],
                 "revision":"r2","serverTime":1762500010000,"spreadsheet":"Groceries","items":[]}
                """.trimIndent(),
            ),
        )

        val result = (client.apply(url, "tok", "Home", changes) as ApiResult.Success).value
        assertEquals(listOf(ChangeStatus.APPLIED, ChangeStatus.STALE, ChangeStatus.DUPLICATE), result.results.map { it.status })
        assertEquals("r2", result.snapshot.revision)

        val post = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", post.method)
        assertEquals("/macros/s/DEPLOYMENT/exec", post.path)
        assertTrue(post.getHeader("Content-Type")!!.startsWith("application/json"))
        val body = JSONObject(post.body.readUtf8())
        assertEquals("tok", body.getString("token"))
        assertEquals("apply", body.getString("action"))
        assertEquals("Home", body.getString("device"))
        assertEquals(3, body.getJSONArray("changes").length())

        val get = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("GET", get.method)
        assertEquals("/macros/echo?user_content_key=abc123&lib=xyz", get.path)
        assertEquals(0L, get.bodySize)
    }

    @Test
    fun applyUnauthorizedIsAnError() = runTest {
        server.enqueue(json("""{"ok":false,"error":"unauthorized"}"""))
        assertEquals(Protocol.actionNeeded(Protocol.MSG_UNAUTHORIZED), client.apply(url, "bad", "Home", changes))
    }

    @Test
    fun applyBusyIsTransient() = runTest {
        server.enqueue(json("""{"ok":false,"error":"busy"}"""))
        assertEquals(FailureKind.OFFLINE, (client.apply(url, "t", "Home", changes) as ApiResult.Failure).kind)
    }

    // ---- failures ----

    @Test
    fun htmlBodyIsAnError() = runTest {
        server.enqueue(html("<html><head><title>Google Drive</title></head></html>"))
        val failure = client.list(url, "t", null) as ApiResult.Failure
        assertEquals(FailureKind.ERROR, failure.kind)
        assertEquals(FailureSource.SETUP, failure.source)
        // An unknown page is named by its title (it used to be the plain MSG_HTML).
        assertEquals(
            "Google returned a web page (\"Google Drive\") instead of data. " +
                "In Apps Script use Deploy > Manage deployments and set 'Who has access' to 'Anyone'.",
            failure.message,
        )
    }

    @Test
    fun theTokenNeverReachesAMessageEvenWhenTheServerEchoesIt() = runTest {
        // A wrong URL can reach a server that puts the request into its answer; the app shows these texts.
        val token = "s3cret-Token-42"
        server.enqueue(html("<html><head><title>No route for token=$token</title></head></html>"))
        val ping = client.ping(url, " $token ") as PingResult.Failure
        assertFalse(ping.message, ping.message.contains(token))
        assertTrue(ping.message, ping.message.contains("token=(hidden)"))

        server.enqueue(json("""{"ok":false,"error":"teapot: token $token"}"""))
        val list = client.list(url, token, null) as ApiResult.Failure
        assertEquals(
            "The script reported an error: teapot: token (hidden). Make sure the sheet runs the latest Code.gs.",
            list.message,
        )

        server.enqueue(json("""{"ok":false,"error":"internal: bad $token"}"""))
        val apply = client.apply(url, token, "Home", changes) as ApiResult.Failure
        assertFalse(apply.message, apply.message.contains(token))

        server.enqueue(
            json(
                """
                {"ok":true,"results":[{"id":"id-1","status":"applied"},{"id":"id-2","status":"error","error":"x $token y"},
                  {"id":"id-3","status":"error","error":"sheet_rejected: nope"}],
                 "revision":"r2","serverTime":1762500010000,"spreadsheet":"Groceries","items":[]}
                """.trimIndent(),
            ),
        )
        val results = (client.apply(url, token, "Home", changes) as ApiResult.Success).value.results
        assertEquals(listOf(null, "x (hidden) y", Protocol.changeErrorText("sheet_rejected: nope")), results.map { it.error })
    }

    @Test
    fun anEchoedTokenIsHiddenBeforeMessagesAreCutShort() = runTest {
        // A 64-character token echoed past the 80-character limit of a page title: no part of it may show.
        val token = "0123456789abcdef".repeat(4)
        server.enqueue(html("<html><head><title>Bad request /exec?action=ping&token=$token&v=3</title></head></html>"))
        val ping = client.ping(url, token) as PingResult.Failure
        assertFalse(ping.message, ping.message.contains(token.take(12)))

        // Percent-encoded as the app sends it (a token chosen by hand, with + / =).
        val chosen = "my+long/secret=token"
        server.enqueue(json("""{"ok":false,"error":"denied: action=ping&token=my%2Blong%2Fsecret%3Dtoken&v=3"}"""))
        val denied = client.ping(url, chosen) as PingResult.Failure
        assertFalse(denied.message, denied.message.contains("my%2Blong"))
        assertTrue(denied.message, denied.message.contains("token=(hidden)"))
    }

    @Test
    fun anEchoedTokenIsHiddenInWhatASuccessShowsToo() = runTest {
        // The spreadsheet name becomes "Connected to ..." in Settings and the list's title.
        val token = "s3cret-Token-42"
        server.enqueue(json("""{"ok":true,"app":"grocery-sync","version":3,"spreadsheet":"$token"}"""))
        assertEquals(PingResult.Ok("(hidden)", 3), client.ping(url, token))
        server.enqueue(json("""{"ok":true,"version":3,"revision":"r1","serverTime":1,"spreadsheet":"Sheet $token","items":[]}"""))
        val listed = (client.list(url, token, null) as ApiResult.Success).value
        assertEquals("Sheet (hidden)", listed.snapshot?.spreadsheetName)
    }

    @Test
    fun appsScriptErrorPageIsTheScriptsFailure() = runTest {
        server.enqueue(
            html(
                """<!DOCTYPE html><html><head><link rel="shortcut icon" href="//ssl.gstatic.com/docs/script/images/favicon.ico">""" +
                    """<title>Error</title></head><body><div><img alt="Google Apps Script" """ +
                    """src="//ssl.gstatic.com/docs/script/images/logo.png"></div><div style="font-family:monospace">""" +
                    """ReferenceError: SECRET is not defined (line 3, file &quot;Code&quot;)</div></body></html>""",
            ),
        )
        assertEquals(
            Protocol.actionNeeded(
                "The sheet's script failed: ReferenceError: SECRET is not defined (line 3, file \"Code\")",
                FailureSource.SCRIPT,
            ),
            client.apply(url, "t", "Home", changes),
        )
    }

    @Test
    fun noResponseIsMarkedAsSuch() = runTest {
        // However often OkHttp retries, the connection drops without an answer.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        }
        val failure = client.apply(url, "t", "Home", changes) as ApiResult.Failure
        assertEquals(Protocol.offline(), failure)
        assertEquals(FailureSource.NO_RESPONSE, failure.source)
        assertEquals(false, failure.serverAnswered)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                json("""{"ok":false,"error":"internal: Choose Boolean or Integer."}""")
        }
        val answered = client.apply(url, "t", "Home", changes) as ApiResult.Failure
        assertEquals(FailureSource.SCRIPT, answered.source)
        assertEquals("The sheet's script reported an error: Choose Boolean or Integer.", answered.message)
    }

    @Test
    fun serverNotConfiguredIsAnError() = runTest {
        server.enqueue(json("""{"ok":false,"error":"server_not_configured"}"""))
        assertEquals(Protocol.actionNeeded(Protocol.MSG_NOT_CONFIGURED), client.list(url, "t", null))
    }

    @Test
    fun notFoundIsAnError() = runTest {
        server.enqueue(html("<html>Not Found</html>", code = 404))
        assertEquals(Protocol.actionNeeded(Protocol.MSG_NOT_FOUND), client.list(url, "t", null))
    }

    @Test
    fun serverErrorIsTransient() = runTest {
        server.enqueue(html("<html>Error</html>", code = 500))
        assertEquals(FailureKind.OFFLINE, (client.list(url, "t", null) as ApiResult.Failure).kind)
    }

    @Test
    fun malformedOkResponseIsTransient() = runTest {
        server.enqueue(json("""{"ok":true}"""))
        assertEquals(FailureKind.OFFLINE, (client.list(url, "t", null) as ApiResult.Failure).kind)
    }

    @Test
    fun droppedConnectionIsTransient() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(Protocol.offline(), client.list(url, "t", null))
    }

    @Test
    fun connectionRefusedIsTransient() = runTest {
        server.shutdown()
        assertEquals(Protocol.offline(), client.list(url, "t", null))
        assertEquals(PingResult.Failure(Protocol.MSG_OFFLINE), client.ping(url, "t"))
    }
}
