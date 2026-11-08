package com.grocerysync.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.grocerysync.app.data.Amount
import com.grocerysync.app.data.GroceryItem
import com.grocerysync.app.data.InputValidation
import com.grocerysync.app.data.ItemKeys
import com.grocerysync.app.data.ItemValue
import com.grocerysync.app.data.ShopSection
import com.grocerysync.app.data.Units
import com.grocerysync.app.data.ValueType
import com.grocerysync.app.data.convertTo
import com.grocerysync.app.data.displayText
import com.grocerysync.app.data.stepped
import com.grocerysync.app.text.Texts
import com.grocerysync.app.ui.theme.GrocerySyncTheme
import com.grocerysync.app.ui.theme.quantityStepperColors
import com.grocerysync.app.ui.theme.selectorColors
import com.grocerysync.app.ui.theme.warningFieldColors
import kotlinx.coroutines.delay
import kotlin.math.min

/** Height of the fade at a scrolled-off edge of the dialog body (less when less is hidden). */
private val ScrollHintFadeHeight = 24.dp

/**
 * Add / edit dialog. [item] null = add mode (name autofocused, value starts as "not needed",
 * type = [defaultType]). The draft survives rotation. [defaultNeeded]: add mode starts at needed instead
 * (yes, or 1 of the unit to be chosen), for Simple mode, whose list shows only To buy: there, a new item
 * saved as it starts would otherwise be out of sight at once.
 *
 * Every field needs a value before Save is enabled ([canSaveItem]): a quantity needs a unit, and
 * there is no default one. A new quantity, a yes / no item switched to a quantity and a quantity from
 * before units start with no unit, and the unit picker says [UNIT_PROMPT] until one is chosen.
 *
 * While the user has not touched the name or the value, they follow changes made on the other
 * phone; once touched, the user's draft stays and a hint says the item changed meanwhile. [onSave]
 * receives the value the draft was based on, so an untouched value never overwrites a newer one.
 * [removedElsewhere]: [item] is the last known state of an item that was renamed or removed on
 * another phone; saving adds it again.
 *
 * [existingNames]: every item's name by key, kept up to date while the dialog is open. A name another
 * item already has (capitals and extra spaces don't count) is an error and Save is disabled, so an item
 * is never overwritten by adding or renaming; re-spelling the edited item itself is fine. In add mode a
 * button under the name calls [onOpenExisting] with that item's key, to edit it instead. A new name that the
 * item dictionary knows as another name of an item on the list ("Curd" while "Dahi" is there) only gets a
 * warning ([similarItemName]) and Save stays enabled; in add mode it offers the same button.
 * [initialName]: add mode only, the name the field starts with (e.g. the All items search); when given,
 * the name field is not autofocused.
 *
 * [shopSection]: the item's shop section on this phone for a name (the one chosen for it, else the dictionary's
 * for the name typed); null shows no "Shop section" picker (add mode). A section picked there is passed to
 * [onShopSectionChange] with the name being saved, just before [onSave]; it is this phone's choice, not synced.
 *
 * Its texts are in the language shown (Texts.current: ItemTexts and CommonTexts). Every label wraps rather than
 * being cut when it is long (Hindi) or the font is large, and the body scrolls; only the narrow unit picker keeps
 * one line, with a smaller font for a longer text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemEditDialog(
    item: GroceryItem?,
    existingNames: Map<String, String>,
    defaultType: ValueType,
    onDismiss: () -> Unit,
    onSave: (name: String, value: ItemValue, baseValue: ItemValue?) -> Unit,
    onDelete: () -> Unit,
    removedElsewhere: Boolean = false,
    onOpenExisting: (key: String) -> Unit = {},
    initialName: String = "",
    shopSection: ((name: String) -> ShopSection)? = null,
    onShopSectionChange: (name: String, section: ShopSection) -> Unit = { _, _ -> },
    defaultNeeded: Boolean = false,
) {
    val t = Texts.current
    val isAdd = item == null
    val itemQuantity = item?.value as? ItemValue.Quantity
    val startNeeded = isAdd && defaultNeeded
    var name by rememberSaveable { mutableStateOf(item?.name ?: initialName) }
    var nameTouched by rememberSaveable { mutableStateOf(false) }
    var type by rememberSaveable { mutableStateOf(item?.value?.type ?: defaultType) }
    var needed by rememberSaveable { mutableStateOf((item?.value as? ItemValue.BoolValue)?.value ?: startNeeded) }
    var amountText by rememberSaveable {
        mutableStateOf((itemQuantity?.amount ?: if (startNeeded) Amount.ONE else Amount.ZERO).format())
    }
    // The unit as stored; "" = none chosen yet (no default: the user picks one before saving).
    var unit by rememberSaveable { mutableStateOf(itemQuantity?.unitText.orEmpty()) }
    var valueTouched by rememberSaveable { mutableStateOf(false) }
    // The value the draft started from (the item's value when it was last followed); null when adding.
    var baseNeeded by rememberSaveable { mutableStateOf((item?.value as? ItemValue.BoolValue)?.value) }
    var baseAmount by rememberSaveable { mutableStateOf(itemQuantity?.amount?.thousandths) }
    var baseUnit by rememberSaveable { mutableStateOf(itemQuantity?.unitText) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    // "Other..." in the unit picker: the text typed for a custom unit; null while that dialog is closed.
    var customUnitDraft by rememberSaveable { mutableStateOf<String?>(null) }
    // The section picked under "Shop section"; null until the user picks one.
    var pickedSection by rememberSaveable { mutableStateOf<ShopSection?>(null) }

    val baseValue: ItemValue? = baseNeeded?.let { ItemValue.BoolValue(it) }
        ?: baseAmount?.let { ItemValue.Quantity(Amount(it), baseUnit.orEmpty()) }

    // Follow changes made elsewhere until the user edits the field themselves.
    val liveName = item?.name
    val liveValue = item?.value
    LaunchedEffect(liveName, removedElsewhere) {
        if (liveName != null && !removedElsewhere && !nameTouched) name = liveName
    }
    LaunchedEffect(liveValue, removedElsewhere) {
        if (liveValue == null || removedElsewhere || valueTouched || liveValue == baseValue) return@LaunchedEffect
        baseNeeded = (liveValue as? ItemValue.BoolValue)?.value
        baseAmount = (liveValue as? ItemValue.Quantity)?.amount?.thousandths
        baseUnit = (liveValue as? ItemValue.Quantity)?.unitText
        type = liveValue.type
        when (liveValue) {
            is ItemValue.BoolValue -> needed = liveValue.value
            is ItemValue.Quantity -> {
                amountText = liveValue.amount.format()
                unit = liveValue.unitText
            }
        }
    }

    val draftValue: ItemValue? = when (type) {
        ValueType.BOOLEAN -> ItemValue.BoolValue(needed)
        ValueType.QUANTITY -> InputValidation.parseAmount(amountText)?.let { ItemValue.Quantity(it, unit) }
    }
    val nameError = InputValidation.validateName(name)
    // The other item that already has this name (as stored), if any.
    val takenBy = if (nameError == null) nameTakenBy(name, item?.key, existingNames) else null
    // The same thing under another name ("Curd" while "Dahi" is on the list): a warning only.
    val similarTo = remember(name, item?.key, existingNames) { similarItemName(name, item?.key, existingNames) }
    val canSave = canSaveItem(name, item?.key, existingNames, type, amountText, unit)
    val changedElsewhere = !removedElsewhere && liveValue != null && baseValue != null && liveValue != baseValue

    fun switchType(newType: ValueType) {
        if (newType == type) return
        valueTouched = true
        // A half-typed amount counts as 0 when converting; yes becomes 1 of the unit shown.
        when (val converted = (draftValue ?: ItemValue.Quantity(Amount.ZERO, unit)).convertTo(newType, unit)) {
            is ItemValue.BoolValue -> needed = converted.value
            is ItemValue.Quantity -> amountText = converted.amount.format()
        }
        type = newType
    }

    val focusRequester = remember { FocusRequester() }
    // Only when the add dialog opens, not again when it is recreated after a rotation: that would
    // bring the keyboard back after the user closed it.
    var nameAutoFocused by rememberSaveable { mutableStateOf(false) }
    // A name filled in for the user (from the search) needs no keyboard: the whole dialog stays in view.
    if (isAdd && initialName.isEmpty() && !nameAutoFocused) {
        LaunchedEffect(Unit) {
            // The dialog window needs a moment to gain focus before the field can take it.
            delay(100)
            focusRequester.requestFocus()
            nameAutoFocused = true
        }
    }

    // A phone in landscape is too short for the stacked layout: widen the dialog, put "Track as"
    // next to the value and tighten the spacing (see DialogLayout).
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val windowSize = LocalWindowInfo.current.containerSize
    // The dialog window leaves out the system bars and the display cutout, which in landscape are
    // often at the sides.
    val sideInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val sideInsetsPx = sideInsets.getLeft(density, layoutDirection) + sideInsets.getRight(density, layoutDirection)
    val availableWidthDp = with(density) { (windowSize.width - sideInsetsPx).toDp().value.toInt() }
    val windowHeightDp = with(density) { windowSize.height.toDp().value.toInt() }
    val shortWindow = DialogLayout.isShortWindow(windowHeightDp)
    val spacing = DialogLayout.bodySpacing(shortWindow)
    val textMeasurer = rememberTextMeasurer()
    val segmentLabelStyle = MaterialTheme.typography.labelLarge
    // The segmented buttons' labels, in the language shown: their widths at the current font size decide
    // whether the dialog can put "Track as" next to the value (see DialogLayout.sideBySideColumns).
    val segmentLabels = listOf(t.common.yesNo, t.common.quantity, t.item.yes, t.item.no)
    val segmentLabelWidthDp = remember(textMeasurer, segmentLabelStyle, density, segmentLabels) {
        segmentLabels.maxOf { textMeasurer.measure(it, segmentLabelStyle).size.width } / density.density
    }
    val columns = DialogLayout.sideBySideColumns(
        availableWidthDp = availableWidthDp,
        windowHeightDp = windowHeightDp,
        segmentLabelWidthDp = segmentLabelWidthDp,
    )

    val typeSelector: @Composable () -> Unit = {
        FieldLabel(t.item.trackAs, spacing.labelGapDp.dp)
        // Next to the taller amount stepper, the buttons are centred on it.
        Box(
            modifier = if (columns != null && type == ValueType.QUANTITY) {
                Modifier.heightIn(min = OutlinedTextFieldDefaults.MinHeight)
            } else {
                Modifier
            },
            contentAlignment = Alignment.Center,
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = type == ValueType.BOOLEAN,
                    onClick = { switchType(ValueType.BOOLEAN) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    colors = selectorColors(),
                ) { Text(t.common.yesNo) }
                SegmentedButton(
                    selected = type == ValueType.QUANTITY,
                    onClick = { switchType(ValueType.QUANTITY) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    colors = selectorColors(),
                ) { Text(t.common.quantity) }
            }
        }
    }

    val valueEditor: @Composable () -> Unit = {
        when (type) {
            ValueType.BOOLEAN -> {
                FieldLabel(t.item.needToBuy, spacing.labelGapDp.dp)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = needed,
                        onClick = {
                            valueTouched = true
                            needed = true
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        colors = selectorColors(),
                    ) { Text(t.item.yes) }
                    SegmentedButton(
                        selected = !needed,
                        onClick = {
                            valueTouched = true
                            needed = false
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        colors = selectorColors(),
                    ) { Text(t.item.no) }
                }
            }
            ValueType.QUANTITY -> {
                FieldLabel(t.item.howMuchToBuy, spacing.labelGapDp.dp)
                AmountEditor(
                    amountText = amountText,
                    onAmountTextChange = {
                        valueTouched = true
                        amountText = it
                    },
                    unit = unit,
                    onUnitChange = {
                        valueTouched = true
                        unit = it
                    },
                    onOtherUnit = { customUnitDraft = if (unit.isNotEmpty() && !Units.isStandard(unit)) unit else "" },
                    // A short window has no room for the prompt's own line: the picker says it.
                    promptInPicker = shortWindow,
                )
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        // An explicit width rather than padding, so that the visible edge of the dialog is where a
        // tap starts to dismiss it, and no vertical margin: on a short window every dp counts.
        modifier = if (shortWindow) {
            Modifier.width(DialogLayout.widenedDialogWidthDp(availableWidthDp).dp)
        } else {
            Modifier
        },
        properties = DialogProperties(usePlatformDefaultWidth = !shortWindow),
        title = { Text(if (isAdd) t.common.addItem else t.item.editItemTitle) },
        text = {
            ScrollableDialogBody {
                val noticeGap = spacing.noticeGapDp.dp
                when {
                    removedElsewhere -> Notice(t.item.removedElsewhere, noticeGap)
                    changedElsewhere -> Notice(changedElsewhereText(liveValue), noticeGap)
                }
                val nameMessage = when {
                    nameError != null && name.isNotEmpty() -> nameError
                    takenBy != null -> if (isAdd) alreadyOnListText(takenBy) else anotherItemCalledText(takenBy)
                    else -> null
                }
                // Never together with nameMessage (see similarItemName); not an error, so Save stays enabled.
                val similarMessage = similarTo?.let(::similarItemText)
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        nameTouched = true
                        name = it
                    },
                    label = { Text(t.item.nameLabel) },
                    placeholder = { Text(t.item.namePlaceholder) },
                    singleLine = true,
                    isError = (nameError != null && name.isNotEmpty()) || takenBy != null,
                    // The Similar warning colours the field in marigold (tertiary), as an error colours it red.
                    colors = if (nameMessage == null && similarMessage != null) {
                        warningFieldColors()
                    } else {
                        OutlinedTextFieldDefaults.colors()
                    },
                    supportingText = when {
                        nameMessage != null -> {
                            { Text(nameMessage) }
                        }
                        // In the warning colour (warningFieldColors), like Settings' URL warnings.
                        similarMessage != null -> {
                            { Text(similarMessage) }
                        }
                        else -> null
                    },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        // TalkBack says why (the name is taken, or too long) instead of Material's "Invalid
                        // input"; outermost on the field's node, so it replaces that default.
                        .semantics { nameMessage?.let { error(it) } },
                )
                if (isAdd && takenBy != null) {
                    // Edit the item that is already there instead (this dialog closes).
                    TextButton(onClick = { onOpenExisting(ItemKeys.normalize(name)) }) { Text(openItemText(takenBy)) }
                } else if (isAdd && similarTo != null) {
                    // Or edit the item it is similar to (this dialog closes); Save adds the new one anyway.
                    TextButton(onClick = { onOpenExisting(ItemKeys.normalize(similarTo)) }) { Text(openItemText(similarTo)) }
                }

                Spacer(Modifier.height(spacing.sectionGapDp.dp))
                if (columns != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(DialogLayout.COLUMN_GAP_DP.dp),
                    ) {
                        Column(Modifier.width(columns.trackAsWidthDp.dp)) { typeSelector() }
                        Column(Modifier.weight(1f)) { valueEditor() }
                    }
                } else {
                    typeSelector()
                    Spacer(Modifier.height(spacing.sectionGapDp.dp))
                    valueEditor()
                }
                if (shopSection != null) {
                    Spacer(Modifier.height(spacing.sectionGapDp.dp))
                    ShopSectionPicker(
                        section = pickedSection ?: shopSection(name.trim()),
                        onSectionChange = { pickedSection = it },
                        labelGap = spacing.labelGapDp.dp,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (canSave) {
                        draftValue?.let {
                            pickedSection?.let { section -> onShopSectionChange(name.trim(), section) }
                            onSave(name.trim(), it, baseValue)
                        }
                    }
                },
                enabled = canSave,
            ) { Text(t.common.save) }
        },
        dismissButton = {
            Row {
                if (!isAdd && !removedElsewhere) {
                    TextButton(
                        onClick = { confirmDelete = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text(t.common.delete) }
                }
                TextButton(onClick = onDismiss) { Text(t.common.cancel) }
            }
        },
    )

    customUnitDraft?.let { draft ->
        CustomUnitDialog(
            text = draft,
            onTextChange = { customUnitDraft = it },
            onDismiss = { customUnitDraft = null },
            onDone = { typed ->
                customUnitDraft = null
                valueTouched = true
                unit = Units.normalize(typed)
            },
        )
    }

    if (confirmDelete && item != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(t.item.deleteQuestion(item.name)) },
            text = { Text(t.item.deleteExplanation) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(t.common.delete) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(t.common.cancel) }
            },
        )
    }
}

/**
 * Scrolling dialog body that shows when part of it is hidden: a divider and a short fade at each
 * edge that has more content beyond it (Material's pattern for scrolling dialog content). The
 * hints are drawn over the content, so they never change its size, and they take no touches.
 */
