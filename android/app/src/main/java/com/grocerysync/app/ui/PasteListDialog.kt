package com.grocerysync.app.ui

import android.content.ClipData
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grocerysync.app.data.Amount
import com.grocerysync.app.data.ItemValue
import com.grocerysync.app.data.PasteParser
import com.grocerysync.app.data.Units
import com.grocerysync.app.ui.theme.GrocerySyncTheme
import com.grocerysync.app.ui.theme.appBarColors
import kotlinx.coroutines.launch

/**
 * The "Paste a list" dialog of [viewModel] while it is open ([ListViewModel.pasteList]): opened by the menu's
 * "Paste a list" ([ListViewModel.openPasteList]) or by a list shared into the app
 * ([ListViewModel.openSharedText]). Its result shows as a snackbar of the list screen.
 */
@Composable
fun PasteListHost(viewModel: ListViewModel) {
    val state by viewModel.pasteList.collectAsStateWithLifecycle()
    state?.let { current ->
        PasteListDialog(
            state = current,
            onPreview = viewModel::previewPaste,
            onEditText = viewModel::editPasteText,
            onRowSelected = viewModel::setPasteRowSelected,
            onApply = viewModel::applyPaste,
            onDismiss = viewModel::closePasteList,
        )
    }
}

/**
 * Full-screen dialog in two steps. First a text field with a Paste button (the clipboard is read only when it is
 * tapped) and Next; then the preview: a row per line saying what it does ([pasteRowText]), with a check box for
 * the ones that change something, and a button that says what it applies ([pasteConfirmText]). Back (and "Edit
 * list") goes from the preview to the text, which a shared list starts from too.
 */
@Composable
fun PasteListDialog(
    state: PasteListState,
    onPreview: (text: String) -> Unit,
    onEditText: () -> Unit,
    onRowSelected: (index: Int, selected: Boolean) -> Unit,
    onApply: (actions: List<PasteAction>) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = if (state.previewing) onEditText else onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // Light status bar icons over the green app bar; the navigation bar's over the bottom bar's surface.
        DialogSystemBarIcons(
            statusBarBackground = GrocerySyncTheme.extendedColors.appBar,
            navigationBarBackground = MaterialTheme.colorScheme.surface,
        )
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            if (state.previewing) {
                PastePreviewStep(state, onRowSelected, onEditText, onApply, onDismiss)
            } else {
                PasteTextStep(state.text, onPreview, onDismiss)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PasteScaffold(
    onClose: () -> Unit,
    buttons: @Composable RowScope.() -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(PASTE_LIST_TITLE) },
                colors = appBarColors(),
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                },
            )
        },
        bottomBar = {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))) {
                HorizontalDivider()
                // Wraps (rather than cutting a label) with large text on a narrow phone.
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    buttons()
                }
            }
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { innerPadding ->
        content(Modifier.padding(innerPadding))
    }
}

/** The text: help, the Paste button and the field; Next once it holds something and isn't too long. */
@Composable
private fun PasteTextStep(initialText: String, onPreview: (String) -> Unit, onClose: () -> Unit) {
    // Edited here (not in the view model), so typing never waits for a round trip; Next hands it over.
    var text by rememberSaveable { mutableStateOf(initialText) }
    var clipboardEmpty by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val tooLong = PasteParser.isTooLong(text)

    PasteScaffold(
        onClose = onClose,
        buttons = {
            Button(onClick = { onPreview(text) }, enabled = text.isNotBlank() && !tooLong) { Text("Next") }
        },
    ) { modifier ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(PASTE_HELP, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val pasted = readClipboardText(context, clipboard)
                        clipboardEmpty = pasted == null
                        if (pasted != null) text = withPastedText(text, pasted)
                    }
                },
            ) { Text("Paste") }
            if (clipboardEmpty) {
                Text(NOTHING_TO_PASTE, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            val supporting = if (tooLong) PASTE_TOO_LONG_TEXT else pasteLineCountText(text)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(MAX_PASTE_FIELD_CHARS) },
                label = { Text("List") },
                placeholder = { Text("Rice 2 kg\nDahi\nOnion 1 kg") },
                minLines = 6,
                isError = tooLong,
                supportingText = if (supporting.isNotEmpty()) {
                    { Text(supporting) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier
                    .fillMaxWidth()
                    // TalkBack says why instead of Material's "Invalid input".
                    .semantics { if (tooLong) error(PASTE_TOO_LONG_TEXT) },
            )
        }
    }
}

/**
 * The preview: what the paste holds, then one row per line. While the family's list is still loading
 * ([PasteListState.waitingForList]) a line says so and the confirm button is off.
 */
