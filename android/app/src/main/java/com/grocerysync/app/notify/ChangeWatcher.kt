package com.grocerysync.app.notify

import com.grocerysync.app.data.GroceryItem
import com.grocerysync.app.sync.SyncOutcome
import com.grocerysync.app.text.AppTexts
import com.grocerysync.app.text.Texts
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** How one background check for changes ended (what [ChangeWatcher.check] did). */
enum class CheckResult {
    /** Not run: the phone isn't connected to a sheet. */
    NOT_SET_UP,

    /** Not run: notifications are turned off in Settings > This phone. */
    TURNED_OFF,

    /** Not run: the app is open, so the person sees the list itself (and the live sync keeps it fresh). */
    APP_OPEN,

    /** Not run: Android doesn't let the app show notifications (permission refused, or turned off). */
    BLOCKED,

    /** The sheet didn't answer (offline, or a setup problem); the next check tries again. */
    NO_ANSWER,

    /** No list was seen yet to compare with: the list now becomes the one seen, nothing is said. */
    FIRST_LOOK,

    /** Nobody else put anything on To buy since the list was seen. */
    NOTHING_NEW,

    /** The notification is shown. */
    NOTIFIED,

    /** The app was opened while the check ran: the person sees the changes on the list itself. */
    OPENED_MEANWHILE,

    /** Android refused the notification at the last moment (the permission was just taken away). */
    NOT_SHOWN,
}

/**
 * Whether a background check runs at all, from what it knows before syncing; null when it runs. The order matters
 * only for what [CheckResult] says.
 */
fun listCheckGate(
    isSetUp: Boolean,
    notifyChanges: Boolean,
    appOpen: Boolean,
    notificationsAllowed: Boolean,
): CheckResult? =
    when {
        !isSetUp -> CheckResult.NOT_SET_UP
        !notifyChanges -> CheckResult.TURNED_OFF
        appOpen -> CheckResult.APP_OPEN
        !notificationsAllowed -> CheckResult.BLOCKED
        else -> null
    }

/**
 * Change notifications for the whole app process (GroceryApp holds the one instance): tells the person what someone
 * else put on To buy while the app was closed. Without Android in it, so the whole check is unit-tested.
 *
 * - The app in the foreground: GroceryApp calls [onForeground] (the notification goes; the person sees the list) and
 *   [onBackground] + [markSeen] when it leaves (what the list shows now is what the person saw).
 * - Every 15 minutes in the background, ListCheckWorker calls [check]: it syncs and compares the list with the one
 *   seen ([ListChangeWatch]).
 *
 * The notification never shows over the open app: [onForeground] and the moment [check] shows it take one lock, so
 * either the check sees the app open and says nothing, or the notification is up first and [onForeground] takes it
 * away. The list seen is read and written under a mutex, so a check and the app leaving don't mix them up.
 *
 * @param shownList the list as the app shows it (the sheet's list with this phone's waiting changes); read on [io].
 * @param myName this phone's name as the sheet has it (SettingsStore.effectiveDeviceName).
 * @param texts the language the notification is written in: the one the app shows.
 */
class ChangeWatcher(
    private val seenList: SeenList,
    private val alerts: ChangeAlerts,
    private val shownList: () -> List<GroceryItem>,
    private val myName: () -> String,
    private val texts: () -> AppTexts = { Texts.current },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** True while some screen of the app is visible (between [onForeground] and [onBackground]). */
    @Volatile
    var appOpen: Boolean = false
        private set

    private val lock = Any()
    private val seenMutex = Mutex()

    /** The app came to the foreground: the person sees the list, so the notification about it goes. */
    fun onForeground() {
        synchronized(lock) {
            appOpen = true
            alerts.cancel()
        }
    }

    /** The app went to the background; call [markSeen] next. */
    fun onBackground() {
        appOpen = false
    }

    /** What the list shows now is what the person saw: later checks only tell what changed after this. */
    suspend fun markSeen() {
        seenMutex.withLock {
            withContext(io) { seenList.write(ListChangeWatch.seen(shownList())) }
        }
    }

    /**
     * One background check: runs only when the phone [isSetUp], [notifyChanges] is on, the app isn't open and Android
     * lets the app notify ([listCheckGate]). Then [sync] brings the list up to date (ListCheckWorker:
     * SyncEngine.syncOnce, which waits for a sync the app runs meanwhile), and what someone else put on To buy since
     * the list was seen is notified. Afterwards the list now counts as seen, unless the app was opened meanwhile
     * (leaving it marks it seen then) or the notification couldn't be shown (its changes are told next time).
     */
    suspend fun check(isSetUp: Boolean, notifyChanges: Boolean, sync: suspend () -> SyncOutcome): CheckResult {
        listCheckGate(isSetUp, notifyChanges, appOpen, alerts.allowed())?.let { return it }
        if (sync() !is SyncOutcome.Success) return CheckResult.NO_ANSWER
        return seenMutex.withLock {
            val items = withContext(io) { shownList() }
            val seen = withContext(io) { seenList.read() }
            val changes = seen?.let { ListChangeWatch.newOnToBuy(it, items, myName()) }.orEmpty()
            val result = when {
                seen == null -> CheckResult.FIRST_LOOK
                changes.isEmpty() -> CheckResult.NOTHING_NEW
                else -> notify(ChangeNotice.of(changes, texts()))
            }
            if (result in SEEN_AFTER) withContext(io) { seenList.write(ListChangeWatch.seen(items)) }
            result
        }
    }

    private fun notify(notice: ChangeNotice): CheckResult = synchronized(lock) {
        when {
            appOpen -> CheckResult.OPENED_MEANWHILE
            alerts.show(notice) -> CheckResult.NOTIFIED
            else -> CheckResult.NOT_SHOWN
        }
    }

    private companion object {
        /** The checks after which the list now counts as seen (see [check]). */
        val SEEN_AFTER = setOf(CheckResult.FIRST_LOOK, CheckResult.NOTHING_NEW, CheckResult.NOTIFIED)
    }
}
