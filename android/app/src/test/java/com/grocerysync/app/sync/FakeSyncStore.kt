package com.grocerysync.app.sync

import com.grocerysync.app.data.GroceryItem
import com.grocerysync.app.data.ItemKeys
import com.grocerysync.app.data.ItemValue
import com.grocerysync.app.data.OpStatus
import com.grocerysync.app.data.OpType
import com.grocerysync.app.data.OutboxOp
import com.grocerysync.app.data.ServerSnapshot
import com.grocerysync.app.data.SyncInfo
import com.grocerysync.app.data.SyncStore

/** In-memory [SyncStore] with the same semantics as the SQLite implementation. */
class FakeSyncStore : SyncStore {
    val outbox = mutableListOf<OutboxOp>()
    var mirror: List<GroceryItem> = emptyList()
    var rev: String? = null
    var lastSuccess: Long? = null
    var spreadsheet: String? = null

    /** The web app URL that answered last ([SyncInfo.sheetUrl]). */
    var sheetUrl: String? = null

    /**
     * Protocol version of the script that answered last. Most tests talk to an up-to-date script (3), as if
     * an earlier sync had already seen it; null = no answer yet (the first sync after the update).
     */
    var serverVersion: Int? = Protocol.VERSION

    /** Every version recorded by a commit, in order. */
    val recordedVersions = mutableListOf<Int>()
    private var nextSeq = 1L

    @Synchronized
    fun add(op: OpType, name: String, value: ItemValue? = null, time: Long = 1_000L): OutboxOp {
        val seq = nextSeq++
        return OutboxOp(seq, "id-$seq", op, ItemKeys.normalize(name), name, value, time, OpStatus.PENDING)
            .also { outbox += it }
    }

    @Synchronized
    override fun loadOutbox(): List<OutboxOp> = outbox.toList()

    @Synchronized
    override fun revision(): String? = rev

    @Synchronized
    override fun syncInfo() = SyncInfo(lastSuccess, spreadsheet, serverVersion, sheetUrl)

    @Synchronized
    override fun commitPush(
        deleteIds: Set<String>,
        failures: Map<String, String>,
        snapshot: ServerSnapshot,
        syncedAt: Long,
        serverVersion: Int,
        sheetUrl: String,
    ) {
        outbox.removeAll { it.id in deleteIds }
        outbox.replaceAll { op ->
            val error = failures[op.id]
            if (error != null && op.status == OpStatus.PENDING) op.copy(status = OpStatus.FAILED, error = error) else op
        }
        replace(snapshot)
        lastSuccess = syncedAt
        recordVersion(serverVersion)
        this.sheetUrl = sheetUrl
    }

    @Synchronized
    override fun commitPull(snapshot: ServerSnapshot?, syncedAt: Long, serverVersion: Int, sheetUrl: String) {
        snapshot?.let(::replace)
        lastSuccess = syncedAt
        recordVersion(serverVersion)
        this.sheetUrl = sheetUrl
    }

    private fun recordVersion(version: Int) {
        serverVersion = version
        recordedVersions += version
    }

    private fun replace(snapshot: ServerSnapshot) {
        mirror = snapshot.items
        rev = snapshot.revision
        snapshot.spreadsheetName?.let { spreadsheet = it }
    }
}
