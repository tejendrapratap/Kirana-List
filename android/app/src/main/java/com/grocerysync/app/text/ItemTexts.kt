package com.grocerysync.app.text

/**
 * The Add / Edit item dialog: its labels and buttons, the name and amount checks, the duplicate and similar-item
 * messages, the unit picker and Other unit, the shop section picker and the Delete question. Also the checks of the
 * web app URL that Settings shows, as they are made by InputValidation (in this area's files) with the name checks.
 *
 * Words the dialog shares with other screens come from [CommonTexts]: the title when adding ([CommonTexts.addItem]),
 * Save, Delete and Cancel, the item types ([CommonTexts.yesNo], [CommonTexts.quantity]), "needed", Unit, Amount and
 * the shop sections' names. A [name] in these texts is an item's name, already shortened for quoting, and stays as
 * it is; so does a unit or an amount ("0.5 kg").
 */
interface ItemTexts {
    /** The dialog's title when editing an item. */
    val editItemTitle: String

    /** The name field's label. */
    val nameLabel: String

    /** In the empty name field. */
    val namePlaceholder: String

    /** Under the name field while it is empty (only after something was typed and deleted). */
    val nameEmpty: String

    /** Under the name field while the name is longer than [maxLength] characters. */
    fun nameTooLong(maxLength: Int): String

    /** Under the name field while the name has a line break in it (pasted). */
    val nameLineBreak: String

    /**
     * The add dialog's error, and the snackbar when a save is refused: "Rice" is already on the list. [name] is
     * the other item's name as stored.
     */
    fun alreadyOnList(name: String): String

    /** The edit dialog's error for a rename to another item's name: Another item is already called "Rice". */
    fun anotherItemCalled(name: String): String

    /** The add dialog's button that opens the item already on the list (or the similar one) instead: Open "Rice". */
    fun openItem(name: String): String

    /**
     * The warning under the name for another name of an item on the list ("Curd" while "Dahi" is there); Save
     * stays enabled: Similar: "Dahi" is already on the list.
     */
    fun similarItem(name: String): String

    /** Above the name when the item was renamed or removed on another phone while the dialog was open. */
    val removedElsewhere: String

    /**
     * Above the name when another phone changed the value while the dialog was open and the user changed it too.
     * [value]: the other phone's value as the dialog says it ([CommonTexts.needed], [CommonTexts.notNeeded] or
     * an amount with its unit, "2 kg").
     */
    fun changedElsewhere(value: String): String

    /** The label above the item type's two buttons ([CommonTexts.yesNo], [CommonTexts.quantity]). */
    val trackAs: String

    /** The label above a yes / no item's two buttons, [yes] and [no]. */
    val needToBuy: String
    val yes: String
    val no: String

    /** The label above a quantity's amount and unit. */
    val howMuchToBuy: String

    /** TalkBack's name of the amount's - button: "0.5 kg less" ([step]: what one tap changes, "0.5 kg", "1"). */
    fun stepLess(step: String): String

    /** TalkBack's name of the amount's + button: "0.5 kg more". */
    fun stepMore(step: String): String

    /** Under the amount while it can't be read: an amount from 0 to [max], with up to [decimals] (more than 1) decimals. */
    fun amountError(max: Long, decimals: Int): String

    /**
     * While a quantity has no unit: the line under the amount, also what TalkBack hears as the unit picker's state
     * (with [unitPromptToSave]). Save stays disabled until one is chosen.
     */
    val unitPrompt: String

    /** TalkBack's state of the unit picker while no unit is chosen: why Save is disabled. */
    val unitPromptToSave: String

    /** In the unit picker while no unit is chosen and [unitPrompt] has its own line below (a short word: it is narrow). */
    val noUnitHint: String

    /** In the unit picker while no unit is chosen and there is no room for [unitPrompt]'s line (a short word). */
    val unitPickerPrompt: String

    /** What TalkBack says a tap on the unit picker does ("double-tap to choose unit"). */
    val chooseUnit: String

    /** TalkBack's name of the unit picker once a [unit] is chosen: "Unit: kg". */
    fun unitChosen(unit: String): String

