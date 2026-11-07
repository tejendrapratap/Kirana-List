package com.grocerysync.app.sync

import com.grocerysync.app.data.GroceryItem
import com.grocerysync.app.data.ItemValue.BoolValue
import com.grocerysync.app.data.ItemValue.Quantity
import com.grocerysync.app.data.ListLogic
import com.grocerysync.app.data.OpStatus
import com.grocerysync.app.data.OpType
import com.grocerysync.app.data.ServerClock
import com.grocerysync.app.data.SyncSettings
import kotlinx.coroutines.test.runTest
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
import java.util.concurrent.atomic.AtomicInteger

class SyncEngineTest {

    private lateinit var server: MockWebServer
    private lateinit var store: FakeSyncStore
    private var settings = SyncSettings("", "tok", "Home")
    private val localTime = 1_000_000L
    private val clock = ServerClock { localTime }

    /** Monotonic time seen by the push backoff. */
    private var elapsed = 0L

    private val engine by lazy { newEngine() }

    private fun newEngine() = SyncEngine(store, SheetsClient(), { settings }, { "Home" }, clock, elapsedMs = { elapsed })

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = FakeSyncStore()
        settings = SyncSettings(server.url("/exec").toString(), "tok", "Home")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun applyResponse(results: String, items: String = "[]", revision: String = "r2", serverTime: Long = 5_000_000L) =
        json(
            """{"ok":true,"version":3,"results":[$results],"revision":"$revision","serverTime":$serverTime,
               "spreadsheet":"Groceries","items":$items}""",
        )

    private fun RecordedRequest.changeIds(): List<String> {
        val changes = JSONObject(body.readUtf8()).getJSONArray("changes")
        return (0 until changes.length()).map { changes.getJSONObject(it).getString("id") }
    }

