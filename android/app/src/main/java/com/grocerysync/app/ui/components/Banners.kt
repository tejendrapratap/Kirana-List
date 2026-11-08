package com.grocerysync.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.grocerysync.app.data.FailedChange
import com.grocerysync.app.sync.Protocol
import com.grocerysync.app.sync.PushProblem
import com.grocerysync.app.ui.theme.GrocerySyncTheme
import com.grocerysync.app.ui.theme.leafTonalButtonColors

@Composable
private fun BannerCard(
    icon: ImageVector,
    title: String?,
    message: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 8.dp)) {
            Row(Modifier.padding(end = 8.dp)) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    if (title != null) {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(2.dp))
                    }
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
    }
}

/** Shown while no web app URL / token is saved. The list still works locally meanwhile. */
@Composable
fun SetupBanner(onSetUp: () -> Unit, modifier: Modifier = Modifier) {
    BannerCard(
        icon = Icons.Default.Info,
        title = "Connect your Google Sheet",
        message = "Share one live list between phones. Until then, items are only saved on this phone.",
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier,
    ) {
        Button(onClick = onSetUp, modifier = Modifier.padding(end = 8.dp)) {
            Text("Set up")
        }
    }
}

/** Sync stopped and needs the user (wrong token, deployment access, ...). */
@Composable
fun SyncErrorBanner(
    message: String?,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BannerCard(
        icon = Icons.Default.Warning,
        title = "Not syncing",
        message = message?.takeIf { it.isNotBlank() } ?: "Something is wrong with the connection to your sheet.",
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier,
    ) {
        TextButton(onClick = onOpenSettings) { Text("Open settings") }
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}

/**
 * The list is live (reading works) but sending changes keeps failing: says why and what happens to the
 * waiting changes; Retry sends them now. Shown under the status line, outside the scrolling list, so it
 * is in view however far the list is scrolled; accessibility services announce it when it appears.
 */
@Composable
fun PushWarningLine(
    problem: PushProblem,
    pendingItems: Int,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = pushWarningText(problem, pendingItems)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(PUSH_WARNING_TITLE) }
                    append(text.removePrefix(PUSH_WARNING_TITLE))
                },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp),
            )
            TextButton(
                onClick = onRetry,
                modifier = Modifier.semantics { contentDescription = "Retry sending changes" },
            ) { Text("Retry") }
        }
    }
}

internal const val PUSH_WARNING_TITLE = "Changes aren't saving"

/** Text of [PushWarningLine]: the reason, then what happens to the waiting changes. */
internal fun pushWarningText(problem: PushProblem, pendingItems: Int): String {
    // Transient-failure messages already end with "Changes are saved on this phone ..."; say it once.
    val cause = problem.message.removeSuffix(Protocol.SAVED_ON_PHONE).trim()
    val waiting = if (pendingItems == 1) "1 item is waiting on this phone" else "$pendingItems items are waiting on this phone"
    // Retrying alone won't help a request the script rejects: don't promise that it will.
    val next = if (problem.needsFix) "until that is fixed." else "and will be sent again automatically."
    return "$PUSH_WARNING_TITLE: $cause $waiting $next"
}

/**
 * The sheet runs a script older than protocol 3 (no units): says what to do and that changes wait on the
 * phone. Shown under the status line, outside the scrolling list, for as long as the old script answers;
 * accessibility services announce it when it appears.
 */
@Composable
fun ScriptUpdateLine(pendingItems: Int, modifier: Modifier = Modifier) {
    val text = scriptUpdateText(pendingItems)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(SCRIPT_UPDATE_TITLE) }
                    append(text.removePrefix(SCRIPT_UPDATE_TITLE))
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** The first sentence of [Protocol.MSG_SCRIPT_OUTDATED], shown in bold. */
internal const val SCRIPT_UPDATE_TITLE = "The sheet's script is out of date."

/** Text of [ScriptUpdateLine]: what to do, then what happens to the changes made on this phone. */
internal fun scriptUpdateText(pendingItems: Int): String {
    val waiting = when (pendingItems) {
        0 -> "Until then, changes made here wait on this phone."
        1 -> "1 item is waiting on this phone until then."
        else -> "$pendingItems items are waiting on this phone until then."
    }
    return "${Protocol.MSG_SCRIPT_OUTDATED} $waiting"
}

/** Local changes the server rejected; they stay visible until retried or discarded. */
@Composable
fun FailedChangesBanner(
    failed: List<FailedChange>,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val first = failed.firstOrNull() ?: return
    val count = failed.size
    val what = if (count == 1) "1 change couldn't be saved" else "$count changes couldn't be saved"
    val detail = if (first.itemName.isNotBlank()) "${first.itemName}: ${first.error}" else first.error
    BannerCard(
        icon = Icons.Default.Warning,
        title = null,
        message = "$what: $detail",
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        TextButton(
            onClick = onDiscard,
            modifier = Modifier.semantics { contentDescription = "Discard failed changes" },
        ) { Text("Discard") }
        TextButton(
            onClick = onRetry,
            modifier = Modifier.semantics { contentDescription = "Retry failed changes" },
        ) { Text("Retry") }
    }
}

/**
 * A page with nothing on it: a leaf-green cart, [title] and [hint], and a button [actionText] that calls [onAction]
 * when given (leaf-green tonal, [leafTonalButtonColors]).
 */
@Composable
fun EmptyListState(
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.ShoppingCart,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionText != null) {
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = onAction, colors = leafTonalButtonColors()) {
                Text(actionText, textAlign = TextAlign.Center)
            }
        }
    }
}

/**
 * Shown under the search field when no item on the page matches: [message] (announced by accessibility
 * services when it appears), then one row with the way on: [actionText] after [actionIcon], e.g.
 * Add "Corriander" to the list, or Show 2 in All items. TalkBack reads the row as a button.
 */
@Composable
fun NoSearchMatches(
    message: String,
    actionText: String,
    actionIcon: ImageVector,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        ListItem(
            modifier = Modifier.clickable(role = Role.Button, onClick = onAction),
            leadingContent = {
                Icon(actionIcon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            },
            headlineContent = {
                Text(actionText, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun BannersPreview() {
    GrocerySyncTheme(dynamicColor = false) {
        Column {
            SetupBanner(onSetUp = {})
            SyncErrorBanner(
                message = "The secret token doesn't match SECRET_TOKEN in the script.",
                onOpenSettings = {},
                onRetry = {},
            )
            ScriptUpdateLine(pendingItems = 2, modifier = Modifier.padding(horizontal = 16.dp))
            PushWarningLine(
                problem = PushProblem(Protocol.SCRIPT_ERROR_PREFIX + "Choose Boolean or Integer.", 1, mayBeMomentary = false),
                pendingItems = 3,
                onRetry = {},
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            FailedChangesBanner(
                failed = listOf(FailedChange("1", "Milk", "name too long")),
                onRetry = {},
                onDiscard = {},
            )
            EmptyListState("Nothing to buy right now", "Tap + to add items.", actionText = "Add common items")
            NoSearchMatches("No items match your search", "Add \"Corriander\" to the list", Icons.Default.Add, onAction = {})
            NoSearchMatches(
                "No items to buy match your search",
                "Show 2 in All items",
                Icons.AutoMirrored.Filled.ArrowForward,
                onAction = {},
            )
        }
    }
}
