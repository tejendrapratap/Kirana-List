package com.grocerysync.app.testing

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import androidx.core.content.edit
import androidx.core.database.sqlite.transaction
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.grocerysync.app.AppContainer
import com.grocerysync.app.GroceryApp
import com.grocerysync.app.data.PhoneSettings
import com.grocerysync.app.data.SyncSettings
import com.grocerysync.app.sync.ConnectionState
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

/**
 * The app's state between UI tests, reached through its own classes. All tests run in one process, so the app's
 * [AppContainer] (settings, database, live sync) lives on from test to test; [reset] makes it look like a fresh
 * install again without restarting the process (much faster than the test orchestrator's clearPackageData).
 */
object AppState {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    val container: AppContainer
        get() = (context as GroceryApp).container

    /**
     * No connection, an empty list, nothing waiting to be sent. Call before launching the activity.
     *
     * 1. Saves empty settings through SettingsStore (which also updates what the running app sees): the live sync
     *    stops, and any sync that starts from now on does nothing.
     * 2. Cancels the app's WorkManager pushes, so none of them runs into the next test.
     * 3. Waits for a sync that was already running: SyncEngine.syncOnce takes the same lock, then returns at once
     *    because nothing is configured.
     * 4. Empties every table of the app's database (list, waiting changes, revision, script version), then makes
     *    one write through GroceryDatabase so the app's flows read it again.
     * 5. Clears the saved clock offset.
     * 6. Groups To buy by shop section again and forgets the sections chosen for items (through ShopSectionStore,
     *    which also updates what the running app sees).
     * 7. Puts this phone's own settings (language, Simple mode, notifications) back to their defaults, through
     *    PhoneSettingsStore. MainActivity reads the language when it starts. Except the list's offer of
     *    notifications, which counts as answered: on Android 13+ without the permission it would otherwise sit above
     *    the list in every connected test, and whether it does depends on test order (ThisPhoneSwitchesTest grants
     *    the permission, which can't be taken back without ending the test's process; tools/e2e/uitest.sh takes it
     *    back before the next run). A test of the offer sets it back to unanswered. Android counts as asked for the
     *    permission too, so Settings > This phone shows what it showed before that was saved (blocked, while it
     *    isn't granted).
     * 8. Forgets Ticked today, through the app's own store (clearing its file alone would leave the store's copy in
     *    memory): its entries are kept for the day, so a tick in one test would show under To buy in the next.
     */
    fun reset() {
        val app = container
        app.settings.save(SyncSettings(webAppUrl = "", token = "", deviceName = ""))
        WorkManager.getInstance(context).cancelAllWork().result.get(10, TimeUnit.SECONDS)
        runBlocking { app.syncEngine.syncOnce() }
        clearDatabase(app)
        context.getSharedPreferences(CLOCK_PREFS, Context.MODE_PRIVATE).edit(commit = true) { clear() }
        app.shopSections.setGroupBySection(true)
        app.shopSections.current().chosen.keys.forEach { app.shopSections.setSection(it, null) }
        val phone = PhoneSettings()
        app.phoneSettings.setLanguage(phone.language)
        app.phoneSettings.setSimpleMode(phone.simpleMode)
        app.phoneSettings.setNotifyChanges(phone.notifyChanges)
        app.phoneSettings.setNotifyOfferDone(true)
        app.phoneSettings.setNotifyPermissionAsked(true)
        app.tickedToday.retain { false }
    }

    /** Saves the connection the way Settings > Save does (SettingsStore cleans the values). */
    fun connect(url: String, token: String, deviceName: String) {
        container.settings.save(SyncSettings(webAppUrl = url, token = token, deviceName = deviceName))
    }

    fun settings(): SyncSettings = container.settings.current()

    /** True once the app synced with the sheet at or after [time] (local clock) and is live. */
    fun syncedSince(time: Long): Boolean {
        val status = container.liveSync.status.value
        return status.state == ConnectionState.LIVE && (status.lastSuccessAt ?: 0L) >= time
    }

    /** The installed app's versionName, as Settings reads it. */
    fun versionName(): String? {
        val packageManager = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(context.packageName, 0)
        }
        return info.versionName
    }

    private fun clearDatabase(app: AppContainer) {
        // Opens the database (creating it if the app hasn't yet), so the file below exists.
        app.database.syncInfo()
        val file = context.getDatabasePath(DB_NAME)
        check(file.exists()) { "The app's database $DB_NAME is missing: was it renamed? Update AppState.DB_NAME." }
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            val tables = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata'",
                null,
            ).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            db.transaction { tables.forEach { delete(it, null, null) } }
        }
        // Any write through the app's own database bumps its version, so the list is read again.
        app.database.discardFailed()
    }

    /** GroceryDatabase's file and PrefsClockOffsetStore's preferences (private names in the app). */
    private const val DB_NAME = "grocery_sync.db"
    private const val CLOCK_PREFS = "grocery_sync_clock"
}
