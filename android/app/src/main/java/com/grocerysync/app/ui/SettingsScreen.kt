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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.grocerysync.app.data.SettingsStore
import com.grocerysync.app.data.UrlCheck
import com.grocerysync.app.notify.openNotificationSettings
import com.grocerysync.app.notify.rememberNotificationAccess
import com.grocerysync.app.notify.rememberNotificationPermissionRequest
import com.grocerysync.app.sync.Protocol
import com.grocerysync.app.text.AppLanguage
import com.grocerysync.app.text.AppTexts
import com.grocerysync.app.text.Texts
import com.grocerysync.app.text.systemLanguage
import com.grocerysync.app.text.update
import com.grocerysync.app.ui.theme.GrocerySyncTheme
import com.grocerysync.app.ui.theme.appBarColors
import kotlinx.coroutines.launch

/** The Web app URL field's example: a URL, the same in every language. */
private const val WEB_APP_URL_EXAMPLE = "https://script.google.com/macros/s/.../exec"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val t = Texts.current
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val phoneSettings by viewModel.phoneSettings.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val versionName = remember(context) { installedVersionName(context) }

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
                title = { Text(t.common.settings) },
                colors = appBarColors(),
                navigationIcon = {
                    IconButton(onClick = leave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t.settings.back)
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
            SectionTitle(t.settings.you)
            OutlinedTextField(
                value = viewModel.deviceName,
                onValueChange = viewModel::onDeviceNameChange,
                label = { Text(t.settings.yourName) },
                // The name the sheet gets when none is typed (in English in every language: the sheet keeps it).
                placeholder = { Text(Build.MODEL.orEmpty().ifBlank { SettingsStore.DEFAULT_DEVICE_NAME }) },
                supportingText = { Text(t.settings.yourNameHelp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            SectionTitle(t.settings.thisPhone)
            LanguageChoice(
                selected = phoneSettings.language,
                phoneTexts = Texts.of(AppLanguage.PHONE, systemLanguage(configuration)),
                onChoose = { language ->
                    if (language != phoneSettings.language) {
                        viewModel.setLanguage(language)
                        // Every screen reads Texts.current while it composes: recreating the activity shows the new
                        // language everywhere. Settings stays open, and what is typed here stays (the view model).
                        if (Texts.update(language, configuration)) activity?.recreate()
                    }
                },
            )
            SwitchRow(
                title = t.settings.simpleMode,
                detail = t.settings.simpleModeHelp,
                checked = phoneSettings.simpleMode,
                onCheckedChange = viewModel::setSimpleMode,
            )
            NotifyChangesSwitch(on = phoneSettings.notifyChanges, onChange = viewModel::setNotifyChanges)

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            SectionTitle(t.settings.googleSheet)
            OutlinedButton(
                onClick = {
                    scope.launch {
                        viewModel.pasteSetupCode(readClipboardText(context, clipboard))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(t.settings.pasteSetupCode) }
            Text(
                text = t.settings.pasteSetupHelp,
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
                ) { Text(t.settings.testConnection) }
                Button(
                    onClick = viewModel::save,
                    enabled = viewModel.canSave,
                    modifier = Modifier.weight(1f),
                ) { Text(t.common.save) }
            }
            ConnectionTestResult(viewModel.connectionTest, viewModel.urlCheck)

            HelpCard()
            val versionLine = versionText(versionName)
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

/**
 * Settings > This phone > Language: "Phone's language" (the default, with the language it means now under it),
 * "English" and "हिन्दी". Radio rows in a selectable group, so TalkBack reads each with its state and position
 * ("English, not selected, radio button"). The languages' names are always in their own script, marked with their
 * locale so that a screen reader speaks each in its language. [phoneTexts]: the texts "Phone's language" means now.
 */
@Composable
private fun LanguageChoice(selected: AppLanguage, phoneTexts: AppTexts, onChoose: (AppLanguage) -> Unit) {
    val t = Texts.current
    Column {
        Text(
            text = t.settings.language,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.selectableGroup()) {
            LanguageOption(
                label = AnnotatedString(t.settings.phonesLanguage),
                detail = ownName(phoneTexts),
                selected = selected == AppLanguage.PHONE,
                onClick = { onChoose(AppLanguage.PHONE) },
            )
            LanguageOption(
                label = ownName(AppTexts.English),
                selected = selected == AppLanguage.ENGLISH,
                onClick = { onChoose(AppLanguage.ENGLISH) },
            )
            LanguageOption(
                label = ownName(AppTexts.Hindi),
                selected = selected == AppLanguage.HINDI,
                onClick = { onChoose(AppLanguage.HINDI) },
            )
        }
    }
}

/** A language's name in its own script, marked as that language (for a screen reader's voice and the font). */
private fun ownName(texts: AppTexts): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(localeList = LocaleList(texts.language.id))) { append(texts.languageName) }
}

/** One choice of [LanguageChoice]: the whole row is the radio button (at least 48 dp tall). */
@Composable
private fun LanguageOption(
    label: AnnotatedString,
    selected: Boolean,
    detail: AnnotatedString? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun LanguageChoicePreview() {
    GrocerySyncTheme(dynamicColor = false) {
        Column(Modifier.padding(16.dp)) {
            LanguageChoice(selected = AppLanguage.PHONE, phoneTexts = AppTexts.English, onChoose = {})
        }
    }
}

/**
 * A switch of This phone with its [title] and a line under it ([detail]). The whole row is the switch (at least
 * 56 dp tall), so TalkBack reads the title, the line and the state together ("Simple mode, ..., switch, off").
 */
@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * This phone > "Notify me when someone adds to To buy" ([on]: the saved choice). Turning it on on Android 13+ without
 * the permission asks Android for it. Refused (now or before), the switch stays on and a line says the notifications
 * are blocked, with a button to Android's settings for them; that line goes once they are allowed (checked again each
 * time the screen comes back).
 */
@Composable
private fun NotifyChangesSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val access = rememberNotificationAccess()
    val askPermission = rememberNotificationPermissionRequest { access.refresh() }
    NotifyChangesSetting(
        on = on,
        blocked = on && !access.allowed,
        onChange = { turnOn ->
            onChange(turnOn)
            if (turnOn && access.mayAsk) askPermission()
        },
        onAllow = { openNotificationSettings(context) },
    )
}

/**
 * What [NotifyChangesSwitch] shows: the switch and, while [blocked], the line saying so and the "Allow in Android
 * settings" button ([onAllow]). Without state of its own, so a UI test can show it in either case.
 */
@Composable
internal fun NotifyChangesSetting(on: Boolean, blocked: Boolean, onChange: (Boolean) -> Unit, onAllow: () -> Unit) {
    val t = Texts.current.settings
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SwitchRow(title = t.notifyChanges, detail = t.notifyChangesHelp, checked = on, onCheckedChange = onChange)
        if (blocked) {
            ResultLine(
                icon = Icons.Default.Warning,
                text = t.notificationsBlocked,
                color = MaterialTheme.colorScheme.tertiary,
            )
            OutlinedButton(onClick = onAllow, modifier = Modifier.fillMaxWidth()) { Text(t.allowNotifications) }
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun ThisPhoneSwitchesPreview() {
    GrocerySyncTheme(dynamicColor = false) {
        Column(Modifier.padding(16.dp)) {
            val t = Texts.current.settings
            SwitchRow(title = t.simpleMode, detail = t.simpleModeHelp, checked = false, onCheckedChange = {})
            NotifyChangesSetting(on = true, blocked = true, onChange = {}, onAllow = {})
        }
    }
}

@Composable
private fun WebAppUrlField(value: String, check: UrlCheck, onValueChange: (String) -> Unit) {
    val t = Texts.current
    val isBlank = value.isBlank()
    val (message, color) = when {
        isBlank -> t.settings.webAppUrlHelp to Color.Unspecified
        check is UrlCheck.Invalid -> check.message to Color.Unspecified
        check is UrlCheck.Warning -> check.message to MaterialTheme.colorScheme.tertiary
        else -> null to Color.Unspecified
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(t.settings.webAppUrl) },
        placeholder = { Text(WEB_APP_URL_EXAMPLE) },
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
        label = { Text(Texts.current.settings.secretToken) },
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
            val t = Texts.current.settings
            TokenField(value = "", help = t.tokenSaved, showLock = true, onValueChange = {})
            TokenField(value = "new-token", help = t.tokenReplacesSaved, showLock = true, onValueChange = {})
            TokenField(value = "", help = t.tokenNew, showLock = false, onValueChange = {})
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

/** [urlCheck]: the Web app URL field's check now, which says what is wrong with an invalid URL. */
@Composable
private fun ConnectionTestResult(test: ConnectionTest, urlCheck: UrlCheck) {
    val t = Texts.current
    when (test) {
        ConnectionTest.Idle -> Unit
        ConnectionTest.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(t.settings.testingConnection, style = MaterialTheme.typography.bodyMedium)
        }
        is ConnectionTest.Success -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ResultLine(
                icon = Icons.Default.Check,
                text = t.settings.connectedTo(test.spreadsheetName),
                color = MaterialTheme.colorScheme.primary,
            )
            if (test.scriptOutdated) {
                ResultLine(
                    icon = Icons.Default.Warning,
                    text = scriptOutdatedText(t),
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        is ConnectionTest.Failure -> connectionFailureText(test, urlCheck, t)?.let { text ->
            ResultLine(
                icon = Icons.Default.Warning,
                text = text,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * What a failed Test connection says, in [texts]' language: the sync layer's message is translated here, where it
 * is shown. An invalid URL is described by [urlCheck], the field's check now (the result is cleared whenever the
 * URL changes, so that is the URL that was tried); null in the one case that can't happen, a URL that became valid.
 */
internal fun connectionFailureText(failure: ConnectionTest.Failure, urlCheck: UrlCheck, texts: AppTexts): String? =
    when (failure.reason) {
        ConnectionTest.Failure.Reason.INVALID_URL -> (urlCheck as? UrlCheck.Invalid)?.message
        ConnectionTest.Failure.Reason.NO_TOKEN -> texts.settings.enterTokenFirst
        ConnectionTest.Failure.Reason.NO_ANSWER -> texts.sync.message(failure.message)
    }

/** The warning under a working Test connection when the sheet's script is too old for units, in [texts]' language. */
internal fun scriptOutdatedText(texts: AppTexts): String =
    texts.settings.scriptOutdated(texts.sync.message(Protocol.MSG_SCRIPT_OUTDATED))

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
    val t = Texts.current.settings
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = if (expanded) t.collapse else t.expand) {
                    expanded = !expanded
                }
                .semantics { stateDescription = if (expanded) t.expanded else t.collapsed }
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(
                text = t.helpTitle,
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
                t.helpSteps.forEachIndexed { index, step ->
                    Row {
                        Text("${index + 1}.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(24.dp))
                        Text(step, style = MaterialTheme.typography.bodyMedium)
                    }
                }
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
