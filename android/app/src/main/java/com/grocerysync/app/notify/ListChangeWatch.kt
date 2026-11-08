package com.grocerysync.app.notify

import com.grocerysync.app.data.GroceryItem
import com.grocerysync.app.data.ItemValue
import com.grocerysync.app.data.ListLogic

/**
 * An item someone else put on To buy, or whose amount they changed there, since the list was last seen on this
 * phone. [isNew]: it wasn't needed then, or wasn't on the list at all.
 */
data class ToBuyChange(val item: GroceryItem, val isNew: Boolean)

/**
 * What changed on To buy while the app was closed, worked out from two lists alone (no Android), so the background
 * check ([ChangeWatcher]) and its unit tests use the same rules:
 * - [seen] is To buy as the person last saw it: each needed item's key and value. Taken when the app goes to the
 *   background and after each check, and stored as it is ([SeenListStore]).
 * - [newOnToBuy] compares the list now with it.
 */
object ListChangeWatch {

    /**
     * To buy as [items] (the list the app shows) has it: the key of each needed item with its value, encoded with
     * the database's own helpers ([ListLogic.encodeValue], [ListLogic.encodeUnit]), so two values are the same
     * exactly when they read the same.
     */
    fun seen(items: List<GroceryItem>): Map<String, String> =
        items.filter { it.value.isNeeded }.associate { it.key to encode(it.value) }

    /**
     * The items of To buy in [items] that someone else put there since [seen]: needed now but not needed in [seen]
     * (or not there at all, which includes an item renamed since), and needed items whose amount (or unit, or type)
     * changed. In the order of [items]. Changes made on this phone don't count: an item waiting to be sent, or one
     * the sheet says [me] changed last ([isByOthers]).
     */
    fun newOnToBuy(seen: Map<String, String>, items: List<GroceryItem>, me: String): List<ToBuyChange> =
        items.mapNotNull { item ->
            if (!item.value.isNeeded || !isByOthers(item, me)) return@mapNotNull null
            when (seen[item.key]) {
                null -> ToBuyChange(item, isNew = true)
                encode(item.value) -> null
                else -> ToBuyChange(item, isNew = false)
            }
        }

    /**
     * True when someone else changed [item] last: compared with this phone's name [me] the way the list's
     * highlight of recent changes compares it (spaces around and capitals don't count). A row without a name
     * (from before the sheet kept one) is nobody's change.
     */
    fun isByOthers(item: GroceryItem, me: String): Boolean =
        !item.pendingSync &&
            item.updatedBy.isNotBlank() &&
            !item.updatedBy.trim().equals(me.trim(), ignoreCase = true)

    /** "quantity 2 kg", "boolean true": the type, the value column and the unit column as the database has them. */
    private fun encode(value: ItemValue): String =
        "${value.type.wire} ${ListLogic.encodeValue(value)} ${ListLogic.encodeUnit(value).orEmpty()}"
}
