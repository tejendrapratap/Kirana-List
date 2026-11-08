package com.grocerysync.app.data

import com.grocerysync.app.data.ItemValue.BoolValue
import com.grocerysync.app.data.ItemValue.Quantity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The checks of the Undo edits in the real GroceryRepository, on a list in memory ([FakeListStore]): [updateIf] and
 * [renameIf] write only while the item still has the value this phone set, never re-create an item renamed or removed
 * on the other phone, and a rename back never overwrites an item that has taken the old name meanwhile. Each check is
 * made in the same step as the enqueue, on the list as shown (the sheet's list with this phone's waiting changes), and
 * never goes ahead over a newer row from the sheet that a waiting change hides. The Undo of a delete keeps the item's
 * Added At.
 */
class GroceryRepositoryGuardsTest {

    private val me = "Shop"
    private val store = FakeListStore()
    private val repository = GroceryRepository(
        db = store,
        settings = SettingsStore(FakeSharedPreferences()).apply { save(SyncSettings("", "", me)) },
        clock = ServerClock(localClock = { 50_000L }),
        onLocalChange = {},
        io = Dispatchers.Unconfined,
    )

    private fun item(name: String, value: ItemValue, by: String = "Home", updatedAt: Long = 1_000L) =
        GroceryItem(ItemKeys.normalize(name), name, value, updatedAt, by, pendingSync = false, addedAt = updatedAt)

    private fun shown(): List<GroceryItem> = runBlocking { repository.items.first() }

    private fun shown(name: String): GroceryItem? = shown().firstOrNull { it.key == ItemKeys.normalize(name) }

    private val kg2 = Quantity.of(2, Units.KG)
    private val kg0 = Quantity.of(0, Units.KG)

    @Test
    fun updateIfWritesWhileTheItemHasTheExpectedValue() = runBlocking {
        store.synced(listOf(item("Rice", kg0)))
        assertEquals(EditResult.Saved, repository.updateIf("rice", expected = kg0, value = kg2))
        assertEquals(kg2, shown("Rice")!!.value)
        assertEquals(1, store.outbox.size)
        assertEquals(OpType.UPSERT, store.outbox.single().op)
        assertEquals("Rice", store.outbox.single().name)
    }

    @Test
    fun updateIfNeverOverwritesAValueSetElsewhere() = runBlocking {
        // Got it set Rice to 0 kg; the other phone has since set it to 5 kg.
        store.synced(listOf(item("Rice", Quantity.of(5, Units.KG), updatedAt = 9_000L)))
        assertEquals(EditResult.ValueChanged, repository.updateIf("rice", expected = kg0, value = kg2))
        assertTrue("nothing queued", store.outbox.isEmpty())
        assertEquals(Quantity.of(5, Units.KG), shown("Rice")!!.value)
    }

    @Test
    fun updateIfNeverRecreatesAnItemRenamedOrRemovedElsewhere() = runBlocking {
        store.synced(listOf(item("Basmati rice", kg0)))
        assertEquals(EditResult.NotOnList, repository.updateIf("rice", expected = kg0, value = kg2))
        assertTrue(store.outbox.isEmpty())
        assertNull(shown("Rice"))
    }

    @Test
    fun undoOfGotItSeesItsOwnChangeStillWaiting() = runBlocking {
        // Got it, then Undo before the change reached the sheet: the list as shown has the value Got it set.
        store.synced(listOf(item("Rice", kg2)))
        assertTrue(repository.upsert("Rice", kg0))
        assertEquals(EditResult.Saved, repository.updateIf("rice", expected = kg0, value = kg2))
        assertEquals(kg2, shown("Rice")!!.value)
        assertEquals(listOf(kg0, kg2), store.outbox.map { it.value })
    }

