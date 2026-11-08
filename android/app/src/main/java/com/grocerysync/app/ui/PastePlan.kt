package com.grocerysync.app.ui

import androidx.compose.runtime.Immutable
import com.grocerysync.app.data.Amount
import com.grocerysync.app.data.CatalogEntry
import com.grocerysync.app.data.GroceryItem
import com.grocerysync.app.data.ItemCatalog
import com.grocerysync.app.data.ItemKeys
import com.grocerysync.app.data.ItemValue
import com.grocerysync.app.data.LineProblem
import com.grocerysync.app.data.PasteParser
import com.grocerysync.app.data.PastedLine
import com.grocerysync.app.data.Units
import com.grocerysync.app.data.displayText
import java.util.Locale

/** What applying one line of a pasted list does ([planPaste]). */
sealed interface PasteAction {
    /** The key of the item it adds or changes; null when it changes nothing. */
    val itemKey: String?

    /** A new item, added as needed: yes / no for a line without an amount, else the amount (pcs without a unit). */
    data class Add(val name: String, val value: ItemValue) : PasteAction {
        override val itemKey: String get() = ItemKeys.normalize(name)
    }

    /**
     * The item [key] ([name] as stored) isn't needed and gets [value]. [typedName]: what the line called it when
     * that is another name ("Curd" for Dahi), else null. [expected]: its value now; when it has another value by
     * the time this is applied (changed on another phone), it is left as it is.
     */
    data class MarkNeeded(
        val key: String,
        val name: String,
        val typedName: String?,
        val expected: ItemValue,
        val value: ItemValue,
    ) : PasteAction {
        override val itemKey: String get() = key
    }

    /** [name] is needed already (yes / no, the same amount, or a line without an amount): nothing changes. */
    data class AlreadyNeeded(val name: String, val typedName: String?) : PasteAction {
        override val itemKey: String? get() = null
    }

    /** The quantity [name] is needed with another amount: [expected] becomes [value]. Unticked at first. */
    data class ChangeAmount(
        val key: String,
        val name: String,
        val typedName: String?,
        val expected: ItemValue.Quantity,
        val value: ItemValue.Quantity,
    ) : PasteAction {
        override val itemKey: String get() = key
    }

    /** The line is left out because of [reason]; [itemName] and [itemUnit] name the item for [SkipReason.UNIT_MISMATCH]. */
    data class Skipped(val reason: SkipReason, val itemName: String = "", val itemUnit: String = "") : PasteAction {
        override val itemKey: String? get() = null
    }
}

enum class SkipReason {
    /** [LineProblem.UNREADABLE]. */
    UNREADABLE,

    /** [LineProblem.ZERO_AMOUNT]. */
    ZERO_AMOUNT,

    /** [LineProblem.NAME_TOO_LONG]. */
    NAME_TOO_LONG,

    /** [LineProblem.HEADING]. */
    HEADING,

    /** The amount's unit isn't the item's (amounts are never converted). */
    UNIT_MISMATCH,

    /** An earlier line names the same item (or another name of it: "Curd" after "Dahi"). */
    REPEATED,
}

/** One row of the preview: line [index] of the paste ([line]: as read) and what applying it does. */
data class PasteRow(val index: Int, val line: String, val action: PasteAction) {
    /** Has a check box: adding an item, marking one needed or changing its amount. */
    val selectable: Boolean
        get() = action is PasteAction.Add || action is PasteAction.MarkNeeded || action is PasteAction.ChangeAmount

    /** Ticked until the user says otherwise; a new amount for an item that is needed already is not. */
    val selectedByDefault: Boolean
        get() = action is PasteAction.Add || action is PasteAction.MarkNeeded
}

