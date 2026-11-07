package com.grocerysync.app.ui.components

import com.grocerysync.app.sync.Protocol
import com.grocerysync.app.sync.PushProblem
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveStatusTextTest {

    private val now = 1_000_000_000L

    @Test
    fun saysWhetherThePhoneIsOfflineOrTheSheetHasTrouble() {
        assertEquals("Offline - showing saved list", offlineStatusText(Protocol.MSG_OFFLINE, null, now))
        assertEquals("Offline - showing saved list", offlineStatusText(null, null, now))
        assertEquals("Sheet busy - retrying", offlineStatusText(Protocol.MSG_BUSY, null, now))
        assertEquals("Google isn't responding - retrying", offlineStatusText(Protocol.MSG_GOOGLE_TROUBLE, null, now))
    }

    @Test
    fun saysHowOldTheShownListIs() {
        assertEquals(
            "Offline - showing saved list (updated less than a minute ago)",
            offlineStatusText(Protocol.MSG_OFFLINE, now - 20_000L, now),
        )
        assertEquals("Sheet busy - retrying (updated 5 min ago)", offlineStatusText(Protocol.MSG_BUSY, now - 5 * 60_000L, now))
        assertEquals(
            "Offline - showing saved list (updated 3 h ago)",
            offlineStatusText(Protocol.MSG_OFFLINE, now - 3 * 3_600_000L, now),
        )
        assertEquals(
            "Offline - showing saved list (updated 4 days ago)",
            offlineStatusText(Protocol.MSG_OFFLINE, now - 4 * 86_400_000L, now),
        )
    }

    @Test
    fun liveTextIsUnchanged() {
        assertEquals("Live - updated 3s ago", liveStatusText(now - 3_000L, now))
    }

    @Test
    fun scriptErrorsShowTheScriptsWords() {
        val internal = Protocol.mapServerError("internal: Choose Boolean or Integer.").message
        assertEquals("Sheet error - retrying: Choose Boolean or Integer.", offlineStatusText(internal, null, now))
        assertEquals(
            "Sheet error - retrying: Choose Boolean or Integer. (updated 2 min ago)",
            offlineStatusText(internal, now - 2 * 60_000L, now),
        )
        assertEquals(
            "Sheet error - retrying: Exceeded maximum execution time",
            offlineStatusText(Protocol.SCRIPT_FAILED_PREFIX + "Exceeded maximum execution time", null, now),
        )
    }

    private fun problem(message: String, needsFix: Boolean = false) = PushProblem(message, 1, mayBeMomentary = false, needsFix = needsFix)

    @Test
    fun pushWarningSaysWhyAndWhatHappensToTheChanges() {
        assertEquals("Changes aren't saving", PUSH_WARNING_TITLE)
        assertEquals(
            "Changes aren't saving: The sheet's script reported an error: Choose Boolean or Integer. " +
                "7 items are waiting on this phone and will be sent again automatically.",
            pushWarningText(problem(Protocol.mapServerError("internal: Choose Boolean or Integer.").message), 7),
        )
        // Transient-failure texts don't repeat that changes are kept on the phone.
        assertEquals(
            "Changes aren't saving: The sheet is busy with other changes. " +
                "1 item is waiting on this phone and will be sent again automatically.",
            pushWarningText(problem(Protocol.MSG_BUSY), 1),
        )
        assertEquals(
            "Changes aren't saving: Google Sheets isn't responding properly right now. " +
                "2 items are waiting on this phone and will be sent again automatically.",
            pushWarningText(problem(Protocol.MSG_GOOGLE_TROUBLE), 2),
        )
    }

    @Test
    fun pushWarningPromisesNoAutomaticResendWhenOnlyAFixHelps() {
        val badRequest = Protocol.mapServerError("bad_request: unknown action \"apply\"").message
        assertEquals(
            "Changes aren't saving: $badRequest 1 item is waiting on this phone until that is fixed.",
            pushWarningText(problem(badRequest, needsFix = true), 1),
        )
    }
}
