package com.grocerysync.app.sync

import com.grocerysync.app.data.ItemValue.BoolValue
import com.grocerysync.app.data.ItemValue.Quantity
import com.grocerysync.app.data.OpType
import com.grocerysync.app.data.ServerClock
import com.grocerysync.app.data.SyncSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Runs the real polling loop (real time, short intervals) against a local mock web app. */
class LiveSyncTest {

    private lateinit var server: MockWebServer
    private lateinit var settings: MutableStateFlow<SyncSettings>
    private val store = FakeSyncStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        settings = MutableStateFlow(configured())
        respond { request ->
            if (request.method == "POST") applyAll(request) else json(LIST_BODY)
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun configured() = SyncSettings(server.url("/exec").toString(), "tok", "Home")

    private fun liveSync(pollIntervalMs: Long) = LiveSync(
        engine = SyncEngine(store, SheetsClient(), { settings.value }, { "Home" }, ServerClock()),
        settings = settings,
        store = store,
        scope = scope,
        pollIntervalMs = pollIntervalMs,
    )

    private fun respond(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = handler(request)
        }
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun applyAll(request: RecordedRequest): MockResponse {
        val changes = JSONObject(request.body.readUtf8()).getJSONArray("changes")
        val results = (0 until changes.length()).joinToString(",") {
            """{"id":"${changes.getJSONObject(it).getString("id")}","status":"applied"}"""
        }
        return json("""{"ok":true,"version":3,"results":[$results],"revision":"r2","serverTime":1,"spreadsheet":"Groceries","items":[]}""")
    }

    private suspend fun LiveSync.awaitState(state: ConnectionState): SyncStatus =
        withTimeout(5_000) { status.first { it.state == state } }

    private suspend fun awaitRequests(count: Int) =
        withTimeout(5_000) { while (server.requestCount < count) delay(10) }

    @Test
    fun becomesLiveKeepsPollingAndStopsOnStop() = runBlocking {
        val live = liveSync(pollIntervalMs = 50)
        live.start()
        live.start() // idempotent

        val status = live.awaitState(ConnectionState.LIVE)
        assertEquals("Groceries", status.spreadsheetName)
        assertNotNull(status.lastSuccessAt)
        awaitRequests(3)

        live.stop()
        delay(200) // let a request that was already on the wire arrive
        val afterStop = server.requestCount
        delay(400)
        assertEquals(afterStop, server.requestCount)
    }

    @Test
    fun errorStopsPollingUntilSyncNow() = runBlocking {
        respond { json("""{"ok":false,"error":"unauthorized"}""") }
        val live = liveSync(pollIntervalMs = 30)
        live.start()

        val status = live.awaitState(ConnectionState.ERROR)
        assertEquals(Protocol.MSG_UNAUTHORIZED, status.message)
        delay(300)
        assertEquals(1, server.requestCount)

        respond { json(LIST_BODY) }
        live.syncNow()
        live.awaitState(ConnectionState.LIVE)
        assertEquals(2, server.requestCount)
        live.stop()
    }

    @Test
    fun localEditIsPushedWithoutWaitingForThePoll() = runBlocking {
        val live = liveSync(pollIntervalMs = 60_000)
        live.start()
        live.awaitState(ConnectionState.LIVE)
        awaitRequests(1)

        store.add(OpType.UPSERT, "Milk", Quantity.of(2))
        live.nudge()

        withTimeout(5_000) { while (store.loadOutbox().isNotEmpty()) delay(10) }
        assertEquals(2, server.requestCount)
        server.takeRequest() // initial pull
        assertEquals("POST", server.takeRequest().method)
        live.stop()
    }

    @Test
    fun syncNowCompletesWhenTheManualSyncEndsEvenIfTheStatusLooksTheSame() = runBlocking {
        // Every attempt fails the same way, so the status after a manual sync equals the one before.
        respond { MockResponse().setResponseCode(503) }
        val live = liveSync(pollIntervalMs = 60_000)
        live.start()
        val before = live.awaitState(ConnectionState.OFFLINE)
        val requestsBefore = server.requestCount

        val done = live.syncNow()
        withTimeout(2_000) { done.join() } // the offline backoff (5 s) is cut short
        assertEquals(requestsBefore + 1, server.requestCount)
        assertEquals(before, live.status.value)

        // Also while stopped on an error that needs the user.
        respond { json("""{"ok":false,"error":"unauthorized"}""") }
        withTimeout(2_000) { live.syncNow().join() }
        live.awaitState(ConnectionState.ERROR)
        withTimeout(2_000) { live.syncNow().join() }
        live.stop()
    }

    @Test
    fun syncNowCompletesWithoutTheLoopAndWhenNotConfigured() = runBlocking {
        val live = liveSync(pollIntervalMs = 60_000)
        withTimeout(2_000) { live.syncNow().join() }
        assertEquals(ConnectionState.LIVE, live.status.value.state)
        assertEquals(1, server.requestCount)

        settings.value = SyncSettings("", "", "")
        assertTrue(live.syncNow().isCompleted)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun waitsForSettingsThenConnects() = runBlocking {
        settings.value = SyncSettings("", "", "")
        val live = liveSync(pollIntervalMs = 50)
        assertEquals(ConnectionState.NOT_CONFIGURED, live.status.value.state)

        live.start()
        delay(200)
        assertEquals(0, server.requestCount)
        assertEquals(ConnectionState.NOT_CONFIGURED, live.status.value.state)

        settings.value = configured()
        live.awaitState(ConnectionState.LIVE)
        live.stop()
    }

    @Test
    fun failingPushKeepsTheListLiveWithTheReasonAndBacksOff() = runBlocking {
        val posts = AtomicInteger()
        val gets = AtomicInteger()
        val pushWorks = AtomicBoolean(false)
        respond { request ->
            if (request.method == "POST") {
                posts.incrementAndGet()
                if (pushWorks.get()) applyAll(request) else json("""{"ok":false,"error":"internal: Choose Boolean or Integer."}""")
            } else {
                gets.incrementAndGet()
                json(LIST_BODY)
            }
        }
        store.add(OpType.UPSERT, "Milk", Quantity.of(2))
        val live = liveSync(pollIntervalMs = 30)
        live.start()

        val status = withTimeout(5_000) { live.status.first { it.pushProblem != null } }
        assertEquals(ConnectionState.LIVE, status.state)
        assertEquals("The sheet's script reported an error: Choose Boolean or Integer.", status.pushProblem!!.message)
        assertTrue(status.pushProblem!!.shouldWarn)

        // Polls keep reading the list; the push waits out its backoff (5 s) instead of going out every poll.
        val getsBefore = gets.get()
        delay(500)
        assertTrue("polls kept pulling", gets.get() >= getsBefore + 3)
        assertEquals(1, posts.get())
        assertEquals(ConnectionState.LIVE, live.status.value.state)
        assertNotNull(live.status.value.pushProblem)

        // Retry pushes at once.
        withTimeout(2_000) { live.syncNow().join() }
        assertEquals(2, posts.get())
        assertNotNull(live.status.value.pushProblem)

        // So does a new local edit; once the push goes through the problem is gone.
        pushWorks.set(true)
        store.add(OpType.UPSERT, "Bread", BoolValue(true))
        live.nudge()
        withTimeout(5_000) { live.status.first { it.state == ConnectionState.LIVE && it.pushProblem == null } }
        assertEquals(3, posts.get())
        assertTrue(store.loadOutbox().isEmpty())
        live.stop()
    }

    @Test
    fun retryIsNeverLostWhenItCoincidesWithThePollTimer() = runBlocking {
        // A 1 ms poll makes a Retry arrive just as the wait times out many times over. (Before the
        // timeout became part of the select, such a request was dropped and syncNow never finished.)
        val posts = AtomicInteger()
        respond { request ->
            if (request.method == "POST") {
                posts.incrementAndGet()
                json("""{"ok":false,"error":"internal: Choose Boolean or Integer."}""")
            } else {
                json(LIST_BODY)
            }
        }
        store.add(OpType.UPSERT, "Milk", Quantity.of(2))
        val live = liveSync(pollIntervalMs = 1)
        live.start()
        withTimeout(5_000) { live.status.first { it.pushProblem != null } }

        repeat(60) { attempt ->
            delay((attempt % 5).toLong())
            val before = posts.get()
            withTimeout(2_000) { live.syncNow().join() }
            assertTrue("Retry $attempt pushed", posts.get() > before)
        }
        live.stop()
    }

    @Test
    fun pushWithoutAnyAnswerIsJustOffline() = runBlocking {
        respond { request ->
            if (request.method == "POST") MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) else json(LIST_BODY)
        }
        store.add(OpType.UPSERT, "Milk", Quantity.of(2))
        val live = liveSync(pollIntervalMs = 60_000)
        live.start()

        val status = live.awaitState(ConnectionState.OFFLINE)
        assertEquals(Protocol.MSG_OFFLINE, status.message)
        assertNull(status.pushProblem)
        assertTrue(server.requestCount >= 1)
        live.stop()
    }

    private companion object {
        const val LIST_BODY =
            """{"ok":true,"version":3,"revision":"r1","serverTime":1,"spreadsheet":"Groceries","items":[]}"""
    }
}
