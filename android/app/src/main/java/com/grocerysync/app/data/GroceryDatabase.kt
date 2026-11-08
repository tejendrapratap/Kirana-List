package com.grocerysync.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.grocerysync.app.data.DatabaseSchema.C_ADDED_AT
import com.grocerysync.app.data.DatabaseSchema.C_ERROR
import com.grocerysync.app.data.DatabaseSchema.C_ID
import com.grocerysync.app.data.DatabaseSchema.C_K
import com.grocerysync.app.data.DatabaseSchema.C_KEY
import com.grocerysync.app.data.DatabaseSchema.C_NAME
import com.grocerysync.app.data.DatabaseSchema.C_OP
import com.grocerysync.app.data.DatabaseSchema.C_SEQ
import com.grocerysync.app.data.DatabaseSchema.C_STATUS
import com.grocerysync.app.data.DatabaseSchema.C_TIME
import com.grocerysync.app.data.DatabaseSchema.C_TYPE
import com.grocerysync.app.data.DatabaseSchema.C_UNIT
import com.grocerysync.app.data.DatabaseSchema.C_UPDATED_AT
import com.grocerysync.app.data.DatabaseSchema.C_UPDATED_BY
import com.grocerysync.app.data.DatabaseSchema.C_V
import com.grocerysync.app.data.DatabaseSchema.C_VALUE
import com.grocerysync.app.data.DatabaseSchema.M_LAST_SUCCESS
import com.grocerysync.app.data.DatabaseSchema.M_REVISION
import com.grocerysync.app.data.DatabaseSchema.M_SERVER_VERSION
import com.grocerysync.app.data.DatabaseSchema.M_SHEET_URL
import com.grocerysync.app.data.DatabaseSchema.M_SPREADSHEET
import com.grocerysync.app.data.DatabaseSchema.T_ITEMS
import com.grocerysync.app.data.DatabaseSchema.T_META
import com.grocerysync.app.data.DatabaseSchema.T_OUTBOX
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The full list as the sheet returned it, with the revision that identifies that content. */
data class ServerSnapshot(val revision: String, val spreadsheetName: String?, val items: List<GroceryItem>)

/**
 * [serverVersion]: protocol version of the script that answered last (2 for scripts that don't say), null
 * before any answer; see [SyncStore.commitPull]. [sheetUrl]: the web app URL that answered last, so the list
 * kept is its list (null before any answer since 1.6); after the phone switches to another sheet it still names
 * the old one until the new one answers.
 */
data class SyncInfo(
    val lastSuccessAt: Long?,
    val spreadsheetName: String?,
    val serverVersion: Int? = null,
    val sheetUrl: String? = null,
)

/** Storage operations used by the sync engine. All calls block; run them on an IO dispatcher. */
interface SyncStore {
    fun loadOutbox(): List<OutboxOp>
    fun revision(): String?
    fun syncInfo(): SyncInfo

    /**
     * After an `apply` response, in one transaction: deletes the confirmed ops [deleteIds], marks
     * [failures] (op id -> server error) FAILED, replaces the mirror with [snapshot] and records the
     * [serverVersion] and the web app URL ([sheetUrl]) that answered.
     */
    fun commitPush(
        deleteIds: Set<String>,
        failures: Map<String, String>,
        snapshot: ServerSnapshot,
        syncedAt: Long,
        serverVersion: Int,
        sheetUrl: String,
    )

    /**
     * After a `list` response: replaces the mirror with [snapshot], or just records success when unchanged
     * (null); records the [serverVersion] that answered (the version the mirror and revision come from) and its
     * web app URL ([sheetUrl]: "unchanged" means the mirror is that sheet's list too).
     */
    fun commitPull(snapshot: ServerSnapshot?, syncedAt: Long, serverVersion: Int, sheetUrl: String)
}

/**
 * SQLite storage: `items` mirrors the last list received from the sheet, `outbox` holds local
 * changes not yet confirmed, `meta` keeps the revision and sync bookkeeping. Schema and upgrades:
 * [DatabaseSchema].
 *
 * Every write bumps [version] after its transaction commits so flows can re-query.
 * Methods block; callers run them on Dispatchers.IO.
 */
class GroceryDatabase(context: Context) : SyncStore {

    private val helper = Helper(context.applicationContext)
    private val versionState = MutableStateFlow(0L)

    /** Invalidation counter; changes after every committed write. */
    val version: StateFlow<Long> = versionState.asStateFlow()

    // ---- Reads -------------------------------------------------------------------------------

    fun loadMirror(): List<GroceryItem> =
        helper.readableDatabase.query(
            T_ITEMS, arrayOf(C_KEY, C_NAME, C_TYPE, C_VALUE, C_UPDATED_AT, C_UPDATED_BY, C_UNIT, C_ADDED_AT),
            null, null, null, null, "rowid",
        ).use { c -> c.mapNotNullRows(::readItem) }

