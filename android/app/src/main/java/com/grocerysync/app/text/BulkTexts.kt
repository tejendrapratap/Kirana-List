package com.grocerysync.app.text

import com.grocerysync.app.data.CatalogSet
import java.util.Locale

/**
 * Add common items and Paste a list: their dialogs, the preview's rows and their notes, the buttons that count what
 * they do, and the snackbars with the results. Item names, units and amounts inside them are data, passed in as they
 * are shown.
 */
interface BulkTexts {

    // ---- Add common items --------------------------------------------------------------------------------------

    /** The dialog's title, and the empty list's button that opens it. */
    val addCommonItemsTitle: String

    /** The name of [set] on its chip. */
    fun setName(set: CatalogSet): String

    /** Above the rows: where the ticked items go, and how they become needed. */
    val addCommonItemsHint: String

    /** Above the rows instead of [addCommonItemsHint] when every item of the set is on the list already. */
    val allOfSetOnList: String

    /** Ticks every row of the set shown that can be added. */
    val selectAll: String

    /** Unticks every row of the set shown. */
    val selectNone: String

    /** Under the name of a row whose item is on the list already: "On the list as Oil" ([name] as it is there). */
    fun onListAs(name: String): String

    /** What TalkBack says for such a row: "Oil, on the list as Tel". */
    fun onListAsSpoken(displayName: String, name: String): String

    /** What TalkBack says, in a row, for an item added as yes / no (the screen shows [CommonTexts.yesNo]). */
    val yesNoSpoken: String

    /** The confirm button: "Add 12 items", "Add 1 item"; "Add items" (disabled) for 0. */
    fun addItemsButton(count: Int): String

    /** The snackbar once they are added: "Added 12 items to All items"; for 0, that nothing was. */
    fun commonItemsAdded(count: Int): String

    /** After Undo, when [count] of the items added were changed on another phone meanwhile and stay. */
    fun commonItemsKept(count: Int): String

    /**
     * The snackbar when Add common items or Paste a list is opened before the phone is connected to the sheet: the
     * items already on it would look new.
     */
    val connectFirst: String

    /** The same, while connected but before the family's list has come from the sheet. */
    val waitForList: String

    // ---- Paste a list ------------------------------------------------------------------------------------------

    /** The dialog's title. */
    val pasteListTitle: String

    /** Above the text field: where a list comes from and how it is written. */
    val pasteHelp: String

    /** The button that pastes the clipboard's text into the field. */
    val paste: String

    /** Under the Paste button when the clipboard holds no text. */
    val nothingToPaste: String

    /** The text field's label. */
    val listField: String

    /** The text field's example, shown while it is empty (one item per line). */
    val listFieldExample: String

    /** Under the text field: "1 line", "12 lines" (never 0: nothing is shown then). */
    fun lineCount(n: Int): String

    /** Under the text field, and as a snackbar for a shared text, when a paste has too many lines or characters. */
    fun pasteTooLong(maxLines: Int, maxChars: Int): String

    /** From the text to the preview. */
    val next: String

    /** From the preview back to the text. */
    val editList: String

    /** The snackbar when a list is shared into the app before it is set up. */
    val shareBeforeSetup: String

    /** The snackbar when something without a text the app can read (a file, an image) is shared into it. */
    val shareUnreadable: String

    /** Above the preview's rows while the family's list hasn't come from the sheet: the button waits for it. */
    val waitingForList: String

    /** The preview's first line when the text holds no line to use. */
    val nothingInPaste: String

    /** The preview's first line: "5 new, 3 to update, 1 already needed, 2 skipped" (only the counts above 0). */
    fun pasteSummary(new: Int, toUpdate: Int, alreadyNeeded: Int, skipped: Int): String

    /**
     * A row that adds an item: "Add Rice - 2 kg". [amount] is "" (yes / no), or the amount after its separator as
     * the screen shows it (" - 2 kg") or TalkBack says it (", 2 kg"); the same for the rows below.
     */
    fun addRow(name: String, amount: String): String