    /** The unit picker's last choice, which opens a dialog to type a unit of one's own. */
    val otherUnit: String

    /** That dialog's title. */
    val otherUnitTitle: String

    /** In its empty unit field: examples of units people type. */
    val otherUnitPlaceholder: String

    /** Its button that takes the unit typed. */
    val useUnit: String

    /** TalkBack's name of the tick at the chosen unit or shop section in a menu. */
    val selected: String

    /** The question before deleting: Delete "Rice"? ([name] as stored). */
    fun deleteQuestion(name: String): String

    /** Under that question: what deleting does. */
    val deleteExplanation: String

    /** The shop section picker's label. */
    val shopSection: String

    /** What TalkBack says a tap on the shop section picker does. */
    val chooseShopSection: String

    /** TalkBack's name of the shop section picker: "Shop section: Masala" ([section] as shown, CommonTexts.sectionName). */
    fun shopSectionChosen(section: String): String

    /** Under the shop section picker: the choice is never synced. */
    val thisPhoneOnly: String

    /** Settings' web app URL: a /dev URL, which only works for the script's owner. */
    val urlDevWarning: String

    /** Settings' web app URL: plain http://. */
    val urlHttpWarning: String

    /** Settings' web app URL: an https:// URL that isn't an Apps Script /exec URL. */
    val urlNotAppsScriptWarning: String

    /** Settings' web app URL: the field is empty. */
    val urlEmpty: String

    /** Settings' web app URL: another scheme than http(s)://. */
    val urlBadScheme: String

    /** Settings' web app URL: not a URL at all. */
    val urlUnparseable: String
}

object EnglishItemTexts : ItemTexts {
    override val editItemTitle = "Edit item"
    override val nameLabel = "Item"
    override val namePlaceholder = "e.g. Milk"
    override val nameEmpty = "Enter an item name"
    override fun nameTooLong(maxLength: Int) = "Keep it under $maxLength characters"
    override val nameLineBreak = "Item name can't contain line breaks"
    override fun alreadyOnList(name: String) = "\"$name\" is already on the list"
    override fun anotherItemCalled(name: String) = "Another item is already called \"$name\""
    override fun openItem(name: String) = "Open \"$name\""
    override fun similarItem(name: String) = "Similar: \"$name\" is already on the list"
    override val removedElsewhere = "This item was renamed or removed on another phone. Save to add it again."
    override fun changedElsewhere(value: String) = "Changed on another phone to $value. Save to use your value instead."
    override val trackAs = "Track as"
    override val needToBuy = "Need to buy?"
    override val yes = "Yes"
    override val no = "No"
    override val howMuchToBuy = "How much to buy? (0 = none)"
    override fun stepLess(step: String) = "$step less"
    override fun stepMore(step: String) = "$step more"
    override fun amountError(max: Long, decimals: Int) = "Enter an amount from 0 to $max, up to $decimals decimals"
    override val unitPrompt = "Choose a unit"
    override val unitPromptToSave = "$unitPrompt to save"
    override val noUnitHint = "unit"
    override val unitPickerPrompt = "Choose"
    override val chooseUnit = "Choose unit"
    override fun unitChosen(unit: String) = "${EnglishCommonTexts.unit}: $unit"
    override val otherUnit = "Other..."
    override val otherUnitTitle = "Other unit"
    override val otherUnitPlaceholder = "e.g. bunch, box, bottle"
    override val useUnit = "Use"
    override val selected = "Selected"
    override fun deleteQuestion(name: String) = "Delete \"$name\"?"
    override val deleteExplanation = "It will be removed from the list on every phone and from the sheet."
    override val shopSection = "Shop section"
    override val chooseShopSection = "Choose shop section"
    override fun shopSectionChosen(section: String) = "$shopSection: $section"
    override val thisPhoneOnly = "This phone only"
    override val urlDevWarning =
        "This is a test (/dev) URL. Use the Web app URL ending in /exec from Deploy > Manage deployments."
    override val urlHttpWarning = "Not secure - only use http:// for local testing."
    override val urlNotAppsScriptWarning = "This doesn't look like an Apps Script web app URL (it should end in /exec)."
    override val urlEmpty = "Paste the Web app URL from Apps Script"
    override val urlBadScheme = "The URL must start with https://"
    override val urlUnparseable = "That isn't a valid URL"
}

