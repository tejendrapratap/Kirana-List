package com.grocerysync.app.data

import kotlin.math.abs

/**
 * One row of the shared list as shown in the app.
 *
 * @param key normalized name ([ItemKeys.normalize]); unique within the list.
 * @param updatedAt epoch millis of the last change (server time for synced rows).
 * @param pendingSync true while a local change for this item has not been confirmed by the sheet.
 * @param addedAt epoch millis when the item was added (the sheet's "Added At"); 0 = unknown: rows from
 *   before Added At existed, or a script that doesn't report it. See [isUnchangedSinceAdded].
 */
data class GroceryItem(
    val key: String,
    val name: String,
    val value: ItemValue,
    val updatedAt: Long,
    val updatedBy: String,
    val pendingSync: Boolean,
    val addedAt: Long = 0L,
) {
    /**
     * True while nobody has changed the item since it was added: its Added At is known and its last change
     * is that same moment (the sheet writes both with one value when it creates the row; the tolerance
     * covers times stored to the second). Rows then say "Added by", otherwise "Updated by".
     */
    val isUnchangedSinceAdded: Boolean
        get() = addedAt > 0L && abs(updatedAt - addedAt) < ADDED_AT_TOLERANCE_MS

    /**
     * True when someone else changed the item last: its Updated By isn't [me], this phone's name as it writes it
     * there (spaces around and capitals don't count). A change still waiting on this phone ([pendingSync]) is this
     * phone's, and a row without a name (from before the sheet kept one) is nobody's change. Ticked today asks this
     * ([TickedEntry.isStillTicked]); it is the rule the list's highlight of recent changes and the change
     * notifications use too (`isRecentChangeByOthers`, `ListChangeWatch.isByOthers`), so they agree on whose change
     * it was.
     */
    fun isChangedLastByOthers(me: String): Boolean =
        !pendingSync && updatedBy.isNotBlank() && !updatedBy.trim().equals(me.trim(), ignoreCase = true)

    companion object {
        /** Added At and Updated At closer than this mean the same moment (see [isUnchangedSinceAdded]). */
        const val ADDED_AT_TOLERANCE_MS = 1_000L
    }
}

data class FailedChange(val id: String, val itemName: String, val error: String)

/** [pending] = number of items with local changes waiting to be sent; [failed] = one entry per item whose changes failed. */
data class OutboxStatus(val pending: Int, val failed: List<FailedChange>)
