package com.grocerysync.app.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Schedules [SyncWorker] runs through WorkManager. */
class SyncScheduler(context: Context) {

    private val appContext = context.applicationContext
    private val workManager: WorkManager by lazy { WorkManager.getInstance(appContext) }

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

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

    private companion object {
        const val PUSH_WORK = "grocery-sync-push"
        const val PERIODIC_WORK = "grocery-sync-periodic"
        const val PUSH_BACKOFF_SECONDS = 30L
    }
}
