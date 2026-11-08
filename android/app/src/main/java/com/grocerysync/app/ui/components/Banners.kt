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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
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
import com.grocerysync.app.R
import com.grocerysync.app.data.FailedChange
import com.grocerysync.app.sync.Protocol
import com.grocerysync.app.sync.PushProblem
import com.grocerysync.app.text.Texts
import com.grocerysync.app.ui.StarterList
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
    val t = Texts.current
    BannerCard(
        icon = Icons.Default.Info,
        title = t.list.setupTitle,
        message = t.list.setupMessage,
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier,
    ) {
        Button(onClick = onSetUp, modifier = Modifier.padding(end = 8.dp)) {
            Text(t.list.setUp)
        }
    }
}

/** Sync stopped and needs the user (wrong token, deployment access, ...): [message] is the sync layer's (English). */
@Composable
fun SyncErrorBanner(
    message: String?,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = Texts.current
    BannerCard(
        icon = Icons.Default.Warning,
        title = t.list.notSyncing,
        message = syncErrorText(message),
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier,
    ) {
        TextButton(onClick = onOpenSettings) { Text(t.list.openSettings) }
        TextButton(onClick = onRetry) { Text(t.common.retry) }
    }
}

/**
 * Text of [SyncErrorBanner]: the sync layer's [message] in the language shown (SyncTexts.message: the script's own
 * words stay as they are), or a general one without it.
 */
internal fun syncErrorText(message: String?): String {
    val t = Texts.current
    return message?.takeIf { it.isNotBlank() }?.let(t.sync::message) ?: t.list.syncErrorFallback
}

/**
 * The list is live (reading works) but sending changes keeps failing: says why and what happens to the
 * waiting changes; Retry sends them now. Shown under the status line, outside the scrolling list, so it
 * is in view however far the list is scrolled; accessibility services announce it when it appears. At most
 * six lines, shortened if need be, or with [showAll] (Simple mode, whose text is bigger) as many as it takes. Retry
 * goes under the text when its label would leave the text too narrow ([ButtonBesideOrBelow]).
 */
@Composable
fun PushWarningLine(
    problem: PushProblem,
    pendingItems: Int,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    showAll: Boolean = false,
) {
    val t = Texts.current
    val text = pushWarningText(problem, pendingItems)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        ButtonBesideOrBelow(Modifier.padding(start = 12.dp, top = 4.dp, end = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(PUSH_WARNING_TITLE) }
                        append(text.removePrefix(PUSH_WARNING_TITLE))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = if (showAll) Int.MAX_VALUE else 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 4.dp),
                )
            }
            TextButton(
                onClick = onRetry,
                modifier = Modifier.semantics { contentDescription = t.list.retrySendingChanges },
            ) { Text(t.common.retry) }
        }
    }
}

/**
 * The most of a line's width its button takes beside the text ([ButtonBesideOrBelow]); a wider one goes under it.
 * Hindi's Retry (फिर से कोशिश करें) at the usual text size fits, so the line stays compact.
 */
private const val BESIDE_BUTTON_MAX_WIDTH_FRACTION = 0.4f

/**
 * Lays out [content], a text and then a button (two children, in that order), as one line of the width it is given:
 * the button at the end beside the text, both centred, while the button takes at most
 * [BESIDE_BUTTON_MAX_WIDTH_FRACTION] of the width; else the text across the whole width and the button under it at
 * the end. Measured beside it, the text gets only what the button leaves (a button doesn't wrap its label first): with
 * a long label (Hindi) and large text that is a strip of a few letters a line, many lines tall.
 */
