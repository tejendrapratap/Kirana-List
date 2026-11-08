package com.grocerysync.app.notify

import com.grocerysync.app.data.Amount
import com.grocerysync.app.data.GroceryItem
import com.grocerysync.app.data.ItemKeys
import com.grocerysync.app.data.ItemValue
import com.grocerysync.app.data.ListLogic
import com.grocerysync.app.data.OpType
import com.grocerysync.app.data.ServerClock
import com.grocerysync.app.data.SyncSettings
import com.grocerysync.app.data.Units
import com.grocerysync.app.sync.FakeSyncStore
import com.grocerysync.app.sync.PushTiming
import com.grocerysync.app.sync.SheetsClient
import com.grocerysync.app.sync.SyncEngine
import com.grocerysync.app.sync.SyncOutcome
import com.grocerysync.app.testing.FakeSheetServer
import com.grocerysync.app.testing.FakeSheetServer.Companion.quantity
import com.grocerysync.app.testing.FakeSheetServer.Companion.yesNo
import com.grocerysync.app.text.AppTexts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The background check for change notifications (ListCheckWorker's whole work, without Android): when it runs at
 * all, what it says, what it then counts as seen, and that it never shows over the open app.
 */
class ChangeWatcherTest {

    private class FakeAlerts : ChangeAlerts {
        var allowed = true
        var refuse = false
        val shown = mutableListOf<ChangeNotice>()
        var cancels = 0

        /** Runs while [show] is called, before the notice counts as shown (to open the app at that moment). */
        var onShow: () -> Unit = {}

        override fun allowed() = allowed
        override fun show(notice: ChangeNotice): Boolean {
            onShow()
            if (refuse) return false
            shown += notice
            return true
        }

        override fun cancel() {
            cancels++
        }
    }

    private class MemorySeenList(var seen: Map<String, String>? = null) : SeenList {
        override fun read() = seen
        override fun write(seen: Map<String, String>) {
            this.seen = seen
        }
    }

    private val alerts = FakeAlerts()
    private val seenList = MemorySeenList()
    private var list: List<GroceryItem> = emptyList()
    private var syncs = 0
    private var syncOutcome: SyncOutcome = SyncOutcome.Success(at = 1L, spreadsheetName = "Groceries")

    private val watcher = ChangeWatcher(
        seenList = seenList,
        alerts = alerts,
        shownList = { list },
        myName = { ME },
        io = Dispatchers.Unconfined,
    )

    private fun item(name: String, needed: Boolean = true, by: String = MUMMY) =
        GroceryItem(ItemKeys.normalize(name), name, ItemValue.BoolValue(needed), 1_000L, by, pendingSync = false)

    private suspend fun check(isSetUp: Boolean = true, notifyChanges: Boolean = true): CheckResult =
        watcher.check(isSetUp, notifyChanges) {
            syncs++
            syncOutcome
        }

    @Test
    fun theGateSaysWhyACheckDoesNotRun() {
        fun gate(setUp: Boolean = true, on: Boolean = true, open: Boolean = false, allowed: Boolean = true) =
            listCheckGate(isSetUp = setUp, notifyChanges = on, appOpen = open, notificationsAllowed = allowed)
        assertNull(gate())
        assertEquals(CheckResult.NOT_SET_UP, gate(setUp = false))
        assertEquals(CheckResult.TURNED_OFF, gate(on = false))
        assertEquals(CheckResult.APP_OPEN, gate(open = true))
        assertEquals(CheckResult.BLOCKED, gate(allowed = false))
        // The first reason counts: a phone that isn't set up says so whatever else holds.
        assertEquals(CheckResult.NOT_SET_UP, gate(setUp = false, on = false, open = true, allowed = false))
    }

    @Test
    fun aCheckThatMayNotRunNeitherSyncsNorNotifies() = runTest {
        seenList.seen = emptyMap()
        list = listOf(item("Dahi"))
        assertEquals(CheckResult.NOT_SET_UP, check(isSetUp = false))
        assertEquals(CheckResult.TURNED_OFF, check(notifyChanges = false))
        alerts.allowed = false
        assertEquals(CheckResult.BLOCKED, check())
        alerts.allowed = true
        watcher.onForeground()
        assertEquals(CheckResult.APP_OPEN, check())
        assertEquals(0, syncs)
        assertTrue(alerts.shown.isEmpty())
        assertEquals(emptyMap<String, String>(), seenList.seen)
    }

    @Test
    fun whatSomeoneElseAddedIsNotifiedOnceAndThenCountsAsSeen() = runTest {
        list = listOf(item("Rice"))
        watcher.onBackground()
        watcher.markSeen()

        list = listOf(item("Dahi"), item("Oil"), item("Rice"))
        assertEquals(CheckResult.NOTIFIED, check())
        assertEquals(1, syncs)
        assertEquals(listOf(ChangeNotice("Mummy added 2 items to To buy", "Dahi, Oil")), alerts.shown)
        assertEquals(ListChangeWatch.seen(list), seenList.seen)

        // The next check doesn't tell the same again.
        assertEquals(CheckResult.NOTHING_NEW, check())
        assertEquals(1, alerts.shown.size)
    }

    @Test
    fun theNotificationIsInTheLanguageShown() = runTest {
        val hindiWatcher =
            ChangeWatcher(seenList, alerts, { list }, { ME }, texts = { AppTexts.Hindi }, io = Dispatchers.Unconfined)
        seenList.seen = emptyMap()
        list = listOf(item("Dahi"))
        val result = hindiWatcher.check(isSetUp = true, notifyChanges = true) { syncOutcome }
        assertEquals(CheckResult.NOTIFIED, result)
        assertEquals("Mummy ने \"ख़रीदना है\" में 1 चीज़ जोड़ी", alerts.shown.single().title)
    }

    @Test
    fun withoutAListSeenTheFirstCheckOnlyLooks() = runTest {
        // A new install, or the first check after updating: everything would look new.
        list = listOf(item("Dahi"), item("Oil"))
        assertEquals(CheckResult.FIRST_LOOK, check())
        assertTrue(alerts.shown.isEmpty())
        assertEquals(ListChangeWatch.seen(list), seenList.seen)

        list = list + item("Batti")
        assertEquals(CheckResult.NOTIFIED, check())
        assertEquals("Batti", alerts.shown.single().text)
    }

    @Test
    fun nothingNewStillUpdatesWhatWasSeen() = runTest {
        list = listOf(item("Dahi"), item("Rice"))
        watcher.markSeen()
        // Bought at the shop (another phone ticked it off), and this phone's own change.
        list = listOf(item("Dahi", needed = false), item("Rice"), item("Salt", by = ME))
        assertEquals(CheckResult.NOTHING_NEW, check())
        assertEquals(setOf("rice", "salt"), seenList.seen?.keys)

        // Needed again later: that is news, although it was on the list when last seen.
        list = listOf(item("Dahi"), item("Rice"), item("Salt", by = ME))
        assertEquals(CheckResult.NOTIFIED, check())
        assertEquals("Dahi", alerts.shown.single().text)
    }

    @Test
    fun aSheetThatDoesntAnswerChangesNothing() = runTest {
        seenList.seen = emptyMap()
        list = listOf(item("Dahi"))
        syncOutcome = SyncOutcome.Offline("No connection")
        assertEquals(CheckResult.NO_ANSWER, check())
        syncOutcome = SyncOutcome.Error("Wrong token")
        assertEquals(CheckResult.NO_ANSWER, check())
        assertTrue(alerts.shown.isEmpty())
        assertEquals(emptyMap<String, String>(), seenList.seen)
    }

    @Test
    fun theAppOpenedWhileTheCheckSyncedIsNotNotified() = runTest {
        seenList.seen = emptyMap()
        list = listOf(item("Dahi"))
        val result = watcher.check(isSetUp = true, notifyChanges = true) {
            // The app comes to the foreground while the sync runs.
            watcher.onForeground()
            syncOutcome
        }
        assertEquals(CheckResult.OPENED_MEANWHILE, result)
        assertTrue(alerts.shown.isEmpty())
        // Not seen yet: leaving the app marks it seen.
        assertEquals(emptyMap<String, String>(), seenList.seen)
    }

    @Test
    fun openingTheAppTakesTheNotificationAway() = runTest {
        seenList.seen = emptyMap()
        list = listOf(item("Dahi"))
        assertEquals(CheckResult.NOTIFIED, check())
        watcher.onForeground()
        assertEquals(1, alerts.cancels)
        assertTrue(watcher.appOpen)
        watcher.onBackground()
        assertTrue(!watcher.appOpen)
    }

    @Test
    fun theAppOpeningAsTheNotificationShowsWaitsForIt() = runTest {
        // onForeground on another thread while show() runs: it waits for the lock, then takes the notification away.
        seenList.seen = emptyMap()
        list = listOf(item("Dahi"))
        var opener: Thread? = null
        alerts.onShow = {
            opener = Thread { watcher.onForeground() }.also { it.start() }
            // Give it time to reach the lock (it can't get past it while show() runs).
            Thread.sleep(50)
            assertEquals(0, alerts.cancels)
        }
        assertEquals(CheckResult.NOTIFIED, check())
        opener!!.join(5_000)
        assertEquals(1, alerts.cancels)
    }

    @Test
    fun aRefusedNotificationIsToldNextTime() = runTest {
        seenList.seen = emptyMap()
        list = listOf(item("Dahi"))
        alerts.refuse = true
        assertEquals(CheckResult.NOT_SHOWN, check())
        assertEquals(emptyMap<String, String>(), seenList.seen)
        alerts.refuse = false
        assertEquals(CheckResult.NOTIFIED, check())
        assertEquals("Dahi", alerts.shown.single().text)
    }

    @Test
    fun withTheRealSyncEngineAndAFakeSheet() = runTest {
        FakeSheetServer().start().use { fake ->
            val store = FakeSyncStore()
            val settings = SyncSettings(fake.url, fake.token, ME)
            val engine = SyncEngine(store, SheetsClient(), { settings }, { ME }, ServerClock())
            val sheetWatcher = ChangeWatcher(
                seenList = seenList,
                alerts = alerts,
                shownList = { ListLogic.overlay(store.mirror, store.loadOutbox(), ME) },
                myName = { ME },
                io = Dispatchers.Unconfined,
            )
            suspend fun sheetCheck() = sheetWatcher.check(isSetUp = true, notifyChanges = true) {
                engine.syncOnce(pull = true, push = PushTiming.BACKGROUND)
            }

            // The person saw Rice and Atta on To buy, then closed the app with a change of their own still waiting.
            fake.seed("Rice", quantity("2", Units.KG))
            fake.seed("Atta", yesNo(true))
            engine.syncOnce()
            store.add(OpType.UPSERT, "Salt", ItemValue.BoolValue(true))
            sheetWatcher.onBackground()
            sheetWatcher.markSeen()

            // Meanwhile at home: Mummy adds Dahi and changes Rice; the shop's phone takes Atta off.
            fake.upsertAs("Mummy", "Dahi", yesNo(true))
            fake.upsertAs("Mummy", "Rice", quantity("5", Units.KG))
            fake.upsertAs("Shop phone", "Atta", yesNo(false))

            assertEquals(CheckResult.NOTIFIED, sheetCheck())
            assertEquals(ChangeNotice("Mummy added 2 items to To buy", "Dahi, Rice 5 kg"), alerts.shown.single())
            // The waiting change went to the sheet on the way, and isn't news: it is this phone's own.
            assertTrue(store.outbox.isEmpty())
            assertEquals(ME, fake.item("Salt")?.updatedBy)

            assertEquals(CheckResult.NOTHING_NEW, sheetCheck())
            assertEquals(1, alerts.shown.size)
            assertEquals(setOf("dahi", "rice", "salt"), seenList.seen?.keys)
            assertEquals(ItemValue.Quantity(Amount.whole(5), Units.KG), store.mirror.first { it.name == "Rice" }.value)
        }
    }

    private companion object {
        const val ME = "Shop"
        const val MUMMY = "Mummy"
    }
}