    /** A row that marks an item on the list needed: "Mark Rice needed - 2 kg". */
    fun markNeededRow(name: String, amount: String): String

    /** A row for an item that is needed already: "Already needed: Oil". */
    fun alreadyNeededRow(name: String): String

    /** A row that gives a needed item another amount, as shown: "Change Rice 2 kg → 3 kg". */
    fun changeAmountRow(name: String, from: String, to: String): String

    /** The same row as TalkBack says it, in words: "Change Rice from 2 kg to 3 kg". */
    fun changeAmountRowSpoken(name: String, from: String, to: String): String

    /** A line left out: "Skipped: Rice 2-3 kg" ([line] as read). */
    fun skippedRow(line: String): String

    /** Between the notes under a row ("Curd → Dahi. Dahi is yes / no, ..."). */
    val noteSeparator: String

    /** TalkBack's words for "Curd → Dahi" (shown under the row): "written as Curd". */
    fun writtenAs(typedName: String): String

    /** Under a row for a yes / no item, when the line had an amount: "Dahi is yes / no, so the amount is left out". */
    fun amountLeftOut(name: String): String

    /** The same as TalkBack says it ("yes or no" rather than the slash). */
    fun amountLeftOutSpoken(name: String): String

    /** Under a new item that was wholly in bold and names nothing known, so it starts unticked. */
    val looksLikeHeadingNotTicked: String

    /** Why a line is skipped: no item or amount could be read from it. */
    val unreadable: String

    /** Why a line is skipped: its amount is 0. */
    val zeroAmount: String

    /** Why a line is skipped: the name is longer than an item name may be. */
    val nameTooLong: String

    /** Why a line is skipped: it ends with ':', so it heads the lines below it. */
    val looksLikeHeading: String

    /** Why a line is skipped: its amount's unit isn't the item's ("Eggs is in dozen"; [unit] as stored). */
    fun inOtherUnit(name: String, unit: String): String

    /** Why a line is skipped: an earlier line names the same item. */
    val listedTwice: String

    /** The preview's button: "Add 5 items", "Update 3 items", "Apply 8 changes", or "Nothing to add" (disabled). */
    fun pasteConfirm(adds: Int, updates: Int): String

    /**
     * The snackbar once a paste is applied: "Added 5 items, updated 3", then how many rows were left out because
     * their item changed on another phone meanwhile; "Nothing to change" when nothing was done.
     */
    fun pasteResult(added: Int, updated: Int, leftOut: Int): String
}

/** "10,000": a count grouped by thousands, the same in both languages (Indian grouping is the same up to 99,999). */
private fun grouped(n: Int): String = "%,d".format(Locale.ROOT, n)

object EnglishBulkTexts : BulkTexts {
    private val common = EnglishCommonTexts

    override val addCommonItemsTitle = "Add common items"

    override fun setName(set: CatalogSet) = when (set) {
        CatalogSet.EVERYDAY -> "Everyday"
        CatalogSet.MONTHLY_KIRANA -> "Monthly kirana"
        CatalogSet.DIWALI_PUJA -> "Diwali puja"
        CatalogSet.VRAT -> "Vrat / fasting"
        CatalogSet.ALL -> "All"
    }

    override val addCommonItemsHint =
        "Ticked items are added to ${common.allItems}, not needed yet. Mark them as needed when you run out."
    override val allOfSetOnList = "Everything in this set is on your list already."
    override val selectAll = "Select all"
    override val selectNone = "Select none"
    override fun onListAs(name: String) = "On the list as $name"
    override fun onListAsSpoken(displayName: String, name: String) = "$displayName, on the list as $name"
    override val yesNoSpoken = "yes or no"

    override fun addItemsButton(count: Int) = if (count == 0) "Add items" else "Add ${common.itemCount(count)}"

    override fun commonItemsAdded(count: Int) = when (count) {
        0 -> "Nothing added: those items are on the list already"
        else -> "Added ${common.itemCount(count)} to ${common.allItems}"
    }

