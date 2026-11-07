package com.grocerysync.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.grocerysync.app.GroceryApp

/**
 * Safety net for edits made just before the app goes to the background: pushes the outbox when
 * the device has a network, even if the app is no longer open. Pulling is left to [LiveSync].
 */
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? GroceryApp)?.container ?: return Result.failure()
        if (!container.settings.current().isConfigured) return Result.success()

        // Pushes now whatever the foreground push backoff says (without restarting it); WorkManager
        // spaces out this worker's own retries.
        return when (val outcome = container.syncEngine.syncOnce(pull = false, push = PushTiming.BACKGROUND)) {
            is SyncOutcome.Success -> when {
                // Changes wait for the sheet's script to be updated; retrying here wouldn't send them.
                // The app sends them once it sees the new script (and the hourly run tries too).
                outcome.scriptOutdated -> Result.success()
                container.syncEngine.hasPendingChanges() -> retryOrGiveUp()
                else -> Result.success()
            }
            is SyncOutcome.Offline -> retryOrGiveUp()
            is SyncOutcome.Error -> Result.failure()
            SyncOutcome.NotConfigured -> Result.success()
        }
    }

    private fun retryOrGiveUp(): Result =
        if (runAttemptCount + 1 >= MAX_ATTEMPTS) Result.failure() else Result.retry()

    private companion object {
        const val MAX_ATTEMPTS = 10
    }
}