/**
 * What each of [lines] ([PasteParser.parse]) does to the list [items] (as shown now):
 * - A name on the list (capitals and spaces don't count), or another name of the same item in the dictionary
 *   ([ItemCatalog]: "Curd" for Dahi), changes that item and never renames it. A yes / no item is marked needed
 *   and ignores an amount. A quantity takes the line's amount when its unit is the item's ([unitFits]; amounts
 *   are never converted): marked needed when it isn't, else unchanged for the same amount or a new amount
 *   ([PasteAction.ChangeAmount], unticked at first). A line without an amount marks a quantity needed with one
 *   unit, or one - / + step when that is more ([neededAmount]: 1 kg, 100 g, 1 pcs).
 * - Any other name is a new item ([PasteAction.Add]) named as typed, with a capital first letter. Without an
 *   amount it is yes / no, as it was on the phone that shared it, so a shared list comes back the same.
 * - A later line for an item an earlier line already took is skipped ([SkipReason.REPEATED]), so is every line
 *   the parser couldn't use.
 */
fun planPaste(lines: List<PastedLine>, items: List<GroceryItem>): List<PasteRow> {
    val byKey = HashMap<String, GroceryItem>(items.size * 2)
    for (item in items) byKey.putIfAbsent(item.key, item)
    // Like ItemCatalog.nameOnList: the item called by the entry's own name, else the first one with that entry.
    val byEntry = HashMap<CatalogEntry, GroceryItem>()
    for (item in items) {
        val entry = ItemCatalog.entryFor(item.name) ?: continue
        if (byEntry[entry] == null || item.key == ItemKeys.normalize(entry.displayName)) byEntry[entry] = item
    }
    val takenKeys = HashSet<String>()
    val takenEntries = HashSet<CatalogEntry>()
    return lines.mapIndexed { index, line ->
        val action = line.problem?.let { PasteAction.Skipped(it.skipReason()) } ?: run {
            val key = ItemKeys.normalize(line.name)
            val entry = ItemCatalog.entryFor(line.name)
            val existing = byKey[key] ?: entry?.let(byEntry::get)
            val target = existing?.key ?: key
            if (target in takenKeys || (existing == null && entry != null && entry in takenEntries)) {
                PasteAction.Skipped(SkipReason.REPEATED)
            } else {
                val planned = if (existing == null) newItem(line) else changeOf(existing, line)
                if (planned !is PasteAction.Skipped) {
                    takenKeys += target
                    entry?.let(takenEntries::add)
                }
                planned
            }
        }
        PasteRow(index, line.text, action)
    }
}

private fun LineProblem.skipReason(): SkipReason = when (this) {
    LineProblem.UNREADABLE -> SkipReason.UNREADABLE
    LineProblem.ZERO_AMOUNT -> SkipReason.ZERO_AMOUNT
    LineProblem.NAME_TOO_LONG -> SkipReason.NAME_TOO_LONG
    LineProblem.HEADING -> SkipReason.HEADING
}

private fun newItem(line: PastedLine): PasteAction {
    val value = line.amount?.let { ItemValue.Quantity(it, line.unit ?: Units.PCS) } ?: ItemValue.BoolValue(true)
    return PasteAction.Add(ItemSearch.suggestedName(line.name), value)
}

private fun changeOf(item: GroceryItem, line: PastedLine): PasteAction {
    val typedName = line.name.takeIf { ItemKeys.normalize(it) != item.key }
    return when (val value = item.value) {
        is ItemValue.BoolValue ->
            if (value.value) {
                PasteAction.AlreadyNeeded(item.name, typedName)
            } else {
                PasteAction.MarkNeeded(item.key, item.name, typedName, value, ItemValue.BoolValue(true))
            }
        is ItemValue.Quantity -> quantityChange(item, value, line, typedName)
    }
}

