package com.grocerysync.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.grocerysync.app.data.CatalogEntry
import com.grocerysync.app.data.CatalogSet
import com.grocerysync.app.data.GroceryItem
import com.grocerysync.app.data.ItemCatalog
import com.grocerysync.app.data.ShopSection
import com.grocerysync.app.text.Texts
import com.grocerysync.app.ui.components.SectionAccentBar
import com.grocerysync.app.ui.theme.GrocerySyncTheme
import com.grocerysync.app.ui.theme.appBarColors
import com.grocerysync.app.ui.theme.choiceChipColors

/** Alpha of the texts of a row that can't be ticked (its item is on the list), as Material dims disabled content. */
private const val DISABLED_ALPHA = 0.38f

/** Saves what was unticked ([StarterRow.id]s) as a list, which a Bundle holds. */
private val UntickedSaver = Saver<Set<String>, ArrayList<String>>(
    save = { ArrayList(it) },
    restore = { it.toSet() },
)

/**
 * "Add common items" (the starter list): a full-screen dialog to tick items of the built-in dictionary
 * ([ItemCatalog]) and add them to All items in one go, not needed. Chips at the top choose a set ([CatalogSet]:
 * Everyday, Monthly kirana, ...); its items are listed by shop section in walking order, each with a checkbox, its
 * other names in small text ("Pyaaz · प्याज़") and what it is added as (a unit, or yes / no). An item already on the
 * list ([onList]: the same name, or another name of the same item, e.g. "Curd" for Dahi) is unticked and disabled
 * ("On the list as Curd"); the rest start ticked, except meat, fish and eggs ([StarterList.UNTICKED_AT_FIRST]:
 * many families never buy them), and Select all / Select none at the bottom changes the set shown. The set and
 * what was unticked survive rotation; what was unticked stays unticked in the other sets too. The rules are in
 * [StarterList].
 *
 * "Add N items" calls [onAdd] with the ticked entries of the set shown; the X and Back call [onDismiss].
 */
@Composable
fun StarterListDialog(onList: List<GroceryItem>, onDismiss: () -> Unit, onAdd: (List<CatalogEntry>) -> Unit) {
    var set by rememberSaveable { mutableStateOf(StarterList.SETS.first()) }
    var unticked by rememberSaveable(stateSaver = UntickedSaver) { mutableStateOf(StarterList.UNTICKED_AT_FIRST) }
    val names = remember(onList) { onList.map { it.name } }
    val groups = remember(set, names) { StarterList.groups(set, names) }

    Dialog(
        onDismissRequest = onDismiss,
        // Full screen, a set has up to about 150 items, and edge to edge: drawn under the status bar (its green app
        // bar) and the navigation bar (its bottom bar), so the list behind never shows through there.
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        DialogSystemBarIcons(
            statusBarBackground = GrocerySyncTheme.extendedColors.appBar,
            navigationBarBackground = MaterialTheme.colorScheme.surfaceContainer,
        )
        StarterListContent(
            set = set,
            onSetChange = { set = it },
            groups = groups,
            unticked = unticked,
            // Applied to the newest value, so two quick taps never undo each other.
            onUntickedChange = { change -> unticked = change(unticked) },
            onDismiss = onDismiss,
            onAdd = onAdd,
        )
    }
}

/**
 * The dialog's screen: the green top bar (X and title), the set chips, the rows under sticky section headers
 * (TalkBack headings), and a bottom bar with Select all / Select none and "Add N items". The bars reach under the
 * system bars; the rows keep clear of them and of a display cutout ([WindowInsets.safeDrawing]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StarterListContent(
    set: CatalogSet,
    onSetChange: (CatalogSet) -> Unit,
    groups: List<StarterGroup>,
    unticked: Set<String>,
    onUntickedChange: ((Set<String>) -> Set<String>) -> Unit,
    onDismiss: () -> Unit,
    onAdd: (List<CatalogEntry>) -> Unit,
) {
    val toAdd = remember(groups, unticked) { StarterList.toAdd(groups, unticked) }
    val hasAddable = remember(groups) { StarterList.hasAddable(groups) }
    val allTicked = StarterList.allTicked(groups, unticked)
    // Each set starts at its top; saved, so a rotation keeps the place.
    val listState = rememberSaveable(set, saver = LazyListState.Saver) { LazyListState() }
    val t = Texts.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t.bulk.addCommonItemsTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = appBarColors(),
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = t.common.close)
                    }
                },
            )
        },
        bottomBar = {
            AddBar(
                selectText = when {
                    !hasAddable -> null
                    allTicked -> t.bulk.selectNone
                    else -> t.bulk.selectAll
                },
                onSelect = { onUntickedChange { StarterList.selectAll(groups, it, select = !allTicked) } },
                addText = StarterList.addButtonText(toAdd.size),
                addEnabled = toAdd.isNotEmpty(),
                onAdd = { onAdd(toAdd) },
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { innerPadding ->
        Column(
            Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            SetChips(set = set, onSetChange = onSetChange)
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
            ) {
                item(key = "hint", contentType = "hint") {
                    Text(
                        text = if (hasAddable) t.bulk.addCommonItemsHint else t.bulk.allOfSetOnList,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                for (group in groups) {
                    stickyHeader(key = "section:" + group.section.id, contentType = "section") {
                        SectionHeader(group.section)
                    }
                    items(group.rows, key = { "entry:" + it.id }, contentType = { "row" }) { row ->
                        StarterRowItem(
                            row = row,
                            ticked = StarterList.isTicked(row, unticked),
                            onTickedChange = { ticked -> onUntickedChange { StarterList.toggle(it, row.id, ticked) } },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The sets, one chip each, on one line that scrolls sideways: on a narrow phone with large text the last chips
 * are cut off at the edge, which shows there are more. Wrapped onto several lines they would take the room of
 * the rows, which matters on a phone in landscape.
 */
