package com.grocerysync.app.ui.components

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.grocerysync.app.data.PhoneSettings
import com.grocerysync.app.notify.rememberNotificationAccess
import com.grocerysync.app.notify.rememberNotificationPermissionRequest
import com.grocerysync.app.text.Texts
import com.grocerysync.app.ui.theme.GrocerySyncTheme

/**
 * Whether the list screen offers change notifications now: they are on ([PhoneSettings.notifyChanges], the default),
 * the phone [isConfigured] (connected to a sheet), Android 13+ ([sdkInt]) where they need a permission, the
 * permission isn't granted, and the offer wasn't answered yet ([PhoneSettings.notifyOfferDone]). Below Android 13
 * notifications need no permission, so there is nothing to offer.
 */
fun shouldOfferNotifications(
    phone: PhoneSettings,
    isConfigured: Boolean,
    sdkInt: Int,
    permissionGranted: Boolean,
): Boolean =
    phone.notifyChanges &&
        !phone.notifyOfferDone &&
        isConfigured &&
        sdkInt >= Build.VERSION_CODES.TIRAMISU &&
        !permissionGranted

/**
 * The list screen's one-time offer of change notifications, complete: shown only when [shouldOfferNotifications]
 * (it reads the permission itself and checks it again whenever the screen comes back), and it asks Android for the
 * permission itself. "Turn on" asks for it (ActivityResultContracts.RequestPermission with POST_NOTIFICATIONS, through
 * [rememberNotificationPermissionRequest]) and calls [onPermissionAsked] first, which must save
 * [PhoneSettings.notifyPermissionAsked] = true; "Not now" doesn't ask, so Settings > This phone still offers to.
 * Both call [onAnswered], which must save [PhoneSettings.notifyOfferDone] = true, so the card never comes back after
 * either (refused, Settings > This phone shows that notifications are blocked and how to allow them).
 *
 * How the list screen places it, at the top of the banners above the list (it draws nothing when not offered):
 *
 *     // ListScreen: phoneSettings = ListViewModel's PhoneSettingsStore.settings, collected with lifecycle
 *     val phoneSettings by viewModel.phoneSettings.collectAsStateWithLifecycle()
 *     NotifyOffer(
 *         phoneSettings = phoneSettings,
 *         isConfigured = state.isConfigured,           // ListUiState.isConfigured
 *         onAnswered = viewModel::onNotifyOfferAnswered,
 *         onPermissionAsked = viewModel::onNotifyPermissionAsked,
 *     )
 *
 *     // ListViewModel
 *     fun onNotifyOfferAnswered() = phoneSettingsStore.setNotifyOfferDone(true)
 *     fun onNotifyPermissionAsked() = phoneSettingsStore.setNotifyPermissionAsked(true)
 *
 * It needs an activity that can start activities for a result (MainActivity, a ComponentActivity); nothing else is
 * set up for it. The background check is already planned while notifications are on, so allowing them is all it
 * takes.
 */
@Composable
fun NotifyOffer(
    phoneSettings: PhoneSettings,
    isConfigured: Boolean,
    onAnswered: () -> Unit,
    onPermissionAsked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val access = rememberNotificationAccess()
    val askPermission = rememberNotificationPermissionRequest { access.refresh() }
    if (!shouldOfferNotifications(phoneSettings, isConfigured, Build.VERSION.SDK_INT, access.permissionGranted)) return
    NotifyOfferCard(
        onTurnOn = {
            onAnswered()
            onPermissionAsked()
            askPermission()
        },
        onNotNow = onAnswered,
        modifier = modifier,
    )
}

/**
 * The offer's card itself, without state: "Get a notification when someone adds to To buy?" with [onTurnOn] and
 * [onNotNow]. [NotifyOffer] decides when it shows and what the buttons do.
 */
@Composable
fun NotifyOfferCard(onTurnOn: () -> Unit, onNotNow: () -> Unit, modifier: Modifier = Modifier) {
    val t = Texts.current.notify
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 8.dp)) {
            Row(Modifier.padding(end = 8.dp)) {
                Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(t.offerQuestion, style = MaterialTheme.typography.bodyMedium)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onNotNow) { Text(t.notNow) }
                Button(onClick = onTurnOn, modifier = Modifier.padding(start = 4.dp, end = 8.dp)) { Text(t.turnOn) }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun NotifyOfferCardPreview() {
    GrocerySyncTheme(dynamicColor = false) {
        NotifyOfferCard(onTurnOn = {}, onNotNow = {})
    }
}