/**
 * The names Apps Script itself shows in English ("Web app URL", Deploy > Manage deployments) stay in English, as
 * in [HindiSyncTexts], so they match what people see there.
 */
object HindiItemTexts : ItemTexts {
    override val editItemTitle = "सामान बदलें"
    override val nameLabel = "सामान"
    override val namePlaceholder = "जैसे दूध"
    override val nameEmpty = "सामान का नाम लिखें"
    override fun nameTooLong(maxLength: Int) = "$maxLength अक्षरों से छोटा रखें"
    override val nameLineBreak = "नाम एक ही लाइन में लिखें"
    override fun alreadyOnList(name: String) = "\"$name\" पहले से लिस्ट में है"
    override fun anotherItemCalled(name: String) = "\"$name\" नाम का दूसरा सामान पहले से है"
    override fun openItem(name: String) = "\"$name\" खोलें"
    override fun similarItem(name: String) = "मिलता-जुलता \"$name\" पहले से लिस्ट में है"
    override val removedElsewhere = "दूसरे फ़ोन पर इसका नाम बदला गया या इसे हटाया गया। फिर से जोड़ने के लिए सेव करें।"

    // "बदला गया: ..." reads well before both a word (चाहिए) and an amount (2 kg).
    override fun changedElsewhere(value: String) = "दूसरे फ़ोन पर बदला गया: $value। अपना बदलाव रखने के लिए सेव करें।"
    override val trackAs = "कैसे लिखें"
    override val needToBuy = "ख़रीदना है?"
    override val yes = "हाँ"
    override val no = "नहीं"
    override val howMuchToBuy = "कितना ख़रीदना है? (0 = कुछ नहीं)"
    // Said as what the button does, like the list rows' − / + (ListTexts.stepDown / stepUp).
    override fun stepLess(step: String) = "$step कम करें"
    override fun stepMore(step: String) = "$step बढ़ाएँ"
    override fun amountError(max: Long, decimals: Int) = "0 से $max तक की मात्रा लिखें, दशमलव के बाद $decimals अंक तक"
    override val unitPrompt = "यूनिट चुनें"
    override val unitPromptToSave = "सेव करने के लिए यूनिट चुनें"
    override val noUnitHint = "यूनिट"
    override val unitPickerPrompt = "चुनें"
    override val chooseUnit = "यूनिट चुनें"
    override fun unitChosen(unit: String) = "${HindiCommonTexts.unit}: $unit"
    override val otherUnit = "कोई और..."
    override val otherUnitTitle = "कोई और यूनिट"
    override val otherUnitPlaceholder = "जैसे गड्डी, डिब्बा, बोतल"
    override val useUnit = "इस्तेमाल करें"
    override val selected = "चुना गया"
    override fun deleteQuestion(name: String) = "\"$name\" हटाएँ?"
    override val deleteExplanation = "यह हर फ़ोन की लिस्ट से और शीट से हट जाएगा।"
    override val shopSection = "सेक्शन"
    override val chooseShopSection = "सेक्शन चुनें"
    override fun shopSectionChosen(section: String) = "$shopSection: $section"
    override val thisPhoneOnly = "सिर्फ़ इस फ़ोन पर"
    override val urlDevWarning =
        "यह टेस्ट (/dev) URL है। Deploy > Manage deployments से वह Web app URL लें जो /exec पर ख़त्म होता है।"
    override val urlHttpWarning = "सुरक्षित नहीं - http:// सिर्फ़ लोकल टेस्टिंग के लिए इस्तेमाल करें।"
    override val urlNotAppsScriptWarning = "यह Apps Script के वेब ऐप का URL नहीं लगता (यह /exec पर ख़त्म होना चाहिए)।"
    override val urlEmpty = "Apps Script से Web app URL पेस्ट करें"
    override val urlBadScheme = "URL https:// से शुरू होना चाहिए"
    override val urlUnparseable = "यह सही URL नहीं है"
}
