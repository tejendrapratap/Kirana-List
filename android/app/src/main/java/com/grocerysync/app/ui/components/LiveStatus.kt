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
import com.grocerysync.app.text.Texts
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

/**
 * Compact connection line shown under the title in the top app bar: at most two lines, shortened if need be, or with
 * [showAll] (Simple mode, whose text is bigger) as many as it takes.
 */
@Composable
fun LiveStatusLine(status: SyncStatus, isConfigured: Boolean, modifier: Modifier = Modifier, showAll: Boolean = false) {
    val maxLines = if (showAll) Int.MAX_VALUE else 2
    val colors = MaterialTheme.colorScheme
    val t = Texts.current.list
    when {
        !isConfigured || status.state == ConnectionState.NOT_CONFIGURED ->
            StatusText(
                text = t.notConnected,
                dotColor = colors.outline,
                textColor = colors.onSurfaceVariant,
                maxLines = maxLines,
                modifier = modifier,
            )
        status.state == ConnectionState.LIVE -> {
            val now by rememberTickingTime(1_000L)
            StatusText(
                text = liveStatusText(status.lastSuccessAt, now),
                dotColor = GrocerySyncTheme.extendedColors.live,
                textColor = colors.onSurfaceVariant,
                maxLines = maxLines,
                modifier = modifier,
            )
        }
        status.state == ConnectionState.CONNECTING ->
            StatusText(
                text = t.connecting,
                dotColor = colors.outline,
                textColor = colors.onSurfaceVariant,
                maxLines = maxLines,
                modifier = modifier,
            )
        status.state == ConnectionState.OFFLINE -> {
            val now by rememberTickingTime(10_000L)
            StatusText(
                text = offlineStatusText(status.message, status.lastSuccessAt, now),
                dotColor = colors.outline,
                textColor = colors.onSurfaceVariant,
                maxLines = maxLines,
                modifier = modifier,
            )
        }
        else ->
            StatusText(
                text = t.notSyncing,
                dotColor = colors.error,
                textColor = colors.error,
                maxLines = maxLines,
                modifier = modifier,
            )
    }
}

@Composable
private fun StatusText(text: String, dotColor: Color, textColor: Color, maxLines: Int, modifier: Modifier = Modifier) {
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
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "Live - updated 3s ago": how long ago the last sync finished, in the short forms of the status line. */
internal fun liveStatusText(lastSuccessAt: Long?, now: Long): String {
    val t = Texts.current.list
    if (lastSuccessAt == null) return t.live
    val seconds = ((now - lastSuccessAt) / 1_000L).coerceAtLeast(0L)
    val ago = when {
        seconds < 60 -> t.secondsAgoShort(seconds.toInt())
        seconds < 3_600 -> t.minutesAgoShort((seconds / 60).toInt())
        else -> t.hoursAgoShort((seconds / 3_600).toInt())
    }
    return t.liveUpdated(ago)
}

/**
 * OFFLINE covers both "no connection" and "Google/the sheet is having trouble": say which (the
 * phone may have full signal), with the script's own error text when it sent one, and how old the
 * shown list is. [message] is the sync layer's (English): which one it is decides the words shown.
 */
internal fun offlineStatusText(message: String?, lastSuccessAt: Long?, now: Long): String {
    val t = Texts.current
    val reason = when (message) {
        null, Protocol.MSG_OFFLINE -> t.list.offlineShowingSavedList
        Protocol.MSG_BUSY -> t.list.sheetBusyRetrying
        else -> Protocol.scriptErrorDetail(message)?.let(t.list::sheetErrorRetrying) ?: t.list.googleNotRespondingRetrying
    }
    if (lastSuccessAt == null) return reason
    val minutes = ((now - lastSuccessAt) / 60_000L).coerceAtLeast(0L)
    val age = when {
        minutes < 1 -> t.list.lessThanAMinuteAgo
        minutes < 60 -> t.list.minutesAgoShort(minutes.toInt())
        minutes < 48 * 60 -> t.list.hoursAgoShort((minutes / 60).toInt())
        // Two days or more: always more than one.
        else -> t.common.daysAgo((minutes / (24 * 60)).toInt())
    }
    return t.list.offlineWithAge(reason, age)
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
