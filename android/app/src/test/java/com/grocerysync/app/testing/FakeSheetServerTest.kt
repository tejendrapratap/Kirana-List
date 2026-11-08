package com.grocerysync.app.testing

import com.grocerysync.app.data.Amount
import com.grocerysync.app.data.ItemKeys
import com.grocerysync.app.data.ItemValue
import com.grocerysync.app.data.OpStatus
import com.grocerysync.app.data.OpType
import com.grocerysync.app.data.OutboxOp
import com.grocerysync.app.data.ServerClock
import com.grocerysync.app.data.SyncSettings
import com.grocerysync.app.data.Units
import com.grocerysync.app.sync.ApiResult
import com.grocerysync.app.sync.ApplyResult
import com.grocerysync.app.sync.ChangeStatus
import com.grocerysync.app.sync.FailureSource
import com.grocerysync.app.sync.FakeSyncStore
import com.grocerysync.app.sync.ListResult
import com.grocerysync.app.sync.PingResult
import com.grocerysync.app.sync.Protocol
import com.grocerysync.app.sync.SheetsClient
import com.grocerysync.app.sync.SyncEngine
import com.grocerysync.app.sync.SyncOutcome
import com.grocerysync.app.testing.FakeSheetServer.Companion.HOME
import com.grocerysync.app.testing.FakeSheetServer.Companion.quantity
import com.grocerysync.app.testing.FakeSheetServer.Companion.yesNo
import com.grocerysync.app.testing.FakeSheetServer.Need
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * The fake web app the UI tests sync with, checked against the app's real SheetsClient and SyncEngine, so the UI
 * tests rest on a fake that answers the way the app expects Code.gs to.
 */
class FakeSheetServerTest {

    private var now = 1_794_052_800_000L
    private lateinit var fake: FakeSheetServer
    private val client = SheetsClient()

    @Before
    fun setUp() {
        fake = FakeSheetServer(clock = { now }).start()
    }

    @After
    fun tearDown() {
        fake.close()
    }

    private fun op(type: OpType, name: String, value: ItemValue?, time: Long = now, addedAt: Long = 0L) = OutboxOp(
        seq = 0L,
        id = UUID.randomUUID().toString(),
        op = type,
        key = ItemKeys.normalize(name),
        name = name,
        value = value,
        time = time,
        status = OpStatus.PENDING,
        addedAt = addedAt,
    )

    private suspend fun apply(vararg ops: OutboxOp): ApplyResult {
        val result = client.apply(fake.url, fake.token, "Shop", ops.toList())
        assertTrue("apply failed: $result", result is ApiResult.Success)
        return (result as ApiResult.Success).value
    }

    private suspend fun list(since: String? = null): ListResult {
        val result = client.list(fake.url, fake.token, since)
        assertTrue("list failed: $result", result is ApiResult.Success)
        return (result as ApiResult.Success).value
    }

    @Test
    fun pingNamesTheSheetAndChecksTheToken() = runTest {
        assertEquals(PingResult.Ok(FakeSheetServer.DEFAULT_SPREADSHEET, Protocol.VERSION), client.ping(fake.url, fake.token))
        assertEquals(PingResult.Failure(Protocol.MSG_UNAUTHORIZED), client.ping(fake.url, "wrong-token-123"))
        assertEquals(2, fake.pingRequests)
    }

    @Test
    fun listSendsUnitsAndAddedAtAndSaysUnchanged() = runTest {
        fake.seed("Rice", quantity("1.5", Units.KG), agoMs = 60_000L)
        fake.seed("Bread", yesNo(true), addedAtKnown = false)

        val first = list()
        assertEquals(Protocol.VERSION, first.serverVersion)
        assertEquals(now, first.serverTime)
        val byName = first.snapshot!!.items.associateBy { it.name }
        assertEquals(ItemValue.Quantity(Amount(1_500L), Units.KG), byName.getValue("Rice").value)
        assertEquals(HOME, byName.getValue("Rice").updatedBy)
        assertTrue(byName.getValue("Rice").isUnchangedSinceAdded)
        assertEquals(ItemValue.BoolValue(true), byName.getValue("Bread").value)
        assertEquals(0L, byName.getValue("Bread").addedAt)

        assertTrue(list(since = first.revision).unchanged)
        fake.upsertAs(HOME, "Bread", yesNo(false))
        val changed = list(since = first.revision)
        assertFalse(changed.unchanged)
        assertEquals(ItemValue.BoolValue(false), changed.snapshot!!.items.first { it.name == "Bread" }.value)
    }

