package com.grocerysync.app.ui

import androidx.lifecycle.SavedStateHandle
import com.grocerysync.app.data.FakeSharedPreferences
import com.grocerysync.app.data.PhoneSettings
import com.grocerysync.app.data.PhoneSettingsStore
import com.grocerysync.app.data.ServerClock
import com.grocerysync.app.data.SettingsStore
import com.grocerysync.app.data.SetupCodeTest
import com.grocerysync.app.data.SyncSettings
import com.grocerysync.app.data.UrlCheck
import com.grocerysync.app.sync.FakeSyncStore
import com.grocerysync.app.sync.LiveSync
import com.grocerysync.app.sync.Protocol
import com.grocerysync.app.sync.SheetsClient
import com.grocerysync.app.sync.SyncEngine
import com.grocerysync.app.text.AppTexts
import com.grocerysync.app.text.EnglishSettingsTexts
import com.grocerysync.app.text.HindiSettingsTexts
import com.grocerysync.app.text.HindiSyncTexts
import com.grocerysync.app.text.withTexts
import com.grocerysync.app.ui.ConnectionTest.Failure.Reason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Modifier
import java.util.Collections

/**
 * The real SettingsViewModel with the real SettingsStore (in-memory preferences), SheetsClient and LiveSync
 * against a local mock web app: the secret token can be typed or pasted, Save and Test connection use the
 * right one, and the saved token never comes back out for display.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var prefs: FakeSharedPreferences
    private lateinit var store: SettingsStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Tokens of the ping requests the mock web app received, in order. */
    private val pingTokens: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** What the mock web app answers to ping. */
    @Volatile
    private var pingBody = """{"ok":true,"app":"grocery-sync","version":3,"spreadsheet":"Groceries"}"""

    private val url get() = server.url("/macros/s/TEST/exec").toString()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val action = request.requestUrl?.queryParameter("action")
                if (action == "ping") pingTokens += request.requestUrl?.queryParameter("token").orEmpty()
                val body = when (action) {
                    "ping" -> pingBody
                    else -> """{"ok":true,"version":3,"revision":"r1","serverTime":1,"spreadsheet":"Groceries","items":[]}"""
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            }
        }
        server.start()
        prefs = FakeSharedPreferences()
        store = SettingsStore(prefs)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
        Dispatchers.resetMain()
    }

    /** This phone's own settings of the view model [viewModel] makes. */
    private val phoneStore = PhoneSettingsStore(FakeSharedPreferences())

    private fun viewModel(handle: SavedStateHandle = SavedStateHandle()): SettingsViewModel {
        val syncStore = FakeSyncStore()
        val liveSync = LiveSync(
            engine = SyncEngine(syncStore, SheetsClient(), store::current, { "Home" }, ServerClock()),
            settings = store.settings,
            store = syncStore,
            scope = scope,
        )
        return SettingsViewModel(store, SheetsClient(), liveSync, phoneStore, handle)
    }

    private fun saveConfigured(token: String = SAVED_TOKEN) {
        store.save(SyncSettings(url, token, "Home"))
    }

    private fun SettingsViewModel.saveAndWait() = runBlocking {
        assertTrue("Save should be enabled", canSave)
        save()
        withTimeout(5_000) { events.first { it == SettingsEvent.Saved } }
    }

    private fun SettingsViewModel.testAndWait(): ConnectionTest {
        testConnection()
        val deadline = System.currentTimeMillis() + 5_000
        while (connectionTest == ConnectionTest.Running && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return connectionTest
    }

    private fun setupMessage(token: String) =
        "Join our grocery list in Grocery Sync:\n\n" + SetupCodeTest.encodeLikeVersion11(url, token)

    @Test
    fun theSavedTokenIsNeverPutIntoTheField() {
        saveConfigured()
        val vm = viewModel()
        assertEquals("", vm.tokenInput)
        assertEquals(TokenSource.SAVED, vm.tokenSource)
        assertEquals(EnglishSettingsTexts.tokenSaved, vm.tokenHelp)
        assertTrue(vm.canSave)
        assertEquals(url, vm.webAppUrl)
    }

    @Test
    fun blankFieldKeepsTheSavedTokenOnSave() {
        saveConfigured()
        val vm = viewModel()
        vm.onDeviceNameChange("Shop")
        vm.saveAndWait()
        assertEquals(SyncSettings(url, SAVED_TOKEN, "Shop"), store.current())
    }

    @Test
    fun aTypedTokenReplacesTheSavedOne() {
        saveConfigured()
        val vm = viewModel()
        vm.onTokenChange("  new-token-1234  ")
        assertEquals(TokenSource.TYPED, vm.tokenSource)
        assertEquals(EnglishSettingsTexts.tokenReplacesSaved, vm.tokenHelp)
        vm.saveAndWait()
        assertEquals("new-token-1234", store.current().token)
    }

    @Test
    fun whitespaceOnlyKeepsTheSavedToken() {
        saveConfigured()
        val vm = viewModel()
        vm.onTokenChange("   ")
        assertEquals(TokenSource.SAVED, vm.tokenSource)
        vm.saveAndWait()
        assertEquals(SAVED_TOKEN, store.current().token)
    }

    @Test
    fun saveNeedsAToken() {
        val vm = viewModel()
        vm.onWebAppUrlChange(url)
        assertEquals(TokenSource.NONE, vm.tokenSource)
        assertFalse(vm.canSave)
        vm.onTokenChange("first-token-1234")
        assertTrue(vm.canSave)
    }

    @Test
    fun pastingASetupCodeFillsTheUrlAndAHiddenToken() {
        val vm = viewModel()
        vm.pasteSetupCode(setupMessage(CODE_TOKEN))
        assertEquals(url, vm.webAppUrl)
        assertEquals("", vm.tokenInput)
        assertEquals(TokenSource.SETUP_CODE, vm.tokenSource)
        assertEquals(EnglishSettingsTexts.tokenFromSetupCode, vm.tokenHelp)
        assertEquals(
            SettingsEvent.Message("Setup code pasted - tap Save"),
            runBlocking { withTimeout(5_000) { vm.events.first() } },
        )
        vm.saveAndWait()
        assertEquals(CODE_TOKEN, store.current().token)
        assertEquals(url, store.current().webAppUrl)
    }

    @Test
    fun pastingReplacesATypedTokenAndTypingWinsOverThePastedOne() {
        saveConfigured()
        val vm = viewModel()
        vm.onTokenChange("typed-token-1234")
        vm.pasteSetupCode(setupMessage(CODE_TOKEN))
        assertEquals("", vm.tokenInput)
        assertEquals(TokenSource.SETUP_CODE, vm.tokenSource)

        vm.onTokenChange("typed-again-5678")
        assertEquals(TokenSource.TYPED, vm.tokenSource)
        assertEquals(EnglishSettingsTexts.tokenReplacesSetupCode, vm.tokenHelp)
        vm.onTokenChange("")
        assertEquals(TokenSource.SETUP_CODE, vm.tokenSource)
        vm.saveAndWait()
        assertEquals(CODE_TOKEN, store.current().token)
    }

    @Test
    fun aPastedSetupCodeIsClearedFromTheClipboard() {
        val vm = viewModel()
        vm.pasteSetupCode(setupMessage(CODE_TOKEN))
        val events = runBlocking { withTimeout(5_000) { vm.events.take(2).toList() } }
        assertEquals(listOf(SettingsEvent.Message("Setup code pasted - tap Save"), SettingsEvent.ClearClipboard), events)
    }

    @Test
    fun aSetupMessagePastedIntoTheTokenFieldIsClearedFromTheClipboardToo() {
        val vm = viewModel()
        vm.onTokenChange(setupMessage(CODE_TOKEN))
        val events = runBlocking { withTimeout(5_000) { vm.events.take(2).toList() } }
        assertEquals(SettingsEvent.ClearClipboard, events.last())
    }

    @Test
    fun noSetupCodeOnTheClipboardLeavesTheClipboardAlone() {
        val vm = viewModel()
        vm.pasteSetupCode("Just a normal message")
        vm.pasteSetupCode(null)
        val events = runBlocking { withTimeout(5_000) { vm.events.take(2).toList() } }
        assertFalse(SettingsEvent.ClearClipboard in events)
    }

    @Test
    fun noSetupCodeOnTheClipboardChangesNothing() {
        saveConfigured()
        val vm = viewModel()
        vm.pasteSetupCode("Just a normal message")
        vm.pasteSetupCode(null)
        assertEquals(TokenSource.SAVED, vm.tokenSource)
        assertEquals(url, vm.webAppUrl)
        assertEquals(
            SettingsEvent.Message("No setup code found on the clipboard"),
            runBlocking { withTimeout(5_000) { vm.events.first() } },
        )
    }

    @Test
    fun testConnectionUsesTheSavedTokenWhenTheFieldIsEmpty() {
        saveConfigured()
        val vm = viewModel()
        assertEquals(ConnectionTest.Success("Groceries"), vm.testAndWait())
        assertEquals(listOf(SAVED_TOKEN), pingTokens.toList())
    }

    @Test
    fun testConnectionUsesTheTypedOrPastedToken() {
        saveConfigured()
        val vm = viewModel()
        vm.onTokenChange("typed-token-1234")
        assertEquals(ConnectionTest.Success("Groceries"), vm.testAndWait())
        vm.pasteSetupCode(setupMessage(CODE_TOKEN))
        assertEquals(ConnectionTest.Success("Groceries"), vm.testAndWait())
        assertEquals(listOf("typed-token-1234", CODE_TOKEN), pingTokens.toList())
    }

    @Test
    fun testConnectionWithoutATokenAsksForOne() {
        val vm = viewModel()
        vm.onWebAppUrlChange(url)
        assertEquals(ConnectionTest.Failure(Reason.NO_TOKEN), vm.testAndWait())
        assertTrue(pingTokens.isEmpty())
    }

    @Test
    fun testConnectionNeverShowsTheToken() {
        saveConfigured()
        // A server that puts the request into its error (the script never does; a wrong URL might).
        pingBody = """{"ok":false,"error":"rejected request ?action=ping&token=$SAVED_TOKEN"}"""
        val vm = viewModel()
        val result = vm.testAndWait()
        assertTrue(result.toString(), result is ConnectionTest.Failure)
        assertEquals(Reason.NO_ANSWER, (result as ConnectionTest.Failure).reason)
        val message = result.message
        assertFalse(message, message.contains(SAVED_TOKEN))
        assertTrue(message, message.contains("token=" + TokenEntry.HIDDEN))
    }

    @Test
    fun leavingDropsTheTypedAndPastedTokens() {
        saveConfigured()
        val handle = SavedStateHandle()
        val vm = viewModel(handle)
        vm.pasteSetupCode(setupMessage(CODE_TOKEN))
        vm.onTokenChange("typed-token-1234")
        vm.forgetTokenEntry()
        assertEquals("", vm.tokenInput)
        assertEquals(TokenSource.SAVED, vm.tokenSource)
        assertHandleHasNoToken(handle, "typed-token-1234", CODE_TOKEN, SAVED_TOKEN)

        vm.pasteSetupCode(setupMessage(CODE_TOKEN))
        vm.onWebAppUrlChange("https://example.com/other")
        vm.resetToSaved()
        assertEquals(url, vm.webAppUrl)
        assertEquals(TokenSource.SAVED, vm.tokenSource)
        assertHandleHasNoToken(handle, CODE_TOKEN, SAVED_TOKEN)
    }

    @Test
    fun processDeathKeepsWhatWasTypedOrPastedButNeverHoldsTheSavedToken() {
        saveConfigured()
        val handle = SavedStateHandle()
        viewModel(handle).pasteSetupCode(setupMessage(CODE_TOKEN))
        assertHandleHasNoToken(handle, SAVED_TOKEN)

        val restored = viewModel(handle)
        assertEquals("", restored.tokenInput)
        assertEquals(TokenSource.SETUP_CODE, restored.tokenSource)

        restored.onTokenChange("typed-token-1234")
        val restoredAgain = viewModel(handle)
        assertEquals("typed-token-1234", restoredAgain.tokenInput)
        assertEquals(TokenSource.TYPED, restoredAgain.tokenSource)
        assertHandleHasNoToken(handle, SAVED_TOKEN)
    }

    @Test
    fun noPublicPropertyReturnsTheSavedOrPastedToken() {
        saveConfigured()
        val vm = viewModel()
        vm.pasteSetupCode(setupMessage(CODE_TOKEN))
        vm.testAndWait()
        val getters = SettingsViewModel::class.java.methods.filter {
            it.parameterCount == 0 && !Modifier.isStatic(it.modifiers) &&
                (it.name.startsWith("get") || it.name.startsWith("is")) && it.declaringClass == SettingsViewModel::class.java
        }
        assertTrue(getters.map { it.name }.toString(), getters.any { it.name == "getTokenInput" })
        for (getter in getters) {
            val value = getter.invoke(vm)?.toString().orEmpty()
            assertFalse("${getter.name} returned $value", value.contains(SAVED_TOKEN) || value.contains(CODE_TOKEN))
        }
    }

    @Test
    fun theSavedTokenIsNeverSentToAnotherUrl() {
        saveConfigured()
        // Someone holding the phone points the URL at a server of their own that echoes the token back.
        val other = MockWebServer()
        val otherTokens: MutableList<String> = Collections.synchronizedList(mutableListOf())
        other.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val token = request.requestUrl?.queryParameter("token").orEmpty()
                otherTokens += token
                return MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"ok":true,"app":"grocery-sync","version":3,"spreadsheet":"$token"}""")
            }
        }
        other.start()
        try {
            val vm = viewModel()
            vm.onWebAppUrlChange(other.url("/macros/s/OTHER/exec").toString())
            assertEquals(TokenSource.NONE, vm.tokenSource)
            assertEquals(EnglishSettingsTexts.tokenOtherUrl, vm.tokenHelp)
            assertFalse("Save would keep the hidden token with the new URL", vm.canSave)
            assertEquals(ConnectionTest.Failure(Reason.NO_TOKEN), vm.testAndWait())
            vm.save()
            assertTrue(otherTokens.toString(), otherTokens.isEmpty())
            assertEquals(url, store.current().webAppUrl)

            // Back to the saved URL: the saved token is used again.
            vm.onWebAppUrlChange(" $url ")
            assertEquals(TokenSource.SAVED, vm.tokenSource)
            assertTrue(vm.canSave)

            // A token typed for the new URL is the one sent there (and saved with it).
            vm.onWebAppUrlChange(other.url("/macros/s/OTHER/exec").toString())
            vm.onTokenChange("typed-token-1234")
            assertEquals(EnglishSettingsTexts.tokenNew, vm.tokenHelp)
            // Even then its echo is not shown (the answer is redacted before it is read).
            assertEquals(ConnectionTest.Success(TokenEntry.HIDDEN), vm.testAndWait())
            assertEquals(listOf("typed-token-1234"), otherTokens.toList())
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun aSetupCodesTokenIsOnlyUsedWithItsOwnUrl() {
        val vm = viewModel()
        vm.pasteSetupCode(setupMessage(CODE_TOKEN))
        assertEquals(TokenSource.SETUP_CODE, vm.tokenSource)
        vm.onWebAppUrlChange("https://script.google.com/macros/s/ELSEWHERE/exec")
        assertEquals(TokenSource.NONE, vm.tokenSource)
        assertEquals(EnglishSettingsTexts.tokenOtherUrl, vm.tokenHelp)
        assertFalse(vm.canSave)
        assertEquals(ConnectionTest.Failure(Reason.NO_TOKEN), vm.testAndWait())
        vm.onWebAppUrlChange(url)
        assertEquals(TokenSource.SETUP_CODE, vm.tokenSource)
        assertEquals(ConnectionTest.Success("Groceries"), vm.testAndWait())
        assertEquals(listOf(CODE_TOKEN), pingTokens.toList())
    }

    @Test
    fun aSetupMessagePastedIntoTheTokenFieldIsReadAsASetupCode() {
        saveConfigured()
        val vm = viewModel()
        vm.onTokenChange(setupMessage(CODE_TOKEN))
        assertEquals("", vm.tokenInput)
        assertEquals(TokenSource.SETUP_CODE, vm.tokenSource)
        assertEquals(url, vm.webAppUrl)
        assertEquals(
            SettingsEvent.Message("Setup code pasted - tap Save"),
            runBlocking { withTimeout(5_000) { vm.events.first() } },
        )
        vm.saveAndWait()
        assertEquals(CODE_TOKEN, store.current().token)
        // An ordinary token is still just a token.
        vm.onTokenChange("grocerysync-setup-like-but-not-a-code")
        assertEquals(TokenSource.TYPED, vm.tokenSource)
    }

    @Test
    fun savingUpdatesWhetherATokenIsSaved() {
        val vm = viewModel()
        vm.onWebAppUrlChange(url)
        vm.onTokenChange("first-token-1234")
        vm.saveAndWait()
        vm.resetToSaved()
        assertEquals("", vm.tokenInput)
        assertEquals(TokenSource.SAVED, vm.tokenSource)
        assertNotEquals(TokenSource.NONE, vm.tokenSource)
    }

    @Test
    fun aTestResultIsSaidInTheLanguageShownWhenItIsShown() {
        // The view model outlives a change of language (the activity is recreated), so it keeps what happened and
        // the screen says it in the language shown then.
        val vm = viewModel()
        vm.onWebAppUrlChange(url)
        val noToken = vm.testAndWait() as ConnectionTest.Failure
        assertEquals("Enter the secret token first.", connectionFailureText(noToken, vm.urlCheck, AppTexts.English))
        assertEquals(HindiSettingsTexts.enterTokenFirst, connectionFailureText(noToken, vm.urlCheck, AppTexts.Hindi))

        // The web app's answer stays the sync layer's English message; the screen translates it.
        pingBody = """{"ok":false,"error":"unauthorized"}"""
        vm.onTokenChange("wrong-token-1234")
        val refused = vm.testAndWait() as ConnectionTest.Failure
        assertEquals(ConnectionTest.Failure(Reason.NO_ANSWER, Protocol.MSG_UNAUTHORIZED), refused)
        assertEquals(Protocol.MSG_UNAUTHORIZED, connectionFailureText(refused, vm.urlCheck, AppTexts.English))
        assertEquals(
            HindiSyncTexts.message(Protocol.MSG_UNAUTHORIZED),
            connectionFailureText(refused, vm.urlCheck, AppTexts.Hindi),
        )
        assertNotEquals(Protocol.MSG_UNAUTHORIZED, HindiSyncTexts.message(Protocol.MSG_UNAUTHORIZED))
    }

    @Test
    fun anInvalidUrlIsDescribedByTheFieldsOwnCheck() {
        val vm = viewModel()
        vm.onWebAppUrlChange("ftp://example.com/exec")
        vm.onTokenChange("typed-token-1234")
        val result = vm.testAndWait()
        assertEquals(ConnectionTest.Failure(Reason.INVALID_URL), result)
        val check = vm.urlCheck
        assertTrue(check.toString(), check is UrlCheck.Invalid)
        val text = connectionFailureText(result as ConnectionTest.Failure, check, AppTexts.English)
        assertEquals((check as UrlCheck.Invalid).message, text)
        assertTrue(pingTokens.isEmpty())
    }

    @Test
    fun theTokenHelpFollowsALanguageChange() {
        saveConfigured()
        val vm = viewModel()
        assertEquals(EnglishSettingsTexts.tokenSaved, vm.tokenHelp)
        // Nothing else changed: the help is worked out again because the texts did.
        assertEquals(HindiSettingsTexts.tokenSaved, withTexts(AppTexts.Hindi) { vm.tokenHelp })
        assertEquals(EnglishSettingsTexts.tokenSaved, vm.tokenHelp)
    }

    @Test
    fun snackbarsAreInTheLanguageShownWhenSent() {
        val vm = viewModel()
        withTexts(AppTexts.Hindi) {
            vm.pasteSetupCode(null)
            vm.pasteSetupCode(setupMessage(CODE_TOKEN))
        }
        val events = runBlocking { withTimeout(5_000) { vm.events.take(3).toList() } }
        assertEquals(
            listOf(
                SettingsEvent.Message(HindiSettingsTexts.noSetupCode),
                SettingsEvent.Message(HindiSettingsTexts.setupCodePasted),
                SettingsEvent.ClearClipboard,
            ),
            events,
        )
    }

    @Test
    fun simpleModeIsSavedAtOnce() {
        val vm = viewModel()
        assertFalse(vm.phoneSettings.value.simpleMode)
        vm.setSimpleMode(true)
        assertTrue(phoneStore.current().simpleMode)
        assertTrue(vm.phoneSettings.value.simpleMode)
        vm.setSimpleMode(false)
        assertFalse(phoneStore.current().simpleMode)
    }

    @Test
    fun theNotificationsSwitchIsSavedAtOnceAndAnswersTheListsOffer() {
        val vm = viewModel()
        assertTrue(vm.phoneSettings.value.notifyChanges)
        assertFalse(vm.phoneSettings.value.notifyOfferDone)
        vm.setNotifyChanges(false)
        assertEquals(PhoneSettings(notifyChanges = false, notifyOfferDone = true), phoneStore.current())
        vm.setNotifyChanges(true)
        assertEquals(PhoneSettings(notifyChanges = true, notifyOfferDone = true), phoneStore.current())
        // Not part of Save: nothing typed is touched.
        assertEquals("", vm.webAppUrl)
    }

    @Test
    fun askingAndroidForTheNotificationPermissionIsSaved() {
        val vm = viewModel()
        assertFalse(vm.phoneSettings.value.notifyPermissionAsked)
        vm.onNotifyPermissionAsked()
        assertTrue(phoneStore.current().notifyPermissionAsked)
        assertTrue(vm.phoneSettings.value.notifyPermissionAsked)
    }

    @Test
    fun theNotificationsSwitchAsksForANameUntilOneIsSaved() {
        val vm = viewModel()
        assertTrue(vm.noNameSaved)
        vm.onWebAppUrlChange(url)
        vm.onTokenChange("first-token-1234")
        vm.onDeviceNameChange("Mummy")
        // Typed but not saved: the sheet would still get the phone's model.
        assertTrue(vm.noNameSaved)
        vm.saveAndWait()
        assertFalse(vm.noNameSaved)
    }

    private fun assertHandleHasNoToken(handle: SavedStateHandle, vararg tokens: String) {
        for (key in handle.keys()) {
            val value = handle.get<Any?>(key).toString()
            for (token in tokens) assertFalse("$key holds a token", value.contains(token))
        }
    }

    private companion object {
        const val SAVED_TOKEN = "saved-secret-0123456789"
        const val CODE_TOKEN = "code-secret-9876543210"
    }
}
