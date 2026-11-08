package com.grocerysync.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/**
 * A full-screen dialog drawn under the system bars (DialogProperties(decorFitsSystemWindows = false): Add common
 * items, Paste a list) has its own window, which sets the colour of the status bar's clock and icons and of the
 * navigation bar's buttons; it doesn't follow the activity's (enableEdgeToEdge). So the dialog says what is under
 * each bar: [statusBarBackground] (its green app bar: light icons in both themes) and [navigationBarBackground] (its
 * bottom bar's surface: dark buttons in the light theme, light ones in the dark theme), and each gets the icons that
 * read on it. Call it inside the dialog's content.
 */
@Composable
internal fun DialogSystemBarIcons(statusBarBackground: Color, navigationBarBackground: Color) {
    val view = LocalView.current
    val lightStatusBar = statusBarBackground.isLight()
    val lightNavigationBar = navigationBarBackground.isLight()
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            // "Light" bars ask for dark icons.
            isAppearanceLightStatusBars = lightStatusBar
            isAppearanceLightNavigationBars = lightNavigationBar
        }
    }
}

private fun Color.isLight(): Boolean = luminance() > 0.5f