private fun quantityChange(item: GroceryItem, value: ItemValue.Quantity, line: PastedLine, typedName: String?): PasteAction {
    val unit = value.unitText
    val amount = line.amount
    if (amount == null) {
        return if (value.isNeeded) {
            PasteAction.AlreadyNeeded(item.name, typedName)
        } else {
            PasteAction.MarkNeeded(item.key, item.name, typedName, value, ItemValue.Quantity(neededAmount(unit), unit))
        }
    }
    if (!unitFits(unit, line.unit)) return PasteAction.Skipped(SkipReason.UNIT_MISMATCH, item.name, unit)
    // A quantity from before units has none: it takes the line's.
    val wanted = ItemValue.Quantity(amount, unit.ifEmpty { line.unit.orEmpty() })
    return when {
        !value.isNeeded -> PasteAction.MarkNeeded(item.key, item.name, typedName, value, wanted)
        value.amount == wanted.amount && unit == wanted.unitText -> PasteAction.AlreadyNeeded(item.name, typedName)
        else -> PasteAction.ChangeAmount(item.key, item.name, typedName, value, wanted)
    }
}

/** Units a bare number ("Rice 2", "Eggs 12") never means: weights, volumes and dozens. */
private val MEASURED_UNITS = setOf(Units.KG, Units.G, Units.L, Units.ML, Units.DOZEN)

/**
 * Whether an amount written with [lineUnit] (null: none, "Maggi 4") may be given to a quantity in [itemUnit]:
 * the same unit (capitals don't count); without a unit, any unit that counts things (pcs, pack, bunch, bottle,
 * ...), not kg, g, L, ml or dozen. A quantity from before units ("") takes any.
 */
internal fun unitFits(itemUnit: String, lineUnit: String?): Boolean = when {
    itemUnit.isEmpty() -> true
    lineUnit == null -> itemUnit !in MEASURED_UNITS
    else -> itemUnit.equals(lineUnit, ignoreCase = true)
}

/** What a quantity in [unit] that isn't needed is marked needed with: one unit, or one - / + step when that is more. */
internal fun neededAmount(unit: String): Amount = maxOf(Units.step(unit), Amount.ONE)

/** What applying a [PasteAction] does to the list as it is by then ([pasteEdit]). */
sealed interface PasteEdit {
    data class Add(val name: String, val value: ItemValue) : PasteEdit
    data class Update(val name: String, val value: ItemValue) : PasteEdit

    /** Nothing to change (the item is needed already, or the row changes nothing). */
    data object Unchanged : PasteEdit

    /** The item changed on another phone since the preview (added, removed, or another value): left as it is. */
    data object ChangedMeanwhile : PasteEdit
}

/**
 * The edit [action] makes now that the item under its key is [current] (null: none). An update goes ahead only
 * while the item still has the value the preview showed, so a paste never overwrites a newer change from the
 * other phone; an item marked needed there meanwhile is simply left as it is. A new item needs a free name.
 */
fun pasteEdit(action: PasteAction, current: GroceryItem?): PasteEdit = when (action) {
    is PasteAction.Add -> if (current == null) PasteEdit.Add(action.name, action.value) else PasteEdit.ChangedMeanwhile
    is PasteAction.MarkNeeded -> when {
        current == null -> PasteEdit.ChangedMeanwhile
        current.value == action.expected -> PasteEdit.Update(current.name, action.value)
        current.value.isNeeded -> PasteEdit.Unchanged
        else -> PasteEdit.ChangedMeanwhile
    }
    is PasteAction.ChangeAmount ->
        if (current != null && current.value == action.expected) {
            PasteEdit.Update(current.name, action.value)
        } else {
            PasteEdit.ChangedMeanwhile
        }
    is PasteAction.AlreadyNeeded, is PasteAction.Skipped -> PasteEdit.Unchanged
}

/**
 * The "Paste a list" dialog as kept in the list screen's SavedStateHandle: its [text], whether the preview is
 * shown, and the rows the user ticked (true) or unticked (false) by line index. One value, so a change (Next, a
 * shared text) never shows half done.
 */
@Immutable
data class PasteDraft(val text: String, val previewing: Boolean, val choices: Map<Int, Boolean> = emptyMap()) {

