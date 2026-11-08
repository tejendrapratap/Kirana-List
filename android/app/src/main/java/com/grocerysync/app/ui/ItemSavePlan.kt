package com.grocerysync.app.ui

import com.grocerysync.app.data.Amount
import com.grocerysync.app.data.GroceryItem
import com.grocerysync.app.data.InputValidation
import com.grocerysync.app.data.ItemKeys
import com.grocerysync.app.data.ItemValue
import com.grocerysync.app.data.Units
import com.grocerysync.app.data.ValueType
import com.grocerysync.app.text.Texts

/** What saving the item dialog has to send. */
sealed interface ItemSavePlan {
    data object Nothing : ItemSavePlan

    /** One upsert: a new item, a new spelling of the same item and/or a new value. */
    data class Upsert(val name: String, val value: ItemValue) : ItemSavePlan

    /** Rename to a different item key (carries the current value), then optionally set [value]. */
    data class Rename(val key: String, val newName: String, val value: ItemValue?) : ItemSavePlan

    /**
     * Nothing is sent: the name belongs to another item already on the list ([existingName], as stored),
     * which saving would overwrite.
     */
    data class AlreadyOnList(val existingName: String) : ItemSavePlan
}

/**
 * Decides what saving the dialog sends.
 *
 * [current] = the item as it is now (null when adding, or when it was removed elsewhere while the
 * dialog was open), [openedValue] = its value when the dialog opened (null when adding), [draft] = the
 * value in the dialog. The other phone may have changed the item while the dialog was open, so the
 * draft only counts as an edit when it differs from what the dialog started with; otherwise the
 * item keeps its current value and only a new name is sent. Without that, Save would write the
 * value the dialog opened with over the newer change.
 *
 * [onList] = the whole list as it is now. A new item, a re-added one or a rename whose name is already
 * taken by another item (same key: capitals and extra spaces don't count) is [ItemSavePlan.AlreadyOnList]:
 * the dialog blocks that, and this catches an item that arrived from the other phone just before Save.
 */
fun planItemSave(
    current: GroceryItem?,
    openedValue: ItemValue?,
    name: String,
    draft: ItemValue,
    onList: List<GroceryItem>,
): ItemSavePlan {
    val newName = name.trim()
    val newKey = ItemKeys.normalize(newName)
    if (newKey != current?.key) {
        onList.firstOrNull { it.key == newKey }?.let { return ItemSavePlan.AlreadyOnList(it.name) }
    }
    if (current == null) return ItemSavePlan.Upsert(newName, draft)
    val valueEdited = openedValue == null || draft != openedValue
    val value = if (valueEdited) draft else current.value
    return if (newKey == current.key) {
        if (current.name != newName || current.value != value) ItemSavePlan.Upsert(newName, value) else ItemSavePlan.Nothing
    } else {
        ItemSavePlan.Rename(current.key, newName, value.takeIf { it != current.value })
    }
}

/**
 * The stored name of the other item that [typedName] would collide with (same key: capitals and extra
 * spaces don't count), or null. [editedKey] = the key of the item being edited (null when adding), so
 * re-spelling an item ("rice" to "Rice") is not a collision. [namesByKey] = every item's name by key.
 */
fun nameTakenBy(typedName: String, editedKey: String?, namesByKey: Map<String, String>): String? {
    val key = ItemKeys.normalize(typedName)
    if (key.isEmpty() || key == editedKey) return null
    return namesByKey[key]
}

/** How long a quoted item name gets in messages; the "Open" button quotes a shorter one. */
internal const val QUOTED_NAME_MAX = 40
internal const val OPEN_BUTTON_NAME_MAX = 24

/**
 * [name] for quoting in a message: on one line, and at most [maxLength] characters with "..." where it
 * was cut (never inside an emoji). Names typed into the sheet can be very long or span lines.
 */
internal fun quotedItemName(name: String, maxLength: Int = QUOTED_NAME_MAX): String {
    val line = ItemKeys.singleLine(ItemKeys.clean(name))
    if (line.length <= maxLength) return line
    var end = (maxLength - ELLIPSIS.length).coerceAtLeast(1)
    if (line[end - 1].isHighSurrogate()) end--
    return line.substring(0, end).trimEnd() + ELLIPSIS
}

private const val ELLIPSIS = "..."

/** The add dialog's error, and the snackbar when a save is refused: "Rice" is already on the list. */
fun alreadyOnListText(existingName: String): String = Texts.current.item.alreadyOnList(quotedItemName(existingName))

/** The edit dialog's error for a rename to another item's name: Another item is already called "Rice". */
fun anotherItemCalledText(existingName: String): String =
    Texts.current.item.anotherItemCalled(quotedItemName(existingName))

/** The add dialog's button that opens the item already on the list instead: Open "Rice". */
fun openItemText(existingName: String): String =
    Texts.current.item.openItem(quotedItemName(existingName, OPEN_BUTTON_NAME_MAX))

/**
 * The unit picker's prompt while a quantity has no unit; Save stays disabled until one is chosen. In the language
 * shown, like the other texts in this file.
 */
val UNIT_PROMPT: String get() = Texts.current.item.unitPrompt