@Composable
private fun SetChips(set: CatalogSet, onSetChange: (CatalogSet) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .selectableGroup()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (option in StarterList.SETS) {
            val selected = option == set
            FilterChip(
                selected = selected,
                onClick = { onSetChange(option) },
                label = { Text(Texts.current.bulk.setName(option), maxLines = 1) },
                // Leaf green when selected, like the list's To buy / All items row.
                colors = choiceChipColors(),
                // Not only the colour says which set is shown.
                leadingIcon = if (selected) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                } else {
                    null
                },
            )
        }
    }
}

/**
 * A shop section's name above its rows, after its accent mark as on To buy ([SectionAccentBar]); it stays at the
 * top while its rows scroll under it.
 */
@Composable
private fun SectionHeader(section: ShopSection) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionAccentBar(section)
            Spacer(Modifier.width(12.dp))
            Text(
                text = Texts.current.common.sectionName(section),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
        }
    }
}

/**
 * One item: a checkbox, the name, its other names in small text (or "On the list as Oil", then the row is
 * disabled) and what it is added as at the end. The whole row ticks; TalkBack reads it as one checkbox with the
 * names separated by commas ([StarterList.rowDescription]).
 */
@Composable
private fun StarterRowItem(row: StarterRow, ticked: Boolean, onTickedChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val enabled = row.canAdd
    val description = StarterList.rowDescription(row)
    ListItem(
        modifier = Modifier
            .toggleable(value = ticked, enabled = enabled, role = Role.Checkbox, onValueChange = onTickedChange)
            .semantics { contentDescription = description },
        colors = ListItemDefaults.colors(
            headlineColor = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = DISABLED_ALPHA),
            supportingColor = if (enabled) colors.onSurfaceVariant else colors.onSurface.copy(alpha = DISABLED_ALPHA),
            trailingIconColor = if (enabled) colors.onSurfaceVariant else colors.onSurface.copy(alpha = DISABLED_ALPHA),
        ),
        // Drawn only: the row is the checkbox.
        leadingContent = { Checkbox(checked = ticked, onCheckedChange = null, enabled = enabled) },
        headlineContent = { Text(row.entry.displayName, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = {
            Text(row.onListAs?.let(StarterList::onListText) ?: StarterList.otherNamesText(row.entry))
        },
        trailingContent = {
            Text(StarterList.unitText(row.entry), style = MaterialTheme.typography.bodyMedium)
        },
    )
}

/**
 * The bottom bar: Select all / Select none ([selectText]; none when nothing in the set can be added) and the
 * confirm button [addText] (filled, primary green), enabled ([addEnabled]) once something is ticked. With very large
 * text the button's label wraps rather than pushing it off the screen. Its surface reaches under the navigation bar.
 */
@Composable
private fun AddBar(selectText: String?, onSelect: () -> Unit, addText: String, addEnabled: Boolean, onAdd: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectText != null) {
                TextButton(onClick = onSelect) { Text(selectText) }
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = onAdd, enabled = addEnabled) {
                Text(addText, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun StarterListPreviewOf(set: CatalogSet, onList: List<String>, darkTheme: Boolean = false) {
    GrocerySyncTheme(darkTheme = darkTheme, dynamicColor = false) {
        StarterListContent(
            set = set,
            onSetChange = {},
            groups = StarterList.groups(set, onList),
            unticked = setOf("Hari mirch"),
            onUntickedChange = {},
            onDismiss = {},
            onAdd = {},
        )
    }
}

/** A family list with Curd and Tel on it: Dahi and Oil are "On the list as ...". */
@Preview(showBackground = true, widthDp = 360, heightDp = 640, fontScale = 1.3f)
@Composable
private fun StarterListPreview() {
    StarterListPreviewOf(CatalogSet.EVERYDAY, onList = listOf("Curd", "Onion", "Tel"))
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun StarterListDarkPreview() {
    StarterListPreviewOf(CatalogSet.DIWALI_PUJA, onList = listOf("Ghee", "Diya"), darkTheme = true)
}

/** A phone in landscape: the chips stay on one line. */
@Preview(showBackground = true, widthDp = 760, heightDp = 360)
@Composable
private fun StarterListLandscapePreview() {
    StarterListPreviewOf(CatalogSet.VRAT, onList = emptyList())
}
