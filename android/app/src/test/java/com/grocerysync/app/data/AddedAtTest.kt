package com.grocerysync.app.data

import com.grocerysync.app.data.ItemValue.BoolValue
import com.grocerysync.app.data.ItemValue.Quantity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** GroceryItem.addedAt: the rule for "Added by", the optimistic list, the hints queued and the wire format. */
class AddedAtTest {

    private val me = "Shop"

    private fun item(name: String, updatedAt: Long, addedAt: Long, value: ItemValue = BoolValue(true), by: String = "Home") =
        GroceryItem(ItemKeys.normalize(name), name, value, updatedAt, by, pendingSync = false, addedAt = addedAt)

    private fun upsert(seq: Long, name: String, time: Long, addedAt: Long = 0L, value: ItemValue = BoolValue(true)) =
        OutboxOp(seq, "id-$seq", OpType.UPSERT, ItemKeys.normalize(name), name, value, time, OpStatus.PENDING, addedAt = addedAt)

    private fun delete(seq: Long, name: String, time: Long) =
        OutboxOp(seq, "id-$seq", OpType.DELETE, ItemKeys.normalize(name), name, null, time, OpStatus.PENDING)

    // ---- the rule ----

    @Test
    fun unchangedSinceAddedWithinOneSecond() {
        assertTrue(item("A", 5_000L, addedAt = 5_000L).isUnchangedSinceAdded)
        assertTrue(item("A", 5_000L, addedAt = 4_001L).isUnchangedSinceAdded)
        assertTrue(item("A", 5_000L, addedAt = 5_999L).isUnchangedSinceAdded)
        assertFalse(item("A", 5_000L, addedAt = 4_000L).isUnchangedSinceAdded)
        assertFalse(item("A", 5_000L, addedAt = 6_000L).isUnchangedSinceAdded)
        // Unknown Added At: "Updated by".
        assertFalse(item("A", 500L, addedAt = 0L).isUnchangedSinceAdded)
        assertFalse(item("A", 0L, addedAt = 0L).isUnchangedSinceAdded)
    }

    // ---- overlay ----

    @Test
    fun mirrorItemsKeepTheirAddedAt() {
        val mirror = listOf(item("Milk", 9_000L, addedAt = 2_000L), item("Eggs", 9_000L, addedAt = 0L))
        assertEquals(mirror.sortedBy { it.name }, ListLogic.overlay(mirror, emptyList(), me))
    }

    @Test
    fun newLocalItemReadsAddedByMeJustNow() {
        val bread = ListLogic.overlay(emptyList(), listOf(upsert(1, "Bread", time = 7_000L)), me).single()
        assertEquals(7_000L, bread.addedAt)
        assertEquals(7_000L, bread.updatedAt)
        assertEquals(me, bread.updatedBy)
        assertTrue(bread.isUnchangedSinceAdded)
    }

    @Test
    fun newLocalItemChangedAgainBeforeItSyncsStillReadsAdded() {
        // The sheet only gets the newest change and creates the row from it.
        val ops = listOf(upsert(1, "Bread", time = 7_000L), upsert(2, "Bread", time = 60_000L))
        val bread = ListLogic.overlay(emptyList(), ops, me).single()
        assertEquals(60_000L, bread.addedAt)
        assertTrue(bread.isUnchangedSinceAdded)
    }

    @Test
    fun localChangeOfAnItemOnTheSheetKeepsItsAddedAt() {
        val mirror = listOf(item("Milk", 9_000L, addedAt = 2_000L), item("Eggs", 9_000L, addedAt = 0L))
        val ops = listOf(upsert(1, "Milk", time = 20_000L), upsert(2, "Eggs", time = 20_000L))
        val shown = ListLogic.overlay(mirror, ops, me).associateBy { it.name }
        assertEquals(2_000L, shown.getValue("Milk").addedAt)
        assertFalse(shown.getValue("Milk").isUnchangedSinceAdded)
        assertEquals(0L, shown.getValue("Eggs").addedAt)
        assertFalse(shown.getValue("Eggs").isUnchangedSinceAdded)
    }

    @Test
    fun renamedItemKeepsTheHintItWasQueuedWith() {
        val mirror = listOf(item("Bred", 9_000L, addedAt = 2_000L))
        val ops = listOf(delete(1, "Bred", 20_000L), upsert(2, "Bread", time = 20_000L, addedAt = 2_000L))
        val bread = ListLogic.overlay(mirror, ops, me).single()
        assertEquals("Bread", bread.name)
        assertEquals(2_000L, bread.addedAt)
        assertFalse(bread.isUnchangedSinceAdded)
    }

