package com.grocerysync.app.sync

import com.grocerysync.app.data.Amount
import com.grocerysync.app.data.ItemValue.Quantity
import com.grocerysync.app.data.OpStatus
import com.grocerysync.app.data.OpType
import com.grocerysync.app.data.ServerClock
import com.grocerysync.app.data.SyncSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

/**
 * Protocol 3 against the sheet's script: changes go only to a script that knows units; an older one
 * (the user hasn't pasted the new Code.gs yet) still serves the list while changes wait on the phone.
 */
class ScriptVersionSyncTest {

    private lateinit var server: MockWebServer
    private lateinit var store: FakeSyncStore
    private var settings = SyncSettings("", "tok", "Home")
    private val clock = ServerClock { 1_000_000L }

    /** Version the mock script answers with; null = a protocol 2 script (no "version" in list / apply). */
    @Volatile
    private var scriptVersion: Int? = 3

    /** Status the mock script gives every change it is sent. */
    @Volatile
    private var changeStatus = """"status":"applied""""

    private val requests: MutableList<Recorded> = Collections.synchronizedList(mutableListOf())

    private data class Recorded(val method: String, val url: okhttp3.HttpUrl, val body: String)

    private fun engine() = SyncEngine(store, SheetsClient(), { settings }, { "Home" }, clock)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                requests += Recorded(request.method!!, request.requestUrl!!, body)
                val version = scriptVersion?.let { """"version":$it,""" }.orEmpty()
                val items = """[{"name":"Rice","type":"${if (scriptVersion == null) "integer" else "quantity"}","value":2,
                    ${if (scriptVersion == null) "" else "\"unit\":\"kg\","} "updatedAt":7,"updatedBy":"Shop"}]"""
                val json = if (request.method == "POST") {
                    val changes = JSONObject(body).getJSONArray("changes")
                    val results = (0 until changes.length()).joinToString(",") {
                        """{"id":"${changes.getJSONObject(it).getString("id")}",$changeStatus}"""
                    }
                    """{"ok":true,$version"results":[$results],"revision":"r-post","serverTime":1,"spreadsheet":"Groceries","items":$items}"""
                } else {
                    val since = request.requestUrl!!.queryParameter("since")
                    if (since == "r-same") {
                        """{"ok":true,$version"revision":"r-same","serverTime":1,"unchanged":true}"""
                    } else {
                        """{"ok":true,$version"revision":"r-same","serverTime":1,"spreadsheet":"Groceries","items":$items}"""
                    }
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(json)
            }
        }
        server.start()
        store = FakeSyncStore()
        settings = SyncSettings(server.url("/exec").toString(), "tok", "Home")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun methods() = requests.map { it.method }

    @Test
    fun firstSyncReadsTheListBeforeSendingAnything() = runTest {
        store.serverVersion = null // just updated: no answer yet
        store.add(OpType.UPSERT, "Rice", Quantity(Amount(1_500L), "kg"))

        val outcome = engine().syncOnce() as SyncOutcome.Success

        assertEquals(listOf("GET", "POST"), methods())
        assertEquals("3", requests[0].url.queryParameter("v"))
        assertEquals(3, JSONObject(requests[1].body).getInt("protocol"))
        assertTrue(store.outbox.isEmpty())
        assertFalse(outcome.scriptOutdated)
        assertEquals(3, store.serverVersion)
    }

    @Test
    fun anOutdatedScriptGetsNoChangesAndTheyWaitOnThePhone() = runTest {
        store.serverVersion = null
        scriptVersion = null
        store.add(OpType.UPSERT, "Rice", Quantity(Amount(1_500L), "kg"))
        store.add(OpType.UPSERT, "Eggs", Quantity.of(12))
        val engine = engine()

        repeat(3) {
            val outcome = engine.syncOnce(push = PushTiming.WHEN_DUE) as SyncOutcome.Success
            assertTrue(outcome.scriptOutdated)
            assertNull(outcome.pushProblem)
        }
        assertTrue(engine.syncOnce(push = PushTiming.NOW).let { it is SyncOutcome.Success && it.scriptOutdated })
        assertTrue(engine.syncOnce(pull = false, push = PushTiming.BACKGROUND).let { it is SyncOutcome.Success && it.scriptOutdated })

        // Every sync read the list (one request each); nothing was ever sent to the old script.
        assertEquals(List(5) { "GET" }, methods())
        assertEquals(listOf("id-1", "id-2"), store.outbox.map { it.id })
        assertTrue(store.outbox.all { it.status == OpStatus.PENDING })
        // Its list still shows: quantities without units.
        assertEquals(Quantity.of(2), store.mirror.single().value)
        assertEquals(2, store.serverVersion)
    }

    @Test
    fun waitingChangesGoOutAsSoonAsTheScriptIsUpdated() = runTest {
        store.serverVersion = 2
        scriptVersion = null
        store.add(OpType.UPSERT, "Rice", Quantity(Amount(1_500L), "kg"))
        val engine = engine()
        assertTrue((engine.syncOnce(push = PushTiming.WHEN_DUE) as SyncOutcome.Success).scriptOutdated)
        assertEquals(listOf("GET"), methods())

        scriptVersion = 3 // the user pasted the new Code.gs and deployed it
        val outcome = engine.syncOnce(push = PushTiming.WHEN_DUE) as SyncOutcome.Success

        assertFalse(outcome.scriptOutdated)
        // "Unchanged" from the new script, but the list on the phone came from the old one: read in full
        // (now with units), then the change goes out.
        assertEquals(listOf("GET", "GET", "GET", "POST"), methods())
        assertEquals(listOf("r-same", null), requests.subList(1, 3).map { it.url.queryParameter("since") })
        assertEquals(Quantity.of(2, "kg"), store.mirror.single().value)
        val change = JSONObject(requests[3].body).getJSONArray("changes").getJSONObject(0)
        assertEquals("kg", change.getString("unit"))
        assertEquals(1.5, change.getDouble("value"), 0.0)
        assertTrue(store.outbox.isEmpty())
    }

    @Test
    fun anOutdatedScriptWithNothingToSendIsJustRead() = runTest {
        store.serverVersion = 2
        scriptVersion = null
        val outcome = engine().syncOnce() as SyncOutcome.Success
        assertTrue(outcome.scriptOutdated)
        assertEquals(listOf("GET"), methods())
    }

    @Test
    fun anOlderScriptAnsweringAnApplyLeavesRefusedChangesWaiting() = runTest {
        // The phone saw protocol 3, then an older deployment answered the push (e.g. it was made live again).
        store.add(OpType.UPSERT, "Rice", Quantity(Amount(1_500L), "kg"))
        scriptVersion = null
        changeStatus = """"status":"error","error":"invalid type""""
        val engine = engine()

        val outcome = engine.syncOnce() as SyncOutcome.Success

        assertTrue(outcome.scriptOutdated)
        assertEquals(OpStatus.PENDING, store.outbox.single().status)
        assertNull(store.outbox.single().error)
        // From now on nothing is sent to it.
        engine.syncOnce()
        assertEquals(listOf("POST", "GET"), methods())
        assertEquals(OpStatus.PENDING, store.outbox.single().status)
    }

    @Test
    fun anOlderScriptAnsweringAnApplyStillConfirmsWhatItApplied() = runTest {
        store.add(OpType.UPSERT, "Eggs", Quantity.of(12))
        scriptVersion = null
        val outcome = engine().syncOnce() as SyncOutcome.Success
        assertTrue(outcome.scriptOutdated)
        assertTrue(store.outbox.isEmpty())
    }

    @Test
    fun unchangedListFromAnotherScriptVersionIsReadInFull() = runTest {
        // The list on the phone came from the old script; the sheet hasn't changed since the update.
        store.serverVersion = 2
        store.rev = "r-same"
        scriptVersion = 3

        engine().syncOnce()

        assertEquals(listOf("r-same", null), requests.map { it.url.queryParameter("since") })
        assertEquals(Quantity.of(2, "kg"), store.mirror.single().value)
        assertEquals(3, store.serverVersion)
    }

    @Test
    fun unchangedListFromTheSameVersionIsNotReadAgain() = runTest {
        store.rev = "r-same"
        engine().syncOnce()
        assertEquals(listOf("r-same"), requests.map { it.url.queryParameter("since") })
    }

    @Test
    fun changesQueuedBeforeTheUpdateKeepTheSheetsUnit() = runTest {
        // An op from the old outbox (database version 1): no unit.
        store.add(OpType.UPSERT, "Rice", Quantity(Amount.whole(3L), unit = null))
        engine().syncOnce()
        val change = JSONObject(requests.single().body).getJSONArray("changes").getJSONObject(0)
        assertEquals("quantity", change.getString("type"))
        assertEquals(3, change.getInt("value"))
        assertFalse(change.has("unit"))
    }

    @Test
    fun backgroundPushFindsOutTheVersionFirst() = runTest {
        store.serverVersion = null
        store.add(OpType.UPSERT, "Rice", Quantity(Amount(500L), "kg"))
        assertTrue(engine().syncOnce(pull = false, push = PushTiming.BACKGROUND) is SyncOutcome.Success)
        assertEquals(listOf("GET", "POST"), methods())
        assertTrue(store.outbox.isEmpty())
    }

    @Test
    fun liveStatusShowsTheOutdatedScriptAndRemembersItWhileOffline() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            scriptVersion = null
            store.serverVersion = 2
            val live = LiveSync(engine(), MutableStateFlow(settings), store, scope, pollIntervalMs = 60_000)
            // Before the first answer, the version stored from the last one says so.
            withTimeout(5_000) { live.status.first { it.scriptOutdated } }

            live.start()
            val status = withTimeout(5_000) { live.status.first { it.state == ConnectionState.LIVE } }
            assertTrue(status.scriptOutdated)

            server.shutdown() // offline
            live.syncNow()
            val offline = withTimeout(5_000) { live.status.first { it.state == ConnectionState.OFFLINE } }
            assertTrue(offline.scriptOutdated)
            live.stop()
        } finally {
            scope.cancel()
        }
    }
}
