package com.grocerysync.app.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The item dialog's catalogue as a whole: every fixed Hindi text is Hindi, and the English texts with parts read
 * exactly as the dialog said them before they moved here. (The texts as the dialog's functions use them, in both
 * languages, are checked in ItemTextsHindiTest and the files' own tests.)
 */
class ItemTextsTest {

    /** Every fixed text ([ItemTexts] members without parameters), by name. */
    private fun fixedTexts(texts: ItemTexts): Map<String, String> =
        ItemTexts::class.java.methods
            .filter { it.parameterCount == 0 && it.returnType == String::class.java }
            .associate { it.name to it.invoke(texts) as String }

    @Test
    fun everyFixedHindiTextIsInDevanagariAndNotTheEnglishOne() {
        val english = fixedTexts(EnglishItemTexts)
        val hindi = fixedTexts(HindiItemTexts)
        assertTrue("found ${hindi.size} texts", hindi.size >= 30)
        for ((name, text) in hindi) {
            assertTrue("$name: $text", text.any { it in 'ऀ'..'ॿ' })
            assertNotEquals(name, english.getValue(name), text)
        }
    }

    @Test
    fun englishTextsWithPartsReadAsBefore() {
        val t = EnglishItemTexts
        assertEquals("Keep it under 100 characters", t.nameTooLong(100))
        assertEquals("Enter an amount from 0 to 999999, up to 3 decimals", t.amountError(999_999L, 3))
        assertEquals("0.5 kg less", t.stepLess("0.5 kg"))
        assertEquals("1 more", t.stepMore("1"))
        assertEquals("Choose a unit to save", t.unitPromptToSave)
        assertEquals("Unit: kg", t.unitChosen("kg"))
        assertEquals("Shop section: Masala", t.shopSectionChosen("Masala"))
        assertEquals("Delete \"Rice\"?", t.deleteQuestion("Rice"))
        assertEquals(
            "Changed on another phone to 2 kg. Save to use your value instead.",
            t.changedElsewhere("2 kg"),
        )
    }

    @Test
    fun hindiTextsWithParts() {
        val t = HindiItemTexts
        assertEquals("100 अक्षरों से छोटा रखें", t.nameTooLong(100))
        assertEquals("0 से 999999 तक की मात्रा लिखें, दशमलव के बाद 3 अंक तक", t.amountError(999_999L, 3))
        // The step is data (an amount and its unit): it stays as it is.
        assertEquals("0.5 kg कम", t.stepLess("0.5 kg"))
        assertEquals("100 g ज़्यादा", t.stepMore("100 g"))
        assertEquals("यूनिट: kg", t.unitChosen("kg"))
        assertEquals("सेक्शन: मसाले", t.shopSectionChosen("मसाले"))
        assertEquals("\"Rice\" हटाएँ?", t.deleteQuestion("Rice"))
    }

    @Test
    fun theDialogSaysTheGlossarysWords() {
        // docs/hindi-glossary.md: Unit = यूनिट, Shop section = सेक्शन, To buy = ख़रीदना है (CommonTexts has them too).
        assertEquals(HindiCommonTexts.unit, HindiItemTexts.noUnitHint)
        assertTrue(HindiItemTexts.unitPrompt.startsWith(HindiCommonTexts.unit))
        assertEquals("सेक्शन", HindiItemTexts.shopSection)
        assertEquals("${HindiCommonTexts.toBuy}?", HindiItemTexts.needToBuy)
    }
}
