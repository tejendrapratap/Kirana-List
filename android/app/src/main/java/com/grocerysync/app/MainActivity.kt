package com.grocerysync.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import com.grocerysync.app.ui.ListScreen
import com.grocerysync.app.ui.SettingsScreen
import com.grocerysync.app.ui.theme.GrocerySyncTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            GrocerySyncTheme {
                GrocerySyncRoot()
            }
        }
    }
}

private enum class Screen { LIST, SETTINGS }

/** Two screens, so a navigation library would be overkill: the current one survives rotation and process death. */
@Composable
private fun GrocerySyncRoot() {
    var screen by rememberSaveable { mutableStateOf(Screen.LIST) }
    // Keeps each screen's saved UI state (list filter, scroll position, ...) while the other one is shown.
    val saveableStateHolder = rememberSaveableStateHolder()

    BackHandler(enabled = screen == Screen.SETTINGS) { screen = Screen.LIST }

    Crossfade(targetState = screen, label = "screen") { current ->
        saveableStateHolder.SaveableStateProvider(current.name) {
            when (current) {
                Screen.LIST -> ListScreen(onOpenSettings = { screen = Screen.SETTINGS })
                Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.LIST })
            }
        }
    }
}
