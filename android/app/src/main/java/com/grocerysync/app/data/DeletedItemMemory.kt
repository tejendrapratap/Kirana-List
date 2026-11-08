package com.grocerysync.app.data

/**
 * Remembers the item deleted last on this phone, so that adding it again right after re-sends the item's Added At
 * ([OutboxOp.addedAt]) and the item reads "Updated by" again rather than "Added by": it is the item that was just
 * there.
 *
 * An add of an item that isn't on the list ([GroceryRepository.add], [GroceryRepository.upsert]) counts as that
 * item again when it has the deleted item's key and value and comes within [windowMs] of the delete. Only the
 * newest delete is kept, and it is used once.
 *
 * The Undo of a delete doesn't depend on this window: its snackbar carries the Added At itself
 * ([GroceryRepository.delete], [GroceryRepository.addBack]), as an Undo can wait much longer (Simple mode's stays
 * until it is tapped). The window still outlasts the usual Undo snackbar (a few seconds, up to 2 minutes with
 * Android's "Time to take action" accessibility setting). Kept in memory only. Thread-safe.
 *
 * @param elapsedMs monotonic milliseconds.
 */
class DeletedItemMemory(
    private val elapsedMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val windowMs: Long = UNDO_WINDOW_MS,
) {
    private class Deleted(val key: String, val value: ItemValue, val addedAt: Long, val at: Long)

    private var last: Deleted? = null

    /** The item [key] with [value] and Added At [addedAt] (0 = unknown) was just deleted. */
    @Synchronized
    fun remember(key: String, value: ItemValue, addedAt: Long) {
        last = Deleted(key, value, addedAt, elapsedMs())
    }

    /**
     * The Added At to send with an upsert of the item [key] with [value] that isn't on the list: the deleted
     * item's when this looks like that item again (forgotten afterwards), else 0.
     */
    @Synchronized
    fun addedAtForReAdd(key: String, value: ItemValue): Long {
        val deleted = last ?: return 0L
        if (elapsedMs() - deleted.at > windowMs) {
            last = null
            return 0L
        }
        if (deleted.key != key || deleted.value != value) return 0L
        last = null
        return deleted.addedAt
    }

    companion object {
        /** How long after a delete adding the item again counts as bringing it back. */
        const val UNDO_WINDOW_MS = 3 * 60 * 1_000L
    }
}