    @Test
    fun undoOfGotItAfterTheOtherPhoneMarkedItNeededKeepsItsAmount() = runBlocking {
        // Before 1.6 the Undo of Got it was newest-wins and wrote its amount over the other phone's.
        store.synced(listOf(item("Milk", Quantity.of(2, Units.L))))
        assertTrue(repository.upsert("Milk", Quantity.of(0, Units.L)))
        // The change reached the sheet; then Home set Milk to 3 L, and that came back.
        store.synced(listOf(item("Milk", Quantity.of(3, Units.L), updatedAt = 60_000L)))
        assertEquals(EditResult.ValueChanged, repository.updateIf("milk", expected = Quantity.of(0, Units.L), value = Quantity.of(2, Units.L)))
        assertEquals(Quantity.of(3, Units.L), shown("Milk")!!.value)
    }

    @Test
    fun updateIfComparesYesNoValues() = runBlocking {
        // The Need switch was turned off here, then Dahi was made an amount on the other phone: the Undo of "off"
        // leaves it.
        store.synced(listOf(item("Dahi", kg2, updatedAt = 9_000L)))
        assertEquals(EditResult.ValueChanged, repository.updateIf("dahi", expected = BoolValue(false), value = BoolValue(true)))
        assertTrue("nothing queued", store.outbox.isEmpty())
        // Turned on again there: the Undo of "off" has nothing left to do.
        store.synced(listOf(item("Dahi", BoolValue(true), updatedAt = 9_000L)))
        assertEquals(EditResult.Saved, repository.updateIf("dahi", expected = BoolValue(false), value = BoolValue(true)))
        assertTrue("nothing queued", store.outbox.isEmpty())
        assertEquals(EditResult.Saved, repository.updateIf("dahi", expected = BoolValue(true), value = BoolValue(false)))
        assertEquals(BoolValue(false), shown("Dahi")!!.value)
    }

    @Test
    fun updateIfOfTheValueTheItemHasIsDoneWithNothingQueued() = runBlocking {
        // Got it, then Put back gave Rice its 2 kg again; the tick's Undo (or a second tap of Put back) comes after.
        store.synced(listOf(item("Rice", kg2)))
        assertTrue(repository.upsert("Rice", kg0))
        assertEquals(EditResult.Saved, repository.updateIf("rice", expected = kg0, value = kg2))
        assertEquals(EditResult.Saved, repository.updateIf("rice", expected = kg0, value = kg2))
        assertEquals(listOf(kg0, kg2), store.outbox.map { it.value })
        assertEquals(kg2, shown("Rice")!!.value)
    }

    @Test
    fun renameIfGivesBackTheOldNameAndValue() = runBlocking {
        // The Edit dialog renamed Curd (1 kg) to Dahi (2 kg); the Undo renames it back.
        store.synced(listOf(item("Curd", Quantity.of(1, Units.KG))))
        assertEquals(EditResult.Saved, repository.rename("curd", "Dahi", kg2))
        assertEquals(EditResult.Saved, repository.renameIf("dahi", expected = kg2, newName = "Curd", value = Quantity.of(1, Units.KG)))
        assertNull(shown("Dahi"))
        assertEquals(Quantity.of(1, Units.KG), shown("Curd")!!.value)
        assertEquals("Curd", shown("Curd")!!.name)
        // A rename is sent as a delete of one name and an upsert of the other, both ways.
        assertEquals(
            listOf(OpType.DELETE to "Curd", OpType.UPSERT to "Dahi", OpType.DELETE to "Dahi", OpType.UPSERT to "Curd"),
            store.outbox.map { it.op to it.name },
        )
    }

    @Test
    fun renameIfNeverOverwritesAnItemThatTookTheOldName() = runBlocking {
        // After Curd was renamed to Dahi, the other phone added a new Curd.
        store.synced(listOf(item("Dahi", kg2), item("Curd", Quantity.of(500, Units.G), updatedAt = 9_000L)))
        val result = repository.renameIf("dahi", expected = kg2, newName = "Curd", value = Quantity.of(1, Units.KG))
        assertEquals(EditResult.AlreadyOnList("Curd"), result)
        assertTrue("nothing queued", store.outbox.isEmpty())
        assertEquals(Quantity.of(500, Units.G), shown("Curd")!!.value)
        assertEquals(kg2, shown("Dahi")!!.value)
    }

