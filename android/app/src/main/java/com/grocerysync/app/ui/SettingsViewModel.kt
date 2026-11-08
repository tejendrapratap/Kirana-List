package com.grocerysync.app.ui

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grocerysync.app.data.InputValidation
import com.grocerysync.app.data.PhoneSettings
import com.grocerysync.app.data.PhoneSettingsStore
import com.grocerysync.app.data.SettingsStore
import com.grocerysync.app.data.SetupCode
import com.grocerysync.app.data.SyncSettings
import com.grocerysync.app.data.UrlCheck
import com.grocerysync.app.sync.LiveSync
import com.grocerysync.app.sync.PingResult
import com.grocerysync.app.sync.SheetsClient
import com.grocerysync.app.text.AppLanguage
import com.grocerysync.app.text.Texts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The result of Test connection. It lives in the view model, which outlives a change of language, so it keeps what
 * happened and the sheet's own words, never a finished sentence: the screen says it in the language it shows.
 */
sealed interface ConnectionTest {
    data object Idle : ConnectionTest
    data object Running : ConnectionTest
    /** [scriptOutdated]: the sheet runs a script older than protocol 3 (no units); it needs updating. */
    data class Success(val spreadsheetName: String, val scriptOutdated: Boolean = false) : ConnectionTest

    /** [message]: for [Reason.NO_ANSWER] only, the sync layer's English message (the token in it redacted). */
    data class Failure(val reason: Reason, val message: String = "") : ConnectionTest {
        enum class Reason {
            /** Not tried: the Web app URL field holds no usable URL. The screen says why, as the field does. */
            INVALID_URL,

            /** Not tried: there is no secret token to send. */
            NO_TOKEN,

            /** The web app couldn't be reached, or answered with a problem ([message] says which). */
            NO_ANSWER,
        }
    }
}

sealed interface SettingsEvent {
    data object Saved : SettingsEvent

    /** A snackbar's [text], in the language shown when it was sent (a one-shot event, see Texts). */
    data class Message(val text: String) : SettingsEvent

    /**
     * A setup message was just read from the clipboard. It contains the secret token, so the screen
     * clears the clipboard: otherwise the keyboard keeps offering it as a suggestion in other fields.
     */
    data object ClearClipboard : SettingsEvent
}

/**
 * Typed (unsaved) field values live in Compose state, so text fields update synchronously, and are
 * mirrored into [SavedStateHandle] so they survive process death.
 *
 * The secret token can be typed or pasted but is never shown (see [TokenEntry]): the saved token stays in
 * [settingsStore] and is read only to Save and to Test connection, [tokenInput] is just what is typed into
 * the field now (shown masked), and the token of a pasted setup code is kept aside, hidden. No property
 * returns the saved token or a setup code's token, and the saved token never goes into [SavedStateHandle];
 * a typed token and a setup code's token do, so they survive process death until Save or leaving.
 *
 * A hidden token is only used with the URL it came with ([TokenEntry.sameUrl]): once the Web app URL field
 * holds another URL, Save and Test connection need a token typed or pasted for it. Otherwise anyone holding
 * the phone could point the URL at a server of their own and have it echo the hidden token back.
 *
 * This phone's own settings ([phoneSettings], Settings > This phone) are not part of Save: each one is saved as soon
 * as it is changed. A new Language recreates the activity; this view model and everything typed here stay.
 */