    @Test
    fun applyFollowsTheScriptsRules() = runTest {
        val bread = op(OpType.UPSERT, "Bread", ItemValue.BoolValue(true))
        assertEquals(ChangeStatus.APPLIED, apply(bread).results.single().status)
        val stored = fake.item("bread")!!
        assertEquals(Need.YesNo(true), stored.need)
        assertEquals("Shop", stored.updatedBy)
        assertEquals(now, stored.addedAt)

        // Sent again (the answer was lost): de-duplicated by its id.
        assertEquals(ChangeStatus.DUPLICATE, apply(bread).results.single().status)
        // Older than what the sheet has: stale.
        assertEquals(ChangeStatus.STALE, apply(op(OpType.UPSERT, "Bread", ItemValue.BoolValue(false), time = now - 1)).results.single().status)

        now += 1_000L
        val deleted = apply(op(OpType.DELETE, "bread", null))
        assertEquals(ChangeStatus.APPLIED, deleted.results.single().status)
        assertNull(fake.item("Bread"))
        assertTrue(deleted.snapshot.items.isEmpty())
        // The delete is remembered: an older change can't bring the item back.
        assertEquals(ChangeStatus.STALE, apply(op(OpType.UPSERT, "Bread", ItemValue.BoolValue(true), time = now - 500)).results.single().status)

        // A change from the phone's future counts as now.
        apply(op(OpType.UPSERT, "Eggs", ItemValue.Quantity(Amount.whole(12L), "pieces"), time = now + 60_000L))
        val eggs = fake.item("Eggs")!!
        assertEquals(now, eggs.updatedAt)
        assertEquals(Need.Quantity(12.toBigDecimal(), Units.PCS), eggs.need)

        // A new name over 100 characters is refused; the app never sends one.
        val tooLong = apply(op(OpType.UPSERT, "x".repeat(101), ItemValue.BoolValue(true))).results.single()
        assertEquals(ChangeStatus.ERROR, tooLong.status)
        assertEquals("name is longer than 100 characters", tooLong.error)
    }

    @Test
    fun aQuantityWithoutUnitKeepsTheRowsUnitAndAddedAtIsWrittenOnce() = runTest {
        fake.seed("Rice", quantity("1", Units.KG))
        val seeded = fake.item("Rice")!!
        apply(op(OpType.UPSERT, "Rice", ItemValue.Quantity(Amount.whole(2L), unit = null)))
        val rice = fake.item("Rice")!!
        assertEquals(Need.Quantity(2.toBigDecimal(), Units.KG), rice.need)
        assertEquals(seeded.addedAt, rice.addedAt)

        // A rename re-creates the row with the hint, so it reads "Updated by".
        val renamed = apply(
            op(OpType.DELETE, "Rice", null),
            op(OpType.UPSERT, "Basmati rice", ItemValue.Quantity(Amount(2_500L), Units.KG), addedAt = seeded.addedAt),
        )
        assertTrue(renamed.results.all { it.status == ChangeStatus.APPLIED })
        val basmati = renamed.snapshot.items.single()
        assertEquals("Basmati rice", basmati.name)
        assertEquals(seeded.addedAt, basmati.addedAt)
        assertFalse(basmati.isUnchangedSinceAdded)
        val sent = fake.changesFromApp("basmati rice").single()
        assertTrue(sent.amountIs("2.5"))
        assertEquals(Units.KG, sent.unit)
        assertEquals(seeded.addedAt, sent.addedAt)
    }

    @Test
    fun answersThroughGooglesRedirectToo() = runTest {
        fake.googleRedirects = true
        assertTrue(client.ping(fake.url, fake.token) is PingResult.Ok)
        apply(op(OpType.UPSERT, "Milk", ItemValue.Quantity(Amount(1_500L), Units.L)))
        assertEquals(ItemValue.Quantity(Amount(1_500L), Units.L), list().snapshot!!.items.single().value)
    }

    @Test
    fun outagesLookLikeTheRealOnes() = runTest {
        fake.mode = FakeSheetServer.Mode.UNREACHABLE
        assertEquals(Protocol.offline(), client.list(fake.url, fake.token, null))

        fake.mode = FakeSheetServer.Mode.SERVER_ERROR
        val failed = client.list(fake.url, fake.token, null) as ApiResult.Failure
        assertEquals(Protocol.MSG_GOOGLE_TROUBLE, failed.message)
        assertEquals(FailureSource.GLITCH, failed.source)

        fake.mode = FakeSheetServer.Mode.UP
        list()
    }

    @Test
    fun theSyncEnginePushesAndPullsThroughIt() = runTest {
        val store = FakeSyncStore().apply { serverVersion = null }
        val settings = SyncSettings(fake.url, fake.token, "Shop")
        val engine = SyncEngine(store, client, { settings }, { "Shop" }, ServerClock { now }, elapsedMs = { 0L })
        fake.seed("Coffee", yesNo(false))
        store.add(OpType.UPSERT, "Milk", ItemValue.Quantity(Amount.whole(2L), Units.L), time = now)

        assertTrue(engine.syncOnce() is SyncOutcome.Success)
        assertTrue(store.outbox.isEmpty())
        assertEquals(setOf("Coffee", "Milk"), store.mirror.map { it.name }.toSet())
        val milk = fake.changesFromApp("Milk").single()
        assertEquals("upsert", milk.op)
        assertEquals("quantity", milk.type)
        assertTrue(milk.amountIs("2"))
        assertEquals(Units.L, milk.unit)
        assertEquals(Protocol.VERSION, milk.protocol)

        now += 5_000L
        assertEquals("applied", fake.upsertAs(HOME, "Eggs", quantity("6", Units.PCS)))
        assertTrue(engine.syncOnce() is SyncOutcome.Success)
        val eggs = store.mirror.first { it.name == "Eggs" }
        assertEquals(HOME, eggs.updatedBy)
        assertTrue(eggs.isUnchangedSinceAdded)
    }
}