    @Test
    fun renameIfNeverOverwritesAValueSetElsewhere() = runBlocking {
        store.synced(listOf(item("Dahi", Quantity.of(3, Units.KG), updatedAt = 9_000L)))
        assertEquals(EditResult.ValueChanged, repository.renameIf("dahi", expected = kg2, newName = "Curd", value = Quantity.of(1, Units.KG)))
        assertTrue(store.outbox.isEmpty())
        assertNull(shown("Curd"))
    }

    @Test
    fun renameIfNeverRecreatesAnItemRemovedElsewhere() = runBlocking {
        store.synced(emptyList())
        assertEquals(EditResult.NotOnList, repository.renameIf("dahi", expected = kg2, newName = "Curd", value = Quantity.of(1, Units.KG)))
        assertTrue(store.outbox.isEmpty())
        assertTrue(shown().isEmpty())
    }

    @Test
    fun renameIfToOtherCapitalsKeepsTheItem() = runBlocking {
        // The Edit dialog changed "rice" (1 kg) to "Rice" (2 kg): the same item, one upsert each way.
        store.synced(listOf(item("Rice", kg2)))
        assertEquals(EditResult.Saved, repository.renameIf("rice", expected = kg2, newName = "rice", value = Quantity.of(1, Units.KG)))
        assertEquals("rice", shown("rice")!!.name)
        assertEquals(Quantity.of(1, Units.KG), shown("rice")!!.value)
        assertEquals(listOf(OpType.UPSERT), store.outbox.map { it.op })
    }

    @Test
    fun renameIfRefusesANameThatCantBeSent() = runBlocking {
        store.synced(listOf(item("Dahi", kg2)))
        assertEquals(EditResult.InvalidName, repository.renameIf("dahi", expected = kg2, newName = " ", value = kg0))
        assertTrue(store.outbox.isEmpty())
    }

    // ---- A newer row from the sheet behind this phone's waiting change ----------------------------------------
    // Pushes fail while pulls still land: the list shows this phone's waiting change on top of a row the other phone
    // changed later. That waiting change will be answered "stale"; an Undo stamped now would overwrite the row.

    private val l2 = Quantity.of(2, Units.L)
    private val l0 = Quantity.of(0, Units.L)
    private val l3 = Quantity.of(3, Units.L)

    @Test
    fun updateIfNeverOverwritesANewerValueThatAWaitingChangeHides() = runBlocking {
        store.synced(listOf(item("Milk", l2)))
        assertTrue(repository.upsert("Milk", l0))
        // Home's 3 L came in after this phone's Got it, which couldn't be sent yet.
        store.pulled(listOf(item("Milk", l3, updatedAt = 60_000L)))
        assertEquals(l0, shown("Milk")!!.value)
        assertEquals(EditResult.ValueChanged, repository.updateIf("milk", expected = l0, value = l2))
        assertEquals("only the Got it waits", listOf(l0), store.outbox.map { it.value })
    }

    @Test
    fun aWaitingChangeThatFailedHidesANewerValueToo() = runBlocking {
        store.synced(listOf(item("Milk", l2)))
        assertTrue(repository.upsert("Milk", l0))
        store.outbox.replaceAll { it.copy(status = OpStatus.FAILED, error = "rejected") }
        store.pulled(listOf(item("Milk", l3, updatedAt = 60_000L)))
        assertEquals(EditResult.ValueChanged, repository.updateIf("milk", expected = l0, value = l2))
        assertEquals(1, store.outbox.size)
    }

    @Test
    fun thisPhonesOwnChangeBackFromTheSheetIsNotNewer() = runBlocking {
        store.synced(listOf(item("Milk", l2)))
        assertTrue(repository.upsert("Milk", l0))
        // It reached the sheet but the answer was lost: the row has the change's own time.
        val sent = store.outbox.single()
        store.pulled(listOf(item("Milk", l0, by = me, updatedAt = sent.time)))
        assertEquals(EditResult.Saved, repository.updateIf("milk", expected = l0, value = l2))
        assertEquals(listOf(l0, l2), store.outbox.map { it.value })
    }

