package com.grocerysync.app.ui

import android.content.ClipData
import android.content.Context
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.grocerysync.app.data.UrlCheck
import com.grocerysync.app.sync.Protocol
import com.grocerysync.app.ui.theme.GrocerySyncTheme
import com.grocerysync.app.ui.theme.appBarColors
import kotlinx.coroutines.launch

/** Help under "Paste setup code". The app no longer makes setup messages, so it doesn't say who shared one. */
internal const val PASTE_SETUP_HELP = "Have a setup message? Copy it, then tap Paste setup code."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val versionLine = remember(context) { versionText(installedVersionName(context)) }

    // The ViewModel is activity-scoped, so forget unsaved edits when the user leaves this screen
    // (but not when the activity is merely recreated for a configuration change).
    val activity = LocalActivity.current
    DisposableEffect(viewModel, activity) {
        onDispose {
            if (activity?.isChangingConfigurations != true) viewModel.resetToSaved()
        }
    }

    // The token field is a password field for autofill (KeyboardType.Password), and Compose (1.11.4,
    // AndroidAutofillManager.onEndApplyChanges) commits the autofill session by itself once that field
    // leaves the screen, which can make a password manager offer to save the token. Cancelling the
    // session ends it without saving anything, so it is cancelled before every way out: leave() for the
    // arrow, system Back and Save; ON_STOP for Home, another app and rotation (the old screen is disposed
    // after onStop). Typing alone never starts a new session; focusing the field again does, and the
    // next way out cancels that one too.
    val autofillManager = LocalAutofillManager.current
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { autofillManager?.cancel() }
    val currentOnBack by rememberUpdatedState(onBack)
    val leave: () -> Unit = remember(viewModel, autofillManager) {
        {
            autofillManager?.cancel()
            viewModel.forgetTokenEntry()
            currentOnBack()
        }
    }
    BackHandler(onBack = leave)

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.events.collect { event ->
                when (event) {
                    SettingsEvent.Saved -> leave()
                    // setClipEntry(null) clears it (clearPrimaryClip on Android 9+, an empty clip before).
                    SettingsEvent.ClearClipboard -> launch {
                        try {
                            clipboard.setClipEntry(null)
                        } catch (e: SecurityException) {
                            // Not allowed to change the clipboard right now: nothing else to do.
                        }
                    }
                    is SettingsEvent.Message -> {
                        snackbarHostState.currentSnackbarData?.dismiss()
                        launch { snackbarHostState.showSnackbar(event.text, duration = SnackbarDuration.Short) }
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                colors = appBarColors(),
                navigationIcon = {
                    IconButton(onClick = leave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle("You")
            OutlinedTextField(
                value = viewModel.deviceName,
                onValueChange = viewModel::onDeviceNameChange,
                label = { Text("Your name") },
                placeholder = { Text(Build.MODEL.orEmpty().ifBlank { "Phone" }) },
                supportingText = { Text("Shown to the other person as \"Added by\" or \"Updated by\".") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            SectionTitle("Google Sheet")
            OutlinedButton(
                onClick = {
                    scope.launch {
                        viewModel.pasteSetupCode(readClipboardText(context, clipboard))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Paste setup code") }
            Text(
                text = PASTE_SETUP_HELP,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            WebAppUrlField(
                value = viewModel.webAppUrl,
                check = viewModel.urlCheck,
                onValueChange = viewModel::onWebAppUrlChange,
            )
            TokenField(
                value = viewModel.tokenInput,
                help = viewModel.tokenHelp,
                showLock = TokenEntry.usesHiddenToken(viewModel.tokenSource),
                onValueChange = viewModel::onTokenChange,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = viewModel::testConnection,
                    enabled = viewModel.connectionTest != ConnectionTest.Running,
                    modifier = Modifier.weight(1f),
                ) { Text("Test connection") }
                Button(
                    onClick = viewModel::save,
                    enabled = viewModel.canSave,
                    modifier = Modifier.weight(1f),
                ) { Text("Save") }
            }
            ConnectionTestResult(viewModel.connectionTest)

            HelpCard()
            if (versionLine != null) {
                Text(
                    text = versionLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(top = 4.dp)
            .semantics { heading() },
    )
}

@Composable
private fun WebAppUrlField(value: String, check: UrlCheck, onValueChange: (String) -> Unit) {
    val isBlank = value.isBlank()
    val (message, color) = when {
        isBlank -> "From Deploy > Manage deployments in Apps Script (ends in /exec)." to Color.Unspecified
        check is UrlCheck.Invalid -> check.message to Color.Unspecified
        check is UrlCheck.Warning -> check.message to MaterialTheme.colorScheme.tertiary
        else -> null to Color.Unspecified
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Web app URL") },
        placeholder = { Text("https://script.google.com/macros/s/.../exec") },
        singleLine = true,
        isError = !isBlank && check is UrlCheck.Invalid,
        supportingText = if (message != null) {
            { Text(message, color = color) }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The secret token can be typed or pasted here but never viewed, copied or shared (user decision, 1.2):
 * - [value] is only a token typed or pasted now. The saved token and a setup code's token are never put
 *   into the field, so they can't be revealed, selected, copied, or read by accessibility or autofill.
 *   [help] says which token Save will use; [showLock] marks that a hidden one is in use.
 * - No Show button: PasswordVisualTransformation always masks the text. Checked in the Compose foundation
 *   1.11.4 sources (BOM 2026.06.01): with exactly this transformation the field offers no Copy or Cut (text
 *   menu, keyboard shortcuts, accessibility actions), and the text menu's Translate / smart actions,
 *   accessibility and content capture only get the dots. Paste still works.
 * - KeyboardType.Password: keyboards don't learn or suggest it. It also marks the field as a password for
 *   autofill; SettingsScreen cancels the autofill session before leaving, so nothing offers to save it.
 */
@Composable
private fun TokenField(value: String, help: String, showLock: Boolean, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Secret token") },
        supportingText = { Text(help) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        trailingIcon = if (showLock && value.isEmpty()) {
            { Icon(Icons.Default.Lock, contentDescription = null) }
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun TokenFieldPreview() {
    GrocerySyncTheme(dynamicColor = false) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Saved token in use (field empty), a new one typed, and none yet.
            TokenField(value = "", help = TokenEntry.HELP_SAVED, showLock = true, onValueChange = {})
            TokenField(value = "new-token", help = TokenEntry.HELP_REPLACES_SAVED, showLock = true, onValueChange = {})
            TokenField(value = "", help = TokenEntry.HELP_NEW, showLock = false, onValueChange = {})
            versionText("1.2")?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ConnectionTestResult(test: ConnectionTest) {
    when (test) {
        ConnectionTest.Idle -> Unit
        ConnectionTest.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text("Testing connection...", style = MaterialTheme.typography.bodyMedium)
        }
        is ConnectionTest.Success -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ResultLine(
                icon = Icons.Default.Check,
                text = "Connected to ${test.spreadsheetName}",
                color = MaterialTheme.colorScheme.primary,
            )
            if (test.scriptOutdated) {
                ResultLine(
                    icon = Icons.Default.Warning,
                    text = Protocol.MSG_SCRIPT_OUTDATED + " Until then the list syncs, but changes wait on this phone.",
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        is ConnectionTest.Failure -> ResultLine(
            icon = Icons.Default.Warning,
            text = test.message,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun ResultLine(icon: ImageVector, text: String, color: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun HelpCard() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = if (expanded) "Collapse" else "Expand") {
                    expanded = !expanded
                }
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(
                text = "How to connect a Google Sheet",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HELP_STEPS.forEachIndexed { index, step ->
                    Row {
                        Text("${index + 1}.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(24.dp))
                        Text(step, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

private val HELP_STEPS = listOf(
    "Open your sheet > Extensions > Apps Script, paste Code.gs.",
    "Set SECRET_TOKEN to your own secret.",
    "Deploy > New deployment > Web app; Execute as: Me; Who has access: Anyone.",
    "Copy the Web app URL (ends in /exec) here.",
)

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
