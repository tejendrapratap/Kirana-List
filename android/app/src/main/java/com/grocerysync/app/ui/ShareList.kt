package com.grocerysync.app.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.grocerysync.app.MainActivity
import com.grocerysync.app.data.GroceryItem
import com.grocerysync.app.data.ItemCatalog
import com.grocerysync.app.data.ItemKeys
import com.grocerysync.app.data.ItemValue
import com.grocerysync.app.data.ListLogic
import com.grocerysync.app.data.ShopSection
import com.grocerysync.app.data.displayText

// Share in the app bar: the To buy list as a plain-text message (WhatsApp, SMS, a note) through Android's share
// sheet. The text is built by a pure function (unit-tested in ShareListTest).

/** The snackbar when Share is tapped and nothing is needed. */
internal const val NOTHING_TO_SHARE_TEXT = "Nothing to buy right now"

/** The snackbar when no app on the phone can take the text (the share sheet couldn't open). */
internal const val NO_SHARE_APP_TEXT = "No app on this phone can share the list"

/** The first line of a shared list: "Grocery list (3 items)", "Grocery list (1 item)". */
internal fun shareListTitle(count: Int): String = "Grocery list ($count ${if (count == 1) "item" else "items"})"

/**
 * The shared text of every needed item of [items] (the others are left out): [shareListTitle], a blank line, then
 * one line per item, "• Atta - 10 kg" or "• Dahi" (a quantity as its row shows it, [displayText]).
 *
 * With [sectionOf], the items are grouped by shop section in walking order ([ShopSection]'s order), each group
 * under its name in WhatsApp bold ("*Dairy, eggs & bread*") and the groups separated by a blank line; only
 * sections with items appear. Without it (null), one list. Items are A to Z either way, inside each section too,
 * as on To buy. Nothing needed: just the title (Share says "Nothing to buy right now" instead, [shareToBuy]).
 */
internal fun buildShareText(items: List<GroceryItem>, sectionOf: ((GroceryItem) -> ShopSection)?): String {
    val needed = ListLogic.sortForDisplay(items.filter { it.value.isNeeded })
    if (needed.isEmpty()) return shareListTitle(0)
    val lines = mutableListOf(shareListTitle(needed.size), "")
    if (sectionOf == null) {
        needed.mapTo(lines, ::shareLine)
    } else {
        // groupBy keeps the A to Z order inside each section.
        needed.groupBy(sectionOf).entries.sortedBy { it.key.ordinal }.forEachIndexed { index, (section, sectionItems) ->
            if (index > 0) lines += ""
            lines += "*${section.label}*"
            sectionItems.mapTo(lines, ::shareLine)
        }
    }
    return lines.joinToString("\n")
}

/** One item of the shared text: "• Atta - 10 kg", "• Milk - 1.5 L", "• Eggs - × 6" (no unit), "• Dahi" (yes / no). */
internal fun shareLine(item: GroceryItem): String {
    // A name typed into the sheet may hold a line break; the message keeps one line per item.
    val name = ItemKeys.singleLine(item.name)
    return when (val value = item.value) {
        is ItemValue.Quantity -> "• $name - ${value.displayText()}"
        is ItemValue.BoolValue -> "• $name"
    }
}

/** The built-in shop section of [item] ([ItemCatalog.sectionFor]; Other for a name it doesn't know). */
internal fun catalogSectionOf(item: GroceryItem): ShopSection = ItemCatalog.sectionFor(item.name)

/**
 * Share: opens Android's share sheet with the needed items of [toBuy] as text ([buildShareText], grouped by
 * [sectionOf] or A to Z when it is null). The sheet doesn't offer this app itself: Kirana List takes shared text
 * (Paste a list), and sharing the list back into it would only open its preview. When nothing is needed, or no app
 * can take the text, it calls [onMessage] with the snackbar to show instead.
 */
internal fun shareToBuy(
    context: Context,
    toBuy: List<GroceryItem>,
    sectionOf: ((GroceryItem) -> ShopSection)?,
    onMessage: (String) -> Unit,
) {
    if (toBuy.none { it.value.isNeeded }) {
        onMessage(NOTHING_TO_SHARE_TEXT)
        return
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, buildShareText(toBuy, sectionOf))
    }
    // No chooser title: Android uses its own ("Share"), and newer versions show none for text.
    val chooser = Intent.createChooser(send, null).apply {
        putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, MainActivity::class.java)))
    }
    try {
        context.startActivity(chooser)
    } catch (e: ActivityNotFoundException) {
        onMessage(NO_SHARE_APP_TEXT)
    }
}