    @Test
    fun deleteIfNeverDeletesANewerValueThatAWaitingChangeHides() = runBlocking {
        // Add common items queued Oil; then an Oil changed later on the other phone came in from the sheet.
        store.synced(emptyList())
        assertTrue(repository.upsert("Oil", l2))
        store.pulled(listOf(item("Oil", l3, updatedAt = 60_000L)))
        assertEquals(EditResult.ValueChanged, repository.deleteIf("oil", expected = l2))
        assertEquals(listOf(OpType.UPSERT), store.outbox.map { it.op })
    }

    @Test
    fun renameIfNeverOverwritesANewerRowThatAWaitingDeleteHides() = runBlocking {
        // Curd was renamed to Dahi (a delete of Curd and an upsert of Dahi, both waiting); then the sheet's Curd
        // was changed on the other phone, and that came in.
        store.synced(listOf(item("Curd", kg2)))
        assertEquals(EditResult.Saved, repository.rename("curd", "Dahi"))
        store.pulled(listOf(item("Curd", Quantity.of(500, Units.G), updatedAt = 60_000L)))
        assertNull(shown("Curd"))
        val result = repository.renameIf("dahi", expected = kg2, newName = "Curd", value = kg2)
        assertEquals(EditResult.AlreadyOnList("Curd"), result)
        assertEquals(2, store.outbox.size)
    }

    @Test
    fun addNeverOverwritesANewerRowThatAWaitingDeleteHides() = runBlocking {
        store.synced(listOf(item("Milk", l2)))
        assertEquals(EditResult.Saved, repository.delete("milk").result)
        store.pulled(listOf(item("Milk", l3, updatedAt = 60_000L)))
        assertEquals(EditResult.AlreadyOnList("Milk"), repository.add("Milk", l2))
        assertEquals(EditResult.AlreadyOnList("Milk"), repository.addBack("Milk", l2, addedAt = 1_000L))
        assertEquals(listOf(OpType.DELETE), store.outbox.map { it.op })
    }

    @Test
    fun theUndoOfADeleteStillWaitingGoesAhead() = runBlocking {
        // The sheet's row is the one this phone deleted: older than the delete.
        store.synced(listOf(item("Milk", l2)))
        assertEquals(EditResult.Saved, repository.delete("milk").result)
        assertEquals(EditResult.Saved, repository.addBack("Milk", l2, addedAt = 1_000L))
        assertEquals(l2, shown("Milk")!!.value)
    }

    // ---- Delete and its Undo ---------------------------------------------------------------------------------

    @Test
    fun theUndoOfADeleteKeepsTheAddedAtHoweverLongItWaited() = runBlocking {
        var elapsed = 0L
        val repository = GroceryRepository(
            db = store,
            settings = SettingsStore(FakeSharedPreferences()).apply { save(SyncSettings("", "", me)) },
            clock = ServerClock(localClock = { 50_000L }),
            onLocalChange = {},
            io = Dispatchers.Unconfined,
            deletedItems = DeletedItemMemory(elapsedMs = { elapsed }),
        )
        store.synced(listOf(item("Ghee", kg2)))
        assertEquals(GroceryRepository.Deletion(EditResult.Saved, addedAt = 1_000L), repository.delete("ghee"))
        store.synced(emptyList())
        // Simple mode's Undo stays until it is tapped: long after the time for adding it again by hand.
        elapsed += DeletedItemMemory.UNDO_WINDOW_MS + 60_000L
        assertEquals(EditResult.Saved, repository.addBack("Ghee", kg2, addedAt = 1_000L))
        assertEquals(1_000L, store.outbox.single().addedAt)
    }

    @Test
    fun aDeleteOfAnItemThatWentDeletesNothing() = runBlocking {
        store.synced(listOf(item("Basmati rice", kg2)))
        assertEquals(EditResult.NotOnList, repository.delete("rice").result)
        assertTrue(store.outbox.isEmpty())
    }
}
