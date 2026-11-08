package com.grocerysync.app.data

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The list the UI works with. Edits are written to the outbox first (so they survive being offline
 * or the app being closed) and shown immediately on top of the last list received from the sheet.
 *
 * Every upsert carries the item's known Added At ([ListLogic.addedAtHint]): a rename and the Undo of a delete
 * re-create the row on the sheet, and with it the item keeps reading "Updated by" instead of "Added by".
 * For the Undo, the deleted item's Added At is kept in [deletedItems] (see [DeletedItemMemory]).
 *
 * Each edit checks the list and queues its changes in one database transaction, so a list arriving from the
 * sheet can't slip in between: adding an item, renaming one or undoing a delete never overwrites another item
 * that has that name (see [EditIntent], [EditResult]).
 */
class GroceryRepository(
    private val db: GroceryDatabase,
    private val settings: SettingsStore,
    private val clock: ServerClock,
    private val onLocalChange: () -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val deletedItems: DeletedItemMemory = DeletedItemMemory(),
) {

    /** Server list with pending local changes applied, sorted by name ignoring case. */
    val items: Flow<List<GroceryItem>> = db.version
        .map {
            val (mirror, ops) = db.loadSnapshot()
            ListLogic.overlay(mirror, ops, settings.effectiveDeviceName())
        }
        .conflate()
        .distinctUntilChanged()
        .flowOn(io)

    val outboxStatus: Flow<OutboxStatus> = db.version
        .map {
            val ops = db.loadOutbox()
            OutboxStatus(pending = ListLogic.pendingItemCount(ops), failed = ListLogic.failedChanges(ops))
        }
        .conflate()
        .distinctUntilChanged()
        .flowOn(io)

    /**
     * Adds the item or changes it (matched by [ItemKeys.normalize]); the typed spelling becomes the name. For
     * the Need switch, - / +, "Got it" and an edit of the item itself: the newest change wins ([EditIntent.ANY]).
     * Returns false (and changes nothing) when [name] can't be sent: a new item whose name breaks the
     * [InputValidation.validateName] rules.
     *
     * An item that isn't on the list gets the Added At of the item deleted last when this is that delete's
     * Undo (same name and value, see [DeletedItemMemory]); otherwise it is new, and the sheet stamps it.
     */
    suspend fun upsert(name: String, value: ItemValue): Boolean = save(name, value, EditIntent.ANY) == EditResult.Saved

    /**
     * Adds a new item: the Add dialog, saving an item that was removed elsewhere while its dialog was open, and
     * the Undo of a delete ([EditIntent.CREATE]). Never overwrites an item that is on the list now, even one that
     * arrived from the other phone after the dialog's check: then nothing is queued and the result names it
     * ([EditResult.AlreadyOnList]). The check and the enqueue are one database transaction.
     */
    suspend fun add(name: String, value: ItemValue): EditResult = save(name, value, EditIntent.CREATE)

    /**
     * Sets the value of an item that must still be on the list: the Undo of "Got it" ([EditIntent.UPDATE]).
     * When it was renamed or removed on the other phone meanwhile, nothing is queued ([EditResult.NotOnList]):
     * writing it anyway would bring the old name back as a second item.
     */
    suspend fun update(name: String, value: ItemValue): EditResult = save(name, value, EditIntent.UPDATE)

    /**
     * Sets the value of the item [key] to [value], but only while it still has [expected], the value the user saw:
     * the paste preview's updates. Checked in the same transaction as the enqueue ([ListLogic.updateConflict]), so
     * a list from the sheet that lands while a long paste is being applied can't slip in between: a value set on
     * the other phone meanwhile is never overwritten ([EditResult.ValueChanged]), and an item renamed or removed
     * there is never re-created ([EditResult.NotOnList]). Keeps the item's name.
     */
    suspend fun updateIf(key: String, expected: ItemValue, value: ItemValue): EditResult = withContext(io) {
        edit { list ->
            val current = list.current(key)
            val conflict = ListLogic.updateConflict(expected, current?.item)
            if (current == null || conflict != null) return@edit refuse(conflict ?: EditResult.NotOnList)
            val item = current.item
            val name = ListLogic.nameForChange(item.name, item) ?: return@edit refuse(EditResult.InvalidName)
            listOf(upsertOp(key, name, value.sanitized(), editTime(item), current.addedAtHint)) to EditResult.Saved
        }
    }

    private suspend fun save(name: String, value: ItemValue, intent: EditIntent): EditResult = withContext(io) {
        val key = ItemKeys.normalize(name)
        edit { list ->
            val current = list.current(key)
            ListLogic.editConflict(intent, current?.item)?.let { return@edit refuse(it) }
            val sendName = ListLogic.nameForChange(name, current?.item)
            if (sendName == null) {
                Log.w(TAG, "Ignoring edit with an invalid item name")
                return@edit refuse(EditResult.InvalidName)
            }
            val addedAt = current?.addedAtHint ?: deletedItems.addedAtForReAdd(key, value)
            listOf(upsertOp(key, sendName, value.sanitized(), editTime(current?.item), addedAt)) to EditResult.Saved
        }
    }

    /**
     * Renames an item, optionally setting [value] too (null keeps its value). A change of case or spacing keeps
     * its identity (one upsert); a different name becomes delete(old) + upsert(new), sent together. The new name
     * keeps the item's Added At (see [ListLogic.addedAtHint]), so it reads "Updated by" like any other change.
     *
     * Never overwrites another item: when the new name belongs to an item on the list now (also one that just
     * arrived from the other phone), nothing is queued ([EditResult.AlreadyOnList]). [EditResult.NotOnList] when
     * the item itself is gone.
     */
    suspend fun rename(key: String, newName: String, value: ItemValue? = null): EditResult {
        if (InputValidation.validateName(newName) != null) {
            Log.w(TAG, "Ignoring rename to an invalid item name")
            return EditResult.InvalidName
        }
        return withContext(io) {
            edit { list ->
                val current = list.current(key) ?: return@edit refuse(EditResult.NotOnList)
                val item = current.item
                val cleanName = ItemKeys.clean(newName)
                val newKey = ItemKeys.normalize(cleanName)
                val newValue = value?.sanitized() ?: item.value
                if (newKey == key) {
                    if (cleanName == item.name && newValue == item.value) return@edit refuse(EditResult.Saved)
                    return@edit listOf(upsertOp(key, cleanName, newValue, editTime(item), current.addedAtHint)) to EditResult.Saved
                }
                ListLogic.editConflict(EditIntent.CREATE, list.current(newKey)?.item)?.let { return@edit refuse(it) }
                val oldName = ListLogic.nameForChange(item.name, item) ?: return@edit refuse(EditResult.InvalidName)
                listOf(
                    deleteOp(key, oldName, editTime(item)),
                    upsertOp(newKey, cleanName, newValue, editTime(null), current.addedAtHint),
                ) to EditResult.Saved
            }
        }
    }

    /** Deletes the item; its Added At is kept for a while in case the delete is undone (see [upsert]). */
    suspend fun delete(key: String) {
        withContext(io) {
            edit { list ->
                val current = list.current(key) ?: return@edit refuse(EditResult.NotOnList)
                val item = current.item
                val name = ListLogic.nameForChange(item.name, item) ?: return@edit refuse(EditResult.InvalidName)
                deletedItems.remember(key, item.value, current.addedAtHint)
                listOf(deleteOp(key, name, editTime(item))) to EditResult.Saved
            }
        }
    }

    /** Puts changes the sheet rejected back in the queue. */
    suspend fun retryFailed() {
        withContext(io) { db.retryFailed() }
        onLocalChange()
    }

    /** Drops changes the sheet rejected; the list falls back to what the sheet has. */
    suspend fun discardFailed() {
        withContext(io) { db.discardFailed() }
    }

    /**
     * Runs one edit: [plan] sees the list as it is now ([ShownList]) and returns the ops to queue (or none) and
     * the result; the check and the enqueue are one database transaction ([GroceryDatabase.enqueuePlanned]).
     */
    private fun edit(plan: (ShownList) -> Pair<List<OutboxOp>, EditResult>): EditResult {
        val (queued, result) = db.enqueuePlanned { mirror, ops -> plan(ShownList(mirror, ops, settings.effectiveDeviceName())) }
        if (queued.isNotEmpty()) onLocalChange()
        return result
    }

    private fun refuse(result: EditResult): Pair<List<OutboxOp>, EditResult> = emptyList<OutboxOp>() to result

    /** An item as currently shown, and the Added At its next upsert carries ([ListLogic.addedAtHint]). */
    private class Current(val item: GroceryItem, val addedAtHint: Long)

    /** The list as currently shown: the server [mirror] with the queued [ops] applied ([ListLogic.overlay]). */
    private class ShownList(private val mirror: List<GroceryItem>, ops: List<OutboxOp>, device: String) {
        private val items = ListLogic.overlay(mirror, ops, device)

        /** The item [key] as shown; null when it isn't on the list. */
        fun current(key: String): Current? {
            val item = items.firstOrNull { it.key == key } ?: return null
            // The first row of a key, as the overlay uses it.
            val sheetItem = mirror.firstOrNull { it.key == key }
            return Current(item, ListLogic.addedAtHint(item, sheetItem))
        }
    }

    /**
     * Edit time on the server's clock, but never earlier than the version of the item the user is
     * looking at ([seen]): an edit made after seeing a change must win over that change even if
     * this phone's clock is behind. The clock remembers it, so the next edit (e.g. an Undo of a
     * delete stamped this way) is never stamped earlier.
     */
    private fun editTime(seen: GroceryItem?): Long = clock.atLeast(seen?.updatedAt ?: 0L)

    private fun upsertOp(key: String, name: String, value: ItemValue, time: Long, addedAt: Long) = OutboxOp(
        seq = 0L,
        id = UUID.randomUUID().toString(),
        op = OpType.UPSERT,
        key = key,
        name = name,
        value = value,
        time = time,
        status = OpStatus.PENDING,
        addedAt = addedAt,
    )

    private fun deleteOp(key: String, name: String, time: Long) = OutboxOp(
        seq = 0L,
        id = UUID.randomUUID().toString(),
        op = OpType.DELETE,
        key = key,
        name = name,
        value = null,
        time = time,
        status = OpStatus.PENDING,
    )

    private companion object {
        const val TAG = "GroceryRepository"
    }
}