    override fun loadOutbox(): List<OutboxOp> =
        helper.readableDatabase.query(
            T_OUTBOX,
            arrayOf(C_SEQ, C_ID, C_OP, C_KEY, C_NAME, C_TYPE, C_VALUE, C_TIME, C_STATUS, C_ERROR, C_UNIT, C_ADDED_AT),
            null, null, null, null, C_SEQ,
        ).use { c -> c.mapNotNullRows(::readOp) }

    /** Mirror and outbox read together so the overlay never mixes two different states. */
    fun loadSnapshot(): Pair<List<GroceryItem>, List<OutboxOp>> {
        val db = helper.readableDatabase
        db.beginTransactionNonExclusive()
        try {
            val result = loadMirror() to loadOutbox()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    override fun revision(): String? = getMeta(helper.readableDatabase, M_REVISION)

    override fun syncInfo(): SyncInfo {
        val db = helper.readableDatabase
        return SyncInfo(
            lastSuccessAt = getMeta(db, M_LAST_SUCCESS)?.toLongOrNull(),
            spreadsheetName = getMeta(db, M_SPREADSHEET),
            serverVersion = getMeta(db, M_SERVER_VERSION)?.toIntOrNull(),
            sheetUrl = getMeta(db, M_SHEET_URL),
        )
    }

    // ---- Local edits -------------------------------------------------------------------------

    /**
     * One local edit, atomically: [plan] gets the mirror and the outbox as they are now and returns the ops to
     * append (none to refuse the edit) and a result, and the ops are appended in the same write transaction
     * (their [OutboxOp.seq] is assigned by SQLite).
     * A sync committing a new list ([commitPull], [commitPush]) lands before or after, never in between, so
     * what [plan] checked (e.g. that no other item has the name) still holds when its ops are queued.
     * Returns the ops appended and the result.
     */
    fun <T> enqueuePlanned(plan: (mirror: List<GroceryItem>, outbox: List<OutboxOp>) -> Pair<List<OutboxOp>, T>): Pair<List<OutboxOp>, T> =
        write { db ->
            // Same connection and transaction as the insert (the readable database is this writable one).
            val planned = plan(loadMirror(), loadOutbox())
            insertOps(db, planned.first)
            planned
        }

    private fun insertOps(db: SQLiteDatabase, ops: List<OutboxOp>) {
        for (op in ops) {
            val values = ContentValues().apply {
                put(C_ID, op.id)
                put(C_OP, op.op.wire)
                put(C_KEY, op.key)
                put(C_NAME, op.name)
                put(C_TYPE, op.value?.type?.wire)
                put(C_VALUE, op.value?.let(ListLogic::encodeValue))
                put(C_UNIT, ListLogic.encodeUnit(op.value))
                put(C_TIME, op.time)
                put(C_STATUS, op.status.name)
                putNull(C_ERROR)
                put(C_ADDED_AT, op.addedAt.coerceAtLeast(0L))
            }
            db.insertOrThrow(T_OUTBOX, null, values)
        }
    }

    fun retryFailed() = write { db ->
        val values = ContentValues().apply {
            put(C_STATUS, OpStatus.PENDING.name)
            putNull(C_ERROR)
        }
        db.update(T_OUTBOX, values, "$C_STATUS = ?", arrayOf(OpStatus.FAILED.name))
    }

    fun discardFailed() = write { db ->
        db.delete(T_OUTBOX, "$C_STATUS = ?", arrayOf(OpStatus.FAILED.name))
    }

    // ---- Sync results ------------------------------------------------------------------------

    override fun commitPush(
        deleteIds: Set<String>,
        failures: Map<String, String>,
        snapshot: ServerSnapshot,
        syncedAt: Long,
        serverVersion: Int,
        sheetUrl: String,
    ) = write { db ->
        for (id in deleteIds) db.delete(T_OUTBOX, "$C_ID = ?", arrayOf(id))
        for ((id, error) in failures) {
            val values = ContentValues().apply {
                put(C_STATUS, OpStatus.FAILED.name)
                put(C_ERROR, error)
            }
            db.update(T_OUTBOX, values, "$C_ID = ? AND $C_STATUS = ?", arrayOf(id, OpStatus.PENDING.name))
        }
        replaceMirror(db, snapshot)
        putMeta(db, M_LAST_SUCCESS, syncedAt.toString())
        putMeta(db, M_SERVER_VERSION, serverVersion.toString())
        putMeta(db, M_SHEET_URL, sheetUrl)
    }

    override fun commitPull(snapshot: ServerSnapshot?, syncedAt: Long, serverVersion: Int, sheetUrl: String) =
        write { db ->
            if (snapshot != null) replaceMirror(db, snapshot)
            putMeta(db, M_LAST_SUCCESS, syncedAt.toString())
            putMeta(db, M_SERVER_VERSION, serverVersion.toString())
            putMeta(db, M_SHEET_URL, sheetUrl)
        }

    private fun replaceMirror(db: SQLiteDatabase, snapshot: ServerSnapshot) {
        db.delete(T_ITEMS, null, null)
        for (item in snapshot.items) {
            val values = ContentValues().apply {
                put(C_KEY, item.key)
                put(C_NAME, item.name)
                put(C_TYPE, item.value.type.wire)
                put(C_VALUE, ListLogic.encodeValue(item.value))
                put(C_UNIT, ListLogic.encodeUnit(item.value).orEmpty())
                put(C_UPDATED_AT, item.updatedAt)
                put(C_UPDATED_BY, item.updatedBy)
                put(C_ADDED_AT, item.addedAt)
            }
            // The server keeps keys unique; if it ever doesn't, the first row wins, as in the sheet.
            db.insertWithOnConflict(T_ITEMS, null, values, SQLiteDatabase.CONFLICT_IGNORE)
        }
        putMeta(db, M_REVISION, snapshot.revision)
        snapshot.spreadsheetName?.takeIf { it.isNotBlank() }?.let { putMeta(db, M_SPREADSHEET, it) }
    }

    // ---- Helpers -----------------------------------------------------------------------------

    private inline fun <T> write(block: (SQLiteDatabase) -> T): T {
        val db = helper.writableDatabase
        db.beginTransaction()
        val result = try {
            block(db).also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
        versionState.update { it + 1 }
        return result
    }

    private fun getMeta(db: SQLiteDatabase, key: String): String? =
        db.query(T_META, arrayOf(C_V), "$C_K = ?", arrayOf(key), null, null, null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }

    private fun putMeta(db: SQLiteDatabase, key: String, value: String) {
        val values = ContentValues().apply {
            put(C_K, key)
            put(C_V, value)
        }
        db.insertWithOnConflict(T_META, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun readItem(c: Cursor): GroceryItem? {
        val key = c.getStringOrNull(0) ?: return null
        val name = c.getStringOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
        val type = ValueType.fromWire(c.getStringOrNull(2)) ?: return null
        val value = ListLogic.decodeValue(type, c.getStringOrNull(3), c.getStringOrNull(6).orEmpty()) ?: return null
        return GroceryItem(
            key = key,
            name = name,
            value = value,
            updatedAt = c.getLongOrZero(4),
            updatedBy = c.getStringOrNull(5).orEmpty(),
            pendingSync = false,
            addedAt = c.getLongOrZero(7),
        )
    }

    private fun readOp(c: Cursor): OutboxOp? {
        val op = OpType.fromWire(c.getStringOrNull(2)) ?: return null
        val status = when (c.getStringOrNull(8)) {
            OpStatus.PENDING.name -> OpStatus.PENDING
            OpStatus.FAILED.name -> OpStatus.FAILED
            else -> return null
        }
        val value = if (op == OpType.UPSERT) {
            val type = ValueType.fromWire(c.getStringOrNull(5)) ?: return null
            // A null unit (changes queued before units existed) keeps the sheet's unit.
            ListLogic.decodeValue(type, c.getStringOrNull(6), c.getStringOrNull(10)) ?: return null
        } else {
            null
        }
        return OutboxOp(
            seq = c.getLong(0),
            id = c.getStringOrNull(1) ?: return null,
            op = op,
            key = c.getStringOrNull(3) ?: return null,
            name = c.getStringOrNull(4) ?: return null,
            value = value,
            time = c.getLongOrZero(7),
            status = status,
            error = c.getStringOrNull(9),
            addedAt = c.getLongOrZero(11),
        )
    }

    /** Maps every row, skipping (and logging) rows that can't be read instead of failing the whole query. */
    private inline fun <T : Any> Cursor.mapNotNullRows(map: (Cursor) -> T?): List<T> {
        val result = ArrayList<T>(count.coerceAtLeast(0))
        while (moveToNext()) {
            val row = try {
                map(this)
            } catch (e: RuntimeException) {
                null
            }
            if (row != null) result += row else Log.w(TAG, "Skipping unreadable row ${position} in local database")
        }
        return result
    }

    private fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    private fun Cursor.getLongOrZero(index: Int): Long = if (isNull(index)) 0L else getLong(index)

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DatabaseSchema.VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            DatabaseSchema.CREATE.forEach(db::execSQL)
        }

        /**
         * Runs inside the open helper's transaction: an upgrade is applied completely or not at all. Every step
         * adds columns with defaults or rewrites values in place, so the items and the queued changes stay; only
         * the saved revision is dropped, so the whole list is fetched once (see [DatabaseSchema]).
         */
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            DatabaseSchema.upgrade(oldVersion).forEach(db::execSQL)
        }
    }

    private companion object {
        const val TAG = "GroceryDatabase"
        const val DB_NAME = "grocery_sync.db"
    }
}
