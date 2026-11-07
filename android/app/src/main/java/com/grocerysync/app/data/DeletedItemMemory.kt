package com.grocerysync.app.data

/**
 * Remembers the item deleted last on this phone, so that its Undo can re-send the item's Added At
 * ([OutboxOp.addedAt]) and the item reads "Updated by" again rather than "Added by".
 *
 * The Undo of a delete reaches [GroceryRepository.upsert] like any other upsert: the name and the exact value
 * the item had. So an upsert of an item that isn't on the list any more counts as that Undo when it has the
 * deleted item's key and value and comes within [windowMs] of the delete. Only the newest delete is kept (a
 * new snackbar replaces the old one, so only its Undo can still be tapped), and it is used once.
 *
 * The window is longer than the Undo snackbar stays up (a few seconds, up to 2 minutes with Android's "Time to
 * take action" accessibility setting). Adding the same item with the same value again within that time
 * therefore also reads "Updated by": it is the item that was just there. Kept in memory only (the snackbar
 * doesn't survive the process either). Thread-safe.
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
     * item's when this looks like its Undo (forgotten afterwards), else 0.
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
        /** How long after a delete an Undo is recognised. */
        const val UNDO_WINDOW_MS = 3 * 60 * 1_000L
    }
}
