package com.grocerysync.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import com.grocerysync.app.notify.ChangeNotifier
import com.grocerysync.app.text.Texts
import com.grocerysync.app.text.update
import com.grocerysync.app.ui.AppViewModelProvider
import com.grocerysync.app.ui.ListScreen
import com.grocerysync.app.ui.ListViewModel
import com.grocerysync.app.ui.SettingsScreen
import com.grocerysync.app.ui.theme.GrocerySyncTheme

class MainActivity : ComponentActivity() {

    /** The list screen's view model: activity-scoped, so this is the instance ListScreen gets too. */
    private val listViewModel: ListViewModel by lazy {
        ViewModelProvider(this, AppViewModelProvider.Factory)[ListViewModel::class.java]
    }

    /** Counts the lists shared into the app while it runs; each one brings the list screen to the front. */
    private var sharedLists by mutableIntStateOf(0)

    /** Counts the taps on the change notification while the app runs; each one shows To buy ([receiveShowToBuy]). */
    private var toBuyRequests by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Every screen's app bar is deep green in both themes (appBarColors), so the status bar icons over it are
        // always light. The navigation bar shows the list's surface: dark icons in light theme, light in dark.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        // Before anything is composed: every screen reads its texts from Texts.current. Choosing another language in
        // Settings recreates this activity, so this runs again then; so does a change of the phone's language.
        // A new language also renames the notifications' channel (Android shows it in the app's settings for them).
        val language = (application as GroceryApp).container.phoneSettings.current().language
        if (Texts.update(language, resources.configuration)) ChangeNotifier(this).createChannel()
        // Only on a fresh start: after rotation or process death the shared text was taken already (the list screen
        // keeps it), and Recents starts the activity again with the intent that once shared it.
        if (savedInstanceState == null) {
            receiveSharedList(intent)
            receiveShowToBuy(intent)
        }
        setContent {
            GrocerySyncTheme {
                GrocerySyncRoot(sharedLists = sharedLists, toBuyRequests = toBuyRequests)
            }
        }
    }

    /**
     * launchMode singleTop: a list shared while the app is on top of its task comes here, and so does a tap on the
     * change notification while the app runs.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveSharedList(intent)
        receiveShowToBuy(intent)
    }

    /**
     * A tap on the change notification ([ChangeNotifier]: [EXTRA_SHOW_TO_BUY]) shows To buy, what it is about, also
     * when the app was left on Settings or on All items. Only once: the extra goes, and Recents, which starts the
     * activity again with the intent that once opened it, doesn't switch screens.
     */
    private fun receiveShowToBuy(intent: Intent) {
        if (!intent.getBooleanExtra(EXTRA_SHOW_TO_BUY, false)) return
        intent.removeExtra(EXTRA_SHOW_TO_BUY)
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        toBuyRequests++
    }

    companion object {
        /** On the change notification's intent: show To buy ([receiveShowToBuy]). */
        const val EXTRA_SHOW_TO_BUY = "com.grocerysync.app.extra.SHOW_TO_BUY"
    }

    /**
     * Share > Kirana List (ACTION_SEND, text/plain): opens the "Paste a list" preview of the text
     * ([ListViewModel.openSharedText]), or says it couldn't be read (a file, an empty text). The text comes from
     * another app, so it is only parsed, and nothing changes until the user confirms. Started again from Recents
     * with the intent that once shared it, the share is not read again.
     */
    private fun receiveSharedList(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        val text = if (fromHistory) {
            null
        } else {
            sharedText(intent.action, intent.type) { intent.getCharSequenceExtra(Intent.EXTRA_TEXT) }
        }
        // Every extra of the other app's intent goes, read or not, before the view model exists: its SavedStateHandle
        // starts from (and saves) the intent's extras. A shared text may be far larger than a paste may be (the view
        // model keeps it only if it fits), and an extra of a class this app doesn't know would fail to load there.
        intent.replaceExtras(Bundle())
        if (fromHistory) return
        listViewModel.openSharedText(text)
        sharedLists++
    }
}

/**
 * The text of a share ([action] ACTION_SEND of a text [type]) whose text [readText] reads, or null for any other
 * intent or an empty text. Reading the extra of another app's intent may fail (a malformed parcel): null too.
 */
internal fun sharedText(action: String?, type: String?, readText: () -> CharSequence?): String? {
    if (action != Intent.ACTION_SEND || type?.startsWith("text/") != true) return null
    val text = try {
        readText()?.toString()
    } catch (e: RuntimeException) {
        null
    }
    return text?.takeIf { it.isNotBlank() }
}

private enum class Screen { LIST, SETTINGS }

/**
 * Two screens, so a navigation library would be overkill: the current one survives rotation, process death and a
 * new Language (Settings recreates the activity and stays open).
 * Each list shared into the app ([sharedLists] goes up) shows the list screen, where its preview opens; each tap on
 * the change notification ([toBuyRequests] goes up) shows the list screen on To buy.
 */
@Composable
private fun GrocerySyncRoot(sharedLists: Int, toBuyRequests: Int) {
    var screen by rememberSaveable { mutableStateOf(Screen.LIST) }
    // Keeps each screen's saved UI state (list filter, scroll position, ...) while the other one is shown.
    val saveableStateHolder = rememberSaveableStateHolder()

    BackHandler(enabled = screen == Screen.SETTINGS) { screen = Screen.LIST }
    LaunchedEffect(sharedLists) {
        if (sharedLists > 0) screen = Screen.LIST
    }
    LaunchedEffect(toBuyRequests) {
        if (toBuyRequests > 0) screen = Screen.LIST
    }

    Crossfade(targetState = screen, label = "screen") { current ->
        saveableStateHolder.SaveableStateProvider(current.name) {
            when (current) {
                Screen.LIST -> ListScreen(onOpenSettings = { screen = Screen.SETTINGS }, showToBuy = toBuyRequests)
                Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.LIST })
            }
        }
    }
}