@Composable
private fun ScrollableDialogBody(content: @Composable ColumnScope.() -> Unit) {
    val scrollState = rememberScrollState()
    // Same color as the dialog surface, so the content fades out towards the hidden part.
    val surface = AlertDialogDefaults.containerColor
    val dividerColor = DividerDefaults.color
    Column(
        modifier = Modifier
            .drawWithContent {
                drawContent()
                drawScrollEdgeHints(scrollState, surface, dividerColor)
            }
            .verticalScroll(scrollState),
        content = content,
    )
}

/**
 * The edge hints of [ScrollableDialogBody]. A fade is never taller than the part hidden beyond its
 * edge, so a control that is only a few pixels short of fitting is not washed out. Reading the
 * scroll position here, in the draw phase, means that scrolling only redraws.
 */
private fun DrawScope.drawScrollEdgeHints(scrollState: ScrollState, surface: Color, dividerColor: Color) {
    val hiddenAbove = scrollState.value.toFloat()
    val hiddenBelow = (scrollState.maxValue - scrollState.value).toFloat()
    val maxFade = ScrollHintFadeHeight.toPx()
    // Whole pixels, so the divider covers what is under it completely.
    val line = DividerDefaults.Thickness.roundToPx().coerceAtLeast(1).toFloat()
    if (hiddenAbove > 0f) {
        val fade = min(maxFade, hiddenAbove)
        drawRect(
            brush = Brush.verticalGradient(listOf(surface, Color.Transparent), startY = 0f, endY = fade),
            size = Size(size.width, fade),
        )
        drawRect(color = dividerColor, size = Size(size.width, line))
    }
    if (hiddenBelow > 0f) {
        val fade = min(maxFade, hiddenBelow)
        val fadeTop = size.height - fade
        drawRect(
            brush = Brush.verticalGradient(listOf(Color.Transparent, surface), startY = fadeTop, endY = size.height),
            topLeft = Offset(0f, fadeTop),
            size = Size(size.width, fade),
        )
        drawRect(color = dividerColor, topLeft = Offset(0f, size.height - line), size = Size(size.width, line))
    }
}

