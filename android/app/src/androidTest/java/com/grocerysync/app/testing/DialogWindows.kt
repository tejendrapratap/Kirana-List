package com.grocerysync.app.testing

import android.content.res.Configuration
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.annotation.RequiresApi
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.test.platform.app.InstrumentationRegistry

/**
 * The window of the open full-screen Compose dialog (Add common items, Paste a list), as the system draws it. Such
 * a dialog has its own window, which decides the colour of the status bar's and navigation bar's icons, and whether
 * the dialog reaches under the bars.
 */
data class DialogWindowState(
    /** The window asks for dark status bar icons (for a light background under them). */
    val lightStatusBars: Boolean,
    /** The window asks for dark navigation bar buttons (for a light background under them). */
    val lightNavigationBars: Boolean,
    /** The dialog's content covers the whole screen, under the status and navigation bars too. */
    val edgeToEdge: Boolean,
)

/** The open Compose dialog's window ([DialogWindowState]); null while no dialog is open. Call it off the main thread. */
@RequiresApi(Build.VERSION_CODES.Q)
fun openDialogWindow(): DialogWindowState? {
    var state: DialogWindowState? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        val roots = WindowInspector.getGlobalWindowViews()
        val content = roots.firstNotNullOfOrNull(::dialogContentIn) ?: return@runOnMainSync
        val window = (content as DialogWindowProvider).window
        // The activity draws edge to edge (enableEdgeToEdge): its root view is the size of the screen.
        val screen = roots.filter { dialogContentIn(it) == null }.maxByOrNull { it.height } ?: return@runOnMainSync
        val onScreen = IntArray(2).also(content::getLocationOnScreen)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        state = DialogWindowState(
            lightStatusBars = controller.isAppearanceLightStatusBars,
            lightNavigationBars = controller.isAppearanceLightNavigationBars,
            edgeToEdge = onScreen[1] == 0 && content.height >= screen.height && content.width >= screen.width,
        )
    }
    return state
}

/** Whether the app shows its dark theme (the emulator's dark mode, ui.sh dark on). */
val darkTheme: Boolean
    get() = (InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

/** The view of a Compose dialog's content that [view] holds, if any (it provides the dialog's window). */
private fun dialogContentIn(view: View): View? = when {
    view is DialogWindowProvider -> view
    view is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { dialogContentIn(view.getChildAt(it)) }
    else -> null
}
