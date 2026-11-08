package com.grocerysync.app.notify

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * What Android lets the app do with notifications, as screens show it. Android doesn't announce a change (the person
 * allows them in Android's settings, or answers the permission dialog), so [rememberNotificationAccess] asks again
 * each time the screen comes back ([refresh]).
 */
@Stable
class NotificationAccess internal constructor(private val context: Context) {
    /** The change notification can be shown ([notificationsAllowed]). */
    var allowed by mutableStateOf(notificationsAllowed(context))
        private set

    /** POST_NOTIFICATIONS is granted (always true below Android 13, which has no such permission). */
    var permissionGranted by mutableStateOf(hasNotificationPermission(context))
        private set

    /** Android 13+ without the permission: the app may ask for it (Android itself may answer for the person). */
    val mayAsk: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !permissionGranted

    fun refresh() {
        allowed = notificationsAllowed(context)
        permissionGranted = hasNotificationPermission(context)
    }
}

/** [NotificationAccess] for this screen, asked again at every ON_RESUME (back from Android's settings or a dialog). */
@Composable
fun rememberNotificationAccess(): NotificationAccess {
    val context = LocalContext.current.applicationContext
    val access = remember(context) { NotificationAccess(context) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { access.refresh() }
    return access
}

/**
 * A function that asks Android for POST_NOTIFICATIONS (ActivityResultContracts.RequestPermission) and then calls
 * [onResult] with the answer. Call it only when [NotificationAccess.mayAsk]: below Android 13 it does nothing. After
 * the person refused twice, Android answers "no" at once without asking; the screen then offers its settings.
 */
@Composable
fun rememberNotificationPermissionRequest(onResult: (granted: Boolean) -> Unit): () -> Unit {
    val currentOnResult by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        currentOnResult(granted)
    }
    return remember(launcher) {
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
