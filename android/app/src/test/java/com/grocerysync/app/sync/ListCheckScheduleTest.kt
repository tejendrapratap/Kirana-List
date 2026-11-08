package com.grocerysync.app.sync

import androidx.work.NetworkType
import com.grocerysync.app.data.PhoneSettings
import com.grocerysync.app.data.SyncSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** When the background check for change notifications is planned, and how often it runs. */
class ListCheckScheduleTest {

    private val connected = SyncSettings("https://script.google.com/macros/s/x/exec", "token-0123456789", "Shop")

    @Test
    fun plannedOnlyForAPhoneThatIsSetUpAndWantsNotifications() {
        assertTrue(SyncScheduler.shouldCheckList(connected, PhoneSettings()))
        assertFalse(SyncScheduler.shouldCheckList(connected, PhoneSettings(notifyChanges = false)))
        assertFalse(SyncScheduler.shouldCheckList(connected.copy(token = ""), PhoneSettings()))
        assertFalse(SyncScheduler.shouldCheckList(connected.copy(webAppUrl = " "), PhoneSettings()))
        // Whatever else this phone chose.
        assertTrue(SyncScheduler.shouldCheckList(connected, PhoneSettings(simpleMode = true, notifyOfferDone = true)))
    }

    @Test
    fun everyFifteenMinutesWithANetworkTheFirstAfterOnePeriod() {
        val spec = SyncScheduler.listCheckRequest().workSpec
        assertEquals(ListCheckWorker::class.java.name, spec.workerClassName)
        assertEquals(TimeUnit.MINUTES.toMillis(15), spec.intervalDuration)
        assertEquals(TimeUnit.MINUTES.toMillis(15), spec.initialDelay)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals("grocery-sync-list-check", SyncScheduler.LIST_CHECK_WORK)
    }
}
