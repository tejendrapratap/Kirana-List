package com.grocerysync.app.data

import com.grocerysync.app.text.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

/** This phone's own settings: their defaults, each one kept across restarts, and values this version doesn't know. */
class PhoneSettingsStoreTest {

    private val prefs = FakeSharedPreferences()

    @Test
    fun startsWithTheDefaults() {
        assertEquals(
            PhoneSettings(
                language = AppLanguage.PHONE,
                simpleMode = false,
                notifyChanges = true,
                notifyOfferDone = false,
            ),
            PhoneSettingsStore(prefs).current(),
        )
    }

    @Test
    fun eachSettingIsKeptAndTheFlowFollows() {
        val store = PhoneSettingsStore(prefs)
        store.setLanguage(AppLanguage.HINDI)
        store.setSimpleMode(true)
        store.setNotifyChanges(false)
        store.setNotifyOfferDone(true)

        val changed = PhoneSettings(AppLanguage.HINDI, simpleMode = true, notifyChanges = false, notifyOfferDone = true)
        assertEquals(changed, store.current())
        assertEquals(changed, store.settings.value)
        assertEquals(changed, PhoneSettingsStore(prefs).current())

        store.setLanguage(AppLanguage.ENGLISH)
        store.setSimpleMode(false)
        assertEquals(changed.copy(language = AppLanguage.ENGLISH, simpleMode = false), PhoneSettingsStore(prefs).current())
        store.setLanguage(AppLanguage.PHONE)
        assertEquals(AppLanguage.PHONE, PhoneSettingsStore(prefs).current().language)
    }

    @Test
    fun aSettingChangesOnlyItself() {
        val store = PhoneSettingsStore(prefs)
        store.setNotifyOfferDone(true)
        assertEquals(PhoneSettings(notifyOfferDone = true), store.current())
        assertEquals(PhoneSettings(notifyOfferDone = true), PhoneSettingsStore(prefs).current())
    }

    @Test
    fun theLanguageIsStoredByItsId() {
        PhoneSettingsStore(prefs).setLanguage(AppLanguage.HINDI)
        assertEquals("hi", prefs.values["language"])
        PhoneSettingsStore(prefs).setLanguage(AppLanguage.PHONE)
        assertEquals("phone", prefs.values["language"])
    }

    @Test
    fun unknownOrWrongValuesReadAsTheDefaults() {
        // A language a newer version knows, and values of the wrong type (they must not throw).
        prefs.values["language"] = "mr"
        prefs.values["simple_mode"] = "yes"
        prefs.values["notify_changes"] = 0
        prefs.values["notify_offer_done"] = 1L
        assertEquals(PhoneSettings(), PhoneSettingsStore(prefs).current())

        prefs.values["language"] = 3
        assertEquals(AppLanguage.PHONE, PhoneSettingsStore(prefs).current().language)
    }
}