    @Test
    fun aHintTheSheetWouldIgnoreIsNotShown() {
        // The sheet only takes 0 < addedAt <= the change's time.
        assertEquals(20_000L, ListLogic.addedAtAfter(upsert(1, "A", time = 20_000L, addedAt = 30_000L), null))
        assertEquals(20_000L, ListLogic.addedAtAfter(upsert(1, "A", time = 20_000L, addedAt = 20_000L), null))
        assertEquals(19_999L, ListLogic.addedAtAfter(upsert(1, "A", time = 20_000L, addedAt = 19_999L), null))
        assertEquals(20_000L, ListLogic.addedAtAfter(upsert(1, "A", time = 20_000L, addedAt = -5L), null))
    }

    @Test
    fun deleteAndUndoQueuedTogetherKeepTheRowsAddedAt() {
        // Only the upsert is sent (coalesce), and the sheet still has the row: its Added At stays.
        val mirror = listOf(item("Milk", 9_000L, addedAt = 0L))
        val ops = listOf(delete(1, "Milk", 20_000L), upsert(2, "Milk", time = 21_000L))
        assertEquals(0L, ListLogic.overlay(mirror, ops, me).single().addedAt)
        assertEquals(listOf("id-2"), ListLogic.coalesce(ops).map { it.op.id })
    }

    // ---- hints ----

    @Test
    fun hintIsTheKnownAddedAt() {
        val onSheet = item("Milk", 9_000L, addedAt = 2_000L)
        assertEquals(2_000L, ListLogic.addedAtHint(onSheet, onSheet))
        val changedHere = onSheet.copy(updatedAt = 20_000L, pendingSync = true)
        assertEquals(2_000L, ListLogic.addedAtHint(changedHere, onSheet))
        // Unknown (an item from before 1.2): the time of its last change on the sheet, when it existed for sure.
        val old = item("Eggs", 9_000L, addedAt = 0L)
        assertEquals(9_000L, ListLogic.addedAtHint(old, old))
        assertEquals(9_000L, ListLogic.addedAtHint(old.copy(updatedAt = 20_000L, pendingSync = true), old))
        // Not even that known: nothing.
        val noTimes = item("Jam", 0L, addedAt = 0L)
        assertEquals(0L, ListLogic.addedAtHint(noTimes, noTimes))
        // Renamed here, not on the sheet yet: the hint it was queued with goes on.
        val renamed = item("Bread", 20_000L, addedAt = 2_000L).copy(pendingSync = true)
        assertEquals(2_000L, ListLogic.addedAtHint(renamed, null))
    }

    @Test
    fun noHintForAnItemAddedHereThatIsNotOnTheSheetYet() {
        val new = item("Bread", 7_000L, addedAt = 7_000L).copy(pendingSync = true)
        assertEquals(0L, ListLogic.addedAtHint(new, null))
        // Once on the sheet, its Added At is known.
        val synced = item("Bread", 7_000L, addedAt = 7_000L)
        assertEquals(7_000L, ListLogic.addedAtHint(synced, synced))
    }

    // ---- wire ----

    @Test
    fun parsesAddedAtFromTheList() {
        fun parse(extra: String) =
            ListLogic.parseItem(JSONObject("""{"name":"Rice","type":"boolean","value":true,"updatedAt":1794052740000,"updatedBy":"Home"$extra}"""))!!
        assertEquals(1794052740000L, parse(""","addedAt":1794052740000""").addedAt)
        assertEquals(1794052000000L, parse(""","addedAt":1794052000000.4""").addedAt)
        assertEquals(0L, parse("").addedAt)
        assertEquals(0L, parse(""","addedAt":null""").addedAt)
        assertEquals(0L, parse(""","addedAt":"2026-11-07"""").addedAt)
        assertEquals(0L, parse(""","addedAt":-3""").addedAt)
        // Still a valid item without it, as a 1.1 script sends it.
        assertEquals(GroceryItem("rice", "Rice", BoolValue(true), 1794052740000L, "Home", false), parse(""))
    }

    @Test
    fun applyRequestCarriesTheHintOfUpsertsOnly() {
        val ops = listOf(
            upsert(1, "Bread", time = 20_000L, addedAt = 2_000L, value = Quantity.of(2, "pcs")),
            upsert(2, "Milk", time = 20_000L),
            delete(3, "Jam", 20_000L).copy(addedAt = 2_000L),
        )
        val changes = JSONObject(ListLogic.buildApplyRequest("t", "Shop", ops)).getJSONArray("changes")
        assertEquals(2_000L, changes.getJSONObject(0).getLong("addedAt"))
        assertFalse(changes.getJSONObject(1).has("addedAt"))
        assertFalse(changes.getJSONObject(2).has("addedAt"))
        // Everything else as before.
        assertEquals(20_000L, changes.getJSONObject(0).getLong("time"))
        assertEquals("pcs", changes.getJSONObject(0).getString("unit"))
    }

    @Test
    fun coalescingSendsTheNewestOpWithItsHint() {
        val ops = listOf(upsert(1, "Bread", time = 20_000L, addedAt = 2_000L), upsert(2, "Bread", time = 21_000L, addedAt = 2_000L))
        assertEquals(2_000L, ListLogic.coalesce(ops).single().op.addedAt)
    }
}