    override fun commonItemsKept(count: Int) =
        "${common.itemCount(count)} ${if (count == 1) "stays" else "stay"}: changed on another phone"

    override val connectFirst = "Connect to your sheet in Settings first, so items already on it aren't replaced."
    override val waitForList = "Wait until your list has loaded from the sheet, then try again."

    override val pasteListTitle = "Paste a list"
    override val paste = "Paste"
    override val pasteHelp =
        "Copy a list from WhatsApp or a note, then tap $paste. One item per line, like \"Rice 2 kg\" or \"Dahi\"."
    override val nothingToPaste = "Nothing to paste. Copy a list first."
    override val listField = "List"
    override val listFieldExample = "Rice 2 kg\nDahi\nOnion 1 kg"
    override fun lineCount(n: Int) = if (n == 1) "1 line" else "$n lines"
    override fun pasteTooLong(maxLines: Int, maxChars: Int) =
        "Too long: paste up to $maxLines lines and ${grouped(maxChars)} characters."
    override val next = "Next"
    override val editList = "Edit list"
    override val shareBeforeSetup = "Set up Kirana List first, then share the list again."
    override val shareUnreadable = "Couldn't read that. Share the list as text."
    override val waitingForList = "Waiting for your list to load from the sheet. You can add these once it has."

    override val nothingInPaste = "Nothing to add from this text."

    override fun pasteSummary(new: Int, toUpdate: Int, alreadyNeeded: Int, skipped: Int) = listOf(
        new to "new",
        toUpdate to "to update",
        alreadyNeeded to "already needed",
        skipped to "skipped",
    ).filter { it.first > 0 }.joinToString(", ") { (count, what) -> "$count $what" }

    override fun addRow(name: String, amount: String) = "Add $name$amount"
    override fun markNeededRow(name: String, amount: String) = "Mark $name needed$amount"
    override fun alreadyNeededRow(name: String) = "Already needed: $name"
    override fun changeAmountRow(name: String, from: String, to: String) = "Change $name $from → $to"
    override fun changeAmountRowSpoken(name: String, from: String, to: String) = "Change $name from $from to $to"
    override fun skippedRow(line: String) = "Skipped: $line"

    override val noteSeparator = ". "
    override fun writtenAs(typedName: String) = "written as $typedName"
    override fun amountLeftOut(name: String) = "$name is yes / no, so the amount is left out"
    override fun amountLeftOutSpoken(name: String) = "$name is $yesNoSpoken, so the amount is left out"
    override val looksLikeHeadingNotTicked = "Looks like a heading, so it isn't ticked"
    override val unreadable = "Couldn't read"
    override val zeroAmount = "The amount is 0"
    override val nameTooLong = "The name is too long"
    override val looksLikeHeading = "Looks like a heading"
    override fun inOtherUnit(name: String, unit: String) = "$name is in $unit"
    override val listedTwice = "Listed twice"

    override fun pasteConfirm(adds: Int, updates: Int) = when {
        adds > 0 && updates > 0 -> "Apply ${adds + updates} changes"
        adds > 0 -> "Add ${common.itemCount(adds)}"
        updates > 0 -> "Update ${common.itemCount(updates)}"
        else -> "Nothing to add"
    }

    override fun pasteResult(added: Int, updated: Int, leftOut: Int): String {
        val done = when {
            added > 0 && updated > 0 -> "Added ${common.itemCount(added)}, updated $updated"
            added > 0 -> "Added ${common.itemCount(added)}"
            updated > 0 -> "Updated ${common.itemCount(updated)}"
            else -> null
        }
        val left = if (leftOut > 0) "${common.itemCount(leftOut)} skipped: changed on another phone" else null
        return listOfNotNull(done, left).joinToString(". ").ifEmpty { "Nothing to change" }
    }
}

