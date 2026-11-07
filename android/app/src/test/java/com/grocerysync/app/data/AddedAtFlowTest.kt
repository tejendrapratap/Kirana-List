package com.grocerysync.app.data

import com.grocerysync.app.data.ItemValue.BoolValue
import com.grocerysync.app.data.ItemValue.Quantity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Added by" / "Updated by" from an edit to the sheet and back. [Phone] queues changes the way GroceryRepository
 * does (same ListLogic calls, same [DeletedItemMemory]); [Sheet] applies them the way the Added At contract
 * says Code.gs does. Each scenario checks what the row says on this phone before the sync, what is sent, and
 * that the row says the same after the sync (no flip from "Added" to "Updated" or back).
 */
class AddedAtFlowTest {

    /** Code.gs as the contract describes it: Added At is written only when a row is created. */
    private class Sheet {
        val rows = LinkedHashMap<String, GroceryItem>()

        fun put(item: GroceryItem) {
            rows[item.key] = item
        }

        fun apply(device: String, ops: List<OutboxOp>) {
            for (op in ops) {
                when (op.op) {
                    OpType.DELETE -> rows.remove(op.key)
                    OpType.UPSERT -> {
                        val existing = rows[op.key]
                        val addedAt = when {
                            existing != null -> existing.addedAt
                            op.addedAt > 0L && op.addedAt <= op.time -> op.addedAt
                            else -> op.time
                        }
                        rows[op.key] = GroceryItem(op.key, op.name, op.value!!, op.time, device, false, addedAt)
                    }
                }
            }
        }
    }

    /** One phone: the outbox and the last list from the sheet, edited like GroceryRepository edits them. */
    private class Phone(val name: String, val sheet: Sheet) {
        var mirror: List<GroceryItem> = sheet.rows.values.toList()
        val ops = mutableListOf<OutboxOp>()
        val deletedItems = DeletedItemMemory(elapsedMs = { 0L })
        /** Server time of this phone's next change: a minute later each time, well after the sheet's items. */
        var clock = 10 * 86_400_000L
        private var seq = 0L

        fun shown(): List<GroceryItem> = ListLogic.overlay(mirror, ops, name)

        fun row(name: String): GroceryItem = shown().single { it.key == ItemKeys.normalize(name) }

        /** GroceryRepository.current: the item as shown and the hint its next upsert carries. */
        private fun current(key: String): Pair<GroceryItem, Long>? {
            val item = shown().firstOrNull { it.key == key } ?: return null
            return item to ListLogic.addedAtHint(item, mirror.firstOrNull { it.key == key })
        }

        private fun queue(op: OpType, name: String, value: ItemValue?, addedAt: Long = 0L) {
            seq++
            clock += 60_000L
            ops += OutboxOp(seq, "id-$seq", op, ItemKeys.normalize(name), name, value, clock, OpStatus.PENDING, addedAt = addedAt)
        }

        fun upsert(name: String, value: ItemValue) {
            val key = ItemKeys.normalize(name)
            val hint = current(key)?.second ?: deletedItems.addedAtForReAdd(key, value)
            queue(OpType.UPSERT, name, value, hint)
        }

        fun rename(oldName: String, newName: String) {
            val (item, hint) = current(ItemKeys.normalize(oldName))!!
            queue(OpType.DELETE, item.name, null)
            queue(OpType.UPSERT, newName, item.value, hint)
        }

        fun delete(name: String) {
            val (item, hint) = current(ItemKeys.normalize(name))!!
            queue(OpType.DELETE, item.name, null)
            deletedItems.remember(item.key, item.value, hint)
        }

        /** What the next push sends. */
        fun outgoing(): List<OutboxOp> = ListLogic.coalesce(ops).map { it.op }

        fun sync() {
            sheet.apply(name, outgoing())
            ops.clear()
            mirror = sheet.rows.values.toList()
        }
    }

    private fun says(item: GroceryItem) = if (item.isUnchangedSinceAdded) "Added" else "Updated"

    private val day = 86_400_000L

    private fun sheetWith(vararg items: GroceryItem) = Sheet().apply { items.forEach(::put) }

    /** An item added by the other phone a day ago and changed since (Added At known). */
    private fun milk(addedAt: Long = 1_000L) =
        GroceryItem("milk", "Milk", Quantity.of(1, "L"), addedAt + day, "Home", false, addedAt)

