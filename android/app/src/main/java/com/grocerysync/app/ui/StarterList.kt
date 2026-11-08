package com.grocerysync.app.ui

import com.grocerysync.app.data.CatalogEntry
import com.grocerysync.app.data.CatalogSet
import com.grocerysync.app.data.EditResult
import com.grocerysync.app.data.ItemCatalog
import com.grocerysync.app.data.ItemKeys
import com.grocerysync.app.data.ItemValue
import com.grocerysync.app.data.ShopSection
import com.grocerysync.app.data.ValueType

// Pure rules of "Add common items" (the starter list, StarterListDialog): which rows a set shows, which of them
// are ticked, their texts, and what adding them does (unit-tested in StarterListTest).

/**
 * One row of "Add common items": a [CatalogEntry] and, when the entry is already on the list, the name it is on
 * the list as ([onListAs]: the same name, or another name of the entry, e.g. "Curd" for Dahi). Such a row is
 * unticked and can't be ticked.
 */
internal data class StarterRow(val entry: CatalogEntry, val onListAs: String?) {
    val canAdd: Boolean get() = onListAs == null

    /** What the selection remembers the row by: the display name, which no other entry has. */
    val id: String get() = entry.displayName
}

/** The rows of one shop section, in the catalog's order (most common first). */
internal data class StarterGroup(val section: ShopSection, val rows: List<StarterRow>)

/**
 * The name each catalog entry is on the list as, from the list's names ([names], in the list's order): like
 * [ItemCatalog.nameOnList] (a name with the display name's item key first, else the first name that names the
 * entry), but every name of the list is looked up once, not once per entry of the set.
 */
internal class NamesOnList(names: Collection<String>) {
    private val byKey = HashMap<String, String>()
    private val byEntry = HashMap<CatalogEntry, String>()

    init {
        for (name in names) {
            byKey.putIfAbsent(ItemKeys.normalize(name), name)
            ItemCatalog.entryFor(name)?.let { byEntry.putIfAbsent(it, name) }
        }
    }

    /** The name [entry] is on the list as; null when it isn't on the list. */
    fun nameOf(entry: CatalogEntry): String? = byKey[ItemKeys.normalize(entry.displayName)] ?: byEntry[entry]
}

internal object StarterList {

    /** The sets offered as chips, in this order; the dialog opens on the first. */
    val SETS: List<CatalogSet> = CatalogSet.entries

    /** Shown with an entry added as yes / no, where a quantity shows its unit. */
    const val YES_NO_TEXT = "Yes / no"

    /** The dialog's title, and the empty list's button that opens it. */
    const val TITLE = "Add common items"

    /** Above the rows: where the items go. */
    const val HINT = "Ticked items are added to All items, not needed yet. Mark them as needed when you run out."

    /** Above the rows instead of [HINT] when every item of the set is on the list already. */
    const val ALL_ON_LIST_HINT = "Everything in this set is on your list already."

    /** The snackbar when it is opened before the phone is connected to the sheet. */
    const val NOT_CONNECTED_TEXT = "Connect to your sheet in Settings first, so items already on it aren't replaced."

    const val SELECT_ALL = "Select all"
    const val SELECT_NONE = "Select none"

    /**
     * The rows of [set] grouped by shop section, in walking order ([ShopSection]); inside a section in the
     * catalog's order. [namesOnList]: the name of every item on the list, in the list's order.
     */
    fun groups(set: CatalogSet, namesOnList: Collection<String>): List<StarterGroup> {
        val onList = NamesOnList(namesOnList)
        return ItemCatalog.entriesIn(set)
            .map { StarterRow(it, onList.nameOf(it)) }
            .groupBy { it.entry.section }
            .toSortedMap()
            .map { (section, rows) -> StarterGroup(section, rows) }
    }

    /** Whether [row] is ticked: it can be added and the user hasn't unticked it ([unticked] holds [StarterRow.id]s). */
    fun isTicked(row: StarterRow, unticked: Set<String>): Boolean = row.canAdd && row.id !in unticked

    /** The ticked entries of [groups], in the order shown: what "Add N items" adds. */
    fun toAdd(groups: List<StarterGroup>, unticked: Set<String>): List<CatalogEntry> =
        groups.flatMap { group -> group.rows.filter { isTicked(it, unticked) }.map { it.entry } }

