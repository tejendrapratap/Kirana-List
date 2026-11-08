package com.grocerysync.app.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.grocerysync.app.data.PhoneSettings
import com.grocerysync.app.data.SyncSettings
import java.util.concurrent.TimeUnit

/** Schedules [SyncWorker] and [ListCheckWorker] runs through WorkManager. */
class SyncScheduler(context: Context) {

    private val appContext = context.applicationContext
    private val workManager: WorkManager by lazy { WorkManager.getInstance(appContext) }

    /** Called on every local edit: makes sure the outbox is pushed even if the app is closed right away. */
    fun requestPush() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, PUSH_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        // APPEND_OR_REPLACE: a run already in progress may have read the outbox before this edit,
        // so the new run queues behind it instead of being dropped.
        workManager.enqueueUniqueWork(PUSH_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Hourly push of anything left behind (e.g. edits made while the phone was offline for long). */
    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(networkConstraint)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * The background check for change notifications ([ListCheckWorker]) every 15 minutes while [enabled]
     * ([shouldCheckList]), else none. GroceryApp calls it at app start and whenever the connection or the
     * notifications switch changes. UPDATE keeps the schedule of a check already planned (a new start of the app
     * doesn't push it back) while the request's details follow this version.
     */
    fun scheduleListCheck(enabled: Boolean) {
        if (enabled) {
            val request = listCheckRequest()
            workManager.enqueueUniquePeriodicWork(LIST_CHECK_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        } else {
            workManager.cancelUniqueWork(LIST_CHECK_WORK)
        }
    }

    companion object {
        /** The unique name of the change notifications' periodic check. */
        const val LIST_CHECK_WORK = "grocery-sync-list-check"

        /** The check's period: the shortest WorkManager allows (PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS). */
        const val LIST_CHECK_MINUTES = 15L

        private const val PUSH_WORK = "grocery-sync-push"
        private const val PERIODIC_WORK = "grocery-sync-periodic"
        private const val PUSH_BACKOFF_SECONDS = 30L

        private val networkConstraint = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** The background check runs only for a phone that is set up and wants the notifications. */
        fun shouldCheckList(settings: SyncSettings, phone: PhoneSettings): Boolean =
            settings.isConfigured && phone.notifyChanges

        /**
         * Every [LIST_CHECK_MINUTES] with a network. The first run waits one period too: when the check is planned,
         * the app is open (setup, the switch, app start), and the check does nothing then.
         */
        internal fun listCheckRequest(): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<ListCheckWorker>(LIST_CHECK_MINUTES, TimeUnit.MINUTES)
                .setConstraints(networkConstraint)
                .setInitialDelay(LIST_CHECK_MINUTES, TimeUnit.MINUTES)
                .build()
    }
}