/**
 * Whether the item dialog's Save button is enabled: every field needs a value (user decision for 1.2).
 * That is a valid [name] that no other item has ([editedKey] = the key of the item being edited, null
 * when adding; see [nameTakenBy]) and, for a quantity, a valid [amountText] and a [unit]. Track as and
 * Need to buy always have a value, so a yes / no item needs nothing more. There is no default unit: a
 * new quantity, a yes / no item switched to a quantity and a quantity from before units (no unit) all
 * need one chosen before they can be saved.
 */
fun canSaveItem(
    name: String,
    editedKey: String?,
    namesByKey: Map<String, String>,
    type: ValueType,
    amountText: String,
    unit: String,
): Boolean {
    if (InputValidation.validateName(name) != null || nameTakenBy(name, editedKey, namesByKey) != null) return false
    return when (type) {
        ValueType.BOOLEAN -> true
        ValueType.QUANTITY -> InputValidation.parseAmount(amountText) != null && !isUnitMissing(type, unit)
    }
}

/** True while a quantity has no unit chosen: the dialog then shows [UNIT_PROMPT] at the unit picker. */
fun isUnitMissing(type: ValueType, unit: String): Boolean = type == ValueType.QUANTITY && Units.normalize(unit).isEmpty()

/** In the unit picker while no unit is chosen and [UNIT_PROMPT] has a line of its own below it. */
internal val NO_UNIT_HINT: String get() = Texts.current.item.noUnitHint

/** In the unit picker (in the primary colour) while no unit is chosen and there is no room for [UNIT_PROMPT]'s line. */
internal val UNIT_PICKER_PROMPT: String get() = Texts.current.item.unitPickerPrompt

/**
 * What the unit picker shows for a quantity's [unit]: the unit; while none is chosen, [UNIT_PICKER_PROMPT] when
 * [promptInPicker] (a short window, a phone in landscape, where the line [UNIT_PROMPT] below would make the
 * dialog scroll and the line end up under its bottom edge), else [NO_UNIT_HINT] above that line.
 */
internal fun unitPickerText(unit: String, promptInPicker: Boolean): String = when {
    !isUnitMissing(ValueType.QUANTITY, unit) -> unit
    promptInPicker -> UNIT_PICKER_PROMPT
    else -> NO_UNIT_HINT
}

/** Whether the line [UNIT_PROMPT] shows below the amount: no unit is chosen and the picker doesn't say it itself. */
internal fun showsUnitPromptLine(unit: String, promptInPicker: Boolean): Boolean =
    isUnitMissing(ValueType.QUANTITY, unit) && !promptInPicker

/**
 * Text of the dialog's amount field after the user typed or pasted [input] over [previous]: digits and one
 * decimal separator (a ',' becomes '.'), at most [maxWholeDigits] digits before it and [maxDecimals] after
 * it, no leading zeros. A field showing just the default "0" is replaced by what is typed beside it,
 * wherever the cursor was ("0" then "3" gives "3", not "03" or "30"), while "0" then "." gives "0.".
 *
 * [previousSelection] is the selection in [previous] before the edit, as character indices (empty for a
 * cursor: `1 until 1` is after the "0"), or null when unknown. Text that replaced a selected "0" (a paste
 * over it) is taken as it is: "250" stays 250. When unknown, only a single digit next to the "0" replaces
 * it ("03" and "30" give 3); more digits at once ("250", "100") are kept as they are.
 */
fun normalizeAmountInput(
    previous: String,
    input: String,
    maxWholeDigits: Int = InputValidation.MAX_QUANTITY.toString().length,
    maxDecimals: Int = Amount.DECIMALS,
    previousSelection: IntRange? = null,
): String {
    val typed = if (previous == "0" && previousSelection != null) withoutDefaultZero(input, previousSelection) else input
    val kept = StringBuilder(typed.length)
    var hasSeparator = false
    for (c in typed) {
        if (c in '0'..'9') {
            kept.append(c)
        } else if ((c == '.' || c == ',') && !hasSeparator) {
            kept.append('.')
            hasSeparator = true
        }
    }
    var text = kept.toString()
    if (previous == "0" && previousSelection == null && !hasSeparator && text.length == 2) text = text.replaceFirst("0", "")
    val whole = text.substringBefore('.')
    val trimmed = whole.trimStart('0').ifEmpty { if (whole.isNotEmpty()) "0" else "" }.take(maxWholeDigits)
    return if (hasSeparator) trimmed + "." + text.substringAfter('.').take(maxDecimals) else trimmed
}

/**
 * [input] typed or pasted at the cursor of a field showing the default "0", without that "0" when digits
 * came along ("0" then "250" gives "250" wherever the cursor was); [input] as it is when the "0" was
 * selected (replaced by what came), no digit came ("0" then "." stays "0.") or what came after the "0"
 * starts with a separator ("0" then ",5" gives "0.5").
 */
private fun withoutDefaultZero(input: String, previousSelection: IntRange): String {
    if (!previousSelection.isEmpty()) return input
    val zeroFirst = previousSelection.first >= 1
    val inserted = when {
        !zeroFirst && input.endsWith('0') -> input.dropLast(1)
        zeroFirst && input.startsWith('0') -> input.drop(1)
        else -> return input
    }
    val keepZero = inserted.none { it in '0'..'9' } || (zeroFirst && (inserted.first() == '.' || inserted.first() == ','))
    return if (keepZero) input else inserted
}
