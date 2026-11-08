package com.grocerysync.app.sync

import com.grocerysync.app.data.ListLogic
import com.grocerysync.app.data.OpStatus
import com.grocerysync.app.data.ServerClock
import com.grocerysync.app.data.SyncSettings
import com.grocerysync.app.data.SyncStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

sealed interface SyncOutcome {
    /**
     * The sheet answered. [at] = local time of the successful exchange. [pushProblem] is set while
     * changes are waiting because the last push failed, although the list could be read.
     * [scriptOutdated]: the sheet runs a script older than protocol 3; the list is read, but changes are
     * held on the phone until the script is updated.
     */
    data class Success(
        val at: Long,
        val spreadsheetName: String?,
        val pushProblem: PushProblem? = null,
        val scriptOutdated: Boolean = false,
    ) : SyncOutcome
    data object NotConfigured : SyncOutcome
    data class Offline(val message: String) : SyncOutcome
    data class Error(val message: String) : SyncOutcome
}

/** When [SyncEngine.syncOnce] may push, given earlier push failures the server answered. */
enum class PushTiming {
    /** Regular poll: push only once the backoff after failed pushes has passed; until then just pull. */
    WHEN_DUE,

    /** User Retry, pull-to-refresh, a local edit, the loop (re)starting: push now and restart the backoff. */
    NOW,

    /** Background worker: push now, without restarting the foreground backoff. */
    BACKGROUND,
}

/**
 * The web app answered the last push with a failure (the script's error, sheet busy, Google 5xx, ...)
 * and changes are still waiting. [failures] = pushes in a row that failed that way (the background
 * worker's pushes are not counted: it pushes each edit at about the same moment as the live loop).
 * [mayBeMomentary]: the kind of problem that usually clears up by itself (busy, 5xx, cut-off body, a
 * Sheets time-out the script reported). [needsFix]: retrying alone won't help (e.g. the script rejects
 * the request as malformed); the changes wait until the script or sheet is fixed.
 */
data class PushProblem(
    val message: String,
    val failures: Int,
    val mayBeMomentary: Boolean,
    val needsFix: Boolean = false,
) {
    /** Worth a warning: a real error from the script, or a hiccup that happened twice in a row. */
    val shouldWarn: Boolean get() = !mayBeMomentary || failures >= 2
}

/**
 * One push + pull round trip with the sheet. A single instance is shared by [LiveSync] and
 * [SyncWorker]; the mutex keeps them from syncing at the same time.
 *
 * A failed push doesn't stop the pull when the server answered (script error, busy, Google 5xx,
 * garbled body): the list keeps updating and the outcome carries a [PushProblem]. Only "no response"
 * (offline) and setup problems that break reads too (URL, token, access) end the sync at once.
 *
 * Data-safety invariant: an outbox op is deleted only after a response confirmed it, and only ops
 * that existed when the request was built can be deleted by that request. Cancelling a sync at any
 * point therefore loses nothing: unconfirmed ops are sent again and the server de-duplicates them
 * by change id.
 *
 * Changes are sent only to a script known to speak protocol 3 (amounts with decimals and units; the
 * version of the last answer is kept in the store). While the version is unknown the list is read
 * first; while the script is older, changes stay PENDING and every sync just reads the list
 * ([SyncOutcome.Success.scriptOutdated]).
 */
