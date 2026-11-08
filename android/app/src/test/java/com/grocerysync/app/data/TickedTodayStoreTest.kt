package com.grocerysync.app.data

import com.grocerysync.app.data.ItemValue.BoolValue
import com.grocerysync.app.data.ItemValue.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * "Ticked today" on this phone ([TickedTodayStore]): one entry per item from its newest tick, gone after local
 * midnight, removed by the tick's Undo, put back by the Undo of a change that took it away, kept across restarts,
 * and shown only while the item is still as this phone left it ([shownTickedToday], [TickedEntry.isStillTicked]).
 */
class TickedTodayStoreTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val prefs = FakeSharedPreferences()

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    /** This phone's clock: Sunday 8 Nov 2026, 18:00 in India. */
    private var now = at(8, 18)

    private fun store() = TickedTodayStore(prefs, clock = { now }, zone = { zone })

    private val rice2 = Quantity.of(2, Units.KG)
    private val rice0 = Quantity.of(0, Units.KG)

    /** This phone's name. */
    private val me = "Shop"

    private fun item(name: String, value: ItemValue, by: String = me, pending: Boolean = false) =
        GroceryItem(ItemKeys.normalize(name), name, value, 1_000L, by, pendingSync = pending)

    @Test
    fun recordsWhatTheItemHadAndWhatWasSet() {
        val store = store()
        store.record("rice", "Rice", before = rice2, set = rice0)
        assertEquals(listOf(TickedEntry("rice", "Rice", rice2, rice0, now)), store.today())
        assertFalse(store.isEmpty)
    }

    @Test
    fun anItemTickedAgainKeepsOneEntryFromItsNewestTick() {
        val store = store()
        store.record("rice", "Rice", before = rice2, set = rice0)
        now += 60_000L
        store.record("dahi", "Dahi", before = BoolValue(true), set = BoolValue(false))
        // Put back, then ticked again an hour later with another amount.
        now += 3_600_000L
        store.record("rice", "Rice", before = Quantity.of(3, Units.KG), set = rice0)
        val today = store.today()
        assertEquals(listOf("rice", "dahi"), today.map { it.key })
        assertEquals(Quantity.of(3, Units.KG), today.first().before)
        assertEquals(now, today.first().at)
    }

    @Test
    fun entriesFromBeforeMidnightAreDroppedWhenRead() {
        now = at(8, 23, 50)
        val store = store()
        store.record("rice", "Rice", before = rice2, set = rice0)
        assertEquals(1, store.today().size)
        // Ten minutes later it is the next day in India.
        now = at(9, 0, 0)
        store.record("dahi", "Dahi", before = BoolValue(true), set = BoolValue(false))
        assertEquals(listOf("dahi"), store.today().map { it.key })
        // Gone from storage too: a restart doesn't bring it back.
        assertEquals(listOf("dahi"), store().today().map { it.key })
        assertTrue(prefs.values.keys.none { it.endsWith("rice") })
    }

    @Test
    fun anEntryDatedLaterThanTodayIsDropped() {
        // Ticked while the phone's clock was a day ahead; the clock was set right since.
        now = at(9, 10)
        store().record("rice", "Rice", before = rice2, set = rice0)
        now = at(8, 18)
        assertTrue(store().today().isEmpty())
    }

    @Test
    fun theUndoOfATickRemovesItsEntry() {
        val store = store()
        store.record("rice", "Rice", before = rice2, set = rice0)
        store.record("dahi", "Dahi", before = BoolValue(true), set = BoolValue(false))
        val changes = store.changes.value
        store.remove("rice")
        assertEquals(listOf("dahi"), store.today().map { it.key })
        assertTrue("a change for the list to read", store.changes.value > changes)
        // Removing one that isn't there changes nothing.
        val after = store.changes.value
        store.remove("rice")
        assertEquals(after, store.changes.value)
        assertEquals(listOf("dahi"), store().today().map { it.key })
    }

    @Test
    fun entriesComeBackAfterARestartNewestFirst() {
        val store = store()
        store.record("dahi", "Dahi", before = BoolValue(true), set = BoolValue(false))
        now += 60_000L
        store.record("milk", "Milk", before = Quantity(Amount(1_500L), Units.L), set = Quantity(Amount.ZERO, Units.L))
        now += 60_000L
        store.record("eggs", "Eggs", before = Quantity.of(6, ""), set = Quantity.of(0, ""))
        assertEquals(store.today(), store().today())
        assertEquals(listOf("eggs", "milk", "dahi"), store().today().map { it.key })
        assertEquals(Quantity(Amount(1_500L), Units.L), store().today()[1].before)
    }

    @Test
    fun anUnreadableEntryIsDropped() {
        store().record("rice", "Rice", before = rice2, set = rice0)
        prefs.values["entry:dahi"] = "not json"
        prefs.values["entry:jam"] = """{"name":"Jam","before":{"type":"mango"},"set":{"type":"boolean","value":"false"},"at":1}"""
        assertEquals(listOf("rice"), store().today().map { it.key })
        assertFalse(prefs.values.containsKey("entry:dahi"))
        assertFalse(prefs.values.containsKey("entry:jam"))
    }

    @Test
    fun withoutPreferencesEntriesStayInMemory() {
        val store = TickedTodayStore(prefs = null, clock = { now }, zone = { zone })
        store.record("rice", "Rice", before = rice2, set = rice0)
        assertEquals(1, store.today().size)
        assertTrue(prefs.values.isEmpty())
    }

    @Test
    fun anEntryIsShownOnlyWhileItsItemIsAsThisPhoneLeftIt() {
        val rice = TickedEntry("rice", "Rice", rice2, rice0, now)
        assertTrue(rice.isStillTicked(item("Rice", rice0), me))
        // Needed again, changed otherwise, renamed (its key is gone) or removed.
        assertFalse(rice.isStillTicked(item("Rice", Quantity.of(1, Units.KG)), me))
        assertFalse(rice.isStillTicked(item("Rice", Quantity.of(0, Units.G)), me))
        assertFalse(rice.isStillTicked(null, me))
        val dahi = TickedEntry("dahi", "Dahi", BoolValue(true), BoolValue(false), now)
        assertTrue(dahi.isStillTicked(item("Dahi", BoolValue(false)), me))
        assertFalse(dahi.isStillTicked(item("Dahi", BoolValue(true)), me))
    }

    @Test
    fun anItemTickedOffAgainOnTheOtherPhoneIsNotThisPhonesTick() {
        val rice = TickedEntry("rice", "Rice", rice2, rice0, now)
        // Home needed Rice again and ticked it off itself: the same value, but not this phone's tick.
        assertFalse(rice.isStillTicked(item("Rice", rice0, by = "Home"), me))
        // This phone's own change, waiting or come back from the sheet (spaces and capitals don't count).
        assertTrue(rice.isStillTicked(item("Rice", rice0, by = "Home", pending = true), me))
        assertTrue(rice.isStillTicked(item("Rice", rice0, by = " shop "), me))
        // A row without a name is nobody's change: only the value counts.
        assertTrue(rice.isStillTicked(item("Rice", rice0, by = ""), me))
        assertTrue(shownTickedToday(listOf(rice), listOf(item("Rice", rice0, by = "Home")), me).isEmpty())
    }

    @Test
    fun theListShowsTheEntriesStillTickedWithTheItemsNameNow() {
        val entries = listOf(
            TickedEntry("rice", "Rice", rice2, rice0, now),
            TickedEntry("dahi", "Dahi", BoolValue(true), BoolValue(false), now - 1),
            TickedEntry("jam", "Jam", BoolValue(true), BoolValue(false), now - 2),
            TickedEntry("milk", "Milk", BoolValue(true), BoolValue(false), now - 3),
        )
        // Rice was re-spelled on the other phone; Dahi is needed again; Jam was renamed; Milk is as it was left.
        val items = listOf(item("rice", rice0), item("Dahi", BoolValue(true)), item("Strawberry jam", BoolValue(false)), item("Milk", BoolValue(false)))
        val shown = shownTickedToday(entries, items, me)
        assertEquals(listOf("rice", "milk"), shown.map { it.key })
        assertEquals("rice", shown.first().name)
    }

    @Test
    fun retainPrunesTheEntriesNotShown() {
        val store = store()
        store.record("rice", "Rice", before = rice2, set = rice0)
        store.record("dahi", "Dahi", before = BoolValue(true), set = BoolValue(false))
        val items = listOf(item("Rice", Quantity.of(1, Units.KG)), item("Dahi", BoolValue(false))).associateBy { it.key }
        store.retain { it.isStillTicked(items[it.key], me) }
        assertEquals(listOf("dahi"), store.today().map { it.key })
        assertEquals(listOf("dahi"), store().today().map { it.key })
        // Nothing to prune: no change.
        val changes = store.changes.value
        store.retain { true }
        assertEquals(changes, store.changes.value)
    }

    @Test
    fun anEntryTakenAwayComesBackAsItWas() {
        val store = store()
        store.record("rice", "Rice", before = rice2, set = rice0)
        now += 60_000L
        store.record("dahi", "Dahi", before = BoolValue(true), set = BoolValue(false))
        val rice = store.entryOf("rice")!!
        // + on Rice took it away; the + was undone a minute later.
        store.retain { it.key != "rice" }
        now += 60_000L
        val changes = store.changes.value
        store.restore(rice)
        assertTrue("a change for the list to read", store.changes.value > changes)
        // Its own time, so it keeps its place: older than Dahi's tick.
        assertEquals(listOf("dahi", "rice"), store.today().map { it.key })
        assertEquals(rice, store.entryOf("rice"))
        assertEquals(store.today(), store().today())
    }

    @Test
    fun aNewerTickOrAnotherDayIsNotReplaced() {
        val store = store()
        store.record("rice", "Rice", before = rice2, set = rice0)
        val old = store.entryOf("rice")!!
        now += 60_000L
        store.record("rice", "Rice", before = Quantity.of(3, Units.KG), set = rice0)
        store.restore(old)
        assertEquals(Quantity.of(3, Units.KG), store.entryOf("rice")!!.before)
        // Taken away before midnight, undone after it: yesterday's tick stays gone.
        store.remove("rice")
        now = at(9, 0, 5)
        store.restore(old)
        assertTrue(store.today().isEmpty())
    }

    @Test
    fun holdsOnlyTheSameTickOfTheItem() {
        val store = store()
        store.record("rice", "Rice", before = rice2, set = rice0)
        val first = store.entryOf("rice")!!
        // The list shows the item's name as it is now: still the same entry.
        assertTrue(store.holds(first.copy(name = "rice")))
        // Ticked again later: a tap on the first entry's row no longer counts.
        now += 60_000L
        store.record("rice", "Rice", before = rice2, set = rice0)
        val second = store.entryOf("rice")!!
        assertFalse(store.holds(first))
        assertTrue(store.holds(second))
        // Put back or undone: gone.
        store.remove("rice")
        assertFalse(store.holds(second))
        assertNull(store.entryOf("rice"))
    }
}
