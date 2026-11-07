package com.grocerysync.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsStoreTest {

    private val prefs = FakeSharedPreferences()

    @Test
    fun savesTrimmedSettingsAndReadsThemBack() {
        SettingsStore(prefs).save(SyncSettings(" https://x/exec ", "  secret-token-123 ", "  Home  "))
        val reopened = SettingsStore(prefs)
        assertEquals(SyncSettings("https://x/exec", "secret-token-123", "Home"), reopened.current())
        assertTrue(reopened.current().isConfigured)
    }

    @Test
    fun startsEmptyAndNotConfigured() {
        val store = SettingsStore(prefs)
        assertEquals(SyncSettings("", "", ""), store.current())
        assertFalse(store.current().isConfigured)
    }

    @Test
    fun settingsFlowFollowsSave() {
        val store = SettingsStore(prefs)
        store.save(SyncSettings("https://x/exec", "secret-token-123", "Home"))
        assertEquals("secret-token-123", store.settings.value.token)
    }

    @Test
    fun toStringNeverContainsTheToken() {
        val settings = SyncSettings("https://x/exec", "secret-token-123", "Home")
        val text = settings.toString()
        assertFalse(text, text.contains("secret-token-123"))
        assertTrue(text, text.contains("token=hidden"))
        assertTrue(text, text.contains("https://x/exec") && text.contains("Home"))
        assertTrue(SyncSettings("u", "", "n").toString().contains("token=none"))
    }

    @Test
    fun equalityStillComparesTheToken() {
        assertFalse(SyncSettings("u", "a", "n") == SyncSettings("u", "b", "n"))
        assertEquals(SyncSettings("u", "a", "n"), SyncSettings("u", "a", "n"))
    }
}
