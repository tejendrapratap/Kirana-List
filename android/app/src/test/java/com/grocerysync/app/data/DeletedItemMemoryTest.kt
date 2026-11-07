package com.grocerysync.app.data

import com.grocerysync.app.data.ItemValue.BoolValue
import com.grocerysync.app.data.ItemValue.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeletedItemMemoryTest {

    private var elapsed = 0L
    private val memory = DeletedItemMemory(elapsedMs = { elapsed }, windowMs = 180_000L)

    private val rice = Quantity.of(2, "kg")

    @Test
    fun undoGetsTheDeletedItemsAddedAtOnce() {
        memory.remember("rice", rice, addedAt = 1_234L)
        elapsed = 4_000L
        assertEquals(1_234L, memory.addedAtForReAdd("rice", rice))
        assertEquals(0L, memory.addedAtForReAdd("rice", rice))
    }

    @Test
    fun anotherItemOrAnotherValueIsNotTheUndo() {
        memory.remember("rice", rice, addedAt = 1_234L)
        assertEquals(0L, memory.addedAtForReAdd("milk", rice))
        assertEquals(0L, memory.addedAtForReAdd("rice", Quantity.of(0, "kg")))
        assertEquals(0L, memory.addedAtForReAdd("rice", BoolValue(true)))
        // Those didn't use it up.
        assertEquals(1_234L, memory.addedAtForReAdd("rice", rice))
    }

    @Test
    fun onlyTheNewestDeleteIsKept() {
        memory.remember("rice", rice, addedAt = 1_234L)
        memory.remember("milk", BoolValue(true), addedAt = 5_678L)
        assertEquals(0L, memory.addedAtForReAdd("rice", rice))
        assertEquals(5_678L, memory.addedAtForReAdd("milk", BoolValue(true)))
    }

    @Test
    fun forgottenAfterTheWindow() {
        memory.remember("rice", rice, addedAt = 1_234L)
        elapsed = 180_001L
        assertEquals(0L, memory.addedAtForReAdd("rice", rice))
        elapsed = 0L
        assertEquals(0L, memory.addedAtForReAdd("rice", rice))
    }

    @Test
    fun theWindowOutlastsTheUndoSnackbar() {
        // Up to 2 minutes with Android's "Time to take action" accessibility setting.
        memory.remember("rice", rice, addedAt = 1_234L)
        elapsed = 120_000L
        assertEquals(1_234L, memory.addedAtForReAdd("rice", rice))
        assertTrue(DeletedItemMemory.UNDO_WINDOW_MS > 120_000L)
    }

    @Test
    fun unknownAddedAtStaysUnknown() {
        memory.remember("rice", rice, addedAt = 0L)
        assertEquals(0L, memory.addedAtForReAdd("rice", rice))
    }

    @Test
    fun nothingDeletedNothingToSend() {
        assertEquals(0L, memory.addedAtForReAdd("rice", rice))
    }
}