@Composable
private fun PastePreviewStep(
    state: PasteListState,
    onRowSelected: (index: Int, selected: Boolean) -> Unit,
    onEditText: () -> Unit,
    onApply: (List<PasteAction>) -> Unit,
    onClose: () -> Unit,
) {
    val chosen = state.chosen
    PasteScaffold(
        onClose = onClose,
        buttons = {
            TextButton(onClick = onEditText) { Text("Edit list") }
            Button(onClick = { onApply(chosen) }, enabled = state.canApply) { Text(pasteConfirmText(chosen)) }
        },
    ) { modifier ->
        LazyColumn(modifier.fillMaxSize()) {
            item(key = "summary", contentType = "summary") {
                Text(
                    text = pasteSummaryText(state.rows),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (state.waitingForList) {
                item(key = "waiting", contentType = "waiting") {
                    Text(
                        text = PASTE_WAITING_TEXT,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            // Says why the button is off, also to TalkBack when the preview opens with it.
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            items(state.rows, key = { it.index }, contentType = { "row" }) { row ->
                PasteRowItem(
                    row = row,
                    selected = row.index in state.selected,
                    onSelectedChange = { onRowSelected(row.index, it) },
                )
            }
        }
    }
}

/**
 * One line: a check box (the whole row toggles it) when it changes something; else a tick for "Already needed"
 * or an info icon for a skipped line. The text says it all, so the icons are silent for TalkBack, which reads the
 * row as one, in words rather than the arrows shown ([pasteRowDescription]).
 */
@Composable
private fun PasteRowItem(row: PasteRow, selected: Boolean, onSelectedChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val description = pasteRowDescription(row)
    val rowModifier = if (row.selectable) {
        Modifier
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = onSelectedChange)
            .semantics { contentDescription = description }
    } else {
        Modifier.semantics(mergeDescendants = true) { contentDescription = description }
    }
    Row(
        modifier = rowModifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when {
                row.selectable -> Checkbox(checked = selected, onCheckedChange = null)
                row.action is PasteAction.AlreadyNeeded ->
                    Icon(Icons.Default.Check, contentDescription = null, tint = colors.primary)
                else -> Icon(Icons.Default.Info, contentDescription = null, tint = colors.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = pasteRowText(row),
                style = MaterialTheme.typography.bodyLarge,
                color = if (row.selectable) colors.onSurface else colors.onSurfaceVariant,
            )
            pasteRowDetail(row)?.let { detail ->
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
    }
}

/** All text on the clipboard (a chat message may come as several items); null if empty or unreadable. */
private suspend fun readClipboardText(context: Context, clipboard: Clipboard): String? =
    try {
        clipboard.getClipEntry()?.clipData?.let { clip: ClipData ->
            (0 until clip.itemCount).joinToString("\n") { index ->
                clip.getItemAt(index).coerceToText(context)?.toString().orEmpty()
            }
        }?.takeIf { it.isNotBlank() }
    } catch (e: SecurityException) {
        null
    }

private val PreviewRows = listOf(
    PasteRow(0, "Rice 2 kg", PasteAction.Add("Rice", ItemValue.Quantity(Amount.whole(2), Units.KG))),
    PasteRow(
        1,
        "Curd 1 kg",
        PasteAction.MarkNeeded("dahi", "Dahi", "Curd", ItemValue.BoolValue(false), ItemValue.BoolValue(true), amountLeftOut = true),
    ),
    PasteRow(2, "Oil", PasteAction.AlreadyNeeded("Oil", null)),
    PasteRow(
        3,
        "Atta 10 kg",
        PasteAction.ChangeAmount("atta", "Atta", null, ItemValue.Quantity(Amount.whole(5), Units.KG), ItemValue.Quantity(Amount.whole(10), Units.KG)),
    ),
    PasteRow(4, "Eggs 12", PasteAction.Skipped(SkipReason.UNIT_MISMATCH, "Eggs", Units.DOZEN)),
    PasteRow(5, "2-3 kg", PasteAction.Skipped(SkipReason.UNREADABLE)),
    PasteRow(6, "Kirana", PasteAction.Add("Kirana", ItemValue.BoolValue(true), looksLikeHeading = true)),
)

@Composable
private fun PasteListDialogPreviewOf(state: PasteListState) {
    GrocerySyncTheme(dynamicColor = false) {
        PasteListDialog(state = state, onPreview = {}, onEditText = {}, onRowSelected = { _, _ -> }, onApply = {}, onDismiss = {})
    }
}

/** A narrow phone with large text. */
@Preview(widthDp = 360, heightDp = 640, fontScale = 1.3f)
@Composable
private fun PasteTextPreview() {
    PasteListDialogPreviewOf(PasteListState(text = "Rice 2 kg\nCurd\nOil", previewing = false))
}

@Preview(widthDp = 360, heightDp = 640, fontScale = 1.3f)
@Preview(widthDp = 360, heightDp = 640, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PastePreviewPreview() {
    PasteListDialogPreviewOf(PasteListState(text = "", previewing = true, rows = PreviewRows, selected = setOf(0, 1)))
}

/** A list shared into the app before the family's list has come from the sheet: the button waits. */
@Preview(widthDp = 360, heightDp = 640, fontScale = 1.3f)
@Composable
private fun PastePreviewWaitingPreview() {
    PasteListDialogPreviewOf(
        PasteListState(text = "", previewing = true, rows = PreviewRows.take(2), selected = setOf(0, 1), waitingForList = true),
    )
}
