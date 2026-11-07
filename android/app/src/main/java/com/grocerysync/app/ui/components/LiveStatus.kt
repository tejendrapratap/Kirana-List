package com.grocerysync.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.grocerysync.app.sync.ConnectionState
import com.grocerysync.app.sync.Protocol
import com.grocerysync.app.sync.SyncStatus
import com.grocerysync.app.ui.theme.GrocerySyncTheme
import kotlinx.coroutines.delay

/**
 * Wall-clock time that updates every [periodMillis] (aligned to the period) while the screen is
 * at least STARTED. Read it only in the small composables that display relative times, so a tick
 * recomposes those and nothing else.
 */
@Composable
fun rememberTickingTime(periodMillis: Long): State<Long> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(System.currentTimeMillis(), periodMillis, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val now = System.currentTimeMillis()
                value = now
                delay(periodMillis - now % periodMillis)
            }
        }
    }
}

/** Compact connection line shown under the title in the top app bar. */
@Composable
fun LiveStatusLine(status: SyncStatus, isConfigured: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    when {
        !isConfigured || status.state == ConnectionState.NOT_CONFIGURED ->
            StatusText("Not connected", dotColor = colors.outline, textColor = colors.onSurfaceVariant, modifier = modifier)
        status.state == ConnectionState.LIVE -> {
            val now by rememberTickingTime(1_000L)
            StatusText(
                text = liveStatusText(status.lastSuccessAt, now),
                dotColor = GrocerySyncTheme.extendedColors.live,
                textColor = colors.onSurfaceVariant,
                modifier = modifier,
            )
        }
        status.state == ConnectionState.CONNECTING ->
            StatusText("Connecting...", dotColor = colors.outline, textColor = colors.onSurfaceVariant, modifier = modifier)
        status.state == ConnectionState.OFFLINE -> {
            val now by rememberTickingTime(10_000L)
            StatusText(
                text = offlineStatusText(status.message, status.lastSuccessAt, now),
                dotColor = colors.outline,
                textColor = colors.onSurfaceVariant,
                modifier = modifier,
            )
        }
        else ->
            StatusText("Not syncing", dotColor = colors.error, textColor = colors.error, modifier = modifier)
    }
}

@Composable
private fun StatusText(text: String, dotColor: Color, textColor: Color, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .background(dotColor, CircleShape),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = textColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun liveStatusText(lastSuccessAt: Long?, now: Long): String {
    if (lastSuccessAt == null) return "Live"
    val seconds = ((now - lastSuccessAt) / 1_000L).coerceAtLeast(0L)
    return when {
        seconds < 60 -> "Live - updated ${seconds}s ago"
        seconds < 3_600 -> "Live - updated ${seconds / 60} min ago"
        else -> "Live - updated ${seconds / 3_600} h ago"
    }
}

/**
 * OFFLINE covers both "no connection" and "Google/the sheet is having trouble": say which (the
 * phone may have full signal), with the script's own error text when it sent one, and how old the
 * shown list is.
 */
internal fun offlineStatusText(message: String?, lastSuccessAt: Long?, now: Long): String {
    val reason = when (message) {
        null, Protocol.MSG_OFFLINE -> "Offline - showing saved list"
        Protocol.MSG_BUSY -> "Sheet busy - retrying"
        else -> Protocol.scriptErrorDetail(message)?.let { "Sheet error - retrying: $it" }
            ?: "Google isn't responding - retrying"
    }
    if (lastSuccessAt == null) return reason
    val minutes = ((now - lastSuccessAt) / 60_000L).coerceAtLeast(0L)
    val age = when {
        minutes < 1 -> "less than a minute ago"
        minutes < 60 -> "$minutes min ago"
        minutes < 48 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} days ago"
    }
    return "$reason (updated $age)"
}

@Preview(showBackground = true)
@Composable
private fun LiveStatusLinePreview() {
    GrocerySyncTheme(dynamicColor = false) {
        LiveStatusLine(
            status = SyncStatus(ConnectionState.LIVE, System.currentTimeMillis() - 3_000L, null, "Groceries"),
            isConfigured = true,
        )
    }
}
