package com.grocerysync.app.ui

import com.grocerysync.app.data.OutboxStatus
import com.grocerysync.app.sync.ConnectionState
import com.grocerysync.app.sync.Protocol
import com.grocerysync.app.sync.PushProblem
import com.grocerysync.app.sync.SyncStatus
import com.grocerysync.app.ui.components.SCRIPT_UPDATE_TITLE
import com.grocerysync.app.ui.components.scriptUpdateText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the list screen says the sheet's script is out of date (changes wait on the phone). */
class ScriptUpdateStateTest {

    private fun state(
        connection: ConnectionState = ConnectionState.LIVE,
        outdated: Boolean = true,
        pending: Int = 2,
        configured: Boolean = true,
        problem: PushProblem? = null,
    ) = ListUiState(
        loaded = true,
        outbox = OutboxStatus(pending = pending, failed = emptyList()),
        status = SyncStatus(connection, 1L, null, "Groceries", problem, scriptOutdated = outdated),
        isConfigured = configured,
    )

    @Test
    fun shownWhileTheOldScriptAnswersAlsoWithoutWaitingChanges() {
        assertTrue(state().scriptUpdateNeeded)
        assertTrue(state(pending = 0).scriptUpdateNeeded)
        assertTrue(state(connection = ConnectionState.CONNECTING).scriptUpdateNeeded)
        // Offline: still the last thing known about the script.
        assertTrue(state(connection = ConnectionState.OFFLINE).scriptUpdateNeeded)
    }

    @Test
    fun notShownForAnUpToDateScriptOrWhenOtherBannersApply() {
        assertFalse(state(outdated = false).scriptUpdateNeeded)
        assertFalse(state(configured = false).scriptUpdateNeeded)
        assertFalse(state(connection = ConnectionState.ERROR).scriptUpdateNeeded)
        assertFalse(state(connection = ConnectionState.NOT_CONFIGURED).scriptUpdateNeeded)
    }

    @Test
    fun replacesTheChangesArentSavingWarning() {
        val problem = PushProblem("The sheet's script reported an error: x.", 1, mayBeMomentary = false)
        assertNull(state(problem = problem).pushWarning)
        assertEquals(problem, state(outdated = false, problem = problem).pushWarning)
    }

    @Test
    fun textSaysWhatToDoAndWhatWaits() {
        val two = scriptUpdateText(2)
        assertTrue(two.startsWith(SCRIPT_UPDATE_TITLE))
        assertTrue(two.startsWith(Protocol.MSG_SCRIPT_OUTDATED))
        assertTrue(two, two.endsWith("2 items are waiting on this phone until then."))
        assertTrue(scriptUpdateText(1).endsWith("1 item is waiting on this phone until then."))
        assertTrue(scriptUpdateText(0).endsWith("Until then, changes made here wait on this phone."))
    }
}
