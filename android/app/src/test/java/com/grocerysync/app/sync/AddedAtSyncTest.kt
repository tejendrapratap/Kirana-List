package com.grocerysync.app.sync

import com.grocerysync.app.data.Amount
import com.grocerysync.app.data.ItemKeys
import com.grocerysync.app.data.ItemValue.BoolValue
import com.grocerysync.app.data.ItemValue.Quantity
import com.grocerysync.app.data.OpStatus
import com.grocerysync.app.data.OpType
import com.grocerysync.app.data.OutboxOp
import com.grocerysync.app.data.ServerClock
import com.grocerysync.app.data.SyncSettings
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Added At through a real sync round trip: what the app sends and what it keeps from the answers. */
class AddedAtSyncTest {

    private lateinit var server: MockWebServer
    private lateinit var store: FakeSyncStore
    private var settings = SyncSettings("", "tok", "Shop")
    private val clock = ServerClock { 1_000_000L }

    private val engine by lazy { SyncEngine(store, SheetsClient(), { settings }, { "Shop" }, clock, elapsedMs = { 0L }) }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = FakeSyncStore()
        settings = SyncSettings(server.url("/exec").toString(), "tok", "Shop")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    /** As the script with Added At answers a list (contract: "addedAt" only where Added At holds a date). */
    private val listWithAddedAt = """{"ok":true,"version":3,"revision":"r9","serverTime":1794052800000,"spreadsheet":"Groceries",
        "items":[{"name":"Rice","type":"quantity","value":1.5,"unit":"kg","updatedAt":1794052740000,"updatedBy":"Home","addedAt":1794052740000},
                 {"name":"Milk","type":"quantity","value":1,"unit":"L","updatedAt":1794052740000,"updatedBy":"Home","addedAt":1794000000000},
                 {"name":"Bread","type":"boolean","value":true,"updatedAt":1794052740000,"updatedBy":"Home"}]}"""

    @Test
    fun theListsAddedAtIsKept() = runTest {
        server.enqueue(json(listWithAddedAt))
        assertTrue(engine.syncOnce() is SyncOutcome.Success)
        val byName = store.mirror.associateBy { it.name }
        assertEquals(1794052740000L, byName.getValue("Rice").addedAt)
        assertTrue(byName.getValue("Rice").isUnchangedSinceAdded)
        assertEquals(1794000000000L, byName.getValue("Milk").addedAt)
        assertFalse(byName.getValue("Milk").isUnchangedSinceAdded)
        // Rows from before Added At: unknown, "Updated by".
        assertEquals(0L, byName.getValue("Bread").addedAt)
        assertFalse(byName.getValue("Bread").isUnchangedSinceAdded)
    }

    @Test
    fun aScriptWithoutAddedAtLeavesEveryItemUnknown() = runTest {
        // The 1.1 script: no "addedAt" anywhere. Everything reads "Updated by"; nothing else changes.
        server.enqueue(
            json(
                """{"ok":true,"version":3,"revision":"r1","serverTime":1794052800000,"spreadsheet":"Groceries",
                   "items":[{"name":"Rice","type":"quantity","value":1.5,"unit":"kg","updatedAt":1794052740000,"updatedBy":"Home"}]}""",
            ),
        )
        assertTrue(engine.syncOnce() is SyncOutcome.Success)
        val rice = store.mirror.single()
        assertEquals(0L, rice.addedAt)
        assertFalse(rice.isUnchangedSinceAdded)
        assertEquals(Quantity(Amount(1_500L), "kg"), rice.value)
    }

    @Test
    fun theHintOfARenameIsSentAndOtherChangesWithoutOne() = runTest {
        store.outbox += OutboxOp(1, "id-1", OpType.DELETE, "milk", "Milk", null, 1_794_052_790_000L, OpStatus.PENDING)
        store.outbox += OutboxOp(
            2, "id-2", OpType.UPSERT, ItemKeys.normalize("Oat milk"), "Oat milk", Quantity.of(1, "L"), 1_794_052_790_000L, OpStatus.PENDING,
            addedAt = 1794000000000L,
        )
        store.outbox += OutboxOp(3, "id-3", OpType.UPSERT, "bread", "Bread", BoolValue(true), 1_794_052_790_000L, OpStatus.PENDING)
        server.enqueue(
            json(
                """{"ok":true,"version":3,"results":[{"id":"id-1","status":"applied"},{"id":"id-2","status":"applied"},
                   {"id":"id-3","status":"applied"}],"revision":"r2","serverTime":1794052800000,"spreadsheet":"Groceries",
                   "items":[{"name":"Oat milk","type":"quantity","value":1,"unit":"L","updatedAt":1794052790000,"updatedBy":"Shop","addedAt":1794000000000}]}""",
            ),
        )

        assertTrue(engine.syncOnce() is SyncOutcome.Success)

        val changes = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()).getJSONArray("changes")
        assertFalse(changes.getJSONObject(0).has("addedAt"))
        assertEquals(1794000000000L, changes.getJSONObject(1).getLong("addedAt"))
        assertFalse(changes.getJSONObject(2).has("addedAt"))
        assertTrue(store.outbox.isEmpty())
        assertEquals(1794000000000L, store.mirror.single().addedAt)
    }
}
