package com.grocerysync.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory [LocalListStore] with the database's semantics: each local edit is planned on the list and queued in one
 * step (under one lock, as GroceryDatabase.enqueuePlanned does in one transaction), the outbox gets increasing seq
 * numbers, and every write bumps [version].
 */
internal class FakeListStore(mirror: List<GroceryItem> = emptyList()) : LocalListStore {

    /** The last list from the sheet. */
    var mirror: List<GroceryItem> = mirror
        private set

    /** The waiting changes, in seq order. */
    val outbox = mutableListOf<OutboxOp>()

    private var nextSeq = 1L
    private val versionState = MutableStateFlow(0L)

    override val version: StateFlow<Long> = versionState.asStateFlow()

    /**
     * A sync: this phone's waiting changes reached the sheet, and the sheet's list [items] came back (the other
     * phone's changes in it), like GroceryDatabase.commitPush.
     */
    fun synced(items: List<GroceryItem>) {
        synchronized(this) {
            outbox.removeAll { it.status == OpStatus.PENDING }
            mirror = items
        }
        versionState.update { it + 1 }
    }

    @Synchronized
    override fun loadSnapshot(): Pair<List<GroceryItem>, List<OutboxOp>> = mirror to outbox.toList()

    @Synchronized
    override fun loadOutbox(): List<OutboxOp> = outbox.toList()

    override fun <T> enqueuePlanned(plan: (mirror: List<GroceryItem>, outbox: List<OutboxOp>) -> Pair<List<OutboxOp>, T>): Pair<List<OutboxOp>, T> {
        val result = synchronized(this) {
            val (ops, planned) = plan(mirror, outbox.toList())
            val queued = ops.map { it.copy(seq = nextSeq++) }
            outbox += queued
            queued to planned
        }
        versionState.update { it + 1 }
        return result
    }

    override fun retryFailed() {
        synchronized(this) { outbox.replaceAll { if (it.status == OpStatus.FAILED) it.copy(status = OpStatus.PENDING, error = null) else it } }
        versionState.update { it + 1 }
    }

    override fun discardFailed() {
        synchronized(this) { outbox.removeAll { it.status == OpStatus.FAILED } }
        versionState.update { it + 1 }
    }
}
