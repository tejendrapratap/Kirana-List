package com.grocerysync.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.tooling.preview.Preview
import com.grocerysync.app.text.Texts
import com.grocerysync.app.ui.components.AppBarTitle
import com.grocerysync.app.ui.theme.GrocerySyncTheme
import com.grocerysync.app.ui.theme.appBarColors

/**
 * The list screen's app bar, deep green ([appBarColors]): the app's name after a turmeric jhola ([AppBarTitle]),
 * then Search, Share (the To buy list as a message) and the ⋮ menu ([ListMenu]), all three in the bar's light
 * icon colour (onAppBar). [groupBySection] is the menu's tick for "Group by shop section". In [simpleMode] there is
 * no Search, and the menu holds only Settings ([listMenuChoices]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ListTopBar(groupBySection: Boolean, actions: ListActions, simpleMode: Boolean = false) {
    val t = Texts.current
    TopAppBar(
        title = { AppBarTitle() },
        colors = appBarColors(),
        actions = {
            if (!simpleMode) {
                IconButton(onClick = actions.openSearch) {
                    Icon(Icons.Default.Search, contentDescription = t.list.searchItems)
                }
            }
            IconButton(onClick = actions.share) {
                Icon(Icons.Default.Share, contentDescription = t.list.shareList)
            }
            ListMenu(groupBySection = groupBySection, actions = actions, choices = listMenuChoices(simpleMode))
        },
    )
}

/**
 * The ⋮ button (More options) and its menu: [choices] in their order, normally Add common items, Paste a list, the
 * "Group by shop section" tick and Settings. Choosing anything closes the menu first, the tick too (it flips
 * [groupBySection]). The button takes the app bar's icon colour; the menu is a normal surface (not the green of the
 * bar) with a leaf-green tick.
 */
@Composable
private fun ListMenu(groupBySection: Boolean, actions: ListActions, choices: List<ListMenuChoice>) {
    var expanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val t = Texts.current
    val choose = { action: () -> Unit ->
        expanded = false
        action()
    }
    // The menu opens at its button: both in one Box.
    Box {
        // Like a dialog, the menu hides the search's keyboard first, so it doesn't come back when the menu closes.
        IconButton(onClick = {
            focusManager.clearFocus()
            expanded = true
        }) {
            Icon(Icons.Default.MoreVert, contentDescription = t.list.moreOptions)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            for (choice in choices) {
                when (choice) {
                    ListMenuChoice.ADD_COMMON_ITEMS -> DropdownMenuItem(
                        text = { Text(t.list.addCommonItems) },
                        onClick = { choose(actions.openStarterList) },
                    )
                    ListMenuChoice.PASTE_LIST -> DropdownMenuItem(
                        text = { Text(t.list.pasteList) },
                        onClick = { choose(actions.openPasteList) },
                    )
                    ListMenuChoice.GROUP_BY_SECTION -> DropdownMenuItem(
                        text = { Text(t.list.groupBySection) },
                        onClick = { choose { actions.setGroupBySection(!groupBySection) } },
                        // The box only shows the state; TalkBack reads the whole row as one checkbox ("checked").
                        trailingIcon = {
                            Checkbox(
                                checked = groupBySection,
                                onCheckedChange = null,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = MaterialTheme.colorScheme.primary,
                                    checkmarkColor = MaterialTheme.colorScheme.onPrimary,
                                    uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                        },
                        modifier = Modifier.semantics {
                            role = Role.Checkbox
                            toggleableState = ToggleableState(groupBySection)
                        },
                    )
                    ListMenuChoice.SETTINGS -> DropdownMenuItem(
                        text = { Text(t.common.settings) },
                        onClick = { choose(actions.openSettings) },
                    )
                }
            }
        }
    }
}

private val PreviewActions = ListActions(
    openSettings = {},
    openSearch = {},
    share = {},
    refresh = {},
    retrySync = {},
    retryFailed = {},
    discardFailed = {},
    add = {},
    edit = {},
    gotIt = {},
    setNeeded = { _, _ -> },
    stepQuantity = { _, _ -> },
    putBack = {},
    openStarterList = {},
    openPasteList = {},
    setGroupBySection = {},
)

/** A small phone with large text, in dark theme: the title is shortened if need be, the icons stay. */
@Preview(widthDp = 360, fontScale = 1.3f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ListTopBarPreview() {
    GrocerySyncTheme(dynamicColor = false) {
        ListTopBar(groupBySection = true, actions = PreviewActions)
    }
}

/** Simple mode at 200% text: Share and the ⋮ menu (Settings only), no Search. */
@Preview(widthDp = 360, fontScale = 2f)
@Composable
private fun SimpleListTopBarPreview() {
    GrocerySyncTheme(dynamicColor = false) {
        ListTopBar(groupBySection = true, actions = PreviewActions, simpleMode = true)
    }
}
