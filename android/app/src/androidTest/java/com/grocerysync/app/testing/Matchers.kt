package com.grocerysync.app.testing

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable

// Finders for the app's screens, built only from what the app already exposes to accessibility services:
// texts, content descriptions, roles and states. The app has no test tags.

/** A node with a text (not an input field's) that matches [regex] entirely. */
fun hasTextMatching(regex: Regex): SemanticsMatcher = SemanticsMatcher("text matches $regex") { node ->
    node.config.getOrNull(SemanticsProperties.Text)?.any { regex.matches(it.text) } == true
}

/** A text, editable text, typed text, content description or state description that contains [text]. */
fun mentions(text: String): SemanticsMatcher = SemanticsMatcher("mentions \"$text\" anywhere") { node ->
    val config = node.config
    val texts = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
        listOfNotNull(config.getOrNull(SemanticsProperties.EditableText)?.text) +
        listOfNotNull(config.getOrNull(SemanticsProperties.InputText)?.text) +
        config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
        listOfNotNull(config.getOrNull(SemanticsProperties.StateDescription))
    texts.any { it.contains(text) }
}

/** A text or content description with the word [word] in it, in any case ("Show", "show"; not "Shown"). */
fun hasWord(word: String): SemanticsMatcher {
    val regex = Regex("\\b${Regex.escape(word)}\\b", RegexOption.IGNORE_CASE)
    return SemanticsMatcher("has the word \"$word\"") { node ->
        val config = node.config
        val texts = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        texts.any { regex.containsMatchIn(it) }
    }
}

/** A text field whose own text (what was typed, before masking) is exactly [text]. */
fun hasInputText(text: String): SemanticsMatcher = SemanticsMatcher("input text is \"$text\"") { node ->
    node.config.getOrNull(SemanticsProperties.InputText)?.text == text
}

/** A field that masks what is typed (a password field). */
val isPasswordField: SemanticsMatcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)

// ---- The list screen ----------------------------------------------------------------------------------

/** "To buy (2)" / "All items (5)" in the segmented row above the list. */
fun tab(label: String): SemanticsMatcher = isSelectable() and hasTextMatching(Regex("${Regex.escape(label)} \\(\\d+\\)"))

fun tab(label: String, count: Int): SemanticsMatcher = isSelectable() and hasTextMatching(Regex("${Regex.escape(label)} \\($count\\)"))

/** The status line under the title while syncing works: "Live - updated 3s ago". */
val liveStatus: SemanticsMatcher = hasTextMatching(Regex("Live( - updated .+)?"))

/** The pager with the To buy and All items pages (the only horizontally scrolling node). */
val pager: SemanticsMatcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)

/** The search field (the only text field on the list screen; none while a dialog is open). */
val searchField: SemanticsMatcher = hasSetTextAction() and !hasText("Item")

/**
 * The row of item [name] on the page shown (To buy or All items): tapping it opens the item. Its meta line
 * ("Added by Home - 2 hours ago") and the "Not synced yet" marker are part of it.
 */
fun itemRow(name: String): SemanticsMatcher = hasText(name) and hasClickAction() and !hasSetTextAction()

/** The meta line of an All items row: "Added by Home - just now", "Updated by Shop - 2 hours ago". */
fun itemRow(name: String, meta: String): SemanticsMatcher = itemRow(name) and hasText(meta, substring = true)

/** A row whose change hasn't reached the sheet yet (All items). */
fun pendingRow(name: String): SemanticsMatcher = itemRow(name) and hasContentDescription(NOT_SYNCED)

const val NOT_SYNCED = "Not synced yet"

/** To buy: the "Got it" box of [name]. */
fun gotIt(name: String): SemanticsMatcher = hasContentDescription("Got it: $name")

/** To buy: the row of [name] with its amount badge, e.g. "2 L". */
fun toBuyRowWithAmount(name: String, amount: String): SemanticsMatcher =
    itemRow(name) and hasContentDescription("Quantity $amount")

/** All items: the Need switch of a yes / no item. */
fun needSwitch(name: String): SemanticsMatcher = hasContentDescription("Need $name")

/** All items: - ([more] false) or + of a quantity; [step] as the app says it ("0.5 kg", "100 g", "1 pcs"). */
fun stepButton(name: String, step: String, more: Boolean): SemanticsMatcher =
    hasContentDescription("$step ${if (more) "more" else "less"} $name")

/** All items: the amount between - and + of [name], e.g. "1.5 kg". */
fun stepperAmount(name: String, amount: String): SemanticsMatcher =
    hasContentDescription("Quantity $amount") and hasClickAction() and hasAnyAncestor(itemRow(name))

// ---- Dialogs ------------------------------------------------------------------------------------------

/** A button (or a clickable row) labelled [label]. */
fun button(label: String): SemanticsMatcher = hasText(label) and hasClickAction() and !hasSetTextAction()

/** One of the segmented choices in the item dialog: "Yes / no", "Quantity", "Yes", "No". */
fun choice(label: String): SemanticsMatcher = hasText(label) and isSelectable()

/** The item dialog's name field (labelled "Item"). */
val nameField: SemanticsMatcher = hasSetTextAction() and hasText("Item")

/** The item dialog's amount field, between - and +. */
val amountField: SemanticsMatcher = hasSetTextAction() and hasContentDescription("Amount")

/** The item dialog's unit picker: "Unit" while none is chosen, "Unit: kg" once one is. */
val unitPicker: SemanticsMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.DropdownList)

/** A labelled text field, e.g. "Secret token" or "Web app URL" in Settings, "Unit" in the Other unit dialog. */
fun textField(label: String): SemanticsMatcher = hasSetTextAction() and hasText(label)
