package com.grocerysync.app.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.grocerysync.app.GroceryApp
import com.grocerysync.app.notify.ChangeWatcher

/**
 * The background check for change notifications, every 15 minutes (Android's shortest period) while the phone is set
 * up and they are on ([SyncScheduler.scheduleListCheck]): syncs and tells what someone else put on To buy since the
 * list was last seen. Everything it decides is in [ChangeWatcher.check]; it does nothing while the app is open or
 * Android doesn't let the app notify.
 *
 * The sync is SyncEngine.syncOnce, the one the live sync uses: its mutex lets only one run at a time, so when the app
 * opens mid-check its first sync waits for this one (nothing is sent twice or lost; see SyncEngine), and the
 * notification is not shown over the open app. It pushes waiting changes too ([PushTiming.BACKGROUND], like
 * [SyncWorker]), without touching the live sync's push backoff.
 *
 * Always succeeds: a check that couldn't reach the sheet is simply tried again at the next period.
 */
class ListCheckWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? GroceryApp ?: return Result.success()
        val container = app.container
        val result = app.changeWatcher.check(
            isSetUp = container.settings.current().isConfigured,
            notifyChanges = container.phoneSettings.current().notifyChanges,
        ) {
            container.syncEngine.syncOnce(pull = true, push = PushTiming.BACKGROUND)
        }
        Log.d(TAG, "List check: $result")
        return Result.success()
    }

    private companion object {
        const val TAG = "ListCheckWorker"
    }
}
