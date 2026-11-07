package com.grocerysync.app.sync

import com.grocerysync.app.data.SyncSettings
import com.grocerysync.app.data.SyncStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

enum class ConnectionState { NOT_CONFIGURED, CONNECTING, LIVE, OFFLINE, ERROR }

/**
 * LIVE = last poll succeeded; OFFLINE = transient failure, retrying with backoff;
 * ERROR = needs user action ([message] says what to do).
 * [pushProblem] (LIVE only): the list can be read, but changes can't be saved right now.
 * [scriptOutdated]: the last answer came from a script older than protocol 3 (no units); changes wait on
 * the phone until it is updated. Kept while offline: it is still the last thing known about the script.
 */
data class SyncStatus(
    val state: ConnectionState,
    val lastSuccessAt: Long?,
    val message: String?,
    val spreadsheetName: String?,
    val pushProblem: PushProblem? = null,
    val scriptOutdated: Boolean = false,
)

/**
 * Keeps the list "live" while the app is in the foreground. Apps Script can't push, so this polls:
 * sync, wait [pollIntervalMs], repeat. Local edits wake the loop at once (debounced) so they reach
 * the sheet immediately. Transient failures back off 5 -> 10 -> 20 -> 30 s; errors that need the
 * user stop the loop until [syncNow] or a settings change.
 *
 * When only pushing fails (the sheet answers but refuses or fails the change), polls keep pulling
 * and the push is retried with its own backoff in [SyncEngine]; a local edit, [syncNow] (Retry,
 * pull-to-refresh) or a restart of the loop pushes at once.
 */
