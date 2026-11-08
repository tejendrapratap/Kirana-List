package com.grocerysync.app.ui

import androidx.lifecycle.SavedStateHandle
import com.grocerysync.app.data.FakeListStore
import com.grocerysync.app.data.FakeSharedPreferences
import com.grocerysync.app.data.GroceryRepository
import com.grocerysync.app.data.PhoneSettingsStore
import com.grocerysync.app.data.ServerClock
import com.grocerysync.app.data.SettingsStore
import com.grocerysync.app.data.ShopSectionStore
import com.grocerysync.app.data.SyncSettings
import com.grocerysync.app.sync.FakeSyncStore
import com.grocerysync.app.sync.LiveSync
import com.grocerysync.app.sync.SheetsClient
import com.grocerysync.app.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The list screen's Simple mode comes from this phone's settings (PhoneSettingsStore) and follows them while the view
 * model lives: turning it on or off in Settings shows on the list screen when the person comes back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ListViewModelSimpleModeTest {

    private val dispatcher = StandardTestDispatcher()
    private val liveSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = FakeSharedPreferences()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        liveSyncScope.cancel()
    }

    private fun viewModel(phoneSettings: PhoneSettingsStore): ListViewModel {
        val settings = SettingsStore(FakeSharedPreferences()).apply { save(SyncSettings("", "", "Shop")) }
        val repository = GroceryRepository(FakeListStore(), settings, ServerClock(localClock = { 50_000L }), onLocalChange = {}, io = dispatcher)
        val syncStore = FakeSyncStore()
        val liveSync = LiveSync(
            engine = SyncEngine(syncStore, SheetsClient(), settings::current, { "Shop" }, ServerClock()),
            settings = settings.settings,
            store = syncStore,
            scope = liveSyncScope,
        )
        return ListViewModel(
            repository = repository,
            liveSync = liveSync,
            settingsStore = settings,
            shopSectionStore = ShopSectionStore(FakeSharedPreferences()),
            phoneSettingsStore = phoneSettings,
            savedStateHandle = SavedStateHandle(),
        )
    }

    @Test
    fun offUntilItIsTurnedOnThenFollowsTheSetting() = runTest(dispatcher) {
        val phoneSettings = PhoneSettingsStore(prefs)
        val viewModel = viewModel(phoneSettings)
        assertFalse(viewModel.simpleMode.value)

        phoneSettings.setSimpleMode(true)
        advanceUntilIdle()
        assertTrue(viewModel.simpleMode.value)

        phoneSettings.setSimpleMode(false)
        advanceUntilIdle()
        assertFalse(viewModel.simpleMode.value)
    }

    @Test
    fun aPhoneInSimpleModeShowsItFromTheFirstFrame() = runTest(dispatcher) {
        PhoneSettingsStore(prefs).setSimpleMode(true)
        // A new start of the app: the saved choice, before anything has run.
        val viewModel = viewModel(PhoneSettingsStore(prefs))
        assertTrue(viewModel.simpleMode.value)
    }
}