    @Test
    fun anItemAddedHereSaysAddedBeforeAndAfterSyncing() {
        val phone = Phone("Shop", Sheet())
        phone.upsert("Bread", BoolValue(false))
        assertEquals("Added", says(phone.row("Bread")))
        assertEquals("Shop", phone.row("Bread").updatedBy)
        assertEquals(0L, phone.outgoing().single().addedAt)

        phone.sync()
        assertEquals("Added", says(phone.row("Bread")))
    }

    @Test
    fun anItemAddedHereAndChangedBeforeItSyncsIsStillNew() {
        val phone = Phone("Shop", Sheet())
        phone.upsert("Rice", Quantity.of(1, "kg"))
        phone.upsert("Rice", Quantity.of(2, "kg"))
        assertEquals("Added", says(phone.row("Rice")))
        phone.sync()
        assertEquals("Added", says(phone.row("Rice")))
        assertEquals(Quantity.of(2, "kg"), phone.row("Rice").value)
    }

    @Test
    fun aChangeSaysUpdatedBeforeAndAfterSyncing() {
        val sheet = sheetWith(GroceryItem("rice", "Rice", Quantity.of(1, "kg"), 5_000L, "Home", false, addedAt = 5_000L))
        val phone = Phone("Shop", sheet)
        assertEquals("Added", says(phone.row("Rice")))

        phone.upsert("Rice", Quantity.of(2, "kg"))
        assertEquals("Updated", says(phone.row("Rice")))
        phone.sync()
        assertEquals("Updated", says(phone.row("Rice")))
        assertEquals(5_000L, phone.row("Rice").addedAt)
    }

    @Test
    fun anItemFromBeforeAddedAtSaysUpdated() {
        val phone = Phone("Shop", sheetWith(milk(addedAt = 0L)))
        assertEquals("Updated", says(phone.row("Milk")))
        phone.upsert("Milk", Quantity.of(2, "L"))
        assertEquals("Updated", says(phone.row("Milk")))
        phone.sync()
        assertEquals("Updated", says(phone.row("Milk")))
    }

    @Test
    fun aRenameKeepsTheItemsAddedAt() {
        val phone = Phone("Shop", sheetWith(milk()))
        phone.rename("Milk", "Oat milk")
        assertEquals("Updated", says(phone.row("Oat milk")))
        assertEquals(1_000L, phone.row("Oat milk").addedAt)
        val sent = phone.outgoing()
        assertEquals(listOf(OpType.DELETE, OpType.UPSERT), sent.map { it.op })
        assertEquals(1_000L, sent[1].addedAt)
        val wire = JSONObject(ListLogic.buildApplyRequest("t", "Shop", sent)).getJSONArray("changes")
        assertEquals(1_000L, wire.getJSONObject(1).getLong("addedAt"))

        phone.sync()
        assertEquals("Updated", says(phone.row("Oat milk")))
        assertEquals(1_000L, phone.row("Oat milk").addedAt)
    }

    @Test
    fun aRenameFollowedByAnotherChangeStillSendsTheHint() {
        // The edit dialog renames and changes the amount in one save: two upserts of the new name, and only
        // the newest is sent.
        val phone = Phone("Shop", sheetWith(milk()))
        phone.rename("Milk", "Oat milk")
        phone.upsert("Oat milk", Quantity.of(3, "L"))
        val upsert = phone.outgoing().single { it.op == OpType.UPSERT }
        assertEquals(Quantity.of(3, "L"), upsert.value)
        assertEquals(1_000L, upsert.addedAt)
        phone.sync()
        assertEquals("Updated", says(phone.row("Oat milk")))
    }

    @Test
    fun renamingAnItemAddedHereBeforeItSyncsKeepsItNew() {
        val phone = Phone("Shop", Sheet())
        phone.upsert("Rcie", BoolValue(true))
        phone.rename("Rcie", "Rice")
        assertEquals("Added", says(phone.row("Rice")))
        assertEquals(0L, phone.outgoing().single { it.op == OpType.UPSERT }.addedAt)
        phone.sync()
        assertEquals("Added", says(phone.row("Rice")))
    }

