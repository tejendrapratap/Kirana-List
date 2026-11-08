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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The background check for change notifications (ListCheckWorker's whole work, without Android): when it runs at
 * all, what it says, what it then counts as seen (and from which sheet), what a notification still up becomes, and
 * that it never shows over the open app.
 */
class ChangeWatcherTest {

    private class FakeAlerts : ChangeAlerts {
        var allowed = true
        var refuse = false
        val shown = mutableListOf<ChangeNotice>()

        /** For each notice in [shown]: whether it sounded. */
        val alerted = mutableListOf<Boolean>()
        var cancels = 0

        /** The notification is up: shown, and neither opened (cancelled) nor swiped away (set false). */
        var showing = false

        /** Runs while [show] is called, before the notice counts as shown (to open the app at that moment). */
        var onShow: () -> Unit = {}

        override fun allowed() = allowed
        override fun show(notice: ChangeNotice, alert: Boolean): Boolean {
            onShow()
            if (refuse) return false
            shown += notice
            alerted += alert
            showing = true
            return true
        }

        override fun cancel() {
            cancels++
            showing = false
        }

        override fun isShowing() = showing
    }

    private class MemorySeenList(var state: SeenState? = null) : SeenList {
        /** The list seen, as from [SHEET]. */
        var seen: Map<String, String>?
            get() = state?.seen
            set(value) {
                state = value?.let { SeenState(SHEET, it) }
            }

        override fun read() = state
        override fun write(state: SeenState?) {
            this.state = state
        }
    }

    private val alerts = FakeAlerts()
    private val seenList = MemorySeenList()
    private var list: List<GroceryItem> = emptyList()

    /** The sheet [list] came from, while it is the one the phone is connected to (null: not pulled from it yet). */
    private var sheet: String? = SHEET
    private var syncs = 0
    private var syncOutcome: SyncOutcome = SyncOutcome.Success(at = 1L, spreadsheetName = "Groceries")

    private val watcher = ChangeWatcher(
        seenList = seenList,
        alerts = alerts,
        shownList = { list },
        listSheet = { sheet },
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
        assertEquals(listOf(true), alerts.alerted)
        // Not seen yet: the notification is up. What it told is kept with it.
        assertEquals(setOf("rice"), seenList.seen?.keys)
        assertEquals(ListChangeWatch.seen(list), seenList.state?.notice?.list)

        // The next check doesn't tell the same again: the notification up already does.
        assertEquals(CheckResult.ALREADY_TOLD, check())
        assertEquals(1, alerts.shown.size)

        // Swiped away, it counts as seen.
        alerts.showing = false
        assertEquals(CheckResult.NOTHING_NEW, check())
        assertEquals(1, alerts.shown.size)
        assertEquals(SeenState(SHEET, ListChangeWatch.seen(list)), seenList.state)
    }

    @Test
    fun theNotificationIsInTheLanguageShown() = runTest {
        val hindiWatcher = ChangeWatcher(
            seenList,
            alerts,
            { list },
            { sheet },
            { ME },
            texts = { AppTexts.Hindi },
            io = Dispatchers.Unconfined,
        )
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
    fun theNotificationStillUpTellsEverythingSinceTheListWasSeen() = runTest {
        list = listOf(item("Rice"))
        watcher.markSeen()

        list = listOf(item("Dahi"), item("Oil"), item("Rice"))
        assertEquals(CheckResult.NOTIFIED, check())
        // The phone stays in the pocket: Mummy adds Batti, and the notification is replaced by one that tells it all.
        list = list + item("Batti")
        assertEquals(CheckResult.NOTIFIED, check())
        assertEquals(ChangeNotice("Mummy added 3 items to To buy", "Dahi, Oil, Batti"), alerts.shown.last())
        // It sounds again: Batti is news.
        assertEquals(listOf(true, true), alerts.alerted)

        // Hindi says the same.
        val hindiWatcher = ChangeWatcher(
            seenList,
            alerts,
            { list },
            { sheet },
            { ME },
            texts = { AppTexts.Hindi },
            io = Dispatchers.Unconfined,
        )
        list = list + item("Atta")
        assertEquals(CheckResult.NOTIFIED, hindiWatcher.check(isSetUp = true, notifyChanges = true) { syncOutcome })
        assertEquals("Mummy ने \"ख़रीदना है\" में 4 चीज़ें जोड़ीं", alerts.shown.last().title)
    }

    @Test
    fun aNotificationSwipedAwayIsNotToldAgain() = runTest {
        list = listOf(item("Rice"))
        watcher.markSeen()
        list = listOf(item("Dahi"), item("Oil"), item("Rice"))
        assertEquals(CheckResult.NOTIFIED, check())

        alerts.showing = false
        list = list + item("Batti")
        assertEquals(CheckResult.NOTIFIED, check())
        assertEquals(ChangeNotice("Mummy added 1 item to To buy", "Batti"), alerts.shown.last())
    }

    @Test
    fun aNotificationUpIsUpdatedQuietlyWhenItsItemsAreBought() = runTest {
        list = listOf(item("Rice"))
        watcher.markSeen()
        list = listOf(item("Dahi"), item("Oil"), item("Rice"))
        assertEquals(CheckResult.NOTIFIED, check())

        // Bought by the shop's phone: the notification no longer tells it, without sounding.
        list = listOf(item("Dahi", needed = false, by = "Shop phone"), item("Oil"), item("Rice"))
        assertEquals(CheckResult.NOTIFIED, check())
        assertEquals(ChangeNotice("Mummy added 1 item to To buy", "Oil"), alerts.shown.last())
        assertEquals(listOf(true, false), alerts.alerted)

        // Everything it told was bought: it goes, and the list counts as seen.
        list = listOf(item("Dahi", needed = false, by = "Shop phone"), item("Oil", needed = false), item("Rice"))
        assertEquals(CheckResult.NOTHING_NEW, check())
        assertEquals(1, alerts.cancels)
        assertFalse(alerts.showing)
        assertEquals(SeenState(SHEET, ListChangeWatch.seen(list)), seenList.state)
    }

    @Test
    fun openingTheAppForgetsTheNotification() = runTest {
        list = listOf(item("Rice"))
        watcher.markSeen()
        list = listOf(item("Dahi"), item("Rice"))
        assertEquals(CheckResult.NOTIFIED, check())

        watcher.onForeground()
        watcher.markSeen()
        watcher.onBackground()
        assertEquals(SeenState(SHEET, ListChangeWatch.seen(list)), seenList.state)
        assertEquals(CheckResult.NOTHING_NEW, check())
        assertEquals(1, alerts.shown.size)
    }

    @Test
    fun aListSeenWhileTheAppIsOpenCountsAlthoughItNeverLeft() = runTest {
        // Mummy's Dahi came in while the app was open; then the app was closed from Recents before Android told it
        // it left (its process ended), so only the write while it was open happened.
        list = listOf(item("Rice"))
        watcher.markSeen()
        watcher.onForeground()
        list = listOf(item("Dahi"), item("Rice"))
        watcher.markSeen()

        val nextProcess = ChangeWatcher(seenList, alerts, { list }, { sheet }, { ME }, io = Dispatchers.Unconfined)
        assertEquals(CheckResult.NOTHING_NEW, nextProcess.check(isSetUp = true, notifyChanges = true) { syncOutcome })
        assertTrue(alerts.shown.isEmpty())
    }

    @Test
    fun aListSeenBeforeTheSheetFirstAnsweredIsForgotten() = runTest {
        // Set up, and left before the first sync answered (or it failed): the list is still empty.
        sheet = null
        watcher.markSeen()
        assertNull(seenList.state)

        // The background check gets the family's whole list: it only looks.
        sheet = SHEET
        list = listOf(item("Atta"), item("Dal"), item("Oil", by = "Papa"))
        assertEquals(CheckResult.FIRST_LOOK, check())
        assertTrue(alerts.shown.isEmpty())

        // From then on what is added is news.
        list = list + item("Dahi")
        assertEquals(CheckResult.NOTIFIED, check())
        assertEquals("Dahi", alerts.shown.single().text)
    }

    @Test
    fun anotherSheetsListIsNeverComparedWithThisOne() = runTest {
        list = listOf(item("Rice"))
        watcher.markSeen()

        // Switched to another sheet's setup code; left before it answered (the old list still shows): forgotten.
        sheet = null
        watcher.markSeen()
        assertNull(seenList.state)

        // A list kept from the old sheet (written before the switch) isn't compared with the new one either.
        seenList.state = SeenState(OTHER_SHEET, ListChangeWatch.seen(listOf(item("Rice"))))
        sheet = SHEET
        list = listOf(item("Atta"), item("Dal"))
        assertEquals(CheckResult.FIRST_LOOK, check())
        assertTrue(alerts.shown.isEmpty())
        assertEquals(SeenState(SHEET, ListChangeWatch.seen(list)), seenList.state)
    }

    @Test
    fun aCheckWhileThePhoneSwitchesSheetsSaysNothing() = runTest {
        seenList.seen = emptyMap()
        list = listOf(item("Dahi"))
        // The sync answered, but the phone was connected to another sheet meanwhile.
        sheet = null
        assertEquals(CheckResult.NO_ANSWER, check())
        assertTrue(alerts.shown.isEmpty())
        assertEquals(emptyMap<String, String>(), seenList.seen)
    }

    @Test
    fun anEmptyToBuySeenIsStillComparedWith() = runTest {
        // Everything bought: an empty To buy, seen after a real pull. What is added to it is news.
        list = listOf(item("Rice", needed = false))
        watcher.markSeen()
        assertEquals(emptyMap<String, String>(), seenList.seen)
        list = listOf(item("Rice"))
        assertEquals(CheckResult.NOTIFIED, check())
        assertEquals("Rice", alerts.shown.single().text)
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
                listSheet = { store.syncInfo().sheetUrl?.takeIf { it == settings.webAppUrl } },
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
            // Only Dahi was added; Rice's new amount is listed beside it.
            assertEquals(ChangeNotice("Mummy added 1 item to To buy", "Dahi, Rice 5 kg"), alerts.shown.single())
            // The waiting change went to the sheet on the way, and isn't news: it is this phone's own.
            assertTrue(store.outbox.isEmpty())
            assertEquals(ME, fake.item("Salt")?.updatedBy)

            assertEquals(CheckResult.ALREADY_TOLD, sheetCheck())
            assertEquals(1, alerts.shown.size)
            alerts.showing = false
            assertEquals(CheckResult.NOTHING_NEW, sheetCheck())
            assertEquals(setOf("dahi", "rice", "salt"), seenList.seen?.keys)
            assertEquals(fake.url, seenList.state?.sheet)
            assertEquals(ItemValue.Quantity(Amount.whole(5), Units.KG), store.mirror.first { it.name == "Rice" }.value)
        }
    }

    private companion object {
        const val ME = "Shop"
        const val MUMMY = "Mummy"
        const val SHEET = "https://script.google.com/macros/s/home/exec"
        const val OTHER_SHEET = "https://script.google.com/macros/s/office/exec"
    }
}