/**
 * The Hindi texts. चीज़ (item) is feminine, so a verb after a count of items agrees with it, and takes the plural
 * nasal for more than one: "1 चीज़ जोड़ दी", "5 चीज़ें जोड़ दीं" ([plural]).
 */
object HindiBulkTexts : BulkTexts {
    private val common = HindiCommonTexts

    /** [one] for a count of 1, else [many]: the verb forms of [HindiBulkTexts]' counted texts. */
    private fun plural(n: Int, one: String, many: String) = if (n == 1) one else many

    /** A page's or a button's name inside a sentence, in quotes as the other screens write it. */
    private fun quoted(name: String) = "\"$name\""

    override val addCommonItemsTitle = "आम सामान जोड़ें"

    override fun setName(set: CatalogSet) = when (set) {
        CatalogSet.EVERYDAY -> "रोज़ का"
        CatalogSet.MONTHLY_KIRANA -> "महीने का किराना"
        CatalogSet.DIWALI_PUJA -> "दिवाली पूजा"
        CatalogSet.VRAT -> "व्रत"
        CatalogSet.ALL -> "सब"
    }

    // A page's name and the Need word inside a sentence are quoted, as on the list screen: "सारा सामान" में.
    override val addCommonItemsHint =
        "टिक की गई चीज़ें ${quoted(common.allItems)} में जुड़ेंगी, अभी ${quoted(common.needed)} नहीं होंगी। " +
            "ख़त्म होने पर उन्हें ${quoted(common.needed)} कर दें।"
    override val allOfSetOnList = "इस सेट का सारा सामान पहले से आपकी लिस्ट में है।"
    override val selectAll = "सब चुनें"
    override val selectNone = "कोई न चुनें"
    override fun onListAs(name: String) = "लिस्ट में $name नाम से है"
    override fun onListAsSpoken(displayName: String, name: String) = "$displayName, लिस्ट में $name नाम से है"
    override val yesNoSpoken = common.yesNo

    override fun addItemsButton(count: Int) = if (count == 0) "चीज़ें जोड़ें" else "${common.itemCount(count)} जोड़ें"

    override fun commonItemsAdded(count: Int) =
        if (count == 0) {
            "कुछ नहीं जुड़ा: ये चीज़ें पहले से लिस्ट में हैं"
        } else {
            "${quoted(common.allItems)} में ${common.itemCount(count)} ${plural(count, "जोड़ दी", "जोड़ दीं")}"
        }

    override fun commonItemsKept(count: Int) =
        "${common.itemCount(count)} " +
            plural(count, "नहीं हटी: दूसरे फ़ोन पर बदली गई है", "नहीं हटीं: दूसरे फ़ोन पर बदली गई हैं")

    override val connectFirst = "पहले सेटिंग्स में अपनी शीट से जुड़ें, ताकि उसमें पहले से रखा सामान बदल न जाए।"
    // "Try again" in the words of the Retry button (फिर से कोशिश करें); "इसके बाद" for "then", as SyncTexts says it.
    override val waitForList = "शीट से आपकी लिस्ट आने तक रुकें, इसके बाद फिर से कोशिश करें।"

    override val pasteListTitle = "लिस्ट पेस्ट करें"
    override val paste = "पेस्ट करें"
    override val pasteHelp =
        "WhatsApp या किसी नोट से लिस्ट कॉपी करें, फिर \"$paste\" दबाएँ। " +
            "हर लाइन में एक चीज़, जैसे \"चावल 2 किलो\" या \"दही\"।"
    override val nothingToPaste = "पेस्ट करने को कुछ नहीं है। पहले कोई लिस्ट कॉपी करें।"
    override val listField = "लिस्ट"
    override val listFieldExample = "चावल 2 किलो\nदही\nप्याज़ 1 किलो"
    override fun lineCount(n: Int) = plural(n, "1 लाइन", "$n लाइनें")
    override fun pasteTooLong(maxLines: Int, maxChars: Int) =
        "बहुत लंबा है: ज़्यादा से ज़्यादा $maxLines लाइनें और ${grouped(maxChars)} अक्षर पेस्ट करें।"
    override val next = "आगे"
    override val editList = "लिस्ट बदलें"
    override val shareBeforeSetup = "पहले किराना लिस्ट सेट अप करें, फिर लिस्ट दोबारा शेयर करें।"
    override val shareUnreadable = "यह पढ़ा नहीं जा सका। लिस्ट को टेक्स्ट के रूप में शेयर करें।"
    override val waitingForList = "शीट से आपकी लिस्ट आने का इंतज़ार है। उसके आते ही आप इन्हें जोड़ सकते हैं।"

