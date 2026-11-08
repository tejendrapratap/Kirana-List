package com.grocerysync.app.uitest

import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grocerysync.app.data.Units
import com.grocerysync.app.testing.FakeSheetServer.Companion.HOME
import com.grocerysync.app.testing.FakeSheetServer.Companion.quantity
import com.grocerysync.app.testing.GroceryUiTest
import com.grocerysync.app.testing.gotIt
import com.grocerysync.app.testing.itemRow
import com.grocerysync.app.testing.stepperAmount
import com.grocerysync.app.testing.tab
import com.grocerysync.app.testing.toBuyRowWithAmount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Changes made on the other phone ("Home") show up by themselves (the app polls every 5 s), saying who made them. */
@RunWith(AndroidJUnit4::class)
class LiveUpdatesTest : GroceryUiTest() {

    @Test
    fun changesFromTheOtherPhoneShowUpLive() {
        fake.seed("Milk", quantity("1", Units.L), device = HOME, agoMs = 2 * 60 * 60 * 1_000L)
        launchConnected()
        showAllItems()
        waitForNode(itemRow("Milk", "Added by Home - 2 hours ago"))

        // Added on the other phone.
        assertEquals("applied", fake.upsertAs(HOME, "Apples", quantity("6", Units.PCS)))
        waitForNode(itemRow("Apples", "Added by Home - just now"), SYNC_TIMEOUT_MS)
        waitForNode(stepperAmount("Apples", "6 pcs"))

        // Changed on the other phone.
        assertEquals("applied", fake.upsertAs(HOME, "Milk", quantity("2", Units.L)))
        waitForNode(itemRow("Milk", "Updated by Home - just now"), SYNC_TIMEOUT_MS)
        waitForNode(stepperAmount("Milk", "2 L"))

        // To buy shows both, the fresh changes marked with who made them.
        node(tab("To buy")).performClick()
        waitForNode(toBuyRowWithAmount("Apples", "6 pcs"))
        waitForNode(itemRow("Milk", "Updated by Home"))

        // Deleted on the other phone.
        assertEquals("applied", fake.deleteAs(HOME, "Apples"))
        waitForNoNode(gotIt("Apples"), SYNC_TIMEOUT_MS)
        waitForNode(tab("All items", 1))

        // This phone only listened.
        assertTrue(fake.sentChanges().all { it.device == HOME })
    }
}
