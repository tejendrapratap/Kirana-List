package com.grocerysync.app.text

import com.grocerysync.app.data.ShopSection

/**
 * Words several areas of the app share: the usual buttons, the two pages' names, "needed", the item types, item
 * counts, the shop sections' names, who added or changed an item and when, and Share's title. A text only one area
 * uses belongs in that area's catalogue instead.
 */
interface CommonTexts {
    /** The snackbar action that takes a change back. */
    val undo: String
    val cancel: String
    val save: String
    val delete: String
    val edit: String
    val ok: String
    val close: String

    /** Tries a failed action (sending changes, syncing) again. Sync messages that say "tap Retry" name this button. */
    val retry: String
    val add: String
    val addItem: String
    val settings: String

    /** The page of needed items, and its tab. */
    val toBuy: String

    /** The page of every item, and its tab. */
    val allItems: String

    /** A yes / no item that is needed, as said after its name ("Dahi: needed"). */
    val needed: String

    /** A yes / no item that is not needed ("Dahi: not needed"). */
    val notNeeded: String

    /** The item type ticked as needed or not, with no amount. */
    val yesNo: String

    /** The item type with an amount and a unit. */
    val quantity: String
    val unit: String
    val amount: String

    /** "1 item", "3 items": a number of items, the number first. */
    fun itemCount(n: Int): String

    /** The name of [section] as shown (To buy's headers, the section picker, Share, Add common items). */
    fun sectionName(section: ShopSection): String

    /**
     * "Added by Home": who added an item nobody has changed since ([name] as shown, never translated). All items'
     * rows say it, and Settings quotes it where it explains Your name, so both use this one text.
     */
    fun addedBy(name: String): String

    /** "Updated by Home": who changed an item last. */
    fun updatedBy(name: String): String

    /** "Added": [addedBy] when the sheet doesn't say who ("Added 2 hours ago"). */
    val added: String

    /** "Updated": [updatedBy] when the sheet doesn't say who. */
    val updated: String

    /** "just now": less than a minute ago. The relative times are in lower case: they follow a name or a verb. */
    val justNow: String

    /** "1 minute ago", "5 minutes ago". */
    fun minutesAgo(n: Int): String

    /** "1 hour ago", "2 hours ago". */
    fun hoursAgo(n: Int): String

    /** "yesterday": the calendar day before today. */
    val yesterday: String

    /** "1 day ago", "3 days ago". */
    fun daysAgo(n: Int): String

    /**
     * The first line of a shared To buy list: "Grocery list (1 item)", "Grocery list (3 items)". Share writes it
     * in the current language and Paste a list skips it in every language, so a list shared from a Hindi phone
     * pastes back on an English one and the other way round: both read it from here.
     */
    fun shareTitle(n: Int): String
}

object EnglishCommonTexts : CommonTexts {
    override val undo = "Undo"
    override val cancel = "Cancel"
    override val save = "Save"
    override val delete = "Delete"
    override val edit = "Edit"
    override val ok = "OK"
    override val close = "Close"
    override val retry = "Retry"
    override val add = "Add"
    override val addItem = "Add item"
    override val settings = "Settings"
    override val toBuy = "To buy"
    override val allItems = "All items"
    override val needed = "needed"
    override val notNeeded = "not needed"
    override val yesNo = "Yes / no"
    override val quantity = "Quantity"
    override val unit = "Unit"
    override val amount = "Amount"

    override fun itemCount(n: Int) = if (n == 1) "1 item" else "$n items"

    /** [ShopSection.label]: the English names stay there, as the stable names Share and Paste a list use. */
    override fun sectionName(section: ShopSection) = section.label

    override fun addedBy(name: String) = "Added by $name"
    override fun updatedBy(name: String) = "Updated by $name"
    override val added = "Added"
    override val updated = "Updated"
    override val justNow = "just now"
    override fun minutesAgo(n: Int) = if (n == 1) "1 minute ago" else "$n minutes ago"
    override fun hoursAgo(n: Int) = if (n == 1) "1 hour ago" else "$n hours ago"
    override val yesterday = "yesterday"
    override fun daysAgo(n: Int) = if (n == 1) "1 day ago" else "$n days ago"
    override fun shareTitle(n: Int) = "Grocery list (${itemCount(n)})"
}

object HindiCommonTexts : CommonTexts {
    override val undo = "पहले जैसा करें"
    override val cancel = "रद्द करें"
    override val save = "सेव करें"
    override val delete = "हटाएँ"
    override val edit = "बदलें"
    override val ok = "ठीक है"
    override val close = "बंद करें"
    override val retry = "फिर से कोशिश करें"
    override val add = "जोड़ें"
    override val addItem = "सामान जोड़ें"
    override val settings = "सेटिंग्स"
    override val toBuy = "ख़रीदना है"
    override val allItems = "सारा सामान"
    override val needed = "चाहिए"
    override val notNeeded = "नहीं चाहिए"
    override val yesNo = "हाँ या नहीं"
    override val quantity = "कितना चाहिए"
    override val unit = "यूनिट"
    override val amount = "मात्रा"

    override fun itemCount(n: Int) = if (n == 1) "1 चीज़" else "$n चीज़ें"

    override fun sectionName(section: ShopSection) = when (section) {
        ShopSection.SABZI_FRUIT -> "सब्ज़ी और फल"
        ShopSection.DAIRY_EGGS_BREAD -> "दूध, अंडे और ब्रेड"
        ShopSection.MEAT_FISH -> "मीट और मछली"
        ShopSection.ATTA_RICE_DAL -> "आटा, चावल और दाल"
        ShopSection.OIL_GHEE -> "तेल और घी"
        ShopSection.MASALA -> "मसाले"
        ShopSection.TEA_SUGAR_SALT -> "चाय, चीनी और नमक"
        ShopSection.DRY_FRUITS_NUTS -> "मेवे"
        ShopSection.SNACKS_PACKAGED -> "नमकीन और पैक्ड सामान"
        ShopSection.CLEANING_HOUSEHOLD -> "सफ़ाई और घर का सामान"
        ShopSection.PERSONAL_CARE -> "पर्सनल केयर"
        ShopSection.PUJA -> "पूजा"
        ShopSection.OTHER -> "अन्य"
    }

    override fun addedBy(name: String) = "$name ने जोड़ा"
    override fun updatedBy(name: String) = "$name ने बदला"

    /** Without a name the passive form reads naturally: "जोड़ा गया" (was added). */
    override val added = "जोड़ा गया"
    override val updated = "बदला गया"
    override val justNow = "अभी"

    // "पहले" (ago) takes the oblique form, the same for one and many: "1 घंटे पहले", "2 घंटे पहले".
    override fun minutesAgo(n: Int) = "$n मिनट पहले"
    override fun hoursAgo(n: Int) = "$n घंटे पहले"
    override val yesterday = "कल"
    override fun daysAgo(n: Int) = "$n दिन पहले"
    override fun shareTitle(n: Int) = "किराने की लिस्ट (${itemCount(n)})"
}
