package com.grocerysync.app.data

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * What [GroceryRepository] needs of the database: the last list from the sheet with the outbox, and one transaction
 * per local edit. [GroceryDatabase] in the app (the repository's public constructor wraps it); an interface so the
 * JVM tests can run the repository's checks on a list in memory.
 */
internal interface LocalListStore {
    /** Changes after every committed write, so the flows read the list again. */
    val version: StateFlow<Long>

    /** The mirror and the outbox, read together ([GroceryDatabase.loadSnapshot]). */
    fun loadSnapshot(): Pair<List<GroceryItem>, List<OutboxOp>>

    fun loadOutbox(): List<OutboxOp>

    /** One local edit in one transaction ([GroceryDatabase.enqueuePlanned]). */
    fun <T> enqueuePlanned(plan: (mirror: List<GroceryItem>, outbox: List<OutboxOp>) -> Pair<List<OutboxOp>, T>): Pair<List<OutboxOp>, T>

    fun retryFailed()

    fun discardFailed()
}

/** [GroceryDatabase] as the repository's [LocalListStore]. */
private class DatabaseListStore(private val db: GroceryDatabase) : LocalListStore {
    override val version: StateFlow<Long> get() = db.version

    override fun loadSnapshot() = db.loadSnapshot()

    override fun loadOutbox() = db.loadOutbox()

    override fun <T> enqueuePlanned(plan: (mirror: List<GroceryItem>, outbox: List<OutboxOp>) -> Pair<List<OutboxOp>, T>) =
        db.enqueuePlanned(plan)

    override fun retryFailed() {
        db.retryFailed()
    }

    override fun discardFailed() {
        db.discardFailed()
    }
}

/**
 * The list the UI works with. Edits are written to the outbox first (so they survive being offline
 * or the app being closed) and shown immediately on top of the last list received from the sheet.
 *
 * Every upsert carries the item's known Added At ([ListLogic.addedAtHint]): a rename and the Undo of a delete
 * re-create the row on the sheet, and with it the item keeps reading "Updated by" instead of "Added by".
 * The Undo of a delete gets the deleted item's Added At from [delete] ([addBack]); the same item added again by
 * hand right after its delete gets it from [deletedItems] (see [DeletedItemMemory]).
 *
 * Each edit checks the list and queues its changes in one database transaction, so a list arriving from the
 * sheet can't slip in between: adding an item, renaming one or undoing a delete never overwrites another item
 * that has that name, and an Undo never overwrites a value the other phone set meanwhile (see [EditIntent],
 * [EditResult], [updateIf], [renameIf]), also not one that this phone's own waiting change still hides
 * ([ListLogic.newerOnSheet]).
 */
class GroceryRepository internal constructor(
    private val db: LocalListStore,
    private val settings: SettingsStore,
    private val clock: ServerClock,
    private val onLocalChange: () -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val deletedItems: DeletedItemMemory = DeletedItemMemory(),
) {

    constructor(
        db: GroceryDatabase,
        settings: SettingsStore,
        clock: ServerClock,
        onLocalChange: () -> Unit,
        io: CoroutineDispatcher = Dispatchers.IO,
        deletedItems: DeletedItemMemory = DeletedItemMemory(),
    ) : this(DatabaseListStore(db), settings, clock, onLocalChange, io, deletedItems)

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
     * An item that isn't on the list gets the Added At of the item deleted last when it is that item again, right
     * after its delete (same name and value, see [DeletedItemMemory]); otherwise it is new, and the sheet stamps it.
     */
    suspend fun upsert(name: String, value: ItemValue): Boolean = save(name, value, EditIntent.ANY) == EditResult.Saved

    /**
     * Adds a new item: the Add dialog, and saving an item that was removed elsewhere while its dialog was open
     * ([EditIntent.CREATE]). Never overwrites an item that is on the list now, even one that arrived from the other
     * phone after the dialog's check, nor one on the sheet that a delete still waiting on this phone hides
     * ([ListLogic.newerOnSheet]): then nothing is queued and the result names it ([EditResult.AlreadyOnList]). The
     * check and the enqueue are one database transaction.
     */
    suspend fun add(name: String, value: ItemValue): EditResult = save(name, value, EditIntent.CREATE)

    /**
     * The Undo of a delete: [add]s the item [name] back with [value] and the Added At [addedAt] that [delete] gave
     * (0 = unknown), so it reads "Updated by" again however long the Undo waited (Simple mode's stays until it is
     * tapped). Never overwrites an item that has the name meanwhile, as [add].
     */
    suspend fun addBack(name: String, value: ItemValue, addedAt: Long): EditResult =
        save(name, value, EditIntent.CREATE, addedAt)

    /**
     * Sets the value of the item [key] to [value], but only while it still has [expected]: the value the user saw
     * (the paste preview's updates), or the value this phone set (every Undo of a value: "Got it", - / +, the Need
     * switch, an edit; and Ticked today's Put back). Checked in the same transaction as the enqueue
     * ([ListLogic.updateConflict]), so a list from the sheet that lands meanwhile can't slip in between: a value set
     * on the other phone is never overwritten ([EditResult.ValueChanged]), also not one that this phone's own waiting
     * change still hides ([ListLogic.newerOnSheet]), and an item renamed or removed there is never re-created under
     * its old name ([EditResult.NotOnList]). Keeps the item's name.
     *
     * An item that has [value] already counts as done ([EditResult.Saved], nothing queued): this phone wrote it a
     * moment ago (Put back, then the tick's Undo; a second tap), and saying it "was changed on another phone" would
     * be wrong.
     */
    suspend fun updateIf(key: String, expected: ItemValue, value: ItemValue): EditResult = withContext(io) {
        edit { list ->
            val current = list.current(key) ?: return@edit refuse(EditResult.NotOnList)
            if (list.newerOnSheet(key) != null) return@edit refuse(EditResult.ValueChanged)
            val item = current.item
            val newValue = value.sanitized()
            if (item.value == newValue) return@edit refuse(EditResult.Saved)
            ListLogic.updateConflict(expected.sanitized(), item)?.let { return@edit refuse(it) }
            val name = ListLogic.nameForChange(item.name, item) ?: return@edit refuse(EditResult.InvalidName)
            listOf(upsertOp(key, name, newValue, editTime(item), current.addedAtHint)) to EditResult.Saved
        }
    }

    /**
     * [upsert] ([EditIntent.ANY]) or [add] ([EditIntent.CREATE]). [addedAtHint]: the Added At a new item gets (the
     * Undo of a delete, [addBack]); without it, the one [deletedItems] may have for it.
     */
    private suspend fun save(
        name: String,
        value: ItemValue,
        intent: EditIntent,
        addedAtHint: Long? = null,
    ): EditResult = withContext(io) {
        val key = ItemKeys.normalize(name)
        edit { list ->
            val current = list.current(key)
            // A new item mustn't replace a newer sheet row that a waiting delete hides (ANY never conflicts).
            ListLogic.editConflict(intent, current?.item ?: list.newerOnSheet(key))?.let { return@edit refuse(it) }
            val sendName = ListLogic.nameForChange(name, current?.item)
            if (sendName == null) {
                Log.w(TAG, "Ignoring edit with an invalid item name")
                return@edit refuse(EditResult.InvalidName)
            }
            val addedAt = current?.addedAtHint ?: addedAtHint ?: deletedItems.addedAtForReAdd(key, value)
            listOf(upsertOp(key, sendName, value.sanitized(), editTime(current?.item), addedAt)) to EditResult.Saved
        }
    }

    /**
     * Renames an item, optionally setting [value] too (null keeps its value). A change of case or spacing keeps
     * its identity (one upsert); a different name becomes delete(old) + upsert(new), sent together. The new name
     * keeps the item's Added At (see [ListLogic.addedAtHint]), so it reads "Updated by" like any other change.
     *
     * Never overwrites another item: when the new name belongs to an item on the list now (also one that just
     * arrived from the other phone, or one on the sheet that a delete still waiting on this phone hides:
     * [ListLogic.newerOnSheet]), nothing is queued ([EditResult.AlreadyOnList]). [EditResult.NotOnList] when the
     * item itself is gone.
     */
    suspend fun rename(key: String, newName: String, value: ItemValue? = null): EditResult =
        renameItem(key, newName, value, expected = null)

    /**
     * [rename] for the Undo of an edit that renamed the item [key] (and maybe changed its value): gives it [newName]
     * (its old name) and [value] (its old value) back, but only while it still has [expected], the value the edit
     * set, like [updateIf]. Checked in the same transaction as the enqueue, with [rename]'s own checks: an item that
     * has taken the old name meanwhile (added, or renamed to it, on the other phone) is never overwritten
     * ([EditResult.AlreadyOnList]), and an item renamed or removed there is never re-created
     * ([EditResult.NotOnList]); [EditResult.ValueChanged] when the other phone changed its value, also behind this
     * phone's own waiting change ([ListLogic.newerOnSheet]).
     */
    suspend fun renameIf(key: String, expected: ItemValue, newName: String, value: ItemValue): EditResult =
        renameItem(key, newName, value, expected)

    /** [rename]; with [expected], [renameIf]. */
    private suspend fun renameItem(key: String, newName: String, value: ItemValue?, expected: ItemValue?): EditResult {
        if (InputValidation.validateName(newName) != null) {
            Log.w(TAG, "Ignoring rename to an invalid item name")
            return EditResult.InvalidName
        }
        return withContext(io) {
            edit { list ->
                val current = list.current(key) ?: return@edit refuse(EditResult.NotOnList)
                val item = current.item
                if (expected != null) {
                    if (list.newerOnSheet(key) != null) return@edit refuse(EditResult.ValueChanged)
                    ListLogic.updateConflict(expected.sanitized(), item)?.let { return@edit refuse(it) }
                }
                val cleanName = ItemKeys.clean(newName)
                val newKey = ItemKeys.normalize(cleanName)
                val newValue = value?.sanitized() ?: item.value
                if (newKey == key) {
                    if (cleanName == item.name && newValue == item.value) return@edit refuse(EditResult.Saved)
                    return@edit listOf(upsertOp(key, cleanName, newValue, editTime(item), current.addedAtHint)) to EditResult.Saved
                }
                val onNewName = list.current(newKey)?.item ?: list.newerOnSheet(newKey)
                ListLogic.editConflict(EditIntent.CREATE, onNewName)?.let { return@edit refuse(it) }
                val oldName = ListLogic.nameForChange(item.name, item) ?: return@edit refuse(EditResult.InvalidName)
                listOf(
                    deleteOp(key, oldName, editTime(item)),
                    upsertOp(newKey, cleanName, newValue, editTime(null), current.addedAtHint),
                ) to EditResult.Saved
            }
        }
    }

    /**
     * Deletes the item [key], but only while it still has [expected]: the Undo of "Add common items", which takes
     * back what it added and nothing more. An item marked needed or changed on the other phone since is kept
     * ([EditResult.ValueChanged]), checked in the same transaction as the enqueue like [updateIf].
     */
    suspend fun deleteIf(key: String, expected: ItemValue): EditResult = withContext(io) {
        edit { list ->
            val current = list.current(key) ?: return@edit refuse(EditResult.NotOnList)
            if (list.newerOnSheet(key) != null) return@edit refuse(EditResult.ValueChanged)
            val item = current.item
            ListLogic.updateConflict(expected.sanitized(), item)?.let { return@edit refuse(it) }
            val name = ListLogic.nameForChange(item.name, item) ?: return@edit refuse(EditResult.InvalidName)
            listOf(deleteOp(key, name, editTime(item))) to EditResult.Saved
        }
    }

    /**
     * Deletes the item. Nothing is deleted when it isn't on the list any more (removed or renamed on the other phone
     * meanwhile: [EditResult.NotOnList]) or its name can't be sent ([EditResult.InvalidName]). [Deletion.addedAt] is
     * the Added At its Undo gives back ([addBack]); it is also kept for a while in case the item is added again by
     * hand ([DeletedItemMemory]).
     */
    suspend fun delete(key: String): Deletion = withContext(io) {
        var addedAt = 0L
        val result = edit { list ->
            val current = list.current(key) ?: return@edit refuse(EditResult.NotOnList)
            val item = current.item
            val name = ListLogic.nameForChange(item.name, item) ?: return@edit refuse(EditResult.InvalidName)
            deletedItems.remember(key, item.value, current.addedAtHint)
            addedAt = current.addedAtHint
            listOf(deleteOp(key, name, editTime(item))) to EditResult.Saved
        }
        Deletion(result, addedAt)
    }

    /** What [delete] did: [result] ([EditResult.Saved]: deleted), and the item's Added At ([addedAt], 0 = unknown). */
    data class Deletion(val result: EditResult, val addedAt: Long)

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
    private class ShownList(private val mirror: List<GroceryItem>, private val ops: List<OutboxOp>, device: String) {
        private val items = ListLogic.overlay(mirror, ops, device)

        /** The item [key] as shown; null when it isn't on the list. */
        fun current(key: String): Current? {
            val item = items.firstOrNull { it.key == key } ?: return null
            // The first row of a key, as the overlay uses it.
            val sheetItem = mirror.firstOrNull { it.key == key }
            return Current(item, ListLogic.addedAtHint(item, sheetItem))
        }

        /**
         * The sheet's row of the item [key] when it is newer than this phone's waiting changes of it
         * ([ListLogic.newerOnSheet]).
         */
        fun newerOnSheet(key: String): GroceryItem? = ListLogic.newerOnSheet(key, mirror, ops)
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
