package com.grocerysync.app

import android.content.Context
import com.grocerysync.app.data.GroceryDatabase
import com.grocerysync.app.data.GroceryRepository
import com.grocerysync.app.data.PrefsClockOffsetStore
import com.grocerysync.app.data.ServerClock
import com.grocerysync.app.data.SettingsStore
import com.grocerysync.app.sync.LiveSync
import com.grocerysync.app.sync.SheetsClient
import com.grocerysync.app.sync.SyncEngine
import com.grocerysync.app.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual dependency injection: one instance of each service for the whole app process. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /** Lives as long as the process; used for sync work that must outlive any screen. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsStore(appContext)
    val database = GroceryDatabase(appContext)
    val serverClock = ServerClock(offsetStore = PrefsClockOffsetStore(appContext))
    val sheetsClient = SheetsClient()
    val syncScheduler = SyncScheduler(appContext)

    val syncEngine = SyncEngine(
        store = database,
        client = sheetsClient,
        settings = settings::current,
        deviceName = settings::effectiveDeviceName,
        clock = serverClock,
    )

    val liveSync = LiveSync(
        engine = syncEngine,
        settings = settings.settings,
        store = database,
        scope = applicationScope,
    )

    val repository = GroceryRepository(
        db = database,
        settings = settings,
        clock = serverClock,
        onLocalChange = {
            liveSync.nudge()
            syncScheduler.requestPush()
        },
    )
}
