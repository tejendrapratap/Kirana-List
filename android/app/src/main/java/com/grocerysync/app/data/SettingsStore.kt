package com.grocerysync.app.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The saved connection. [token] goes only to the web app (sync, and Test connection in Settings); the app
 * never shows it, so [toString] leaves it out in case these settings ever end up in a message or a log.
 */
data class SyncSettings(val webAppUrl: String, val token: String, val deviceName: String) {
    val isConfigured: Boolean get() = webAppUrl.isNotBlank() && token.isNotBlank()

    override fun toString(): String =
        "SyncSettings(webAppUrl=$webAppUrl, token=${if (token.isEmpty()) "none" else "hidden"}, deviceName=$deviceName)"
}

/**
 * Connection settings kept in private SharedPreferences (the app disables backup and device transfer, so
 * the token stays on the device). (Before 1.2 it also kept the unit used last under "last_unit"; new
 * quantity items no longer start with a unit, and that old entry is simply never read.)
 */
class SettingsStore internal constructor(private val prefs: SharedPreferences) {

    constructor(context: Context) :
        this(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    private val state = MutableStateFlow(load())

    val settings: StateFlow<SyncSettings> = state.asStateFlow()

    fun current(): SyncSettings = state.value

    fun save(settings: SyncSettings) {
        val cleaned = SyncSettings(
            webAppUrl = settings.webAppUrl.trim(),
            token = settings.token.trim(),
            deviceName = settings.deviceName.trim().take(MAX_DEVICE_NAME_LENGTH).trim(),
        )
        synchronized(this) {
            prefs.edit {
                putString(KEY_URL, cleaned.webAppUrl)
                putString(KEY_TOKEN, cleaned.token)
                putString(KEY_DEVICE, cleaned.deviceName)
            }
            state.value = cleaned
        }
    }

    /**
     * The name written to "Updated By" in the sheet, spaced exactly as Code.gs stores it
     * (whitespace runs collapsed), so this phone recognises its own changes when they come back.
     */
    fun effectiveDeviceName(): String {
        val name = ItemKeys.collapseWhitespace(current().deviceName.ifBlank { Build.MODEL.orEmpty() })
            .take(MAX_DEVICE_NAME_LENGTH)
            .trim()
        return name.ifEmpty { DEFAULT_DEVICE_NAME }
    }

    private fun load(): SyncSettings = SyncSettings(
        webAppUrl = prefs.getString(KEY_URL, null).orEmpty(),
        token = prefs.getString(KEY_TOKEN, null).orEmpty(),
        deviceName = prefs.getString(KEY_DEVICE, null).orEmpty(),
    )

    private companion object {
        const val PREFS_NAME = "grocery_sync_settings"
        const val KEY_URL = "web_app_url"
        const val KEY_TOKEN = "token"
        const val KEY_DEVICE = "device_name"
        const val MAX_DEVICE_NAME_LENGTH = 40
        const val DEFAULT_DEVICE_NAME = "Phone"
    }
}
