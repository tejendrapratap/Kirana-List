package com.grocerysync.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.grocerysync.app.text.AppLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * This phone's own preferences (Settings > This phone). Each phone has its own: an elder's phone can show Hindi in
 * Simple mode while the shopper's shows English.
 */
data class PhoneSettings(
    /** The language the app shows; PHONE follows the phone's own language. */
    val language: AppLanguage = AppLanguage.PHONE,
    /** Simple mode: only To buy, bigger text and buttons, Undo that stays on screen. */
    val simpleMode: Boolean = false,
    /** Notify when someone else adds to To buy while the app is closed. */
    val notifyChanges: Boolean = true,
    /** The list screen's one-time offer to allow notifications was answered (either way). */
    val notifyOfferDone: Boolean = false,
)

/**
 * [PhoneSettings] in private SharedPreferences, like [ShopSectionStore]: they belong to this phone only and are never
 * synced or written to the sheet. A missing value, an unknown one (a language this version doesn't know) or one of
 * the wrong type reads as the default, so a value a newer version wrote never breaks this one.
 */
class PhoneSettingsStore internal constructor(private val prefs: SharedPreferences) {

    constructor(context: Context) :
        this(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    private val state = MutableStateFlow(load())

    val settings: StateFlow<PhoneSettings> = state.asStateFlow()

    fun current(): PhoneSettings = state.value

    fun setLanguage(language: AppLanguage) =
        update({ putString(KEY_LANGUAGE, language.id) }) { it.copy(language = language) }

    fun setSimpleMode(on: Boolean) = update({ putBoolean(KEY_SIMPLE_MODE, on) }) { it.copy(simpleMode = on) }

    fun setNotifyChanges(on: Boolean) = update({ putBoolean(KEY_NOTIFY_CHANGES, on) }) { it.copy(notifyChanges = on) }

    fun setNotifyOfferDone(done: Boolean) =
        update({ putBoolean(KEY_NOTIFY_OFFER_DONE, done) }) { it.copy(notifyOfferDone = done) }

    /** Writes one value and then updates [settings], under one lock so concurrent setters can't lose a change. */
    private fun update(write: SharedPreferences.Editor.() -> Unit, change: (PhoneSettings) -> PhoneSettings) {
        synchronized(this) {
            prefs.edit(action = write)
            state.value = change(state.value)
        }
    }

    private fun load(): PhoneSettings {
        val stored = prefs.all
        val defaults = PhoneSettings()
        fun flag(key: String, default: Boolean) = stored[key] as? Boolean ?: default
        return PhoneSettings(
            language = AppLanguage.fromId(stored[KEY_LANGUAGE] as? String) ?: defaults.language,
            simpleMode = flag(KEY_SIMPLE_MODE, defaults.simpleMode),
            notifyChanges = flag(KEY_NOTIFY_CHANGES, defaults.notifyChanges),
            notifyOfferDone = flag(KEY_NOTIFY_OFFER_DONE, defaults.notifyOfferDone),
        )
    }

    private companion object {
        const val PREFS_NAME = "phone_settings"
        const val KEY_LANGUAGE = "language"
        const val KEY_SIMPLE_MODE = "simple_mode"
        const val KEY_NOTIFY_CHANGES = "notify_changes"
        const val KEY_NOTIFY_OFFER_DONE = "notify_offer_done"
    }
}
