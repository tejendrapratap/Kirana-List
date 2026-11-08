package com.grocerysync.app.ui

import com.grocerysync.app.data.Amount
import com.grocerysync.app.data.GroceryItem
import com.grocerysync.app.data.ItemKeys
import com.grocerysync.app.data.ItemValue
import com.grocerysync.app.data.PasteParser
import com.grocerysync.app.data.ShopSection
import com.grocerysync.app.data.Units
import com.grocerysync.app.text.AppTexts
import com.grocerysync.app.text.EnglishCommonTexts
import com.grocerysync.app.text.HindiCommonTexts
import com.grocerysync.app.text.withTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer

/**
 * A list shared from a phone that shows Hindi ([buildShareText] writes its title and section headings in Hindi) and
 * pasted on one that shows English ([PasteParser], [planPaste]) gives the same items and amounts, and the other way
 * round: Paste a list skips Share's own lines in both languages, whichever way the nukta letters were written
 * (ShareRoundTripTest does the same within English).
 */
class HindiShareRoundTripTest {

    private fun item(name: String, value: ItemValue) =
        GroceryItem(ItemKeys.normalize(name), name, value, updatedAt = 1_000L, updatedBy = "Home", pendingSync = false)

    private fun yesNo(name: String, needed: Boolean = true) = item(name, ItemValue.BoolValue(needed))

    private fun quantity(name: String, amount: String, unit: String) =
        item(name, ItemValue.Quantity(Amount.parse(amount)!!, unit))

    /** One needed item in each shop section, so the message has every heading; names in English and in Hindi. */
    private val bySection: Map<GroceryItem, ShopSection> = linkedMapOf(
        quantity("प्याज़", "1", Units.KG) to ShopSection.SABZI_FRUIT,
        yesNo("Dahi") to ShopSection.DAIRY_EGGS_BREAD,
        quantity("Chicken", "500", Units.G) to ShopSection.MEAT_FISH,
        quantity("Atta", "10", Units.KG) to ShopSection.ATTA_RICE_DAL,
        quantity("Ghee", "1", Units.L) to ShopSection.OIL_GHEE,
        quantity("हल्दी", "1", Units.PACK) to ShopSection.MASALA,
        quantity("Chai patti", "250", Units.G) to ShopSection.TEA_SUGAR_SALT,
        quantity("Kaju", "200", Units.G) to ShopSection.DRY_FRUITS_NUTS,
        quantity("Parle-G", "3", Units.PACK) to ShopSection.SNACKS_PACKAGED,
        yesNo("Surf") to ShopSection.CLEANING_HOUSEHOLD,
        quantity("Toothpaste", "2", Units.PCS) to ShopSection.PERSONAL_CARE,
        yesNo("Agarbatti") to ShopSection.PUJA,
        quantity("Coriander", "1", "गड्डी") to ShopSection.OTHER,
    )

    /** The list on the sharing phone: [bySection]'s items and two that aren't needed (not in the message). */
    private val shared: List<GroceryItem> =
        bySection.keys.toList() + listOf(yesNo("Coffee", needed = false), quantity("Rice", "0", Units.KG))

    private val expected: List<PasteAction.Add> = bySection.keys.map { PasteAction.Add(it.name, it.value) }

    private fun sectionOf(item: GroceryItem): ShopSection = bySection.getValue(item)

    private fun pasted(text: String): List<PasteAction> = planPaste(PasteParser.parse(text), emptyList()).map { it.action }

    private fun List<PasteAction>.sortedByName() = map { it as PasteAction.Add }.sortedBy { it.name }

    /** [text] with every nukta letter as one character (ज़ as U+095B), as some keyboards and apps write it. */
    private fun precomposed(text: String): String = NUKTA_LETTERS.entries.fold(text) { acc, (pair, letter) -> acc.replace(pair, letter) }

    /** [text] with every nukta letter as its letter followed by the nukta (ज + ़), as other keyboards write it. */
    private fun decomposed(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)

    @Test
    fun aGroupedListSharedInHindiComesBackOnAnEnglishPhone() {
        val text = withTexts(AppTexts.Hindi) { buildShareText(shared, ::sectionOf) }
        // It really is the Hindi message: the Hindi title and every section's Hindi heading in bold.
        assertTrue(text, text.startsWith(HindiCommonTexts.shareTitle(bySection.size) + "\n"))
        for (section in ShopSection.entries) {
            assertTrue(section.name, text.lines().contains("*${HindiCommonTexts.sectionName(section)}*"))
        }

        assertEquals(expected.sortedBy { it.name }, pasted(text).sortedByName())
    }

    @Test
    fun aGroupedListSharedInEnglishComesBackOnAHindiPhone() {
        val text = buildShareText(shared, ::sectionOf)
        assertTrue(text, text.startsWith(EnglishCommonTexts.shareTitle(bySection.size) + "\n"))

        val actions = withTexts(AppTexts.Hindi) { pasted(text) }
        assertEquals(expected.sortedBy { it.name }, actions.sortedByName())
    }

    @Test
    fun anAtoZListSharedInHindiComesBackInTheSameOrder() {
        val text = withTexts(AppTexts.Hindi) { buildShareText(shared, null) }
        assertEquals(expected.sortedBy { it.name.lowercase() }, pasted(text))
    }

    @Test
    fun oneItemHasTheSingularHindiTitleThatIsSkippedToo() {
        val text = withTexts(AppTexts.Hindi) { buildShareText(listOf(yesNo("Dahi")), ::catalogSectionOf) }
        assertEquals("किराने की लिस्ट (1 चीज़):\n\n*दूध, अंडे और ब्रेड*\n• Dahi", text)
        assertEquals(listOf(PasteAction.Add("Dahi", ItemValue.BoolValue(true))), pasted(text))
    }

    @Test
    fun aHindiShareComesBackWhicheverWayItsNuktaLettersAreWritten() {
        val text = withTexts(AppTexts.Hindi) { buildShareText(shared, ::sectionOf) }
        val oneLetter = precomposed(text)
        val twoLetters = decomposed(oneLetter)
        // Both forms are really in the messages (the title's चीज़ें, सब्ज़ी, सफ़ाई), so each is checked.
        assertTrue(oneLetter.contains('ज़') && !oneLetter.contains('़'))
        assertTrue(twoLetters.contains('़') && !twoLetters.contains('ज़'))

        for (message in listOf(oneLetter, twoLetters)) {
            // Item names come back as written in the message (प्याज़ in either form); only Share's lines are skipped.
            assertEquals(
                expected.map { it.name to it.value }.sortedBy { it.first }.map { (name, value) -> decomposed(name) to value },
                pasted(message).sortedByName().map { decomposed(it.name) to it.value },
            )
        }
    }

    private companion object {
        /** The letters with a nukta that have a one-character form (U+0958 to U+095F), by their two-character form. */
        val NUKTA_LETTERS: Map<String, String> = ('क़'..'य़').associate { letter ->
            Normalizer.normalize(letter.toString(), Normalizer.Form.NFD) to letter.toString()
        }
    }
}