@Composable
private fun Notice(text: String, bottomPadding: Dp) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.padding(bottom = bottomPadding),
    )
}

/**
 * The notice when another phone changed the item's value to [value] while the user changed it too: Changed on
 * another phone to 2 kg (or "needed", "not needed"). Save to use your value instead.
 */
internal fun changedElsewhereText(value: ItemValue): String {
    val t = Texts.current
    val described = when (value) {
        is ItemValue.BoolValue -> if (value.value) t.common.needed else t.common.notNeeded
        is ItemValue.Quantity -> value.displayText()
    }
    return t.item.changedElsewhere(described)
}

@Composable
private fun FieldLabel(text: String, bottomPadding: Dp) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = bottomPadding),
    )
}

/** "0.5 kg", "100 g", "1": what one tap on - or + changes. */
internal fun stepDescription(unit: String): String {
    val step = Units.step(unit).format()
    return if (unit.isEmpty()) step else "$step $unit"
}

/**
 * Amount field between - and + (steps fit the unit: 0.5 kg, 100 g, 1 pcs, ...), then the unit picker.
 * The picker goes on the next line when the stepper and it don't fit side by side (narrow phones).
 * While no unit is chosen, [UNIT_PROMPT] shows on a line below, or in the picker itself when
 * [promptInPicker] (see [unitPickerText]).
 */
