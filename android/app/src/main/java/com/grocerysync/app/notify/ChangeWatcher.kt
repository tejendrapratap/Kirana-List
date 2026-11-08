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

    /**
     * The sheet didn't answer (offline, or a setup problem), or the phone was connected to another sheet while the
     * check ran; the next check tries again.
     */
    NO_ANSWER,

    /**
     * No list of this sheet was seen yet to compare with (a new install, set up or switched to another sheet since):
     * the list now becomes the one seen, nothing is said.
     */
    FIRST_LOOK,

    /** Nobody else put anything on To buy since the list was seen (a notification still up about it goes). */
    NOTHING_NEW,

    /** The notification still up already tells every change: it stays as it is, without sounding again. */
    ALREADY_TOLD,

    /**
     * The notification is shown: everything someone else put on To buy since the list was seen, so one still up is
     * replaced by one that tells it all.
     */
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
 * - The app in the foreground: GroceryApp calls [onForeground] (the notification goes; the person sees the list),
 *   [markSeen] each time the list changes while it is open, and [onBackground] + [markSeen] when it leaves (what the
 *   list shows then is what the person saw).
 * - Every 15 minutes in the background, ListCheckWorker calls [check]: it syncs and compares the list with the one
 *   seen ([ListChangeWatch]). The list seen stays the one the person saw until they open the app: a notification
 *   still up when more changes come is replaced by one that tells them all ("Dahi, Oil, Batti", not only "Batti"),
 *   and one swiped away counts as seen.
 *
 * The notification never shows over the open app: [onForeground] and the moment [check] shows it take one lock, so
 * either the check sees the app open and says nothing, or the notification is up first and [onForeground] takes it
 * away. The list seen is read and written under a mutex, so a check and the app leaving don't mix them up.
 *
 * @param shownList the list as the app shows it (the sheet's list with this phone's waiting changes); read on [io].
 * @param listSheet the web app URL that list was pulled from, while that is the sheet the phone is connected to now;
 *   null when it isn't (not set up, or the sheet saved last hasn't answered yet). Read on [io].
 * @param myName this phone's name as the sheet has it (SettingsStore.effectiveDeviceName).
 * @param texts the language the notification is written in: the one the app shows.
 */
class ChangeWatcher(
    private val seenList: SeenList,
    private val alerts: ChangeAlerts,
    private val shownList: () -> List<GroceryItem>,
    private val listSheet: () -> String?,
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

    /**
     * What the list shows now is what the person saw: later checks only tell what changed after this, and a
     * notification posted before has nothing left to tell. A list not pulled from the phone's sheet ([listSheet]
     * null: not set up, the first sync not answered yet, or the old sheet's list after a switch) isn't what this sheet
     * has: what was stored is forgotten instead, so the first check after the sheet answers only looks (otherwise it
     * would tell the whole of To buy as new).
     */
    suspend fun markSeen() {
        seenMutex.withLock {
            withContext(io) {
                // Read before the list: once this sheet has answered, no other sheet's answer replaces its list.
                val sheet = listSheet()
                seenList.write(sheet?.let { SeenState(it, ListChangeWatch.seen(shownList())) })
            }
        }
    }

    /**
     * One background check: runs only when the phone [isSetUp], [notifyChanges] is on, the app isn't open and Android
     * lets the app notify ([listCheckGate]). Then [sync] brings the list up to date (ListCheckWorker:
     * SyncEngine.syncOnce, which waits for a sync the app runs meanwhile), and what someone else put on To buy since
     * the list was seen is notified: all of it, also what a notification still up told already, so nothing it told
     * gets lost when it is replaced. It sounds only when there is something it didn't tell yet.
     *
     * The list seen moves on only when nothing is left to tell (or on the first look); a notification shown is kept
     * as [PostedNotice] instead, and counts as seen once it is swiped away. Nothing moves on when the app was opened
     * meanwhile (leaving it marks the list seen then) or the notification couldn't be shown (its changes are told
     * next time).
     */
    suspend fun check(isSetUp: Boolean, notifyChanges: Boolean, sync: suspend () -> SyncOutcome): CheckResult {
        listCheckGate(isSetUp, notifyChanges, appOpen, alerts.allowed())?.let { return it }
        if (sync() !is SyncOutcome.Success) return CheckResult.NO_ANSWER
        return seenMutex.withLock {
            val sheet = withContext(io) { listSheet() } ?: return@withLock CheckResult.NO_ANSWER
            val items = withContext(io) { shownList() }
            val stored = withContext(io) { seenList.read() }?.takeIf { it.sheet == sheet }
            if (stored == null) {
                withContext(io) { seenList.write(SeenState(sheet, ListChangeWatch.seen(items))) }
                return@withLock CheckResult.FIRST_LOOK
            }
            val state = stored.withoutDismissedNotice()
            val told = state.notice?.told
            val changes = ListChangeWatch.newOnToBuy(state.seen, items, myName())
            val (result, next) = when {
                changes.isEmpty() -> {
                    // A notification still up told only what is off To buy again (bought, or undone): it goes.
                    if (told != null) alerts.cancel()
                    CheckResult.NOTHING_NEW to SeenState(sheet, ListChangeWatch.seen(items))
                }
                ListChangeWatch.told(changes) == told -> CheckResult.ALREADY_TOLD to state
                else -> {
                    val telling = ListChangeWatch.told(changes)
                    // One that only got shorter (someone bought what it told) is updated quietly.
                    val news = told == null || telling.any { (key, value) -> told[key] != value }
                    when (val shown = notify(ChangeNotice.of(changes, texts()), alert = news)) {
                        CheckResult.NOTIFIED ->
                            shown to state.copy(notice = PostedNotice(ListChangeWatch.seen(items), telling))
                        CheckResult.OPENED_MEANWHILE -> shown to stored
                        else -> shown to state
                    }
                }
            }
            if (next != stored) withContext(io) { seenList.write(next) }
            result
        }
    }

    /**
     * The notification this state says is up was taken away without the app being opened (swiped, or cleared with
     * the others): what it told counts as seen.
     */
    private fun SeenState.withoutDismissedNotice(): SeenState =
        if (notice != null && !alerts.isShowing()) SeenState(sheet, notice.list) else this

    private fun notify(notice: ChangeNotice, alert: Boolean): CheckResult = synchronized(lock) {
        when {
            appOpen -> CheckResult.OPENED_MEANWHILE
            alerts.show(notice, alert) -> CheckResult.NOTIFIED
            else -> CheckResult.NOT_SHOWN
        }
    }
}
