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

    override fun onCreate(savedInstanceState: Bundle?) {
        // Every screen's app bar is deep green in both themes (appBarColors), so the status bar icons over it are
        // always light. The navigation bar shows the list's surface: dark icons in light theme, light in dark.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        // Only on a fresh start: after rotation or process death the shared text was taken already (the list screen
        // keeps it), and Recents starts the activity again with the intent that once shared it.
        if (savedInstanceState == null) receiveSharedList(intent)
        setContent {
            GrocerySyncTheme {
                GrocerySyncRoot(sharedLists = sharedLists)
            }
        }
    }

    /** launchMode singleTop: a list shared while the app is on top of its task comes here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveSharedList(intent)
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
 * Two screens, so a navigation library would be overkill: the current one survives rotation and process death.
 * Each list shared into the app ([sharedLists] goes up) shows the list screen, where its preview opens.
 */
@Composable
private fun GrocerySyncRoot(sharedLists: Int) {
    var screen by rememberSaveable { mutableStateOf(Screen.LIST) }
    // Keeps each screen's saved UI state (list filter, scroll position, ...) while the other one is shown.
    val saveableStateHolder = rememberSaveableStateHolder()

    BackHandler(enabled = screen == Screen.SETTINGS) { screen = Screen.LIST }
    LaunchedEffect(sharedLists) {
        if (sharedLists > 0) screen = Screen.LIST
    }

    Crossfade(targetState = screen, label = "screen") { current ->
        saveableStateHolder.SaveableStateProvider(current.name) {
            when (current) {
                Screen.LIST -> ListScreen(onOpenSettings = { screen = Screen.SETTINGS })
                Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.LIST })
            }
        }
    }
}