    @Test
    fun renamingAnItemWithUnknownAddedAtSaysUpdated() {
        // An item from before 1.2: its Added At isn't known, so the rename sends the time of its last change
        // instead (it existed at least since then), and the renamed item reads "Updated by", not "Added by".
        val milk = milk(addedAt = 0L)
        val phone = Phone("Shop", sheetWith(milk))
        phone.rename("Milk", "Oat milk")
        assertEquals("Updated", says(phone.row("Oat milk")))
        assertEquals(milk.updatedAt, phone.outgoing().single { it.op == OpType.UPSERT }.addedAt)
        phone.sync()
        assertEquals("Updated", says(phone.row("Oat milk")))
        assertEquals(milk.updatedAt, phone.row("Oat milk").addedAt)
    }

    @Test
    fun undoOfASyncedDeleteOfAnItemWithUnknownAddedAtSaysUpdated() {
        val phone = Phone("Shop", sheetWith(milk(addedAt = 0L)))
        val before = phone.row("Milk")
        phone.delete("Milk")
        phone.sync()
        phone.upsert(before.name, before.value) // Undo after the delete reached the sheet
        assertEquals("Updated", says(phone.row("Milk")))
        phone.sync()
        assertEquals("Updated", says(phone.row("Milk")))
        assertEquals(before.updatedAt, phone.row("Milk").addedAt)
    }

    @Test
    fun aChangeOfAnItemWithUnknownAddedAtLeavesTheSheetsAddedAtUnknown() {
        // The hint only counts when the upsert creates the row: the row is there, so Added At stays unknown.
        val phone = Phone("Shop", sheetWith(milk(addedAt = 0L)))
        phone.upsert("Milk", Quantity.of(2, "L"))
        phone.sync()
        assertEquals(0L, phone.row("Milk").addedAt)
        assertEquals("Updated", says(phone.row("Milk")))
    }

    @Test
    fun undoOfADeleteThatReachedTheSheetBringsTheAddedAtBack() {
        val phone = Phone("Shop", sheetWith(milk()))
        val before = phone.row("Milk")
        phone.delete("Milk")
        phone.sync()
        assertTrue(phone.shown().isEmpty())

        phone.upsert(before.name, before.value) // Undo: same name and value
        assertEquals("Updated", says(phone.row("Milk")))
        assertEquals(1_000L, phone.outgoing().single().addedAt)
        phone.sync()
        assertEquals("Updated", says(phone.row("Milk")))
        assertEquals(1_000L, phone.row("Milk").addedAt)
    }

    @Test
    fun undoBeforeTheDeleteWasSentOnlySendsTheUpsert() {
        val phone = Phone("Shop", sheetWith(milk(addedAt = 0L)))
        val before = phone.row("Milk")
        phone.delete("Milk")
        phone.upsert(before.name, before.value)
        assertEquals("Updated", says(phone.row("Milk")))
        assertEquals(listOf(OpType.UPSERT), phone.outgoing().map { it.op })
        phone.sync()
        assertEquals("Updated", says(phone.row("Milk")))
    }

    @Test
    fun addingADeletedItemAgainWithOtherValuesIsANewItem() {
        val phone = Phone("Shop", sheetWith(milk()))
        phone.delete("Milk")
        phone.sync()
        phone.upsert("Milk", Quantity.of(0, "L"))
        assertEquals("Added", says(phone.row("Milk")))
        assertEquals(0L, phone.outgoing().single().addedAt)
        phone.sync()
        assertEquals("Added", says(phone.row("Milk")))
    }

    @Test
    fun aChangeThatRecreatesARowDeletedOnTheOtherPhoneKeepsItsAddedAt() {
        val sheet = sheetWith(milk())
        val phone = Phone("Shop", sheet)
        phone.upsert("Milk", Quantity.of(2, "L"))
        // Meanwhile the other phone deletes it; this phone's later change wins and re-creates the row.
        sheet.rows.remove("milk")
        assertEquals("Updated", says(phone.row("Milk")))
        phone.sync()
        assertEquals("Updated", says(phone.row("Milk")))
        assertEquals(1_000L, phone.row("Milk").addedAt)
    }

    @Test
    fun theOtherPhoneSeesTheSameTexts() {
        val sheet = Sheet()
        val home = Phone("Home", sheet)
        home.upsert("Bread", BoolValue(true))
        home.sync()
        val shop = Phone("Shop", sheet)
        assertEquals("Added", says(shop.row("Bread")))
        assertEquals("Home", shop.row("Bread").updatedBy)

        home.upsert("Bread", BoolValue(false))
        home.sync()
        shop.mirror = sheet.rows.values.toList()
        assertEquals("Updated", says(shop.row("Bread")))
        assertFalse(shop.row("Bread").pendingSync)
    }
}