class LiveSync(
    private val engine: SyncEngine,
    private val settings: StateFlow<SyncSettings>,
    private val store: SyncStore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
) {
    private val mutableStatus = MutableStateFlow(
        SyncStatus(
            state = if (settings.value.isConfigured) ConnectionState.CONNECTING else ConnectionState.NOT_CONFIGURED,
            lastSuccessAt = null,
            message = if (settings.value.isConfigured) null else MSG_NOT_CONFIGURED,
            spreadsheetName = null,
        ),
    )
    val status: StateFlow<SyncStatus> = mutableStatus.asStateFlow()

    private val manualRequests = Channel<Unit>(Channel.CONFLATED)
    private val editNudges = Channel<Unit>(Channel.CONFLATED)
    private val lock = Any()
    private var loopJob: Job? = null

    /** Completed when the manual sync that serves them has finished (see [syncNow]). Guarded by [lock]. */
    private val manualWaiters = ArrayList<CompletableDeferred<Unit>>()

    init {
        // Show the last known sheet name and sync time until the first sync answers.
        scope.launch {
            val info = try {
                withContext(io) { store.syncInfo() }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                return@launch
            }
            mutableStatus.update {
                // Before the first answer, the version of the last one says whether the script is out of date.
                val firstAnswerPending = it.lastSuccessAt == null
                it.copy(
                    lastSuccessAt = it.lastSuccessAt ?: info.lastSuccessAt,
                    spreadsheetName = it.spreadsheetName ?: info.spreadsheetName,
                    scriptOutdated = if (firstAnswerPending) Protocol.isOutdated(info.serverVersion) else it.scriptOutdated,
                )
            }
        }
    }

    /** Starts polling; does nothing if already running. */
    fun start() {
        synchronized(lock) {
            if (loopJob?.isActive == true) return
            loopJob = scope.launch { runLoop() }
        }
    }

    /** Stops polling. Safe at any moment: unconfirmed changes stay in the outbox. */
    fun stop() {
        synchronized(lock) {
            loopJob?.cancel()
            loopJob = null
            // Nothing will serve these requests now.
            takeManualWaiters().forEach { it.complete(Unit) }
        }
    }

    /**
     * Immediate push + pull (pull-to-refresh, Retry, after saving settings). The returned job
     * completes when that sync has finished, whatever its outcome (at once when not configured).
     */
    fun syncNow(): Job {
        val done = CompletableDeferred<Unit>()
        if (!settings.value.isConfigured) {
            done.complete(Unit)
            return done
        }
        synchronized(lock) {
            if (loopJob?.isActive == true) {
                manualWaiters += done
                manualRequests.trySend(Unit)
            } else {
                scope.launch {
                    try {
                        syncAndReport(showConnecting = true, forcePush = true)
                    } finally {
                        done.complete(Unit)
                    }
                }
            }
        }
        return done
    }

    private fun takeManualWaiters(): List<CompletableDeferred<Unit>> = synchronized(lock) {
        manualWaiters.toList().also { manualWaiters.clear() }
    }

    /** Called after a local edit so it is pushed right away. */
    fun nudge() {
        editNudges.trySend(Unit)
    }

    /** Connectivity came back: skip the rest of an offline backoff. */
    fun onNetworkAvailable() {
        if (mutableStatus.value.state == ConnectionState.OFFLINE) editNudges.trySend(Unit)
    }

    private suspend fun runLoop() {
        // collectLatest restarts the loop immediately whenever the settings change.
        settings.collectLatest { config ->
            if (!config.isConfigured) {
                mutableStatus.update {
                    it.copy(state = ConnectionState.NOT_CONFIGURED, message = MSG_NOT_CONFIGURED)
                }
                return@collectLatest
            }
            var offlineStreak = 0
            var trigger = Trigger.START
            while (true) {
                val manual = manualRequests.tryReceive().isSuccess || trigger == Trigger.MANUAL
                // Requests made from here on are served by the next manual sync (their signal stays queued).
                val waiters = if (manual) takeManualWaiters() else emptyList()
                // Only the regular poll waits out a push backoff; edits, Retry and (re)starts push now.
                val forcePush = manual || trigger != Trigger.TIMER
                val outcome = try {
                    syncAndReport(showConnecting = manual || trigger == Trigger.START, forcePush = forcePush)
                } finally {
                    waiters.forEach { it.complete(Unit) }
                }
                trigger = when (outcome) {
                    is SyncOutcome.Success -> {
                        offlineStreak = 0
                        awaitTrigger(pollIntervalMs)
                    }
                    is SyncOutcome.Offline -> awaitTrigger(backoffMs(offlineStreak++))
                    is SyncOutcome.Error, SyncOutcome.NotConfigured -> {
                        offlineStreak = 0
                        manualRequests.receive()
                        Trigger.MANUAL
                    }
                }
            }
        }
    }

    private suspend fun syncAndReport(showConnecting: Boolean, forcePush: Boolean): SyncOutcome {
        // This sync covers every edit made so far; later edits nudge again.
        editNudges.tryReceive()
        if (showConnecting) {
            mutableStatus.update { it.copy(state = ConnectionState.CONNECTING, message = null) }
        }
        val outcome = try {
            engine.syncOnce(push = if (forcePush) PushTiming.NOW else PushTiming.WHEN_DUE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncOutcome.Offline(Protocol.MSG_GOOGLE_TROUBLE)
        }
        mutableStatus.update { previous ->
            when (outcome) {
                is SyncOutcome.Success -> SyncStatus(
                    state = ConnectionState.LIVE,
                    lastSuccessAt = outcome.at,
                    message = null,
                    spreadsheetName = outcome.spreadsheetName ?: previous.spreadsheetName,
                    pushProblem = outcome.pushProblem,
                    scriptOutdated = outcome.scriptOutdated,
                )
                is SyncOutcome.Offline ->
                    previous.copy(state = ConnectionState.OFFLINE, message = outcome.message, pushProblem = null)
                is SyncOutcome.Error -> previous.copy(state = ConnectionState.ERROR, message = outcome.message, pushProblem = null)
                SyncOutcome.NotConfigured -> previous.copy(
                    state = ConnectionState.NOT_CONFIGURED,
                    message = MSG_NOT_CONFIGURED,
                    pushProblem = null,
                    scriptOutdated = false,
                )
            }
        }
        return outcome
    }

    /** Waits for a manual request, a local edit (debounced) or the timeout, whichever comes first. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun awaitTrigger(timeoutMs: Long): Trigger {
        // The timeout is a clause of the select: cancelling a select from outside (withTimeoutOrNull)
        // right after it received a request would drop that request, and a Retry would do nothing.
        val trigger = select<Trigger> {
            manualRequests.onReceive { Trigger.MANUAL }
            editNudges.onReceive { Trigger.EDIT }
            onTimeout(timeoutMs) { Trigger.TIMER }
        }
        // Let a burst of edits (e.g. ticking several items) go out in one request.
        if (trigger == Trigger.EDIT) delay(EDIT_DEBOUNCE_MS)
        return trigger
    }

    private enum class Trigger { START, MANUAL, EDIT, TIMER }

    private companion object {
        const val POLL_INTERVAL_MS = 5_000L
        const val EDIT_DEBOUNCE_MS = 300L
        val BACKOFF_MS = longArrayOf(5_000L, 10_000L, 20_000L, 30_000L)
        const val MSG_NOT_CONFIGURED = "Add the web app URL and secret token in Settings."

        fun backoffMs(streak: Int): Long = BACKOFF_MS[streak.coerceIn(0, BACKOFF_MS.lastIndex)]
    }
}
