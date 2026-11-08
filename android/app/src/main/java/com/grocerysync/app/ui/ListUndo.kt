package com.grocerysync.app.ui

import com.grocerysync.app.data.ItemValue
import com.grocerysync.app.text.Texts
import com.grocerysync.app.ui.components.spokenText

// The list screen's Undo snackbars (unit-tested in ListUndoTest): which - / + steps are one change, which changes
// tick an item off, and what each snackbar says.

/**
 * The snackbar event of one - / + of the item [key] ([name]) from [current] to [next]. [shown] is the step snackbar
 * on screen now (null when there is none). When it is the same item's and this step follows on from it (the item
 * still has the amount it set: no change from the other phone came in between), both are one change, and its Undo
 * goes back to the amount before the first step. Otherwise the step is a change of its own.
 */
internal fun stepEvent(
    shown: ListEvent.Stepped?,
    key: String,
    name: String,
    current: ItemValue.Quantity,
    next: ItemValue.Quantity,
): ListEvent.Stepped {
    val before = if (shown != null && shown.key == key && shown.after == current) shown.before else current
    return ListEvent.Stepped(key, name, before, next)
}

/**
 * The change took a needed item off To buy ("Got it", the Need switch turned off, - down to 0; for steps, counted
 * from before the first of them): the item goes under Ticked today, and the change's Undo takes it off again.
 */
internal val ListEvent.ValueChange.ticksOff: Boolean
    get() = before.isNeeded && !after.isNeeded

/** What the Undo snackbar of this change says, in the language shown: "Got Rice", "Rice: 2 kg → 3 kg", "Dahi: needed". */
internal fun ListEvent.ValueChange.snackbarText(): String {
    val t = Texts.current.list
    return when (this) {
        is ListEvent.GotIt -> t.gotItSnackbar(name)
        is ListEvent.Stepped -> t.stepSnackbar(name, before.spokenText(), after.spokenText())
        is ListEvent.NeedChanged -> t.needSnackbar(name, needed)
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
