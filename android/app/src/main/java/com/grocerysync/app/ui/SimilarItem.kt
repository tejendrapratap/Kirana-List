package com.grocerysync.app.ui

import com.grocerysync.app.data.InputValidation
import com.grocerysync.app.data.ItemCatalog
import com.grocerysync.app.data.ItemKeys

/**
 * The item dialog's "Similar" warning: the stored name of an item already on the list that is the same thing
 * under another name in the built-in dictionary ([ItemCatalog.similarExisting]: "Curd" while "Dahi" is on the
 * list), or null. Unlike a name another item already has ([nameTakenBy]), it doesn't block Save: a family may
 * keep both on purpose, and the sheet's unique-name rule is only about the exact name.
 *
 * [editedKey] = the key of the item being edited (null when adding). Only a new name warns: the item's own
 * name (other capitals or spaces too) never does, and the item itself is never "similar" to its new name.
 * Null while [typedName] is not a valid name, or is the name of another item ([nameTakenBy] says so instead).
 * [namesByKey] = every item's name by key.
 */
internal fun similarItemName(typedName: String, editedKey: String?, namesByKey: Map<String, String>): String? {
    if (InputValidation.validateName(typedName) != null) return null
    val key = ItemKeys.normalize(typedName)
    if (key == editedKey || key in namesByKey) return null
    val others = if (editedKey == null) namesByKey.values else namesByKey.filterKeys { it != editedKey }.values
    return ItemCatalog.similarExisting(typedName, others)
}

/** The item dialog's warning under the name (Save stays enabled): Similar: "Dahi" is already on the list. */
internal fun similarItemText(existingName: String): String =
    "Similar: \"${quotedItemName(existingName)}\" is already on the list"