    @Test
    fun notConfiguredDoesNothing() = runTest {
        settings = SyncSettings("", "", "")
        store.add(OpType.UPSERT, "Milk", Quantity.of(1))
        assertEquals(SyncOutcome.NotConfigured, engine.syncOnce())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun pushSendsCoalescedOpsAndClearsConfirmedOnes() = runTest {
        store.add(OpType.UPSERT, "Milk", Quantity.of(1))
        store.add(OpType.UPSERT, "Bread", BoolValue(true))
        store.add(OpType.UPSERT, "MILK", Quantity.of(3))
        server.enqueue(
            applyResponse(
                results = """{"id":"id-2","status":"applied"},{"id":"id-3","status":"applied"}""",
                items = """[{"name":"Bread","type":"boolean","value":true,"updatedAt":4999000,"updatedBy":"Home"},
                           {"name":"MILK","type":"integer","value":3,"updatedAt":4999000,"updatedBy":"Home"}]""",
            ),
        )

        val outcome = engine.syncOnce()

        assertEquals(SyncOutcome.Success(localTime, "Groceries"), outcome)
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals(listOf("id-2", "id-3"), request.changeIds())
        assertTrue("superseded op id-1 is removed too", store.outbox.isEmpty())
        assertEquals(listOf("Bread", "MILK"), store.mirror.map { it.name })
        assertEquals("r2", store.rev)
        assertEquals(localTime, store.lastSuccess)
        // The apply response carried the list: no extra pull.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun staleAndDuplicateAreDoneErrorsFailAndMissingStayPending() = runTest {
        store.add(OpType.UPSERT, "A", BoolValue(true))
        store.add(OpType.UPSERT, "B", BoolValue(true))
        store.add(OpType.DELETE, "C")
        store.add(OpType.UPSERT, "D", Quantity.of(2))
        server.enqueue(
            applyResponse(
                """{"id":"id-1","status":"stale"},{"id":"id-2","status":"duplicate"},
                   {"id":"id-3","status":"error","error":"name: too long"}""",
            ),
        )
        // Second round re-sends the unanswered op; the server again settles nothing.
        server.enqueue(applyResponse(""))

        assertTrue(engine.syncOnce() is SyncOutcome.Success)

        assertEquals(listOf("id-1", "id-2", "id-3", "id-4"), server.takeRequest(5, TimeUnit.SECONDS)!!.changeIds())
        assertEquals(listOf("id-4"), server.takeRequest(5, TimeUnit.SECONDS)!!.changeIds())
        assertEquals(2, server.requestCount)

        val remaining = store.outbox.associateBy { it.id }
        assertEquals(setOf("id-3", "id-4"), remaining.keys)
        assertEquals(OpStatus.FAILED, remaining.getValue("id-3").status)
        assertEquals("name: too long", remaining.getValue("id-3").error)
        assertEquals(OpStatus.PENDING, remaining.getValue("id-4").status)
    }

    @Test
    fun opsAddedDuringARequestAreNotDeletedByIt() = runTest {
        store.add(OpType.UPSERT, "Milk", Quantity.of(1))
        server.dispatcher = object : Dispatcher() {
            private var calls = 0
            override fun dispatch(request: RecordedRequest): MockResponse {
                calls++
                return if (calls == 1) {
                    // The user edits the same item while the first push is in flight.
                    store.add(OpType.UPSERT, "Milk", Quantity.of(5))
                    applyResponse("""{"id":"id-1","status":"applied"}""")
                } else {
                    MockResponse().setResponseCode(503)
                }
            }
        }

        val outcome = engine.syncOnce()

        assertTrue(outcome is SyncOutcome.Offline)
        assertEquals(listOf("id-2"), store.outbox.map { it.id })
        assertEquals(OpStatus.PENDING, store.outbox.single().status)
        assertEquals(Quantity.of(5), store.outbox.single().value)
    }

    @Test
    fun pullUsesRevisionAndKeepsMirrorWhenUnchanged() = runTest {
        val existing = GroceryItem("milk", "Milk", Quantity.of(2), 1L, "Home", false)
        store.mirror = listOf(existing)
        store.rev = "r1"
        server.enqueue(json("""{"ok":true,"version":3,"revision":"r1","serverTime":5000000,"unchanged":true}"""))

        assertTrue(engine.syncOnce() is SyncOutcome.Success)

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        assertEquals("list", request.requestUrl!!.queryParameter("action"))
        assertEquals("r1", request.requestUrl!!.queryParameter("since"))
        assertEquals(listOf(existing), store.mirror)
        assertEquals(localTime, store.lastSuccess)
    }

    @Test
    fun pullReplacesMirror() = runTest {
        store.mirror = listOf(GroceryItem("old", "Old", BoolValue(true), 1L, "Home", false))
        server.enqueue(
            json(
                """{"ok":true,"version":3,"revision":"r9","serverTime":5000000,"spreadsheet":"Groceries",
                   "items":[{"name":"Eggs","type":"integer","value":6,"updatedAt":7,"updatedBy":"Shop"}]}""",
            ),
        )

        assertEquals(SyncOutcome.Success(localTime, "Groceries"), engine.syncOnce())

        assertNull(server.takeRequest(5, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("since"))
        assertEquals(listOf(GroceryItem("eggs", "Eggs", Quantity.of(6), 7L, "Shop", false)), store.mirror)
        assertEquals("r9", store.rev)
    }

    @Test
    fun errorDuringPushKeepsOutbox() = runTest {
        store.add(OpType.UPSERT, "Milk", Quantity.of(1))
        server.enqueue(json("""{"ok":false,"error":"unauthorized"}"""))

        assertEquals(SyncOutcome.Error(Protocol.MSG_UNAUTHORIZED), engine.syncOnce())
        assertEquals(listOf("id-1"), store.outbox.map { it.id })
        assertEquals(OpStatus.PENDING, store.outbox.single().status)
        assertNull(store.lastSuccess)
    }

    @Test
    fun transientFailureKeepsOutbox() = runTest {
        store.add(OpType.DELETE, "Milk")
        server.enqueue(MockResponse().setResponseCode(500))
        // Google answered the push, so the list is still read; here that fails as well.
        server.enqueue(MockResponse().setResponseCode(500))

        assertTrue(engine.syncOnce() is SyncOutcome.Offline)
        assertEquals(1, store.outbox.size)
        assertEquals("POST", server.takeRequest(5, TimeUnit.SECONDS)!!.method)
        assertEquals("GET", server.takeRequest(5, TimeUnit.SECONDS)!!.method)
    }

    @Test
    fun pushOnlyWithNothingPendingMakesNoRequest() = runTest {
        assertTrue(engine.syncOnce(pull = false) is SyncOutcome.Success)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun sendsAtMostOneHundredChangesPerRequest() = runTest {
        repeat(150) { store.add(OpType.UPSERT, "Item $it", Quantity.of(1)) }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val results = request.changeIds().joinToString(",") { """{"id":"$it","status":"applied"}""" }
                return applyResponse(results)
            }
        }

        assertTrue(engine.syncOnce() is SyncOutcome.Success)
        assertEquals(2, server.requestCount)
        assertTrue(store.outbox.isEmpty())
    }

    @Test
    fun learnsServerClockOffset() = runTest {
        store.add(OpType.UPSERT, "Milk", Quantity.of(1))
        server.enqueue(applyResponse("""{"id":"id-1","status":"applied"}""", serverTime = 5_000_000L))

        engine.syncOnce()

        // Local clock is frozen at 1_000_000, so the whole offset is attributed to clock skew.
        assertEquals(5_000_000L, clock.now())
    }

    // ---- A failed push doesn't stop the pull ----

    private val scriptError get() = json("""{"ok":false,"error":"internal: Choose Boolean or Integer."}""")
    private val scriptProblem = PushProblem(
        "The sheet's script reported an error: Choose Boolean or Integer.",
        failures = 1,
        mayBeMomentary = false,
    )
    private val listBody = """{"ok":true,"version":3,"revision":"r5","serverTime":5000000,"spreadsheet":"Groceries",
        "items":[{"name":"Eggs","type":"integer","value":6,"updatedAt":7,"updatedBy":"Shop"}]}"""

    /** Answers apply (POST) and list (GET) separately and counts both. */
    private class Routes(
        @Volatile var apply: (RecordedRequest) -> MockResponse,
        @Volatile var list: () -> MockResponse,
    ) : Dispatcher() {
        val posts = AtomicInteger()
        val gets = AtomicInteger()

        override fun dispatch(request: RecordedRequest): MockResponse =
            if (request.method == "POST") {
                posts.incrementAndGet()
                apply(request)
            } else {
                gets.incrementAndGet()
                list()
            }
    }

    private fun routes(
        apply: (RecordedRequest) -> MockResponse,
        list: () -> MockResponse = { json(listBody) },
    ) = Routes(apply, list).also { server.dispatcher = it }

    private fun applyAll(request: RecordedRequest): MockResponse =
        applyResponse(request.changeIds().joinToString(",") { """{"id":"$it","status":"applied"}""" })

    @Test
    fun scriptErrorOnPushStillPullsAndReportsTheProblem() = runTest {
        store.add(OpType.UPSERT, "Milk", Quantity.of(1))
        val routes = routes(apply = { scriptError })

        val outcome = engine.syncOnce()

        assertEquals(SyncOutcome.Success(localTime, "Groceries", scriptProblem), outcome)
        assertEquals("POST", server.takeRequest(5, TimeUnit.SECONDS)!!.method)
        assertEquals("GET", server.takeRequest(5, TimeUnit.SECONDS)!!.method)
        assertEquals(1, routes.posts.get())
        assertEquals(1, routes.gets.get())
        // The list was updated; the change is still waiting to be sent.
        assertEquals(listOf("Eggs"), store.mirror.map { it.name })
        assertEquals("r5", store.rev)
        assertEquals(localTime, store.lastSuccess)
        assertEquals(listOf("id-1"), store.outbox.map { it.id })
        assertEquals(OpStatus.PENDING, store.outbox.single().status)
    }

    @Test
    fun everyPushFailureGoogleAnsweredStillPulls() = runTest {
        val cases = listOf(
            { json("""{"ok":false,"error":"busy"}""") } to PushProblem(Protocol.MSG_BUSY, 1, mayBeMomentary = true),
            { MockResponse().setResponseCode(503) } to PushProblem(Protocol.MSG_GOOGLE_TROUBLE, 1, mayBeMomentary = true),
            { json("""{"ok":tru""") } to PushProblem(Protocol.MSG_GOOGLE_TROUBLE, 1, mayBeMomentary = true),
            { json("""{"ok":false,"error":"internal"}""") } to PushProblem(Protocol.MSG_GOOGLE_TROUBLE, 1, mayBeMomentary = true),
            { scriptError } to scriptProblem,
            { json("""{"ok":false,"error":"internal: Too many simultaneous invocations: Spreadsheets"}""") } to PushProblem(
                "The sheet's script reported an error: Too many simultaneous invocations: Spreadsheets.",
                1,
                mayBeMomentary = true,
            ),
            // Retrying alone won't help here: the problem says so (and the warning doesn't promise it).
            { json("""{"ok":false,"error":"bad_request: too many changes (max 100)"}""") } to PushProblem(
                "The script rejected the request (too many changes (max 100)). " +
                    "Make sure the sheet runs the latest Code.gs and deploy a new version.",
                1,
                mayBeMomentary = false,
                needsFix = true,
            ),
        )
        store.add(OpType.UPSERT, "Milk", Quantity.of(1))
        for ((answer, problem) in cases) {
            val routes = routes(apply = { answer() })
            val outcome = newEngine().syncOnce()
            assertEquals(problem.message, SyncOutcome.Success(localTime, "Groceries", problem), outcome)
            assertEquals(problem.message, 1, routes.gets.get())
            assertEquals(OpStatus.PENDING, store.outbox.single().status)
        }
    }

    @Test
    fun failedPullAfterAFailedPushIsTheOutcome() = runTest {
        store.add(OpType.UPSERT, "Milk", Quantity.of(1))
        routes(apply = { scriptError }, list = { MockResponse().setResponseCode(503) })
        assertEquals(SyncOutcome.Offline(Protocol.MSG_GOOGLE_TROUBLE), engine.syncOnce())

        routes(apply = { scriptError }, list = { json("""{"ok":false,"error":"unauthorized"}""") })
        assertEquals(SyncOutcome.Error(Protocol.MSG_UNAUTHORIZED), engine.syncOnce())
        assertEquals(OpStatus.PENDING, store.outbox.single().status)
    }

    @Test
    fun noResponseToThePushIsOfflineWithoutAPull() = runTest {
        store.add(OpType.UPSERT, "Milk", Quantity.of(1))
        val routes = routes(apply = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) })