@Composable
private fun ButtonBesideOrBelow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val (textPart, buttonPart) = measurables
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val button = buttonPart.measure(loose)
        val width = constraints.maxWidth
        if (button.width <= width * BESIDE_BUTTON_MAX_WIDTH_FRACTION) {
            val text = textPart.measure(loose.copy(maxWidth = width - button.width))
            val height = maxOf(text.height, button.height)
            layout(width, height) {
                text.placeRelative(0, (height - text.height) / 2)
                button.placeRelative(width - button.width, (height - button.height) / 2)
            }
        } else {
            val text = textPart.measure(loose)
            layout(width, text.height + button.height) {
                text.placeRelative(0, 0)
                button.placeRelative(width - button.width, text.height)
            }
        }
    }
}

/** The start of [pushWarningText], shown in bold. */
internal val PUSH_WARNING_TITLE: String get() = Texts.current.list.pushWarningTitle

/** Text of [PushWarningLine]: the reason (in the language shown), then what happens to the waiting changes. */
internal fun pushWarningText(problem: PushProblem, pendingItems: Int): String {
    val t = Texts.current
    // Transient-failure messages already end with "Changes are saved on this phone ..."; say it once.
    val cause = t.sync.message(problem.message.removeSuffix(Protocol.SAVED_ON_PHONE).trim())
    // Retrying alone won't help a request the script rejects: don't promise that it will.
    return t.list.pushWarning(cause, pendingItems, needsFix = problem.needsFix)
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

/** The first sentence of [Protocol.MSG_SCRIPT_OUTDATED] as the sync layer writes it (English; translated where shown). */
private const val SCRIPT_OUTDATED_FIRST_SENTENCE = "The sheet's script is out of date."

/** The start of [scriptUpdateText] in the language shown, shown in bold. */
internal val SCRIPT_UPDATE_TITLE: String get() = Texts.current.sync.message(SCRIPT_OUTDATED_FIRST_SENTENCE)

/** Text of [ScriptUpdateLine]: what to do, then what happens to the changes made on this phone. */
internal fun scriptUpdateText(pendingItems: Int): String {
    val t = Texts.current
    return "${t.sync.message(Protocol.MSG_SCRIPT_OUTDATED)} ${t.list.scriptUpdateWaiting(pendingItems)}"
}

/** Local changes the server rejected; they stay visible until retried or discarded. */
@Composable
fun FailedChangesBanner(
    failed: List<FailedChange>,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val message = failedChangesText(failed) ?: return
    val t = Texts.current
    BannerCard(
        icon = Icons.Default.Warning,
        title = null,
        message = message,
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        TextButton(
            onClick = onDiscard,
            modifier = Modifier.semantics { contentDescription = t.list.discardFailedChanges },
        ) { Text(t.list.discard) }
        TextButton(
            onClick = onRetry,
            modifier = Modifier.semantics { contentDescription = t.list.retryFailedChanges },
        ) { Text(t.common.retry) }
    }
}

/**
 * Text of [FailedChangesBanner]: how many changes the sheet refused, then the first one's item and why (the sync
 * layer's error, in the language shown); null for none.
 */
internal fun failedChangesText(failed: List<FailedChange>): String? {
    val first = failed.firstOrNull() ?: return null
    val t = Texts.current
    val error = t.sync.message(first.error)
    val detail = if (first.itemName.isNotBlank()) "${first.itemName}: $error" else error
    return "${t.list.failedChanges(failed.size)}: $detail"
}

/**
 * A page with nothing on it: the leaf-green jhola (the app's bag, R.drawable.ic_jhola), [title] and [hint], and a
 * button [actionText] that calls [onAction] when given (leaf-green tonal, [leafTonalButtonColors]).
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
            painter = painterResource(R.drawable.ic_jhola),
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
                message = Protocol.MSG_UNAUTHORIZED,
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
            val t = Texts.current.list
            EmptyListState(t.emptyToBuyTitle, t.emptyToBuyHintNewList, actionText = StarterList.TITLE)
            NoSearchMatches(t.noSearchMatch, t.addToList("Corriander"), Icons.Default.Add, onAction = {})
            NoSearchMatches(t.noToBuySearchMatch, t.showInAllItems(2), Icons.AutoMirrored.Filled.ArrowForward, onAction = {})
        }
    }
}