    override val nothingInPaste = "इस टेक्स्ट में जोड़ने को कुछ नहीं है।"

    override fun pasteSummary(new: Int, toUpdate: Int, alreadyNeeded: Int, skipped: Int) = listOf(
        new to "$new नई",
        toUpdate to plural(toUpdate, "1 बदलेगी", "$toUpdate बदलेंगी"),
        alreadyNeeded to "$alreadyNeeded पहले से ${common.needed}",
        skipped to plural(skipped, "1 छोड़ी", "$skipped छोड़ीं"),
    ).filter { it.first > 0 }.joinToString(", ") { it.second }

    // The rows start with what they do, as a label: the name and amount after it read the same in every row.
    override fun addRow(name: String, amount: String) = "${common.add}: $name$amount"
    override fun markNeededRow(name: String, amount: String) = "${common.needed}: $name$amount"
    override fun alreadyNeededRow(name: String) = "पहले से ${common.needed}: $name"
    override fun changeAmountRow(name: String, from: String, to: String) = "${common.edit}: $name $from → $to"
    override fun changeAmountRowSpoken(name: String, from: String, to: String) = "${common.edit}: $name, $from से $to"
    override fun skippedRow(line: String) = "छोड़ दिया: $line"

    override val noteSeparator = "। "
    override fun writtenAs(typedName: String) = "$typedName लिखा था"
    override fun amountLeftOut(name: String) = "$name ${common.yesNo} वाला है, इसलिए मात्रा छोड़ दी"
    override fun amountLeftOutSpoken(name: String) = amountLeftOut(name)
    override val looksLikeHeadingNotTicked = "हेडिंग लगती है, इसलिए टिक नहीं किया"
    override val unreadable = "समझ नहीं आया"
    override val zeroAmount = "मात्रा 0 है"
    override val nameTooLong = "नाम बहुत लंबा है"
    override val looksLikeHeading = "हेडिंग लगती है"
    override fun inOtherUnit(name: String, unit: String) = "$name की यूनिट $unit है"
    override val listedTwice = "दो बार लिखा है"

    override fun pasteConfirm(adds: Int, updates: Int) = when {
        adds > 0 && updates > 0 -> "${adds + updates} बदलाव करें"
        adds > 0 -> "${common.itemCount(adds)} जोड़ें"
        updates > 0 -> "${common.itemCount(updates)} अपडेट करें"
        else -> "जोड़ने को कुछ नहीं"
    }

    override fun pasteResult(added: Int, updated: Int, leftOut: Int): String {
        val addedText = "${common.itemCount(added)} ${plural(added, "जोड़ दी", "जोड़ दीं")}"
        val done = when {
            added > 0 && updated > 0 -> "$addedText, $updated ${plural(updated, "अपडेट की", "अपडेट कीं")}"
            added > 0 -> addedText
            updated > 0 -> "${common.itemCount(updated)} ${plural(updated, "अपडेट की", "अपडेट कीं")}"
            else -> null
        }
        val left = if (leftOut > 0) {
            "${common.itemCount(leftOut)} " +
                plural(leftOut, "छोड़ दी: दूसरे फ़ोन पर बदली गई थी", "छोड़ दीं: दूसरे फ़ोन पर बदली गई थीं")
        } else {
            null
        }
        return listOfNotNull(done, left).joinToString(noteSeparator).ifEmpty { "कुछ नहीं बदला" }
    }
}