        assertEquals(SyncOutcome.Offline(Protocol.MSG_OFFLINE), engine.syncOnce())

        assertTrue(routes.posts.get() >= 1)
        assertEquals(0, routes.gets.get())
        assertEquals(OpStatus.PENDING, store.outbox.single().status)
        assertNull(store.lastSuccess)
    }

    @Test
    fun setupProblemOnPushStopsWithoutAPull() = runTest {
        val signInPage = "<html><head><title>Sign in - Google Accounts</title></head><body>Sign in</body></html>"
        val cases = listOf(
            { json("""{"ok":false,"error":"unauthorized"}""") } to Protocol.MSG_UNAUTHORIZED,
            { json("""{"ok":false,"error":"server_not_configured"}""") } to Protocol.MSG_NOT_CONFIGURED,
            { json("""{"status":"success"}""") } to Protocol.MSG_NOT_OUR_SCRIPT,
            { MockResponse().setResponseCode(403) } to Protocol.MSG_ACCESS_DENIED,
            { MockResponse().setResponseCode(404) } to Protocol.MSG_NOT_FOUND,
            { MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(signInPage) } to Protocol.MSG_HTML,
        )
        store.add(OpType.UPSERT, "Milk", Quantity.of(1))
        for ((answer, message) in cases) {
            val routes = routes(apply = { answer() })
            assertEquals(SyncOutcome.Error(message), newEngine().syncOnce())
            assertEquals(message, 0, routes.gets.get())
        }
        assertEquals(OpStatus.PENDING, store.outbox.single().status)

        settings = settings.copy(webAppUrl = "not a url")
        assertEquals(SyncOutcome.Error(Protocol.MSG_INVALID_URL), newEngine().syncOnce())
    }

    @Test
    fun backgroundPushReportsTheFailureWithoutPulling() = runTest {
        store.add(OpType.UPSERT, "Milk", Quantity.of(1))
        val routes = routes(apply = { scriptError })

        assertEquals(SyncOutcome.Offline(scriptProblem.message), engine.syncOnce(pull = false))
        assertEquals(0, routes.gets.get())
    }

    @Test
    fun refusedChangeFailsWithAnExplanationAndTheOthersAreDone() = runTest {
        store.add(OpType.UPSERT, "Milk", Quantity.of(1))
        store.add(OpType.UPSERT, "Bread", BoolValue(true))
        store.add(OpType.DELETE, "Jam")
        val routes = routes(
            apply = {
                applyResponse(
                    """{"id":"id-1","status":"applied"},
                       {"id":"id-2","status":"error",
                        "error":"sheet_rejected: Choose Boolean or Integer. (data validation on the Type column of the Items sheet)"},
                       {"id":"id-3","status":"applied"}""",
                )
            },
        )

        // The apply call itself worked: no push problem, and its response carried the list.
        assertEquals(SyncOutcome.Success(localTime, "Groceries"), engine.syncOnce())

        val failed = store.outbox.single()
        assertEquals("id-2", failed.id)
        assertEquals(OpStatus.FAILED, failed.status)
        assertEquals(
            "The sheet refused this change: Choose Boolean or Integer. (data validation on the Type column of the " +
                "Items sheet). Remove or relax that rule in the sheet (Data > Data validation), then tap Retry.",
            failed.error,
        )
        assertEquals(1, routes.posts.get())
        assertEquals(0, routes.gets.get())
    }

    @Test
    fun confirmedBatchesStayDoneWhenALaterBatchFails() = runTest {
        repeat(150) { store.add(OpType.UPSERT, "Item $it", Quantity.of(1)) }
        val posts = AtomicInteger()
        val routes = routes(apply = { request -> if (posts.incrementAndGet() == 1) applyAll(request) else scriptError })

        val outcome = engine.syncOnce() as SyncOutcome.Success

        assertEquals(scriptProblem, outcome.pushProblem)
        assertEquals(2, routes.posts.get())
        assertEquals(1, routes.gets.get())
        assertEquals(50, store.outbox.size)
        assertTrue(store.outbox.all { it.status == OpStatus.PENDING })
    }

    @Test
    fun deferredChangesStayPendingAndGoInTheNextRequest() = runTest {
        // Code.gs answers "deferred" for changes it had no time left to try (nothing written): send them again.
        store.add(OpType.UPSERT, "Milk", Quantity.of(1))
        store.add(OpType.UPSERT, "Bread", BoolValue(true))
        store.add(OpType.DELETE, "Jam")
        val sent = mutableListOf<List<String>>()
        val routes = routes(
            apply = { request ->
                val ids = request.changeIds()
                val first = synchronized(sent) {
                    sent += ids
                    sent.size == 1
                }
                if (first) {
                    applyResponse(
                        """{"id":"id-1","status":"applied"},{"id":"id-2","status":"deferred"},
                           {"id":"id-3","status":"deferred"}""",
                    )
                } else {
                    applyResponse(ids.joinToString(",") { """{"id":"$it","status":"applied"}""" })
                }
            },
        )

        assertEquals(SyncOutcome.Success(localTime, "Groceries"), engine.syncOnce())

        assertEquals(listOf(listOf("id-1", "id-2", "id-3"), listOf("id-2", "id-3")), synchronized(sent) { sent.toList() })
        assertTrue(store.outbox.isEmpty())
        assertEquals(2, routes.posts.get())
        assertEquals(0, routes.gets.get())
    }

    // ---- Push backoff ----

    @Test
    fun failedPushesBackOffWhilePollsKeepPulling() = runTest {
        store.add(OpType.UPSERT, "Milk", Quantity.of(1))
        val routes = routes(apply = { scriptError })
        suspend fun poll() = engine.syncOnce(push = PushTiming.WHEN_DUE) as SyncOutcome.Success

        assertEquals(scriptProblem, poll().pushProblem)
        var polls = 1
        // Wait after 1, 2, 3, 4, 5 failures in a row: 5, 10, 20, 30 s, then 30 s again.
        for ((index, wait) in listOf(5_000L, 10_000L, 20_000L, 30_000L, 30_000L).withIndex()) {
            val failures = index + 1
            val failedAt = elapsed

            elapsed = failedAt + wait - 1
            val waiting = poll()
            polls++
            assertEquals("still waiting after failure $failures", failures, routes.posts.get())
            assertEquals(scriptProblem.copy(failures = failures), waiting.pushProblem)

            elapsed = failedAt + wait
            val retried = poll()
            polls++
            assertEquals("pushed again after failure $failures", failures + 1, routes.posts.get())
            assertEquals(failures + 1, retried.pushProblem!!.failures)
        }
        assertEquals("every poll still pulled", polls, routes.gets.get())
        assertEquals(OpStatus.PENDING, store.outbox.single().status)
    }

    @Test
    fun forcedPushRestartsTheBackoffAndSuccessEndsIt() = runTest {
        store.add(OpType.UPSERT, "Milk", Quantity.of(1))
        val routes = routes(apply = { scriptError })
        suspend fun poll() = engine.syncOnce(push = PushTiming.WHEN_DUE) as SyncOutcome.Success
        repeat(3) {
            elapsed += 60_000L
            poll()
        }
        assertEquals(3, routes.posts.get()) // the next poll would wait 20 s

        // Retry, pull-to-refresh or a local edit pushes at once; the failures keep counting ...
        elapsed += 1_000L
        assertEquals(scriptProblem.copy(failures = 4), (engine.syncOnce() as SyncOutcome.Success).pushProblem)
        assertEquals(4, routes.posts.get())
        // ... and the backoff starts over at 5 s.
        elapsed += 4_999L
        poll()
        assertEquals(4, routes.posts.get())
        elapsed += 1L
        poll()
        assertEquals(5, routes.posts.get())

        // Once a push goes through, the problem is gone and the next change goes out on the next poll.
        routes.apply = { applyAll(it) }
        elapsed += 10_000L // second failure since the restart: 10 s
        assertNull(poll().pushProblem)
        assertEquals(6, routes.posts.get())
        assertTrue(store.outbox.isEmpty())
        store.add(OpType.UPSERT, "Bread", BoolValue(true))
        elapsed += 1L
        assertNull(poll().pushProblem)
        assertEquals(7, routes.posts.get())
        assertTrue(store.outbox.isEmpty())
    }

    @Test
    fun backgroundPushSkipsTheBackoffWithoutRestartingIt() = runTest {
        store.add(OpType.UPSERT, "Milk", Quantity.of(1))
        val routes = routes(apply = { scriptError })
        suspend fun poll() = engine.syncOnce(push = PushTiming.WHEN_DUE) as SyncOutcome.Success
        repeat(3) {
            elapsed += 60_000L
            poll()
        }
        assertEquals(3, routes.posts.get()) // the next poll would wait 20 s

        // The worker pushes at once (and doesn't pull) ...
        elapsed += 1_000L
        assertEquals(SyncOutcome.Offline(scriptProblem.message), engine.syncOnce(pull = false, push = PushTiming.BACKGROUND))
        assertEquals(4, routes.posts.get())
        // ... but the foreground backoff goes on growing: 30 s now, not 5 s. The worker's attempt is not
        // counted as one of the failures in a row the warning is based on.
        elapsed += 29_999L
        assertEquals(scriptProblem.copy(failures = 3), poll().pushProblem)
        assertEquals(4, routes.posts.get())
        elapsed += 1L
        poll()
        assertEquals(5, routes.posts.get())
    }

    @Test
    fun momentaryPushProblemIsWorthAWarningOnlyWhenItRepeats() = runTest {
        store.add(OpType.UPSERT, "Milk", Quantity.of(1))
        routes(apply = { MockResponse().setResponseCode(503) })

        val first = (engine.syncOnce() as SyncOutcome.Success).pushProblem!!
        assertEquals(PushProblem(Protocol.MSG_GOOGLE_TROUBLE, 1, mayBeMomentary = true), first)
        assertFalse(first.shouldWarn)

        val second = (engine.syncOnce() as SyncOutcome.Success).pushProblem!!
        assertEquals(2, second.failures)
        assertTrue(second.shouldWarn)

        // An error the script reported is worth a warning at once.
        assertTrue(scriptProblem.shouldWarn)
    }

    @Test
    fun googleSideTroubleTheScriptReportsIsWorthAWarningOnlyWhenItRepeats() = runTest {
        store.add(OpType.UPSERT, "Milk", Quantity.of(1))
        val timedOut = "internal: Service Spreadsheets timed out while accessing document with id 1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms."
        routes(apply = { json("""{"ok":false,"error":"$timedOut"}""") })

        val first = (engine.syncOnce() as SyncOutcome.Success).pushProblem!!
        assertEquals(
            PushProblem(
                "The sheet's script reported an error: Service Spreadsheets timed out while accessing document.",
                1,
                mayBeMomentary = true,
            ),
            first,
        )
        assertFalse("one time-out flashes no warning", first.shouldWarn)
        assertTrue("a second one in a row does", (engine.syncOnce() as SyncOutcome.Success).pushProblem!!.shouldWarn)
    }

    @Test
    fun theWorkersPushOfTheSameEditDoesNotMakeAMomentaryFailureWarnAtOnce() = runTest {
        // A local edit starts SyncWorker and the live loop together; both push it within a moment.
        store.add(OpType.UPSERT, "Milk", Quantity.of(1))
        val timedOut = "internal: Service Spreadsheets timed out while accessing document with id 1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms."
        val routes = routes(apply = { json("""{"ok":false,"error":"$timedOut"}""") })

        assertTrue(engine.syncOnce(pull = false, push = PushTiming.BACKGROUND) is SyncOutcome.Offline)
        elapsed += 250L
        val live = (engine.syncOnce() as SyncOutcome.Success).pushProblem!!
        assertEquals(2, routes.posts.get())
        assertEquals(1, live.failures)
        assertFalse("one time-out of one edit flashes no warning", live.shouldWarn)

        // The worker failing after the live loop leaves its count alone too (it still lengthens the
        // backoff: the next poll pushes after 10 s instead of 5 s).
        elapsed += 250L
        assertTrue(engine.syncOnce(pull = false, push = PushTiming.BACKGROUND) is SyncOutcome.Offline)
        assertEquals(live, (engine.syncOnce(push = PushTiming.WHEN_DUE) as SyncOutcome.Success).pushProblem)
        assertEquals(3, routes.posts.get())
        elapsed += 10_000L
        assertEquals(2, (engine.syncOnce(push = PushTiming.WHEN_DUE) as SyncOutcome.Success).pushProblem!!.failures)
        assertEquals(4, routes.posts.get())
    }

    @Test
    fun refusedChangeFailsTogetherWithTheEditsItStoodFor() = runTest {
        // Three taps on Milk's stepper: one change is sent for all three.
        store.add(OpType.UPSERT, "Milk", Quantity.of(1))
        store.add(OpType.UPSERT, "Milk", Quantity.of(2))
        store.add(OpType.UPSERT, "Bread", BoolValue(true))
        store.add(OpType.UPSERT, "Milk", Quantity.of(3))
        val rejected = "sheet_rejected: Choose Boolean or Integer. (data validation on the Type column of the Items sheet)"
        val sent = mutableListOf<List<String>>()
        val routes = routes(
            apply = { request ->
                val ids = request.changeIds()
                synchronized(sent) { sent += ids }
                applyResponse(
                    ids.joinToString(",") {
                        if (it == "id-3") """{"id":"$it","status":"applied"}""" else """{"id":"$it","status":"error","error":"$rejected"}"""
                    },
                )
            },
        )

        assertEquals(SyncOutcome.Success(localTime, "Groceries"), engine.syncOnce())

        // One request: the older Milk edits are not sent one by one afterwards.
        assertEquals(listOf(listOf("id-3", "id-4")), synchronized(sent) { sent.toList() })
        assertEquals(1, routes.posts.get())
        assertEquals(listOf("id-1", "id-2", "id-4"), store.outbox.map { it.id })
        assertTrue(store.outbox.all { it.status == OpStatus.FAILED })
        assertEquals(1, store.outbox.map { it.error }.distinct().size)
        // The banner counts items, not taps.
        assertEquals(listOf("id-4"), ListLogic.failedChanges(store.outbox).map { it.id })
    }

    @Test
    fun problemClearsWhenNothingIsLeftToSend() = runTest {
        val op = store.add(OpType.UPSERT, "Milk", Quantity.of(1))
        val routes = routes(apply = { scriptError })
        assertEquals(scriptProblem, (engine.syncOnce(push = PushTiming.WHEN_DUE) as SyncOutcome.Success).pushProblem)

        // E.g. the change went out another way meanwhile; a poll during the backoff notices.
        store.outbox.remove(op)
        elapsed += 1_000L
        assertNull((engine.syncOnce(push = PushTiming.WHEN_DUE) as SyncOutcome.Success).pushProblem)
        assertEquals(1, routes.posts.get())
    }
}
