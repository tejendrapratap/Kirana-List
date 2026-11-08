package com.grocerysync.app.uitest

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grocerysync.app.testing.FakeSheetServer
import com.grocerysync.app.testing.FakeSheetServer.Companion.yesNo
import com.grocerysync.app.testing.FakeSheetServer.Need
import com.grocerysync.app.testing.GroceryUiTest
import com.grocerysync.app.testing.liveStatus
import com.grocerysync.app.testing.needSwitch
import com.grocerysync.app.testing.pendingRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A change made while the sheet can't be reached waits on the phone and goes out by itself once it can. */
@RunWith(AndroidJUnit4::class)
class OfflineTest : GroceryUiTest() {

    @Test
    fun aChangeMadeOfflineIsSentWhenTheSheetIsBack() {
        changeWaitsThenGoesOut(FakeSheetServer.Mode.UNREACHABLE, "Offline - showing saved list")
    }

    @Test
    fun aChangeMadeWhileGoogleFailsIsSentWhenItRecovers() {
        changeWaitsThenGoesOut(FakeSheetServer.Mode.SERVER_ERROR, "Google isn't responding - retrying")
    }

    private fun changeWaitsThenGoesOut(outage: FakeSheetServer.Mode, status: String) {
        fake.seed("Tea", yesNo(false))
        launchConnected()
        showAllItems()

        fake.mode = outage
        node(needSwitch("Tea")).performClick()

        // The status line says why; the row shows it hasn't synced; the sheet has nothing yet.
        waitForNode(hasText(status, substring = true), SYNC_TIMEOUT_MS)
        waitForNode(pendingRow("Tea"))
        assertTrue(fake.changesFromApp("Tea").isEmpty())
        assertEquals(Need.YesNo(false), fake.item("Tea")!!.need)

        fake.mode = FakeSheetServer.Mode.UP
        waitFor("the waiting change on the sheet", RECOVERY_TIMEOUT_MS) { fake.item("Tea")?.need == Need.YesNo(true) }
        assertEquals(ME, fake.item("Tea")!!.updatedBy)
        waitForNoNode(pendingRow("Tea"))
        waitForNode(liveStatus, SYNC_TIMEOUT_MS)
    }
}