class SettingsViewModel(
    private val settingsStore: SettingsStore,
    private val sheetsClient: SheetsClient,
    private val liveSync: LiveSync,
    private val phoneSettingsStore: PhoneSettingsStore,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    var deviceName by mutableStateOf(savedStateHandle.get<String>(KEY_NAME) ?: settingsStore.current().deviceName)
        private set
    var webAppUrl by mutableStateOf(savedStateHandle.get<String>(KEY_URL) ?: settingsStore.current().webAppUrl)
        private set

    /** The Secret token field's text: only a token typed or pasted there now, never the saved one. */
    var tokenInput by mutableStateOf(savedStateHandle.get<String>(KEY_TOKEN).orEmpty())
        private set

    /** The token of a pasted setup code, used by Save until something is typed; never shown. */
    private var setupCodeToken by mutableStateOf(savedStateHandle.get<String>(KEY_SETUP_CODE_TOKEN))

    /** The Web app URL that came with [setupCodeToken]: the only URL that token is sent to. */
    private var setupCodeUrl by mutableStateOf(savedStateHandle.get<String>(KEY_SETUP_CODE_URL))

    /** Whether a token is saved. The token itself stays in [settingsStore]. */
    private var hasSavedToken by mutableStateOf(settingsStore.current().token.isNotBlank())

    /** The saved Web app URL: the only URL the saved token is sent to. */
    private var savedUrl by mutableStateOf(settingsStore.current().webAppUrl)

    /**
     * No name is saved for this phone (the sheet gets its model's name instead): the notifications switch asks for
     * one. The saved name, not the field: a name typed but not saved doesn't count yet.
     */
    var noNameSaved by mutableStateOf(settingsStore.current().deviceName.isBlank())
        private set

    /** The setup code's token while the URL field still holds the code's URL. */
    private val usableSetupCodeToken: String?
        get() = setupCodeToken?.takeIf { TokenEntry.sameUrl(setupCodeUrl, webAppUrl) }

    /** True while a token is saved and the URL field still holds the saved URL. */
    private val savedTokenUsable: Boolean
        get() = hasSavedToken && TokenEntry.sameUrl(savedUrl, webAppUrl)

    var connectionTest by mutableStateOf<ConnectionTest>(ConnectionTest.Idle)
        private set
    var isSaving by mutableStateOf(false)
        private set

    val urlCheck: UrlCheck by derivedStateOf { InputValidation.validateWebAppUrl(webAppUrl) }

    /** Which token Save and Test connection use. */
    val tokenSource: TokenSource by derivedStateOf { TokenEntry.source(tokenInput, usableSetupCodeToken, savedTokenUsable) }

    /**
     * The token field's supporting text (which token will be used; never the token). Worked out from Texts.current
     * too, so it follows a change of language although this view model outlives the screen.
     */
    val tokenHelp: String by derivedStateOf {
        val setupCode = usableSetupCodeToken
        val savedUsable = savedTokenUsable
        val hiddenElsewhere = (hasSavedToken && !savedUsable) || (!setupCodeToken.isNullOrBlank() && setupCode == null)
        TokenEntry.supportingText(tokenInput, setupCode, savedUsable, hiddenElsewhere)
    }

    val canSave: Boolean by derivedStateOf {
        urlCheck !is UrlCheck.Invalid && tokenSource != TokenSource.NONE && !isSaving
    }

    /** This phone's own settings, as saved. */
    val phoneSettings: StateFlow<PhoneSettings> = phoneSettingsStore.settings

    private var testJob: Job? = null

    private val _events = Channel<SettingsEvent>(Channel.BUFFERED)
    val events: Flow<SettingsEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            settingsStore.settings.collect {
                hasSavedToken = it.token.isNotBlank()
                savedUrl = it.webAppUrl
                noNameSaved = it.deviceName.isBlank()
            }
        }
    }

    fun onDeviceNameChange(value: String) {
        deviceName = value.replace('\n', ' ').take(MAX_DEVICE_NAME_LENGTH)
        savedStateHandle[KEY_NAME] = deviceName
    }

    fun onWebAppUrlChange(value: String) {
        webAppUrl = value
        savedStateHandle[KEY_URL] = value
        clearTestResult()
    }

    /**
     * A token typed or pasted into the field; it replaces the hidden one on Save. Empty keeps the hidden one.
     * A whole setup message pasted here (instead of with Paste setup code) is read as one: saved as the token,
     * it could never work, and the field doesn't show what it holds.
     */
    fun onTokenChange(value: String) {
        if (value.contains(SetupCode.PREFIX) && SetupCode.decode(value) != null) {
            pasteSetupCode(value)
            return
        }
        tokenInput = value
        savedStateHandle[KEY_TOKEN] = value
        clearTestResult()
    }

    /** Pings the web app with the typed (not yet saved) URL and the token [tokenSource] names. */
    fun testConnection() {
        val check = urlCheck
        val secret = chosenToken()
        when {
            check is UrlCheck.Invalid -> {
                connectionTest = ConnectionTest.Failure(ConnectionTest.Failure.Reason.INVALID_URL)
                return
            }
            secret.isEmpty() -> {
                connectionTest = ConnectionTest.Failure(ConnectionTest.Failure.Reason.NO_TOKEN)
                return
            }
        }
        val url = webAppUrl.trim()
        testJob?.cancel()
        connectionTest = ConnectionTest.Running
        testJob = viewModelScope.launch {
            connectionTest = when (val result = sheetsClient.ping(url, secret)) {
                is PingResult.Ok -> ConnectionTest.Success(result.spreadsheetName, result.scriptOutdated)
                is PingResult.Failure -> ConnectionTest.Failure(
                    ConnectionTest.Failure.Reason.NO_ANSWER,
                    TokenEntry.redact(result.message, secret),
                )
            }
        }
    }

    fun save() {
        if (!canSave) return
        val settings = SyncSettings(webAppUrl = webAppUrl, token = chosenToken(), deviceName = deviceName)
        isSaving = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { settingsStore.save(settings) }
                liveSync.syncNow()
                _events.send(SettingsEvent.Saved)
            } finally {
                isSaving = false
            }
        }
    }

    /**
     * Saves the Language choice at once. The screen then shows the new language: it sets Texts.current and recreates
     * the activity, which needs the activity's own configuration (for [AppLanguage.PHONE]).
     */
    fun setLanguage(language: AppLanguage) {
        phoneSettingsStore.setLanguage(language)
    }

    /** Simple mode on or off, saved at once; the list screen follows it. */
    fun setSimpleMode(on: Boolean) {
        phoneSettingsStore.setSimpleMode(on)
    }

    /**
     * Change notifications on or off, saved at once (GroceryApp plans or cancels the background check). Either way
     * this answers the list screen's one-time offer too: someone who chose here isn't asked there again. Asking
     * Android for the permission is the screen's part (it needs the activity).
     */
    fun setNotifyChanges(on: Boolean) {
        phoneSettingsStore.setNotifyChanges(on)
        phoneSettingsStore.setNotifyOfferDone(true)
    }

    /**
     * [clipboardText] is everything on the clipboard; the setup code may sit inside a longer chat message.
     * Fills the URL; the token is kept aside, hidden (the field stays empty), and Save uses it.
     */
    fun pasteSetupCode(clipboardText: String?) {
        val decoded = clipboardText?.let(SetupCode::decode)
        if (decoded == null) {
            _events.trySend(SettingsEvent.Message(Texts.current.settings.noSetupCode))
            return
        }
        val (url, secret) = decoded
        onWebAppUrlChange(url)
        onTokenChange("")
        setupCodeToken = secret
        setupCodeUrl = url
        savedStateHandle[KEY_SETUP_CODE_TOKEN] = secret
        savedStateHandle[KEY_SETUP_CODE_URL] = url
        _events.trySend(SettingsEvent.Message(Texts.current.settings.setupCodePasted))
        _events.trySend(SettingsEvent.ClearClipboard)
    }

    /**
     * Drops the typed token and a setup code's token. Settings calls it just before leaving, so the
     * token is out of the field (and of anything an autofill service could still read) before the screen
     * fades out.
     */
    fun forgetTokenEntry() {
        tokenInput = ""
        setupCodeToken = null
        setupCodeUrl = null
        savedStateHandle.remove<String>(KEY_TOKEN)
        savedStateHandle.remove<String>(KEY_SETUP_CODE_TOKEN)
        savedStateHandle.remove<String>(KEY_SETUP_CODE_URL)
    }

    /** Drops unsaved edits; called when the user leaves the screen. */
    fun resetToSaved() {
        val saved = settingsStore.current()
        deviceName = saved.deviceName
        webAppUrl = saved.webAppUrl
        hasSavedToken = saved.token.isNotBlank()
        savedUrl = saved.webAppUrl
        forgetTokenEntry()
        savedStateHandle.remove<String>(KEY_NAME)
        savedStateHandle.remove<String>(KEY_URL)
        clearTestResult()
    }

    /**
     * The token to send (see [TokenEntry.choose]); only for Save and Test connection, never for display. A hidden
     * token only while the URL field holds the URL it came with.
     */
    private fun chosenToken(): String {
        val saved = settingsStore.current()
        val savedToken = if (TokenEntry.sameUrl(saved.webAppUrl, webAppUrl)) saved.token else ""
        return TokenEntry.choose(tokenInput, usableSetupCodeToken, savedToken)
    }

    private fun clearTestResult() {
        testJob?.cancel()
        testJob = null
        connectionTest = ConnectionTest.Idle
    }

    private companion object {
        const val KEY_NAME = "settings_device_name"
        const val KEY_URL = "settings_web_app_url"
        const val KEY_TOKEN = "settings_token"
        const val KEY_SETUP_CODE_TOKEN = "settings_setup_code_token"
        const val KEY_SETUP_CODE_URL = "settings_setup_code_url"
        const val MAX_DEVICE_NAME_LENGTH = 40
    }
}
