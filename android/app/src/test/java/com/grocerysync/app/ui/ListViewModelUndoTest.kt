package com.grocerysync.app.ui

import androidx.lifecycle.SavedStateHandle
import com.grocerysync.app.data.Amount
import com.grocerysync.app.data.FakeListStore
import com.grocerysync.app.data.FakeSharedPreferences
import com.grocerysync.app.data.GroceryItem
import com.grocerysync.app.data.GroceryRepository
import com.grocerysync.app.data.ItemKeys
import com.grocerysync.app.data.ItemValue
import com.grocerysync.app.data.ItemValue.BoolValue
import com.grocerysync.app.data.ItemValue.Quantity
import com.grocerysync.app.data.PhoneSettingsStore
import com.grocerysync.app.data.ServerClock
import com.grocerysync.app.data.SettingsStore
import com.grocerysync.app.data.ShopSection
import com.grocerysync.app.data.ShopSectionStore
import com.grocerysync.app.data.SyncSettings
import com.grocerysync.app.data.TickedEntry
import com.grocerysync.app.data.TickedTodayStore
import com.grocerysync.app.data.Units
import com.grocerysync.app.data.shownTickedToday
import com.grocerysync.app.sync.FakeSyncStore
import com.grocerysync.app.sync.LiveSync
import com.grocerysync.app.sync.SheetsClient
import com.grocerysync.app.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The real ListViewModel and GroceryRepository on a list in memory ([FakeListStore]): the Undo snackbars of
 * "Got it", - / +, the Need switch and the Edit dialog, their guarded Undo, and Ticked today with Put back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ListViewModelUndoTest {

    private val dispatcher = StandardTestDispatcher()
    private val liveSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = ZonedDateTime.of(2026, 11, 8, 18, 0, 0, 0, zone).toInstant().toEpochMilli()

    private val me = "Shop"
    private val store = FakeListStore()
    private val ticked = TickedTodayStore(FakeSharedPreferences(), clock = { now }, zone = { zone })
    private val shopSections = ShopSectionStore(FakeSharedPreferences())
    private lateinit var repository: GroceryRepository
    private lateinit var viewModel: ListViewModel
    private val events = mutableListOf<ListEvent>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val settings = SettingsStore(FakeSharedPreferences()).apply { save(SyncSettings("", "", me)) }
        repository = GroceryRepository(store, settings, ServerClock(localClock = { 50_000L }), onLocalChange = {}, io = dispatcher)
        val syncStore = FakeSyncStore()
        val liveSync = LiveSync(
            engine = SyncEngine(syncStore, SheetsClient(), settings::current, { me }, ServerClock()),
            settings = settings.settings,
            store = syncStore,
            scope = liveSyncScope,
        )
        viewModel = ListViewModel(
            repository = repository,
            liveSync = liveSync,
            settingsStore = settings,
            shopSectionStore = shopSections,
            phoneSettingsStore = PhoneSettingsStore(FakeSharedPreferences()),
            savedStateHandle = SavedStateHandle(),
            tickedTodayStore = ticked,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        liveSyncScope.cancel()
    }

    private fun item(name: String, value: ItemValue, by: String = "Home", updatedAt: Long = 1_000L) =
        GroceryItem(ItemKeys.normalize(name), name, value, updatedAt, by, pendingSync = false, addedAt = updatedAt)

    private fun kg(amount: String) = Quantity(Amount.parse(amount)!!, Units.KG)

    /**
     * Runs [block] with the view model's events collected into [events], as they are sent (unconfined: advanceUntilIdle
     * doesn't wait for work in the background scope).
     */
    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.collect { events += it } }
        block()
    }

    /** Lets the view model's edits (and the events they send) run. */
    private fun TestScope.settle() = advanceUntilIdle()

    private suspend fun shown(name: String): GroceryItem? = repository.items.first().firstOrNull { it.key == ItemKeys.normalize(name) }

    private fun lastMessage(): String? = (events.lastOrNull() as? ListEvent.Message)?.text

    // ---- Got it ----------------------------------------------------------------------------------------------

    @Test
    fun gotItSendsItsChangeAndTicksTheItemOff() = test {
        store.synced(listOf(item("Rice", kg("2"))))
        viewModel.markGotIt("rice")
        settle()
        assertEquals(listOf(ListEvent.GotIt("rice", "Rice", before = kg("2"), after = kg("0"))), events)
        assertEquals(kg("0"), shown("Rice")!!.value)
        assertEquals(listOf(TickedEntry("rice", "Rice", kg("2"), kg("0"), now)), ticked.today())
    }

    @Test
    fun undoOfGotItPutsTheAmountBackAndTakesItOffTickedToday() = test {
        store.synced(listOf(item("Rice", kg("2"))))
        viewModel.markGotIt("rice")
        settle()
        viewModel.undoChange(events.single() as ListEvent.GotIt)
        settle()
        assertEquals(kg("2"), shown("Rice")!!.value)
        assertTrue(ticked.today().isEmpty())
        assertEquals(1, events.size)
    }

    @Test
    fun undoOfGotItAfterAChangeOnTheOtherPhoneSaysSoAndKeepsTheirs() = test {
        store.synced(listOf(item("Milk", Quantity.of(2, Units.L))))
        viewModel.markGotIt("milk")
        settle()
        // Sent; then Home set Milk to 3 L, and that came back.
        store.synced(listOf(item("Milk", Quantity.of(3, Units.L), updatedAt = 60_000L)))
        viewModel.undoChange(events.single() as ListEvent.GotIt)
        settle()
        assertEquals("\"Milk\" was changed on another phone", lastMessage())
        assertEquals(Quantity.of(3, Units.L), shown("Milk")!!.value)
        assertTrue(ticked.today().isEmpty())
    }

    @Test
    fun undoOfGotItOfAnItemRenamedOnTheOtherPhoneDoesNotBringBackTheOldName() = test {
        store.synced(listOf(item("Milk", Quantity.of(2, Units.L))))
        viewModel.markGotIt("milk")
        settle()
        store.synced(listOf(item("Oat milk", Quantity.of(0, Units.L), updatedAt = 60_000L)))
        viewModel.undoChange(events.single() as ListEvent.GotIt)
        settle()
        assertEquals("\"Milk\" was changed on another phone", lastMessage())
        assertNull(shown("Milk"))
    }

    // ---- - / + -----------------------------------------------------------------------------------------------

    @Test
    fun stepsOfOneItemWhileItsSnackbarIsShownAreOneChange() = test {
        store.synced(listOf(item("Rice", kg("1"))))
        viewModel.stepQuantity("rice", up = true)
        settle()
        viewModel.stepQuantity("rice", up = true)
        settle()
        assertEquals(
            listOf(ListEvent.Stepped("rice", "Rice", kg("1"), kg("1.5")), ListEvent.Stepped("rice", "Rice", kg("1"), kg("2"))),
            events,
        )
        // The screen showed the second in place of the first; its Undo goes back to before the first step.
        viewModel.snackbarClosed(events[0])
        viewModel.undoChange(events[1] as ListEvent.Stepped)
        settle()
        assertEquals(kg("1"), shown("Rice")!!.value)
    }

    @Test
    fun aStepAfterItsSnackbarHasGoneIsAChangeOfItsOwn() = test {
        store.synced(listOf(item("Rice", kg("1"))))
        viewModel.stepQuantity("rice", up = true)
        settle()
        viewModel.snackbarClosed(events.single())
        viewModel.stepQuantity("rice", up = true)
        settle()
        assertEquals(ListEvent.Stepped("rice", "Rice", kg("1.5"), kg("2")), events.last())
    }

    @Test
    fun stepsOfAnotherItemStartAChangeOfTheirOwn() = test {
        store.synced(listOf(item("Rice", kg("1")), item("Dal", kg("2"))))
        viewModel.stepQuantity("rice", up = true)
        settle()
        viewModel.stepQuantity("dal", up = false)
        settle()
        viewModel.stepQuantity("rice", up = true)
        settle()
        assertEquals(ListEvent.Stepped("dal", "Dal", kg("2"), kg("1.5")), events[1])
        // Dal's snackbar replaced Rice's: the next step of Rice starts over.
        assertEquals(ListEvent.Stepped("rice", "Rice", kg("1.5"), kg("2")), events[2])
    }

    @Test
    fun stepsDownToZeroTickTheItemOffWithTheAmountFromBeforeTheSteps() = test {
        store.synced(listOf(item("Rice", kg("1"))))
        repeat(2) {
            viewModel.stepQuantity("rice", up = false)
            settle()
        }
        assertEquals(ListEvent.Stepped("rice", "Rice", kg("1"), kg("0")), events.last())
        assertEquals(listOf(TickedEntry("rice", "Rice", kg("1"), kg("0"), now)), ticked.today())
        viewModel.undoChange(events.last() as ListEvent.Stepped)
        settle()
        assertEquals(kg("1"), shown("Rice")!!.value)
        assertTrue(ticked.today().isEmpty())
    }

    @Test
    fun undoOfAStepAfterAChangeOnTheOtherPhoneSaysSo() = test {
        store.synced(listOf(item("Rice", kg("1"))))
        viewModel.stepQuantity("rice", up = true)
        settle()
        store.synced(listOf(item("Rice", kg("5"), updatedAt = 60_000L)))
        viewModel.undoChange(events.single() as ListEvent.Stepped)
        settle()
        assertEquals("\"Rice\" was changed on another phone", lastMessage())
        assertEquals(kg("5"), shown("Rice")!!.value)
    }

    @Test
    fun aTickedItemThatIsNeededAgainLeavesTickedToday() = test {
        store.synced(listOf(item("Rice", kg("1"))))
        viewModel.markGotIt("rice")
        settle()
        assertEquals(1, ticked.today().size)
        // + on All items: needed again.
        viewModel.stepQuantity("rice", up = true)
        settle()
        assertTrue(ticked.today().isEmpty())
    }

    @Test
    fun plusThenMinusHasNothingToUndo() = test {
        store.synced(listOf(item("Rice", kg("2"))))
        viewModel.stepQuantity("rice", up = true)
        settle()
        viewModel.stepQuantity("rice", up = false)
        settle()
        // Back where it started: said without Undo (a "2 kg → 2 kg" Undo would do nothing).
        assertEquals(ListEvent.Stepped("rice", "Rice", kg("2"), kg("2.5")), events[0])
        assertEquals("Rice: 2 kg, as before", lastMessage())
        assertEquals(2, events.size)
        // The next step is a change of its own.
        viewModel.stepQuantity("rice", up = true)
        settle()
        assertEquals(ListEvent.Stepped("rice", "Rice", kg("2"), kg("2.5")), events.last())
    }

    @Test
    fun theUndoOfAPlusOnATickedItemPutsItBackUnderTickedToday() = test {
        store.synced(listOf(item("Rice", kg("2"))))
        viewModel.markGotIt("rice")
        settle()
        val entry = ticked.today().single()
        viewModel.snackbarClosed(events.last())
        // A stray + on All items made Rice needed again, and took it off Ticked today.
        viewModel.stepQuantity("rice", up = true)
        settle()
        assertTrue(ticked.today().isEmpty())
        viewModel.undoChange(events.last() as ListEvent.Stepped)
        settle()
        assertEquals(kg("0"), shown("Rice")!!.value)
        // Put back can give it its 2 kg again.
        assertEquals(listOf(entry), ticked.today())
    }

    @Test
    fun plusThenMinusOnATickedItemLeavesItUnderTickedToday() = test {
        store.synced(listOf(item("Rice", kg("2"))))
        viewModel.markGotIt("rice")
        settle()
        val entry = ticked.today().single()
        viewModel.snackbarClosed(events.last())
        viewModel.stepQuantity("rice", up = true)
        settle()
        viewModel.stepQuantity("rice", up = false)
        settle()
        assertEquals("Rice: 0 kg, as before", lastMessage())
        assertEquals(listOf(entry), ticked.today())
    }

    @Test
    fun theUndoOfTheNeedSwitchOnATickedItemPutsItBackUnderTickedToday() = test {
        store.synced(listOf(item("Dahi", BoolValue(true))))
        viewModel.markGotIt("dahi")
        settle()
        val entry = ticked.today().single()
        viewModel.snackbarClosed(events.last())
        viewModel.setNeeded("dahi", needed = true)
        settle()
        assertEquals(ListEvent.NeedChanged("dahi", "Dahi", needed = true, tickedEntry = entry), events.last())
        assertTrue(ticked.today().isEmpty())
        viewModel.undoChange(events.last() as ListEvent.NeedChanged)
        settle()
        assertEquals(listOf(entry), ticked.today())
    }

    // ---- Need switch -----------------------------------------------------------------------------------------

    @Test
    fun theNeedSwitchSendsItsChangeAndOffTicksTheItemOff() = test {
        store.synced(listOf(item("Dahi", BoolValue(false))))
        viewModel.setNeeded("dahi", needed = true)
        settle()
        assertEquals(ListEvent.NeedChanged("dahi", "Dahi", needed = true), events.last())
        assertTrue(ticked.today().isEmpty())

        viewModel.snackbarClosed(events.last())
        viewModel.setNeeded("dahi", needed = false)
        settle()
        assertEquals(ListEvent.NeedChanged("dahi", "Dahi", needed = false), events.last())
        assertEquals(listOf(TickedEntry("dahi", "Dahi", BoolValue(true), BoolValue(false), now)), ticked.today())

        viewModel.undoChange(events.last() as ListEvent.NeedChanged)
        settle()
        assertEquals(BoolValue(true), shown("Dahi")!!.value)
        assertTrue(ticked.today().isEmpty())
    }

    @Test
    fun theNeedSwitchSetToWhatTheItemHasSendsNothing() = test {
        store.synced(listOf(item("Dahi", BoolValue(true))))
        viewModel.setNeeded("dahi", needed = true)
        settle()
        assertTrue(events.isEmpty())
        assertTrue(store.outbox.isEmpty())
    }

    // ---- Edit dialog -----------------------------------------------------------------------------------------

    @Test
    fun anEditThatRenamesAndChangesTheAmountIsUndoneAsAWhole() = test {
        store.synced(listOf(item("Curd", kg("1"))))
        shopSections.chooseSection("curd", "Curd", ShopSection.OTHER)
        viewModel.saveItem(originalKey = "curd", openedValue = kg("1"), name = "Dahi", value = kg("2"))
        settle()
        val edited = events.single() as ListEvent.Edited
        assertEquals(ListEvent.Edited("dahi", "Curd", "Dahi", kg("1"), kg("2")), edited)
        assertEquals(ShopSection.OTHER, shopSections.current().chosen["dahi"])

        viewModel.undoEdit(edited)
        settle()
        assertNull(shown("Dahi"))
        assertEquals("Curd", shown("Curd")!!.name)
        assertEquals(kg("1"), shown("Curd")!!.value)
        // This phone's shop section for it goes back with the name.
        assertEquals(ShopSection.OTHER, shopSections.current().chosen["curd"])
        assertEquals(1, events.size)
    }

    @Test
    fun anEditOfTheAmountOnlyIsUndone() = test {
        store.synced(listOf(item("Rice", kg("1"))))
        viewModel.saveItem(originalKey = "rice", openedValue = kg("1"), name = "Rice", value = kg("3"))
        settle()
        val edited = events.single() as ListEvent.Edited
        assertEquals(ListEvent.Edited("rice", "Rice", "Rice", kg("1"), kg("3")), edited)
        viewModel.undoEdit(edited)
        settle()
        assertEquals(kg("1"), shown("Rice")!!.value)
    }

    @Test
    fun undoOfARenameNeverOverwritesAnItemThatTookTheOldName() = test {
        store.synced(listOf(item("Curd", kg("1"))))
        viewModel.saveItem(originalKey = "curd", openedValue = kg("1"), name = "Dahi", value = kg("1"))
        settle()
        val edited = events.single() as ListEvent.Edited
        // The rename reached the sheet; then Home added a new Curd.
        store.synced(listOf(item("Dahi", kg("1"), by = me, updatedAt = 50_000L), item("Curd", Quantity.of(500, Units.G), updatedAt = 60_000L)))
        viewModel.undoEdit(edited)
        settle()
        assertEquals("\"Curd\" is already on the list", lastMessage())
        assertEquals(Quantity.of(500, Units.G), shown("Curd")!!.value)
        assertEquals(kg("1"), shown("Dahi")!!.value)
    }

    @Test
    fun undoOfAnEditAfterAChangeOnTheOtherPhoneSaysSo() = test {
        store.synced(listOf(item("Curd", kg("1"))))
        viewModel.saveItem(originalKey = "curd", openedValue = kg("1"), name = "Dahi", value = kg("2"))
        settle()
        store.synced(listOf(item("Dahi", kg("4"), updatedAt = 60_000L)))
        viewModel.undoEdit(events.single() as ListEvent.Edited)
        settle()
        assertEquals("\"Dahi\" was changed on another phone", lastMessage())
        assertEquals(kg("4"), shown("Dahi")!!.value)
        assertNull(shown("Curd"))
    }

    @Test
    fun anEditThatFixesANameTheSheetCantTakeHasNoUndo() = test {
        // Typed into the sheet with a line break: the Edit dialog can't save it, so the user fixes it.
        store.synced(listOf(item("Basmati\nrice", kg("1"))))
        viewModel.saveItem(originalKey = "basmati rice", openedValue = kg("1"), name = "Basmati rice", value = kg("1"))
        settle()
        // Its Undo couldn't bring the old name back: the snackbar says what changed, without Undo.
        assertEquals("Renamed Basmati\nrice to Basmati rice", lastMessage())
        assertEquals("Basmati rice", shown("Basmati rice")!!.name)
    }

    @Test
    fun anEditThatShortensANameTooLongToSendHasNoUndo() = test {
        val long = "Rice " + "x".repeat(150)
        store.synced(listOf(item(long, kg("1"))))
        viewModel.saveItem(originalKey = ItemKeys.normalize(long), openedValue = kg("1"), name = "Rice", value = kg("1"))
        settle()
        assertEquals("Renamed $long to Rice", lastMessage())
        assertEquals(kg("1"), shown("Rice")!!.value)
    }

    @Test
    fun theUndoOfAnEditOfATickedItemPutsItBackUnderTickedToday() = test {
        store.synced(listOf(item("Curd", kg("1"))))
        viewModel.markGotIt("curd")
        settle()
        val entry = ticked.today().single()
        viewModel.saveItem(originalKey = "curd", openedValue = kg("0"), name = "Dahi", value = kg("0"))
        settle()
        assertTrue(ticked.today().isEmpty())
        viewModel.undoEdit(events.last() as ListEvent.Edited)
        settle()
        assertEquals(kg("0"), shown("Curd")!!.value)
        assertEquals(listOf(entry), ticked.today())
    }

    // ---- Delete ----------------------------------------------------------------------------------------------

    @Test
    fun theUndoOfADeleteBringsBackTheAddedAtAndTheTick() = test {
        store.synced(listOf(item("Ghee", kg("1"), updatedAt = 2_000L)))
        viewModel.markGotIt("ghee")
        settle()
        val entry = ticked.today().single()
        viewModel.deleteItem("ghee")
        settle()
        val deleted = events.last() as ListEvent.Deleted
        assertEquals(ListEvent.Deleted("Ghee", kg("0"), addedAt = 2_000L, tickedEntry = entry), deleted)
        assertTrue(ticked.today().isEmpty())
        // The delete reached the sheet.
        store.synced(emptyList())
        viewModel.undoDelete(deleted)
        settle()
        assertEquals(kg("0"), shown("Ghee")!!.value)
        assertEquals(2_000L, store.outbox.single().addedAt)
        assertEquals(listOf(entry), ticked.today())
    }

    @Test
    fun aDeleteOfANameThatCantBeSentHasNoUndo() = test {
        val long = "x".repeat(50_001)
        store.synced(listOf(item(long, kg("1"))))
        viewModel.deleteItem(ItemKeys.normalize(long))
        settle()
        assertEquals("Couldn't change \"${"x".repeat(40)}\". Open it and shorten its name.", lastMessage())
        assertTrue(store.outbox.isEmpty())
    }

    // ---- Simple mode's lasting Undo --------------------------------------------------------------------------

    @Test
    fun aLastingUndoIsKeptUntilItsSnackbarHasGone() = test {
        store.synced(listOf(item("Rice", kg("2")), item("Dal", kg("1"))))
        viewModel.markGotIt("rice")
        settle()
        val rice = events.last()
        viewModel.lastingUndoShown(rice)
        assertEquals(rice, viewModel.lastingUndo)
        // The next Undo replaces it; the screen then says the first one's snackbar has gone.
        viewModel.markGotIt("dal")
        settle()
        val dal = events.last()
        viewModel.lastingUndoShown(dal)
        viewModel.snackbarClosed(rice)
        assertEquals(dal, viewModel.lastingUndo)
        viewModel.snackbarClosed(dal)
        assertNull(viewModel.lastingUndo)
    }

    // ---- Ticked today ----------------------------------------------------------------------------------------

    @Test
    fun putBackGivesTheItemItsAmountAgain() = test {
        store.synced(listOf(item("Rice", kg("2")), item("Dahi", BoolValue(true))))
        viewModel.markGotIt("rice")
        settle()
        viewModel.markGotIt("dahi")
        settle()
        assertEquals(listOf("dahi", "rice"), ticked.today().map { it.key })

        val rice = ticked.today().single { it.key == "rice" }
        viewModel.putBack(rice)
        settle()
        assertEquals(kg("2"), shown("Rice")!!.value)
        assertEquals(listOf("dahi"), ticked.today().map { it.key })
        // Its own Undo snackbar, which replaces the Got it one.
        assertEquals(ListEvent.PutBack(rice), events.last())
        assertEquals(3, events.size)
    }

    @Test
    fun theUndoOfPutBackTicksTheItemOffAgain() = test {
        store.synced(listOf(item("Rice", kg("2"))))
        viewModel.markGotIt("rice")
        settle()
        val entry = ticked.today().single()
        viewModel.putBack(entry)
        settle()
        viewModel.undoChange(events.last() as ListEvent.PutBack)
        settle()
        assertEquals(kg("0"), shown("Rice")!!.value)
        // Back under Ticked today as it was, with the amount from before the tick.
        assertEquals(listOf(entry), ticked.today())
        assertEquals(listOf(entry), shownTickedToday(ticked.today(), repository.items.first(), me))
        assertEquals(2, events.size)
    }

    @Test
    fun theTicksUndoAfterPutBackHasNothingToDo() = test {
        store.synced(listOf(item("Rice", kg("2"))))
        viewModel.markGotIt("rice")
        settle()
        val gotIt = events.single() as ListEvent.GotIt
        viewModel.putBack(ticked.today().single())
        settle()
        // In Simple mode the Got it snackbar may still be up: its Undo finds Rice as this phone put it back.
        viewModel.undoChange(gotIt)
        settle()
        assertEquals(kg("2"), shown("Rice")!!.value)
        assertTrue("no snackbar says it was changed elsewhere", events.none { it is ListEvent.Message })
        assertEquals(listOf(kg("0"), kg("2")), store.outbox.map { it.value })
        assertTrue(ticked.today().isEmpty())
    }

    @Test
    fun theUndoOfStepsDownToZeroAfterPutBackHasNothingToDo() = test {
        store.synced(listOf(item("Rice", kg("1"))))
        repeat(2) {
            viewModel.stepQuantity("rice", up = false)
            settle()
        }
        val stepped = events.last() as ListEvent.Stepped
        viewModel.putBack(ticked.today().single())
        settle()
        viewModel.undoChange(stepped)
        settle()
        assertEquals(kg("1"), shown("Rice")!!.value)
        assertTrue(events.none { it is ListEvent.Message })
    }

    @Test
    fun putBackTappedTwiceWritesOnce() = test {
        store.synced(listOf(item("Rice", kg("2"))))
        viewModel.markGotIt("rice")
        settle()
        val entry = ticked.today().single()
        // The second tap comes before the row has gone.
        viewModel.putBack(entry)
        viewModel.putBack(entry)
        settle()
        assertEquals(kg("2"), shown("Rice")!!.value)
        assertEquals(listOf(kg("0"), kg("2")), store.outbox.map { it.value })
        assertEquals(listOf(ListEvent.GotIt("rice", "Rice", kg("2"), kg("0")), ListEvent.PutBack(entry)), events)
    }

    @Test
    fun putBackOfAnOlderTickOfTheItemDoesNothing() = test {
        store.synced(listOf(item("Rice", kg("2"))))
        viewModel.markGotIt("rice")
        settle()
        val first = ticked.today().single()
        viewModel.undoChange(events.last() as ListEvent.GotIt)
        settle()
        // Undone: a Put back of that tick's row, tapped as it went, finds nothing to do.
        viewModel.putBack(first)
        settle()
        assertEquals(kg("2"), shown("Rice")!!.value)
        assertEquals(1, events.size)
    }

    // ---- Ticked today and the other phone ------------------------------------------------------------------

    @Test
    fun anItemTheOtherPhoneNeededAndTickedOffAgainLeavesTickedToday() = test {
        store.synced(listOf(item("Rice", kg("2"))))
        viewModel.markGotIt("rice")
        settle()
        val entry = ticked.today().single()
        // The tick reached the sheet and came back as this phone's change: still this phone's tick.
        store.synced(listOf(item("Rice", kg("0"), by = me, updatedAt = 50_000L)))
        assertEquals(listOf(entry), shownTickedToday(ticked.today(), repository.items.first(), me))
        // Home needed Rice again (5 kg) and later ticked it off itself; this phone saw only the end of it.
        store.synced(listOf(item("Rice", kg("0"), updatedAt = 70_000L)))
        assertTrue(shownTickedToday(ticked.today(), repository.items.first(), me).isEmpty())
        // Put back from a screen that hadn't caught up yet: Rice isn't this phone's tick any more.
        viewModel.putBack(entry)
        settle()
        assertEquals("\"Rice\" was changed on another phone", lastMessage())
        assertEquals(kg("0"), shown("Rice")!!.value)
        assertTrue(ticked.today().isEmpty())
    }

    @Test
    fun aHiddenEntryIsPrunedByTheNextChangeAndAnUndoDoesNotBringItBack() = test {
        store.synced(listOf(item("Dahi", BoolValue(true))))
        viewModel.setNeeded("dahi", needed = false)
        settle()
        // Home needed Dahi again and ticked it off itself.
        store.synced(listOf(item("Dahi", BoolValue(false), updatedAt = 70_000L)))
        viewModel.snackbarClosed(events.last())
        // Needed again here, and undone: Dahi is as Home left it, which isn't this phone's tick.
        viewModel.setNeeded("dahi", needed = true)
        settle()
        assertTrue(ticked.today().isEmpty())
        viewModel.undoChange(events.last() as ListEvent.NeedChanged)
        settle()
        assertEquals(BoolValue(false), shown("Dahi")!!.value)
        assertTrue(ticked.today().isEmpty())
    }

    @Test
    fun putBackAfterAChangeOnTheOtherPhoneSaysSo() = test {
        store.synced(listOf(item("Rice", kg("2"))))
        viewModel.markGotIt("rice")
        settle()
        val entry = ticked.today().single()
        // Home changed the unit meanwhile (still not needed, so it was still listed until now).
        store.synced(listOf(item("Rice", Quantity.of(0, Units.G), updatedAt = 60_000L)))
        viewModel.putBack(entry)
        settle()
        assertEquals("\"Rice\" was changed on another phone", lastMessage())
        assertEquals(Quantity.of(0, Units.G), shown("Rice")!!.value)
        assertTrue(ticked.today().isEmpty())
    }
}