    /** "P+1,-3" (the step, then the choices) on the first line, then the text. */
    fun encode(): String = buildString {
        append(if (previewing) PREVIEW else TEXT)
        choices.entries.joinTo(this, ",") { (index, on) -> (if (on) "+" else "-") + index }
        append('\n')
        append(text)
    }

    companion object {
        private const val PREVIEW = 'P'
        private const val TEXT = 'T'

        /** What [encode] wrote; null for none (the dialog is closed). */
        fun decode(value: String?): PasteDraft? {
            if (value.isNullOrEmpty() || value.first() != PREVIEW && value.first() != TEXT) return null
            val meta = value.substringBefore('\n')
            val choices = meta.drop(1).split(',').mapNotNull { choice ->
                choice.drop(1).toIntOrNull()?.let { index -> index to (choice.first() == '+') }
            }.toMap()
            return PasteDraft(text = value.substringAfter('\n', ""), previewing = meta.first() == PREVIEW, choices = choices)
        }
    }
}

/**
 * What the "Paste a list" dialog shows: the [text], and while [previewing] a row per line ([planPaste]) with the
 * rows ticked ([selected], by line index).
 */
@Immutable
data class PasteListState(
    val text: String,
    val previewing: Boolean,
    val rows: List<PasteRow> = emptyList(),
    val selected: Set<Int> = emptySet(),
) {
    /** What the confirm button applies: the ticked rows' actions, in line order. */
    val chosen: List<PasteAction>
        get() = rows.filter { it.selectable && it.index in selected }.map { it.action }

    companion object {
        /** The dialog for [draft] on the list [items] (planned only while the preview is shown). */
        fun of(draft: PasteDraft, items: List<GroceryItem>): PasteListState {
            if (!draft.previewing) return PasteListState(draft.text, previewing = false)
            val rows = planPaste(PasteParser.parse(draft.text), items)
            val selected = rows.filter { it.selectable && (draft.choices[it.index] ?: it.selectedByDefault) }
                .mapTo(HashSet()) { it.index }
            return PasteListState(draft.text, previewing = true, rows = rows, selected = selected)
        }
    }
}

// ---- Texts ------------------------------------------------------------------------------------------

internal const val PASTE_LIST_TITLE = "Paste a list"

internal const val PASTE_HELP = "Copy a list from WhatsApp or a note, then tap Paste. One item per line, like \"Rice 2 kg\" or \"Dahi\"."

internal const val NOTHING_TO_PASTE = "Nothing to paste. Copy a list first."

/** Under the text field, and as the snackbar for a shared text, when a paste is too long to read. */
internal val PASTE_TOO_LONG_TEXT =
    "Too long: paste up to ${PasteParser.MAX_LINES} lines and ${"%,d".format(Locale.ROOT, PasteParser.MAX_CHARS)} characters."

/** The snackbar when a list is shared into the app before it is set up. */
internal const val SHARE_NOT_SET_UP_TEXT = "Set up Grocery Sync first, then share the list again."

/** Most characters the text field keeps: more than a paste may have, so it says it is too long. */
internal const val MAX_PASTE_FIELD_CHARS = 2 * PasteParser.MAX_CHARS

private fun itemCount(count: Int): String = if (count == 1) "1 item" else "$count items"

private fun amountSuffix(value: ItemValue): String = (value as? ItemValue.Quantity)?.let { " - ${it.displayText()}" }.orEmpty()

/** The row's text: Add Rice - 2 kg, Mark Dahi needed, Already needed: Oil, Change Rice 2 kg → 3 kg, Skipped: Rice 2. */
internal fun pasteRowText(row: PasteRow): String = when (val action = row.action) {
    is PasteAction.Add -> "Add ${quotedItemName(action.name)}${amountSuffix(action.value)}"
    is PasteAction.MarkNeeded -> "Mark ${quotedItemName(action.name)} needed${amountSuffix(action.value)}"
    is PasteAction.AlreadyNeeded -> "Already needed: ${quotedItemName(action.name)}"
    is PasteAction.ChangeAmount ->
        "Change ${quotedItemName(action.name)} ${action.expected.displayText()} → ${action.value.displayText()}"
    is PasteAction.Skipped -> "Skipped: ${quotedItemName(row.line)}"
}

