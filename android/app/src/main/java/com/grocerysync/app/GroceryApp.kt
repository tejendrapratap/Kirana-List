package com.grocerysync.app

import android.app.Application
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.grocerysync.app.text.Texts
import com.grocerysync.app.text.update

class GroceryApp : Application() {

    lateinit var container: AppContainer

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // For what the app shows without a screen (background work); MainActivity sets it again for its own.
        updateTexts(resources.configuration)

        // Poll the sheet only while some activity of the app is visible.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                container.liveSync.start()
                registerNetworkCallback()
            }

            override fun onStop(owner: LifecycleOwner) {
                unregisterNetworkCallback()
                container.liveSync.stop()
            }
        })

        container.syncScheduler.schedulePeriodic()
    }

    /** The phone's language may have changed (PHONE follows it) while no activity of the app is running. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateTexts(newConfig)
    }

    private fun updateTexts(configuration: Configuration) {
        Texts.update(container.phoneSettings.current().language, configuration)
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
    }
}
