package com.grocerysync.app.ui

import com.grocerysync.app.data.GroceryItem
import com.grocerysync.app.data.ItemValue
import com.grocerysync.app.data.TickedEntry
import com.grocerysync.app.data.stepped
import com.grocerysync.app.text.Texts
import com.grocerysync.app.ui.components.spokenText

// The list screen's Undo snackbars (unit-tested in ListUndoTest): which - / + steps are one change, which changes
// tick an item off, and what each snackbar says.

/**
 * The snackbar event of one - / + of the item [key] ([name]) from [current] to [next]. [shown] is the step snackbar
 * on screen now (null when there is none). When it is the same item's and this step follows on from it (the item
 * still has the amount it set: no change from the other phone came in between), both are one change, and its Undo
 * goes back to the amount before the first step, and to the Ticked today entry the first step took away. Otherwise
 * the step is a change of its own, and [ticked] is the item's entry under Ticked today before it (null: none; see
 * [ListEvent.ValueChange.tickedEntry]).
 *
 * Steps that come back to where they started ("+" then "-") make a change whose [ListEvent.Stepped.before] is its
 * [ListEvent.Stepped.after]: there is nothing to undo then.
 */
internal fun stepEvent(
    shown: ListEvent.Stepped?,
    key: String,
    name: String,
    current: ItemValue.Quantity,
    next: ItemValue.Quantity,
    ticked: TickedEntry? = null,
): ListEvent.Stepped {
    if (shown != null && shown.key == key && shown.after == current) {
        return ListEvent.Stepped(key, name, shown.before, next, shown.tickedEntry)
    }
    return ListEvent.Stepped(key, name, current, next, ticked)
}

/**
 * The change that makes [item], which isn't needed, needed again ([ListViewModel.needAgain]): a yes / no item becomes
 * yes; a quantity gets the amount it had before this phone ticked it off today, as Put back gives it ([ticked], its
 * entry under Ticked today, if any, while the item is still as this phone's tick left it: [TickedEntry.isStillTicked],
 * [me] being this phone's name), else one step of its unit, as + gives it. The change carries that entry, as it takes
 * the item off Ticked today, so its Undo puts it back ([ListEvent.ValueChange.tickedEntry]).
 */
internal fun neededAgainChange(item: GroceryItem, ticked: TickedEntry?, me: String): ListEvent.ValueChange {
    val entry = ticked?.takeIf { it.key == item.key && it.isStillTicked(item, me) }
    return when (val value = item.value) {
        is ItemValue.BoolValue -> ListEvent.NeedChanged(item.key, item.name, needed = true, tickedEntry = entry)
        is ItemValue.Quantity -> {
            val before = (entry?.before as? ItemValue.Quantity)?.takeIf { it.isNeeded }
            ListEvent.Stepped(item.key, item.name, value, before ?: value.stepped(up = true), entry)
        }
    }
}

/**
 * The change took a needed item off To buy ("Got it", the Need switch turned off, - down to 0; for steps, counted
 * from before the first of them): the item goes under Ticked today, and the change's Undo takes it off again.
 */
internal val ListEvent.ValueChange.ticksOff: Boolean
    get() = before.isNeeded && !after.isNeeded

/**
 * What the Undo snackbar of this change says, in the language shown: "Got Rice", "Rice: 2 kg → 3 kg", "Dahi: needed",
 * "Rice is back on To buy".
 */
internal fun ListEvent.ValueChange.snackbarText(): String {
    val t = Texts.current.list
    return when (this) {
        is ListEvent.GotIt -> t.gotItSnackbar(name)
        is ListEvent.Stepped -> t.stepSnackbar(name, before.spokenText(), after.spokenText())
        is ListEvent.NeedChanged -> t.needSnackbar(name, needed)
        is ListEvent.PutBack -> t.putBackSnackbar(name)
    }
}

/** What the Undo snackbar of an Edit dialog save says: "Renamed Curd to Dahi", "Changed Rice", or both. */
internal fun ListEvent.Edited.snackbarText(): String {
    val t = Texts.current.list
    return when {
        renamed && after != before -> t.changedAndRenamedSnackbar(oldName, newName)
        renamed -> t.renamedSnackbar(oldName, newName)
        else -> t.changedSnackbar(newName)
    }
}
