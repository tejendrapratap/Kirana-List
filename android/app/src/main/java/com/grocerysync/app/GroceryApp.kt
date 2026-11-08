package com.grocerysync.app

import android.app.Application
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.grocerysync.app.data.ListLogic
import com.grocerysync.app.notify.ChangeNotifier
import com.grocerysync.app.notify.ChangeWatcher
import com.grocerysync.app.notify.SeenListStore
import com.grocerysync.app.sync.SyncScheduler
import com.grocerysync.app.text.Texts
import com.grocerysync.app.text.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class GroceryApp : Application() {

    lateinit var container: AppContainer

    /** Change notifications: what someone else put on To buy while the app was closed (ListCheckWorker). */
    lateinit var changeWatcher: ChangeWatcher
        private set

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** While the app is visible: marks the list seen each time it changes ([markSeenWhileOpen]). */
    private var seenWhileOpen: Job? = null

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // For what the app shows without a screen (background work); MainActivity sets it again for its own.
        updateTexts(resources.configuration)

        val notifier = ChangeNotifier(this)
        // The channel's name in Android's settings, in the language shown now.
        notifier.createChannel()
        changeWatcher = ChangeWatcher(
            seenList = SeenListStore(this),
            alerts = notifier,
            shownList = {
                val (mirror, ops) = container.database.loadSnapshot()
                ListLogic.overlay(mirror, ops, container.settings.effectiveDeviceName())
            },
            listSheet = {
                // The URL that answered last is the list's sheet; until the one saved last answers, the list isn't its.
                val connection = container.settings.current()
                container.database.syncInfo().sheetUrl?.takeIf { connection.isConfigured && it == connection.webAppUrl }
            },
            myName = container.settings::effectiveDeviceName,
        )

        // Poll the sheet only while some activity of the app is visible. Visible, the person sees the list: no change
        // notification then, and what the list shows when the app leaves is what they saw.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                changeWatcher.onForeground()
                container.liveSync.start()
                registerNetworkCallback()
                markSeenWhileOpen()
            }

            override fun onStop(owner: LifecycleOwner) {
                unregisterNetworkCallback()
                container.liveSync.stop()
                seenWhileOpen?.cancel()
                seenWhileOpen = null
                changeWatcher.onBackground()
                container.applicationScope.launch { changeWatcher.markSeen() }
            }
        })

        container.syncScheduler.schedulePeriodic()
        scheduleListChecks()
    }

    /**
     * Plans the background check for change notifications now and again whenever the connection or the notifications
     * switch changes: only while the phone is set up and the switch is on ([SyncScheduler.shouldCheckList]).
     */
    private fun scheduleListChecks() {
        container.applicationScope.launch {
            container.settings.settings
                .combine(container.phoneSettings.settings, SyncScheduler::shouldCheckList)
                .distinctUntilChanged()
                .collect { enabled -> container.syncScheduler.scheduleListCheck(enabled) }
        }
    }

    /**
     * Marks the list seen a moment after each change while the app is visible, not only when it leaves: Android tells
     * the app it left about 0.7 s late (ProcessLifecycleOwner), and closing it from Recents within that time ends the
     * process first, so the next check would tell what the person just saw. The write when it leaves stays (a change
     * in its last moment). The database's version moves with every change of the list: pulled, sent or made here.
     */
    private fun markSeenWhileOpen() {
        seenWhileOpen?.cancel()
        seenWhileOpen = container.applicationScope.launch {
            container.database.version.collectLatest {
                delay(SEEN_DELAY_MS)
                changeWatcher.markSeen()
            }
        }
    }

    /** The phone's language may have changed (PHONE follows it) while no activity of the app is running. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateTexts(newConfig)
    }

    /**
     * Also names the notifications' channel again when the language changed: Android keeps the name it was given
     * last (shown in the app's notification settings), and the process outlives a change of language.
     */
    private fun updateTexts(configuration: Configuration) {
        if (Texts.update(container.phoneSettings.current().language, configuration)) ChangeNotifier(this).createChannel()
    }

    /** Ends an offline backoff as soon as the phone is back online. */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                container.liveSync.onNetworkAvailable()
            }
        }
        try {
            connectivity.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: RuntimeException) {
            // Missing permission or too many callbacks; polling still recovers on its own.
            Log.w(TAG, "Could not watch network changes", e)
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        } catch (e: IllegalArgumentException) {
            // Already unregistered.
        }
    }

    private companion object {
        const val TAG = "GroceryApp"

        /** How long the list shown stays unchanged before it counts as seen while the app is visible. */
        const val SEEN_DELAY_MS = 500L
    }
}
