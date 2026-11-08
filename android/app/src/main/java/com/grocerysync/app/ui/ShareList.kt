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
import com.grocerysync.app.text.Texts

// Share in the app bar: the To buy list as a plain-text message (WhatsApp, SMS, a note) through Android's share
// sheet. The text is built by a pure function (unit-tested in ShareListTest).

/** The snackbar when Share is tapped and nothing is needed. */
internal val NOTHING_TO_SHARE_TEXT: String get() = Texts.current.list.nothingToShare

/** The snackbar when no app on the phone can take the text (the share sheet couldn't open). */
internal val NO_SHARE_APP_TEXT: String get() = Texts.current.list.noShareApp

/**
 * The first line of a shared list: "Grocery list (3 items)", "Grocery list (1 item)", in the language shown
 * ([CommonTexts.shareTitle][com.grocerysync.app.text.CommonTexts.shareTitle], which Paste a list skips in every
 * language).
 */
internal fun shareListTitle(count: Int): String = Texts.current.common.shareTitle(count)

/**
 * The shared text of every needed item of [items] (the others are left out): [shareListTitle], a blank line, then
 * one line per item, "• Atta - 10 kg" or "• Dahi" (a quantity as its row shows it, [displayText]).
 *
 * With [sectionOf], the items are grouped by shop section in walking order ([ShopSection]'s order), each group
 * under its name in WhatsApp bold ("*Dairy, eggs & bread*") and the groups separated by a blank line; only
 * sections with items appear. Without it (null), one list. Items are A to Z either way, inside each section too,
 * as on To buy. Nothing needed: just the title (Share says "Nothing to buy right now" instead, [shareToBuy]).
 * The title and the section names are in the language shown; item names and amounts are as they are.
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
            lines += "*${Texts.current.common.sectionName(section)}*"
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
    // No chooser title: Android uses its own ("Share"), and newer versions show none for text. (createChooser is
    // only ever null in the JVM unit tests, whose android.jar stubs return defaults.)
    val chooser: Intent? = Intent.createChooser(send, null)
    chooser?.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, MainActivity::class.java)))
    try {
        context.startActivity(chooser)
    } catch (e: ActivityNotFoundException) {
        onMessage(NO_SHARE_APP_TEXT)
    }
}