@Composable
private fun AmountEditor(
    amountText: String,
    onAmountTextChange: (String) -> Unit,
    unit: String,
    onUnitChange: (String) -> Unit,
    onOtherUnit: () -> Unit,
    promptInPicker: Boolean,
) {
    val t = Texts.current
    val amount = InputValidation.parseAmount(amountText)
    val step = stepDescription(unit)

    fun step(up: Boolean) {
        onAmountTextChange(ItemValue.Quantity(amount ?: Amount.ZERO, unit).stepped(up).amount.format())
    }

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DialogLayout.UNIT_GAP_DP.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(
                onClick = { step(up = false) },
                enabled = (amount ?: Amount.ZERO).isPositive,
                colors = quantityStepperColors(),
                modifier = Modifier.semantics { contentDescription = t.item.stepLess(step) },
            ) {
                Text("−", style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics { })
            }
            AmountField(
                text = amountText,
                onTextChange = onAmountTextChange,
                isError = amount == null,
                modifier = Modifier.width(DialogLayout.AMOUNT_FIELD_WIDTH_DP.dp),
            )
            FilledTonalIconButton(
                onClick = { step(up = true) },
                enabled = (amount ?: Amount.ZERO) < Amount.MAX,
                colors = quantityStepperColors(),
                modifier = Modifier.semantics { contentDescription = t.item.stepMore(step) },
            ) {
                Text("+", style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics { })
            }
        }
        UnitPicker(unit = unit, onUnitChange = onUnitChange, onOther = onOtherUnit, promptInPicker = promptInPicker)
    }
    if (amount == null) {
        Text(
            text = InputValidation.ERROR_AMOUNT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            textAlign = TextAlign.Center,
        )
    }
    if (showsUnitPromptLine(unit, promptInPicker)) {
        Text(
            text = UNIT_PROMPT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                // TalkBack hears it as the unit picker's state, so it isn't read twice.
                .clearAndSetSemantics { },
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The amount: decimal keyboard, ',' or '.' as separator, up to 3 decimals. An outlined field with less
 * inner padding than Material's, so "0.125" or "999999" fit in its narrow width.
 */
@Composable
private fun AmountField(text: String, onTextChange: (String) -> Unit, isError: Boolean, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = OutlinedTextFieldDefaults.colors()
    val textColor = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    // The selection tells "250" pasted over the selected default "0" from a digit typed beside it.
    var field by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    // Changed from outside (- / +, a change from the other phone): shown with the cursor at the end.
    val value = if (field.text == text) field else TextFieldValue(text, TextRange(text.length))
    BasicTextField(
        value = value,
        onValueChange = { input ->
            val next = normalizeAmountInput(
                previous = value.text,
                input = input.text,
                previousSelection = value.selection.min until value.selection.max,
            )
            field = when {
                next == input.text -> input
                next.length == input.text.length -> TextFieldValue(next, input.selection)
                else -> TextFieldValue(next, TextRange(next.length))
            }
            if (next != text) onTextChange(next)
        },
        modifier = modifier
            .heightIn(min = OutlinedTextFieldDefaults.MinHeight)
            .semantics { contentDescription = Texts.current.common.amount },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleMedium.copy(textAlign = TextAlign.Center, color = textColor),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        cursorBrush = SolidColor(if (isError) colors.errorCursorColor else colors.cursorColor),
        interactionSource = interactionSource,
        decorationBox = { innerTextField ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value.text,
                innerTextField = innerTextField,
                enabled = true,
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                interactionSource = interactionSource,
                isError = isError,
                colors = colors,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 16.dp),
                container = {
                    OutlinedTextFieldDefaults.Container(
                        enabled = true,
                        isError = isError,
                        interactionSource = interactionSource,
                        colors = colors,
                    )
                },
            )
        },
    )
}