/** The small text under it: "Curd → Dahi" for another name of the item, or why a line is skipped; else null. */
internal fun pasteRowDetail(row: PasteRow): String? = when (val action = row.action) {
    is PasteAction.Add -> null
    is PasteAction.MarkNeeded -> action.typedName?.let { otherNameText(it, action.name) }
    is PasteAction.AlreadyNeeded -> action.typedName?.let { otherNameText(it, action.name) }
    is PasteAction.ChangeAmount -> action.typedName?.let { otherNameText(it, action.name) }
    is PasteAction.Skipped -> when (action.reason) {
        SkipReason.UNREADABLE -> "Couldn't read"
        SkipReason.ZERO_AMOUNT -> "The amount is 0"
        SkipReason.NAME_TOO_LONG -> "The name is too long"
        SkipReason.HEADING -> "Looks like a heading"
        SkipReason.UNIT_MISMATCH -> "${quotedItemName(action.itemName)} is in ${action.itemUnit}"
        SkipReason.REPEATED -> "Listed twice"
    }
}

private fun otherNameText(typedName: String, name: String) = "${quotedItemName(typedName)} → ${quotedItemName(name)}"

/** The preview's first line: what the paste holds, e.g. "5 new, 3 to update, 1 already needed, 2 skipped". */
internal fun pasteSummaryText(rows: List<PasteRow>): String {
    if (rows.isEmpty()) return "Nothing to add from this text."
    val parts = listOf(
        rows.count { it.action is PasteAction.Add } to "new",
        rows.count { it.action is PasteAction.MarkNeeded || it.action is PasteAction.ChangeAmount } to "to update",
        rows.count { it.action is PasteAction.AlreadyNeeded } to "already needed",
        rows.count { it.action is PasteAction.Skipped } to "skipped",
    )
    return parts.filter { it.first > 0 }.joinToString(", ") { (count, what) -> "$count $what" }
}

/** The confirm button: "Add 5 items", "Update 3 items", "Apply 8 changes", or "Nothing to add" (disabled). */
internal fun pasteConfirmText(chosen: List<PasteAction>): String {
    val adds = chosen.count { it is PasteAction.Add }
    val updates = chosen.count { it is PasteAction.MarkNeeded || it is PasteAction.ChangeAmount }
    return when {
        adds > 0 && updates > 0 -> "Apply ${adds + updates} changes"
        adds > 0 -> "Add ${itemCount(adds)}"
        updates > 0 -> "Update ${itemCount(updates)}"
        else -> "Nothing to add"
    }
}

/** The snackbar once a paste is applied: "Added 5 items, updated 3", plus the rows left out ([PasteEdit.ChangedMeanwhile]). */
internal fun pasteResultText(added: Int, updated: Int, leftOut: Int): String {
    val done = when {
        added > 0 && updated > 0 -> "Added ${itemCount(added)}, updated $updated"
        added > 0 -> "Added ${itemCount(added)}"
        updated > 0 -> "Updated ${itemCount(updated)}"
        else -> null
    }
    val left = if (leftOut > 0) "$leftOut skipped: changed on another phone" else null
    return listOfNotNull(done, left).joinToString(". ").ifEmpty { "Nothing to change" }
}

/** The text field after Paste: the clipboard's [pasted] text, after what is there on a new line. */
internal fun withPastedText(current: String, pasted: String): String =
    (if (current.isBlank()) pasted else current.trimEnd() + "\n" + pasted).take(MAX_PASTE_FIELD_CHARS)

/** Under the text field: how many lines with text it has ("1 line", "12 lines"); "" for none. */
internal fun pasteLineCountText(text: String): String = when (val count = PasteParser.lineCount(text)) {
    0 -> ""
    1 -> "1 line"
    else -> "$count lines"
}