class SyncEngine(
    private val store: SyncStore,
    private val client: SheetsClient,
    private val settings: () -> SyncSettings,
    private val deviceName: () -> String,
    private val clock: ServerClock,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Monotonic milliseconds, for the push backoff. */
    private val elapsedMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val mutex = Mutex()

    /** Guarded by [mutex]. */
    private val pushHealth = PushHealth()

    /**
     * Pushes pending changes, then (if [pull]) fetches the list unless a push response already carried it.
     * Before the script is known to take protocol 3 changes, the list is read first (see the class comment).
     *
     * After pushes the server answered with a failure, [PushTiming.WHEN_DUE] (regular polls) only pushes
     * again once [PushHealth.BACKOFF_MS] has passed and meanwhile just pulls; see [PushTiming] for the
     * others. Never throws except for cancellation.
     */
    suspend fun syncOnce(pull: Boolean = true, push: PushTiming = PushTiming.NOW): SyncOutcome = mutex.withLock {
        val config = settings()
        if (!config.isConfigured) return@withLock SyncOutcome.NotConfigured
        try {
            withContext(io) { runSync(config, pull, push) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Unexpected (e.g. storage) problem; report it as transient so the loop retries.
            SyncOutcome.Offline(Protocol.MSG_GOOGLE_TROUBLE)
        }
    }

    /** True while the outbox holds changes that still have to be sent. */
    suspend fun hasPendingChanges(): Boolean =
        withContext(io) { store.loadOutbox().any { it.status == OpStatus.PENDING } }

    private suspend fun runSync(config: SyncSettings, pull: Boolean, push: PushTiming): SyncOutcome {
        var lastSpreadsheetName: String? = null
        // Local time of the last exchange in this sync that the sheet answered successfully.
        var answeredAt: Long? = null
        var pulled = false
        var pushFailed = false

        if (push == PushTiming.NOW) pushHealth.restartBackoff()
        val pushDue = push != PushTiming.WHEN_DUE || pushHealth.mayPush(elapsedMs())

        if (pushDue && !scriptTakesChanges() && hasChangesToSend()) {
            // The script's version isn't known yet (first sync after the update) or it is older than
            // protocol 3: read the list first, its answer says which script runs. Changes go out only to a
            // script that knows units; an old one would refuse amounts with units or decimals, or drop the
            // units. Until the script is updated they wait on the phone (PENDING, nothing lost).
            when (val listed = pullList(config)) {
                is ApiResult.Failure -> return listed.toOutcome()
                is ApiResult.Success -> {
                    answeredAt = listed.value
                    pulled = true
                }
            }
        }

        if (pushDue && scriptTakesChanges()) {
            var rounds = 0
            while (rounds++ < MAX_PUSH_ROUNDS && scriptTakesChanges()) {
                val batch = ListLogic.coalesce(store.loadOutbox()).take(Protocol.MAX_CHANGES_PER_REQUEST)
                if (batch.isEmpty()) break

                val sentAt = clock.localNow()
                val response = client.apply(config.webAppUrl, config.token, deviceName(), batch.map { it.op })
                val receivedAt = clock.localNow()
                val result = when (response) {
                    is ApiResult.Failure -> {
                        // No response, or a setup problem: a pull would fail the same way.
                        if (!response.leavesReadsWorking) return response.toOutcome()
                        pushHealth.onFailure(response, elapsedMs(), countsAsAttempt = push != PushTiming.BACKGROUND)
                        if (!pull) return response.toOutcome()
                        pushFailed = true
                        break
                    }
                    is ApiResult.Success -> response.value
                }
                clock.onServerTime(result.serverTime, sentAt, receivedAt)
                // An older script answered after all (e.g. an older deployment is live again): it may have
                // refused changes only because it doesn't know units, so their errors don't count; they stay
                // PENDING and go out again once the script is updated.
                val scriptKnowsUnits = Protocol.takesUnits(result.serverVersion)

                val byId = result.results.associateBy { it.id }
                val deleteIds = LinkedHashSet<String>()
                val failures = LinkedHashMap<String, String>()
                for (entry in batch) {
                    val changeResult = byId[entry.op.id] ?: continue // no answer: stays pending
                    when (changeResult.status) {
                        ChangeStatus.APPLIED, ChangeStatus.STALE, ChangeStatus.DUPLICATE -> {
                            deleteIds += entry.op.id
                            deleteIds += entry.supersededIds
                        }
                        ChangeStatus.ERROR -> if (scriptKnowsUnits) {
                            // The older edits it stands for would fail the same way: they fail with it (and
                            // Retry sends them as one change again) instead of going out one per round.
                            val error = changeResult.error ?: ListLogic.DEFAULT_FAILURE
                            failures[entry.op.id] = error
                            entry.supersededIds.forEach { failures[it] = error }
                        }
                    }
                }
                store.commitPush(
                    deleteIds,
                    failures,
                    result.snapshot,
                    receivedAt,
                    result.serverVersion,
                    config.webAppUrl,
                )
                lastSpreadsheetName = result.snapshot.spreadsheetName ?: lastSpreadsheetName
                answeredAt = receivedAt

                // The server answered without settling anything: don't spin, try again next sync.
                if (deleteIds.isEmpty() && failures.isEmpty()) break
            }
            if (!pushFailed) pushHealth.onSuccess()
        } else if (!hasChangesToSend()) {
            // Waiting out the backoff (or for the script update), but nothing is left to send any more.
            pushHealth.onSuccess()
        }

        // An apply response carries the full list, and a list read before pushing is still fresh: a
        // separate pull would only repeat it.
        if (pull && !pulled && (pushFailed || answeredAt == null)) {
            when (val listed = pullList(config)) {
                is ApiResult.Failure -> return listed.toOutcome()
                is ApiResult.Success -> answeredAt = listed.value
            }
        }
        return SyncOutcome.Success(
            answeredAt ?: clock.localNow(),
            lastSpreadsheetName ?: store.syncInfo().spreadsheetName,
            pushHealth.problem,
            scriptOutdated = Protocol.isOutdated(store.syncInfo().serverVersion),
        )
    }

    /**
     * Reads the list (only what changed since the stored revision) and stores it. Returns the local time
     * the answer arrived.
     *
     * A list stored from another protocol version (before or after the sheet's script was updated) is
     * read again in full even if the sheet hasn't changed: an older script shows quantities as whole
     * numbers without units.
     */
    private suspend fun pullList(config: SyncSettings): ApiResult<Long> {
        val storedVersion = store.syncInfo().serverVersion
        var since = store.revision()
        while (true) {
            val sentAt = clock.localNow()
            val listResult = when (val response = client.list(config.webAppUrl, config.token, since)) {
                is ApiResult.Failure -> return response
                is ApiResult.Success -> response.value
            }
            val receivedAt = clock.localNow()
            clock.onServerTime(listResult.serverTime, sentAt, receivedAt)
            if (listResult.unchanged && since != null && listResult.serverVersion != storedVersion) {
                since = null
                continue
            }
            store.commitPull(listResult.snapshot, receivedAt, listResult.serverVersion, config.webAppUrl)
            return ApiResult.Success(receivedAt)
        }
    }

    /** The sheet's script is known to take protocol 3 changes. */
    private fun scriptTakesChanges(): Boolean = Protocol.takesUnits(store.syncInfo().serverVersion)

    private fun hasChangesToSend(): Boolean = ListLogic.coalesce(store.loadOutbox()).isNotEmpty()

    /** The server answered, but only this request failed: reading the list may still work. */
    private val ApiResult.Failure.leavesReadsWorking: Boolean
        get() = source == FailureSource.GLITCH || source == FailureSource.SCRIPT

    private fun ApiResult.Failure.toOutcome(): SyncOutcome = when (kind) {
        FailureKind.OFFLINE -> SyncOutcome.Offline(message)
        FailureKind.ERROR -> SyncOutcome.Error(message)
    }

    private companion object {
        const val MAX_PUSH_ROUNDS = 10
    }
}

/**
 * Pushes the server answered with a failure. Spaces out further push attempts (5 -> 10 -> 20 -> 30 s)
 * so polls keep pulling without re-sending a failing request every few seconds. Not thread-safe:
 * [SyncEngine] uses it under its mutex. Times are monotonic milliseconds.
 */
internal class PushHealth {
    /** Null after a push went through (or nothing was left to send). */
    var problem: PushProblem? = null
        private set

    private var step = 0
    private var retryAt = 0L

    fun mayPush(now: Long): Boolean = step == 0 || now >= retryAt

    /** The next push may go now, and a failure waits the shortest delay again (user Retry, local edit, ...). */
    fun restartBackoff() {
        step = 0
    }

    /**
     * [countsAsAttempt] false (background worker): the failure still lengthens the backoff, but leaves
     * [problem] as the foreground pushes left it. Every local edit starts the worker and the live loop
     * at about the same moment; counting both failures would make one momentary failure "twice in a
     * row" and show the warning at once.
     */
    fun onFailure(failure: ApiResult.Failure, now: Long, countsAsAttempt: Boolean = true) {
        retryAt = now + BACKOFF_MS[step.coerceAtMost(BACKOFF_MS.lastIndex)]
        step++
        if (!countsAsAttempt) return
        problem = PushProblem(
            message = failure.message,
            failures = (problem?.failures ?: 0) + 1,
            mayBeMomentary = failure.source == FailureSource.GLITCH,
            needsFix = failure.kind == FailureKind.ERROR,
        )
    }

    fun onSuccess() {
        problem = null
        step = 0
        retryAt = 0L
    }

    companion object {
        /** Wait before the next push after 1, 2, 3, 4+ failures in a row. */
        val BACKOFF_MS = longArrayOf(5_000L, 10_000L, 20_000L, 30_000L)
    }
}