/**
 * The unit: a compact dropdown with the standard units and "Other..." (a custom unit typed in a small
 * dialog). Looks like an outlined field, as tall as the amount field next to it. There is no "No unit"
 * choice: while [unit] is empty the picker is outlined in the primary colour and TalkBack hears
 * [UNIT_PROMPT] as its state, which is also why Save is disabled. Its text: [unitPickerText] (the prompt in
 * the primary colour when [promptInPicker]).
 */
@Composable
private fun UnitPicker(unit: String, onUnitChange: (String) -> Unit, onOther: () -> Unit, promptInPicker: Boolean) {
    val t = Texts.current
    var expanded by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val isCustom = unit.isNotEmpty() && !Units.isStandard(unit)
    val missing = isUnitMissing(ValueType.QUANTITY, unit)
    Box {
        Surface(
            shape = OutlinedTextFieldDefaults.shape,
            border = BorderStroke(if (missing) 2.dp else 1.dp, if (expanded || missing) colors.primary else colors.outline),
            color = Color.Transparent,
            modifier = Modifier
                .width(DialogLayout.UNIT_FIELD_WIDTH_DP.dp)
                .height(OutlinedTextFieldDefaults.MinHeight)
                .clip(OutlinedTextFieldDefaults.shape)
                .clickable(onClickLabel = t.item.chooseUnit) { expanded = true }
                .semantics {
                    role = Role.DropdownList
                    if (missing) {
                        contentDescription = t.common.unit
                        stateDescription = t.item.unitPromptToSave
                    } else {
                        contentDescription = t.item.unitChosen(unit)
                    }
                },
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // A longer custom unit ("bunches", "bottles") gets a smaller font rather than being cut.
                BasicText(
                    text = unitPickerText(unit, promptInPicker),
                    style = MaterialTheme.typography.bodyLarge,
                    color = {
                        when {
                            !missing -> colors.onSurface
                            promptInPicker -> colors.primary
                            else -> colors.onSurfaceVariant
                        }
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = MaterialTheme.typography.bodyLarge.fontSize),
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(24.dp))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            fun choose(choice: String) {
                expanded = false
                onUnitChange(choice)
            }
            if (isCustom) UnitMenuItem(unit, selected = true) { choose(unit) }
            Units.STANDARD.forEach { standard -> UnitMenuItem(standard, selected = standard == unit) { choose(standard) } }
            HorizontalDivider()
            UnitMenuItem(t.item.otherUnit, selected = false) {
                expanded = false
                onOther()
            }
        }
    }
}

