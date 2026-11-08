package com.grocerysync.app.text

import com.grocerysync.app.data.ShopSection

/**
 * Words several areas of the app share: the usual buttons, the two pages' names, "needed", the item types, item
 * counts and the shop sections' names. A text only one area uses belongs in that area's catalogue instead.
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
    override val toBuy = "खरीदना है"
    override val allItems = "सारा सामान"
    override val needed = "चाहिए"
    override val notNeeded = "नहीं चाहिए"
    override val yesNo = "हाँ या नहीं"
    override val quantity = "मात्रा"
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
        ShopSection.OTHER -> "बाकी"
    }
}