    /** Whether some row of [groups] can be added (else Select all / none is hidden). */
    fun hasAddable(groups: List<StarterGroup>): Boolean = groups.any { group -> group.rows.any { it.canAdd } }

    /** Whether every row of [groups] that can be added is ticked: the control then offers [SELECT_NONE]. */
    fun allTicked(groups: List<StarterGroup>, unticked: Set<String>): Boolean =
        groups.all { group -> group.rows.all { !it.canAdd || it.id !in unticked } }

    /**
     * [unticked] after Select all ([select] true) or Select none on [groups]: only the rows shown change, so what was
     * unticked in another set stays unticked there.
     */
    fun selectAll(groups: List<StarterGroup>, unticked: Set<String>, select: Boolean): Set<String> {
        val shown = groups.flatMap { group -> group.rows.filter { it.canAdd }.map { it.id } }
        return if (select) unticked - shown.toSet() else unticked + shown
    }

    /** [unticked] after the row [id] was ticked ([ticked] true) or unticked. */
    fun toggle(unticked: Set<String>, id: String, ticked: Boolean): Set<String> = if (ticked) unticked - id else unticked + id

    /** The small text under the name: the entry's other names, "Pyaaz · प्याज़". */
    fun otherNamesText(entry: CatalogEntry): String = entry.otherNames.joinToString(" · ")

    /** The small text under the name of a row whose entry is on the list: "On the list as Oil". */
    fun onListText(name: String): String = "On the list as ${quotedItemName(name)}"

    /** What the row says at its end: the unit the item is added with ("kg", "pack"), or [YES_NO_TEXT]. */
    fun unitText(entry: CatalogEntry): String = when (entry.type) {
        ValueType.BOOLEAN -> YES_NO_TEXT
        ValueType.QUANTITY -> entry.unit
    }

    /**
     * What TalkBack says for a row (with its checkbox state): the names separated by commas rather than the " · "
     * shown, then the unit or where it is on the list: "Onion, Pyaaz, प्याज़, kg", "Dahi, Curd, दही, yes or no",
     * "Oil, on the list as Tel".
     */
    fun rowDescription(row: StarterRow): String {
        val entry = row.entry
        if (row.onListAs != null) return "${entry.displayName}, on the list as ${quotedItemName(row.onListAs)}"
        val names = (listOf(entry.displayName) + entry.otherNames).joinToString(", ")
        val unit = when (entry.type) {
            ValueType.BOOLEAN -> "yes or no"
            ValueType.QUANTITY -> entry.unit
        }
        return "$names, $unit"
    }

    /** The confirm button: "Add 12 items", "Add 1 item"; "Add items" (disabled) while nothing is ticked. */
    fun addButtonText(count: Int): String = when (count) {
        0 -> "Add items"
        1 -> "Add 1 item"
        else -> "Add $count items"
    }

    /** The snackbar once they are added: "Added 12 items to All items". */
    fun addedText(count: Int): String = when (count) {
        0 -> "Nothing added: those items are on the list already"
        1 -> "Added 1 item to All items"
        else -> "Added $count items to All items"
    }

    /**
     * Adds [entries] one by one through [add], the repository's normal add (it refuses a name that is on the list,
     * and queues the item for the sheet like any new item): each with its display name and not needed
     * ([CatalogEntry.notNeededValue]: yes / no off, or 0 of its unit), so it goes to All items. [namesOnList] is
     * the list as it is now: an entry that is on it by now under any of its names (it may have come from the other
     * phone while the dialog was open) is skipped, so "Curd" from the other phone doesn't get a "Dahi" beside it.
     * Returns how many were added.
     */
    suspend fun addEntries(
        entries: List<CatalogEntry>,
        namesOnList: Collection<String>,
        add: suspend (name: String, value: ItemValue) -> EditResult,
    ): Int {
        val onList = NamesOnList(namesOnList)
        var added = 0
        for (entry in entries) {
            if (onList.nameOf(entry) != null) continue
            if (add(entry.displayName, entry.notNeededValue()) == EditResult.Saved) added++
        }
        return added
    }
}