@Composable
private fun UnitMenuItem(text: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        onClick = onClick,
        trailingIcon = if (selected) {
            { Icon(Icons.Default.Check, contentDescription = Texts.current.item.selected) }
        } else {
            null
        },
    )
}

/** "Other..." in the unit picker: a short text field for a custom unit (bunch, box, bottle, ...). */
@Composable
private fun CustomUnitDialog(text: String, onTextChange: (String) -> Unit, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val t = Texts.current
    val focusRequester = remember { FocusRequester() }
    val canUse = Units.normalize(text).isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t.item.otherUnitTitle) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { input -> onTextChange(input.replace('\n', ' ').replace('\r', ' ').take(Units.MAX_LENGTH)) },
                label = { Text(t.common.unit) },
                placeholder = { Text(t.item.otherUnitPlaceholder) },
                singleLine = true,
                supportingText = { Text("${text.length} / ${Units.MAX_LENGTH}") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canUse) onDone(text) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        },
        confirmButton = {
            TextButton(onClick = { onDone(text) }, enabled = canUse) { Text(t.item.useUnit) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t.common.cancel) }
        },
    )
    LaunchedEffect(Unit) {
        // The dialog window needs a moment to gain focus before the field can take it.
        delay(100)
        focusRequester.requestFocus()
    }
}

@Preview
@Composable
private fun ItemEditDialogPreview() {
    GrocerySyncTheme(dynamicColor = false) {
        ItemEditDialog(
            item = GroceryItem(
                key = "rice",
                name = "Rice",
                value = ItemValue.Quantity(Amount(1_500L), Units.KG),
                updatedAt = 0L,
                updatedBy = "Home",
                pendingSync = false,
            ),
            existingNames = mapOf("rice" to "Rice", "bread" to "Bread"),
            defaultType = ValueType.BOOLEAN,
            onDismiss = {},
            onSave = { _, _, _ -> },
            onDelete = {},
            shopSection = { ShopSection.ATTA_RICE_DAL },
        )
    }
}

/** The add dialog with a name another item already has: the error, Save disabled and Open "Rice". */
@Preview
@Composable
private fun ItemEditDialogDuplicatePreview() {
    GrocerySyncTheme(dynamicColor = false) {
        ItemEditDialog(
            item = null,
            existingNames = mapOf("rice" to "Rice", "bread" to "Bread"),
            defaultType = ValueType.BOOLEAN,
            onDismiss = {},
            onSave = { _, _, _ -> },
            onDelete = {},
            initialName = "rice",
        )
    }
}
