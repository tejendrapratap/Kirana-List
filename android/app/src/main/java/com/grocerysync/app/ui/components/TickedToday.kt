package com.grocerysync.app.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.grocerysync.app.data.Amount
import com.grocerysync.app.data.ItemValue
import com.grocerysync.app.data.TickedEntry
import com.grocerysync.app.data.Units
import com.grocerysync.app.data.displayText
import com.grocerysync.app.text.Texts
import com.grocerysync.app.ui.theme.GrocerySyncTheme

/**
 * The header of To buy's "Ticked today" section: "Ticked today (3)" and an arrow; tapping it shows or hides the items
 * under it. Opaque, as it sticks to the top while its rows scroll under it (like the shop section headers). TalkBack
 * reads it as one heading with its count and whether it is open: "Ticked today, 3 items, Collapsed".
 */
@Composable
fun TickedTodayHeader(count: Int, expanded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = Texts.current.list
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClickLabel = if (expanded) t.hideTickedItems else t.showTickedItems, onClick = onClick)
            .semantics {
                heading()
                contentDescription = t.tickedTodayDescription(count)
                stateDescription = if (expanded) t.expanded else t.collapsed
            }
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = t.tickedToday(count),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // The heading's description says it (with "items"), once.
            modifier = Modifier
                .weight(1f)
                .clearAndSetSemantics { },
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * An item under Ticked today: a tick, its name, the amount Put back gives it again (none for a yes / no item), and
 * the Put back button, which TalkBack reads with the name ("Put back Rice"). Dimmed like an item that isn't needed.
 * The name wraps, so the button keeps its full label with large text.
 */
@Composable
fun TickedTodayRow(entry: TickedEntry, onPutBack: () -> Unit, modifier: Modifier = Modifier) {
    val t = Texts.current.list
    val amount = entry.before as? ItemValue.Quantity
    ListItem(
        modifier = modifier,
        colors = ListItemDefaults.colors(
            headlineColor = MaterialTheme.colorScheme.onSurfaceVariant,
            leadingIconColor = MaterialTheme.colorScheme.primary,
        ),
        leadingContent = { Icon(Icons.Default.Check, contentDescription = null) },
        headlineContent = { Text(entry.name, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = if (amount != null) {
            {
                Text(
                    text = amount.displayText(),
                    modifier = Modifier.clearAndSetSemantics { contentDescription = t.amountDescription(amount.spokenText()) },
                )
            }
        } else {
            null
        },
        trailingContent = {
            TextButton(
                onClick = onPutBack,
                modifier = Modifier.semantics { contentDescription = t.putBackDescription(entry.name) },
            ) {
                Text(t.putBack)
            }
        },
    )
}

/** The list key of Ticked today's header. No row's key ([tickedTodayRowKey]) can be the same. */
internal const val TICKED_TODAY_HEADER_KEY = "ticked:header"

/**
 * The list key of [entry]'s row under Ticked today. Its item's key can be any name ("header" too), so it goes after a
 * prefix of its own that no fixed key of the list starts with: two items with one key would crash the list.
 */
internal fun tickedTodayRowKey(entry: TickedEntry): String = "ticked:item:" + entry.key

/**
 * Whether Ticked today is open, as the person left it while Simple mode was [simpleMode] (on or off): the list saves
 * it like the page shown. A state left in the other mode doesn't count ([expandedIn]): Simple mode is switched in
 * Settings, and the list's saved state comes back from there, so the mode is kept with it.
 */
internal data class TickedTodayOpen(val simpleMode: Boolean, val expanded: Boolean) {

    /**
     * Open or not now that Simple mode is [simpleMode]: as left in that mode, else as that mode starts it (closed, but
     * open in Simple mode, where nothing should be hidden away).
     */
    fun expandedIn(simpleMode: Boolean): Boolean = if (simpleMode == this.simpleMode) expanded else simpleMode

    companion object {
        /** As [simpleMode] starts it. */
        fun startIn(simpleMode: Boolean) = TickedTodayOpen(simpleMode, expanded = simpleMode)

        val Saver = listSaver<TickedTodayOpen, Boolean>(
            save = { listOf(it.simpleMode, it.expanded) },
            restore = { TickedTodayOpen(simpleMode = it[0], expanded = it[1]) },
        )
    }
}

@Composable
private fun PreviewTickedToday(expanded: Boolean) {
    val entries = listOf(
        TickedEntry("rice", "Rice", ItemValue.Quantity(Amount.whole(2L), Units.KG), ItemValue.Quantity(Amount.ZERO, Units.KG), 0L),
        TickedEntry("dahi", "Dahi", ItemValue.BoolValue(true), ItemValue.BoolValue(false), 0L),
    )
    Column {
        TickedTodayHeader(count = entries.size, expanded = expanded, onClick = {})
        if (expanded) entries.forEach { TickedTodayRow(it, onPutBack = {}) }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun TickedTodayPreview() {
    GrocerySyncTheme(dynamicColor = false) { PreviewTickedToday(expanded = true) }
}

/** The narrowest case: a 360dp phone with large text, in the dark theme. */
@Preview(showBackground = true, widthDp = 360, fontScale = 1.3f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TickedTodayLargeTextPreview() {
    GrocerySyncTheme(darkTheme = true, dynamicColor = false) { PreviewTickedToday(expanded = true) }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun TickedTodayCollapsedPreview() {
    GrocerySyncTheme(dynamicColor = false) { PreviewTickedToday(expanded = false) }
}
