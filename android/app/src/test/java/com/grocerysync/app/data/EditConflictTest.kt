package com.grocerysync.app.data

import com.grocerysync.app.data.ItemValue.BoolValue
import com.grocerysync.app.data.ItemValue.Quantity
import com.grocerysync.app.ui.ItemSavePlan
import com.grocerysync.app.ui.planItemSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The repository's last check before it queues an edit ([ListLogic.editConflict]), made on the list as shown
 * ([ListLogic.overlay]) in the same transaction as the enqueue: adding, renaming or undoing a delete never
 * overwrites another item, and an Undo of "Got it" never re-creates an item that was renamed meanwhile.
 */
class EditConflictTest {

    private val me = "Shop"

    private fun item(name: String, value: ItemValue, by: String = "Home", updatedAt: Long = 1_000L) =
        GroceryItem(ItemKeys.normalize(name), name, value, updatedAt, by, false, addedAt = updatedAt)

    private fun shown(mirror: List<GroceryItem>, ops: List<OutboxOp> = emptyList(), name: String): GroceryItem? =
        ListLogic.overlay(mirror, ops, me).firstOrNull { it.key == ItemKeys.normalize(name) }

    @Test
    fun createNeedsAFreeName() {
        val rice = item("Rice", Quantity.of(2, "kg"))
        assertEquals(EditResult.AlreadyOnList("Rice"), ListLogic.editConflict(EditIntent.CREATE, rice))
        assertNull(ListLogic.editConflict(EditIntent.CREATE, null))
    }

    @Test
    fun updateNeedsTheItem() {
        assertEquals(EditResult.NotOnList, ListLogic.editConflict(EditIntent.UPDATE, null))
        assertNull(ListLogic.editConflict(EditIntent.UPDATE, item("Rice", Quantity.of(2, "kg"))))
    }

    @Test
    fun anyEditGoesAhead() {
        assertNull(ListLogic.editConflict(EditIntent.ANY, null))
        assertNull(ListLogic.editConflict(EditIntent.ANY, item("Rice", Quantity.of(2, "kg"))))
    }

    @Test
    fun anItemThatArrivesAfterTheDialogsCheckIsNotOverwritten() {
        // The Add dialog checked a list without Rice, so saving plans an upsert...
        val before = listOf(item("Bread", BoolValue(true)))
        assertEquals(ItemSavePlan.Upsert("rice", Quantity.of(1, "kg")), planItemSave(null, null, "rice", Quantity.of(1, "kg"), before))
        // ...then a pull lands with the other phone's Rice before the change is queued: the repository refuses it.
        val after = before + item("Rice", Quantity.of(2, "kg"))
        assertEquals(EditResult.AlreadyOnList("Rice"), ListLogic.editConflict(EditIntent.CREATE, shown(after, name = "rice")))
    }

    @Test
    fun undoOfADeleteDoesNotOverwriteAnItemThatTookTheNameMeanwhile() {
        // Shop deleted Rice (2 kg); Home then renamed Basmati (500 g) to Rice, and Shop pulled that.
        val homesRice = item("Rice", Quantity.of(500, "g"), updatedAt = 5_000L)
        assertEquals(EditResult.AlreadyOnList("Rice"), ListLogic.editConflict(EditIntent.CREATE, shown(listOf(homesRice), name = "Rice")))
        // Nothing took the name: the Undo adds it back.
        assertNull(ListLogic.editConflict(EditIntent.CREATE, shown(emptyList(), name = "Rice")))
        // Undo before the delete was sent: the delete is still queued, so the item isn't shown and comes back.
        val delete = OutboxOp(1, "id-1", OpType.DELETE, "rice", "Rice", null, 2_000L, OpStatus.PENDING)
        assertNull(ListLogic.editConflict(EditIntent.CREATE, shown(listOf(item("Rice", Quantity.of(2, "kg"))), listOf(delete), "Rice")))
    }

    @Test
    fun aPastedUpdateNeedsTheValueThePreviewShowed() {
        // The preview planned Rice 2 kg -> 3 kg; a sync landed halfway through the paste with Rice set to 5 kg.
        val rice2 = Quantity.of(2, "kg")
        assertNull(ListLogic.updateConflict(rice2, shown(listOf(item("Rice", rice2)), name = "Rice")))
        assertEquals(EditResult.ValueChanged, ListLogic.updateConflict(rice2, shown(listOf(item("Rice", Quantity.of(5, "kg"))), name = "Rice")))
        // Marked needed there meanwhile: not "marked" again on top of it.
        assertEquals(EditResult.ValueChanged, ListLogic.updateConflict(BoolValue(false), item("Dahi", BoolValue(true))))
        // Deleted or renamed there: never re-created under the old name.
        assertEquals(EditResult.NotOnList, ListLogic.updateConflict(rice2, shown(listOf(item("Basmati rice", rice2)), name = "Rice")))
        // A change of its own still queued on this phone counts: it is what the preview showed.
        val mine = OutboxOp(1, "id-1", OpType.UPSERT, "rice", "Rice", Quantity.of(3, "kg"), 2_000L, OpStatus.PENDING)
        assertNull(ListLogic.updateConflict(Quantity.of(3, "kg"), shown(listOf(item("Rice", rice2)), listOf(mine), "Rice")))
    }

    @Test
    fun undoOfGotItDoesNotBringBackANameThatWasRenamed() {
        // Got it on Milk, then the other phone renamed it to Oat milk: the Undo has nothing to change.
        val oatMilk = item("Oat milk", Quantity.of(0, "L"))
        assertEquals(EditResult.NotOnList, ListLogic.editConflict(EditIntent.UPDATE, shown(listOf(oatMilk), name = "Milk")))
        // Still there: the previous value goes back.
        assertNull(ListLogic.editConflict(EditIntent.UPDATE, shown(listOf(item("Milk", Quantity.of(0, "L"))), name = "Milk")))
    }
}
