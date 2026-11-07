package com.grocerysync.app.ui

import com.grocerysync.app.data.FailedChange
import com.grocerysync.app.data.OutboxStatus
import com.grocerysync.app.sync.ConnectionState
import com.grocerysync.app.sync.PushProblem
import com.grocerysync.app.sync.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** When the list screen shows "Changes aren't saving". */
class ListUiStateTest {

    private val scriptProblem = PushProblem("The sheet's script reported an error: Choose Boolean or Integer.", 1, mayBeMomentary = false)

    private fun state(
        connection: ConnectionState = ConnectionState.LIVE,
        problem: PushProblem? = scriptProblem,
        pending: Int = 3,
        configured: Boolean = true,
    ) = ListUiState(
        loaded = true,
        outbox = OutboxStatus(pending = pending, failed = emptyList<FailedChange>()),
        status = SyncStatus(connection, 1L, null, "Groceries", problem),
        isConfigured = configured,
    )

    @Test
    fun warnsWhileLiveWithWaitingChanges() {
        assertEquals(scriptProblem, state().pushWarning)
    }

    @Test
    fun momentaryProblemWarnsOnlyOnceRepeated() {
        val busy = PushProblem("The sheet is busy with other changes.", 1, mayBeMomentary = true)
        assertNull(state(problem = busy).pushWarning)
        assertEquals(busy.copy(failures = 2), state(problem = busy.copy(failures = 2)).pushWarning)
    }

    @Test
    fun noWarningWithoutAProblemOrWaitingChanges() {
        assertNull(state(problem = null).pushWarning)
        assertNull(state(pending = 0).pushWarning)
        assertNull(state(configured = false).pushWarning)
    }

    @Test
    fun otherStatesHaveTheirOwnTexts() {
        // Offline is the status line, errors the "Not syncing" banner.
        for (connection in listOf(ConnectionState.OFFLINE, ConnectionState.ERROR, ConnectionState.NOT_CONFIGURED)) {
            assertNull(connection.name, state(connection = connection).pushWarning)
        }
    }

    @Test
    fun warningStaysWhileARetryRuns() {
        // Retry shows "Connecting..."; the warning must not vanish before the retry has shown whether it helped.
        assertEquals(scriptProblem, state(connection = ConnectionState.CONNECTING).pushWarning)
    }
}
