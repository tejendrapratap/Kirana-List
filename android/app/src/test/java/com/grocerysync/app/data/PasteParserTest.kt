package com.grocerysync.app.data

import com.grocerysync.app.text.CommonTexts
import com.grocerysync.app.text.EnglishCommonTexts
import com.grocerysync.app.text.HindiCommonTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer

/** Reading a pasted or shared list ([PasteParser]): one item per line, with or without an amount. */
class PasteParserTest {

    private fun assertItem(text: String, name: String, amount: String? = null, unit: String? = null) {
        val line = PasteParser.parseLine(text)
        assertNotNull(text, line)
        assertNull("$text: ${line!!.problem}", line.problem)
        assertEquals(text, name, line.name)
        assertEquals(text, amount?.let { Amount.parse(it) }, line.amount)
        assertEquals(text, unit, line.unit)
    }

    private fun assertProblem(text: String, problem: LineProblem) {
        assertEquals(text, problem, PasteParser.parseLine(text)?.problem)
    }

    private fun assertIgnored(text: String) {
        assertNull(text, PasteParser.parseLine(text))
    }

    @Test
    fun readsTheUsualShapes() {
        assertItem("Rice 2 kg", "Rice", "2", "kg")
        assertItem("2 kg rice", "rice", "2", "kg")
        assertItem("Rice - 2kg", "Rice", "2", "kg")
        assertItem("Rice x2", "Rice", "2")
        // No unit: a count (new items get pcs).
        assertItem("Rice 2", "Rice", "2")
        assertItem("Milk 1/2 L", "Milk", "0.5", "L")
        assertItem("Paneer ½ kg", "Paneer", "0.5", "kg")
        assertItem("½ kg paneer", "paneer", "0.5", "kg")
        assertItem("Dahi", "Dahi")
    }

    @Test
    fun readsOtherWaysOfWritingAnAmount() {
        assertItem("Rice: 2 kg", "Rice", "2", "kg")
        assertItem("Rice = 2 kg", "Rice", "2", "kg")
        assertItem("Rice (2 kg)", "Rice", "2", "kg")
        assertItem("Rice (x2)", "Rice", "2")
        assertItem("Rice 2x", "Rice", "2")
        assertItem("Rice x 2 kg", "Rice", "2", "kg")
        assertItem("Rice × 2", "Rice", "2")
        assertItem("2x rice", "rice", "2")
        assertItem("2 x rice", "rice", "2")
        assertItem("2 rice", "rice", "2")
        assertItem("2kg rice", "rice", "2", "kg")
        assertItem("2 kg of rice", "rice", "2", "kg")
        assertItem("Coke 2L", "Coke", "2", "L")
        assertItem("Vim bar 500g", "Vim bar", "500", "g")
        assertItem("Basmati rice 5 kg", "Basmati rice", "5", "kg")
        // How Share writes a quantity without a unit.
        assertItem("Eggs - × 2", "Eggs", "2")
    }

    @Test
    fun readsDecimalsThousandsAndFractions() {
        assertItem("Rice 1.5 kg", "Rice", "1.5", "kg")
        assertItem("Rice 1,5 kg", "Rice", "1.5", "kg")
        assertItem("Rice .5 kg", "Rice", "0.5", "kg")
        assertItem("Rice 2.25 kg", "Rice", "2.25", "kg")
        assertItem("Sugar 1,000 g", "Sugar", "1000", "g")
        assertItem("Salt 1,00,000 g", "Salt", "100000", "g")
        assertItem("Rice 1 1/2 kg", "Rice", "1.5", "kg")
        assertItem("Rice 1½ kg", "Rice", "1.5", "kg")
        assertItem("Rice 1 ½ kg", "Rice", "1.5", "kg")
        assertItem("Milk 1/3 L", "Milk", "0.333", "L")
        assertItem("Milk ¾ L", "Milk", "0.75", "L")
        // Devanagari digits.
        assertItem("चावल २ किलो", "चावल", "2", "kg")
    }

    @Test
    fun cleansUnits() {
        assertItem("Rice 2 kgs", "Rice", "2", "kg")
        assertItem("Rice 2 Kilo", "Rice", "2", "kg")
        assertItem("Milk 1 litre", "Milk", "1", "L")
        assertItem("Eggs 2 dozens", "Eggs", "2", "dozen")
        assertItem("Maggi 4 packets", "Maggi", "4", "pack")
        assertItem("Maggi 4 pkt.", "Maggi", "4", "pack")
        assertItem("Haldi 100 gm", "Haldi", "100", "g")
        assertItem("Dhaniya 2 bunch", "Dhaniya", "2", "bunch")
        assertItem("1 bunch dhaniya", "dhaniya", "1", "bunch")
        assertItem("2 bottles oil", "oil", "2", "bottles")
        assertItem("प्याज़ 1 किलो", "प्याज़", "1", "kg")
        assertItem("Doodh 2 लीटर", "Doodh", "2", "L")
    }

    @Test
    fun dropsBulletsCheckBoxesAndNumbering() {
        for (text in listOf(
            "• Rice", "- Rice", "* Rice", "+ Rice", "1. Rice", "1) Rice", "(1) Rice", "1.Rice", "12. Rice",
            "☐ Rice", "✅ Rice", "✔️ Rice", "[ ] Rice", "[x] Rice", "- [ ] Rice", "👉 Rice", "1. ☐ Rice", "  •  Rice",
        )) {
            assertItem(text, "Rice")
        }
        assertItem("1. 2 kg rice", "rice", "2", "kg")
        assertItem("10) Atta 10 kg", "Atta", "10", "kg")
        // An amount, not numbering.
        assertItem("1.5 kg rice", "rice", "1.5", "kg")
    }

    @Test
    fun dropsFormattingAndPunctuation() {
        assertItem("*Rice* 2 kg", "Rice", "2", "kg")
        assertItem("*Rice 2 kg*", "Rice", "2", "kg")
        assertItem("_Dahi_", "Dahi")
        assertItem("~Oil~", "Oil")
        assertItem("```Oil```", "Oil")
        assertItem("Rice 2 kg.", "Rice", "2", "kg")
        assertItem("Dahi!", "Dahi")
        assertItem("Dahi ✅", "Dahi")
        assertItem("\"Rice\"", "Rice")
        assertItem("दही।", "दही")
    }

    @Test
    fun dropsTheSenderOfACopiedWhatsAppMessage() {
        assertItem("[08/11/26, 10:15 am] Mummy: Rice 2 kg", "Rice", "2", "kg")
        assertItem("[8/11/2026, 10:15:22 PM] Mummy: Dahi", "Dahi")
        // Android writes a narrow no-break space before "am".
        assertItem("[08/11/26, 10:15 am] Mummy: Dahi", "Dahi")
        assertItem("08/11/2026, 10:15 - Mummy: Dahi", "Dahi")
    }

    @Test
    fun keepsNamesThatHoldNoAmount() {
        assertItem("Parle-G", "Parle-G")
        assertItem("7Up", "7Up")
        assertItem("2-minute noodles", "2-minute noodles")
        assertItem("Milk 3%", "Milk 3%")
        assertItem("Rice 2 kg basmati", "Rice 2 kg basmati")
        assertItem("Rice (basmati)", "Rice (basmati)")
        assertItem("Dairy, eggs & bread", "Dairy, eggs & bread")
    }

    @Test
    fun leavesOutBlankLinesDecorationAndSharesHeadings() {
        assertIgnored("")
        assertIgnored("   ")
        assertIgnored("-----")
        assertIgnored("🛒🛒")
        assertIgnored("Grocery list (12 items)")
        assertIgnored("Grocery list (1 item)")
        for (section in ShopSection.entries) assertIgnored("*${section.label}*")
    }

    /** [text] with each nukta letter as one character (ज़ is U+095B), as some keyboards type it. */
    private fun precomposed(text: String): String =
        NUKTA_LETTERS.entries.fold(decomposed(text)) { out, (pair, letter) -> out.replace(pair, letter) }

    /** [text] with each nukta letter as two characters (ज + ़), as other keyboards type it. */
    private fun decomposed(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)

    /** Both ways of writing [text]'s nukta letters; they differ when it has one. */
    private fun nuktaForms(text: String): List<String> = listOf(text, precomposed(text), decomposed(text))

    /**
     * A To buy list grouped by shop section as Share writes it in the language of [texts]: the title, a blank line,
     * then each section's name in bold over its items, the sections separated by blank lines.
     */
    private fun shareText(texts: CommonTexts, sections: List<Pair<ShopSection, List<String>>>): String {
        val lines = mutableListOf(texts.shareTitle(sections.sumOf { it.second.size }), "")
        sections.forEachIndexed { index, (section, items) ->
            if (index > 0) lines += ""
            lines += "*${texts.sectionName(section)}*"
            items.mapTo(lines) { "• $it" }
        }
        return lines.joinToString("\n")
    }

    @Test
    fun theTestsWriteNuktaLettersBothWays() {
        val title = HindiCommonTexts.shareTitle(3)
        assertNotEquals(precomposed(title), decomposed(title))
        assertEquals(SearchText.fold(precomposed(title)), SearchText.fold(decomposed(title)))
    }

    @Test
    fun leavesOutSharesTitleInEveryLanguageForAnyCount() {
        for (texts in listOf(EnglishCommonTexts, HindiCommonTexts)) {
            for (count in listOf(0, 1, 2, 3, 12, 150)) {
                for (title in nuktaForms(texts.shareTitle(count))) assertIgnored(title)
            }
        }
        // As before 1.6: capitals, spaces at the brackets, and the singular or plural word with any count.
        assertIgnored("GROCERY LIST (2 ITEMS)")
        assertIgnored("Grocery list(12 items )")
        assertIgnored("Grocery list (3 item)")
        // The same for the Hindi title, which may also have come with Devanagari digits.
        assertIgnored("किराने की लिस्ट(12 चीज़ें )")
        assertIgnored("किराने की लिस्ट (3 चीज़)")
        assertIgnored("किराने की लिस्ट (३ चीज़ें)")
        assertIgnored("*किराने की लिस्ट (1 चीज़)*")
        // Without its count it is no title: an item of that name, as "Grocery list" always was.
        assertItem("किराने की लिस्ट", "किराने की लिस्ट")
        assertItem("Grocery list", "Grocery list")
        // The Hindi title ends in ':', which someone may delete when forwarding the list: it is left out either way.
        assertIgnored("किराने की लिस्ट (3 चीज़ें)")
        assertIgnored("किराने की लिस्ट (3 चीज़ें) :")
        assertIgnored("Grocery list (3 items):")
    }

    /**
     * How 1.4 and 1.5 read a line that is not in bold, as far as Share's title goes: copies of their PasteParser's
     * rules, which know only the English title. Share's title has nothing their cleaning takes off (a closing
     * bracket and ':' at the end stay). [OLD_HEADING] and [OLD_SKIPPED] are safe; any other line is offered as an
     * item there, ticked.
     */
    private fun olderVersionsRead(line: String): String = when {
        OLD_SHARE_TITLE.matches(line) -> OLD_SKIPPED
        line.endsWith(':') -> OLD_HEADING
        else -> OLD_ITEM
    }

    @Test
    fun olderVersionsDontAddTheHindiTitle() {
        // Family phones may still run 1.4 or 1.5 when a Hindi phone shares; the title must not land on the list.
        for (count in listOf(0, 1, 2, 3, 12, 150)) {
            for (title in nuktaForms(HindiCommonTexts.shareTitle(count))) {
                assertNotEquals(title, OLD_ITEM, olderVersionsRead(title))
            }
            assertEquals(OLD_SKIPPED, olderVersionsRead(EnglishCommonTexts.shareTitle(count)))
        }
        // Why it ends in ':': without one they read "(3 चीज़ें)" as an amount and a unit of the user's own, and offer
        // "किराने की लिस्ट" as an item of 3 चीज़ें.
        val withoutColon = HindiCommonTexts.shareTitle(3).removeSuffix(":")
        assertEquals(OLD_ITEM, olderVersionsRead(withoutColon))
        val read = OLD_PAREN_AMOUNT.matchEntire(withoutColon)
        assertNotNull(withoutColon, read)
        assertEquals(listOf("किराने की लिस्ट", "3", "चीज़ें"), read!!.groupValues.drop(1))
    }

    @Test
    fun leavesOutSharesSectionHeadingsInEveryLanguage() {
        for (section in ShopSection.entries) {
            for (name in listOf(EnglishCommonTexts.sectionName(section), HindiCommonTexts.sectionName(section))) {
                for (heading in nuktaForms(name)) assertIgnored("*$heading*")
            }
        }
        // Some of them have nukta letters, so both ways of writing them are checked above.
        assertTrue(ShopSection.entries.map(HindiCommonTexts::sectionName).any { precomposed(it) != decomposed(it) })
        // Not in bold, a section's name is an item's, in Hindi as in English.
        assertItem("मसाले", "मसाले")
        assertItem("सब्ज़ी और फल", "सब्ज़ी और फल")
        // Only a whole section name: a part of one in bold may be a heading of the family's own list.
        assertEquals(true, PasteParser.parseLine("*सब्ज़ी*")?.bold)
    }

    @Test
    fun aHindiShareGivesTheSameItemsAsAnEnglishOne() {
        // Every section, so every heading is read; names and amounts are data, the same in both languages.
        val items = listOf(
            "Onion - 1 kg", "Dahi", "Chicken - 500 g", "Atta - 10 kg", "Ghee - 1 L", "Haldi - 1 pack",
            "Chai patti - 250 g", "Kaju - 200 g", "Maggi - 4 pack", "Vim bar - × 2", "Colgate", "Agarbatti", "Batteries",
        )
        val sections = ShopSection.entries.zip(items) { section, item -> section to listOf(item) }
        val english = PasteParser.parse(shareText(EnglishCommonTexts, sections))
        assertEquals(
            listOf(
                "Onion", "Dahi", "Chicken", "Atta", "Ghee", "Haldi", "Chai patti", "Kaju", "Maggi", "Vim bar", "Colgate",
                "Agarbatti", "Batteries",
            ),
            english.map { it.name },
        )
        assertTrue(english.none { it.bold || it.problem != null })

        for (hindi in nuktaForms(shareText(HindiCommonTexts, sections))) assertEquals(english, PasteParser.parse(hindi))
        // One item: the singular title.
        val one = listOf(ShopSection.DAIRY_EGGS_BREAD to listOf("Dahi"))
        for (hindi in nuktaForms(shareText(HindiCommonTexts, one))) {
            assertEquals(PasteParser.parse(shareText(EnglishCommonTexts, one)), PasteParser.parse(hindi))
        }
    }

    @Test
    fun aPasteMayMixLanguages() {
        // Two shared messages, one from a Hindi phone (copied with its sender), one from an English phone, then the
        // family's own lines in Hindi and English.
        val text = listOf(
            "[08/11/26, 10:15 am] Mummy: ${HindiCommonTexts.shareTitle(2)}",
            "",
            "*${decomposed(HindiCommonTexts.sectionName(ShopSection.SABZI_FRUIT))}*",
            "• प्याज़ - 1 kg",
            "• Dhaniya",
            EnglishCommonTexts.shareTitle(1),
            "*${EnglishCommonTexts.sectionName(ShopSection.DAIRY_EGGS_BREAD)}*",
            "• Dahi",
            "*${precomposed(HindiCommonTexts.sectionName(ShopSection.CLEANING_HOUSEHOLD))}*",
            "चावल २ किलो",
            "*Sabzi*",
        ).joinToString("\n")
        val lines = PasteParser.parse(text)
        assertEquals(listOf("प्याज़", "Dhaniya", "Dahi", "चावल", "Sabzi"), lines.map { it.name })
        assertEquals(listOf(Amount.parse("1"), null, null, Amount.parse("2"), null), lines.map { it.amount })
        assertEquals(listOf("kg", null, null, "kg", null), lines.map { it.unit })
        // A bold line that is no section name of Share's may be a heading of the family's own: the preview decides.
        assertEquals(listOf(false, false, false, false, true), lines.map { it.bold })
    }

    @Test
    fun marksALineWhollyInBold() {
        // It may be a heading of a hand-written list; the preview decides (it knows the list and the dictionary).
        for (text in listOf("*Sabzi*", "*Dahi*", "*Rice 2 kg*")) assertEquals(text, true, PasteParser.parseLine(text)?.bold)
        assertEquals("Sabzi", PasteParser.parseLine("*Sabzi*")?.name)
        for (text in listOf("Sabzi", "*Rice* 2 kg", "* Rice", "• *Dahi*", "_Dahi_")) {
            assertEquals(text, false, PasteParser.parseLine(text)?.bold)
        }
    }

    @Test
    fun aLineEndingInAColonIsAHeading() {
        val line = PasteParser.parseLine("Sabzi:")
        assertEquals(LineProblem.HEADING, line?.problem)
        assertEquals("Sabzi", line?.text)
        assertProblem("*Kirana list:*", LineProblem.HEADING)
    }

    @Test
    fun saysWhatItCantRead() {
        assertProblem("2 kg", LineProblem.UNREADABLE)
        assertProblem("2", LineProblem.UNREADABLE)
        assertProblem("½", LineProblem.UNREADABLE)
        assertProblem("Rice 2-3 kg", LineProblem.UNREADABLE)
        assertProblem("Rice 2 - 3 kg", LineProblem.UNREADABLE)
        assertProblem("Rice 1/0 kg", LineProblem.UNREADABLE)
        assertProblem("Rice 1.2.3 kg", LineProblem.UNREADABLE)
        assertProblem("Rice 9999999 kg", LineProblem.UNREADABLE)
        assertProblem("Rice 0 kg", LineProblem.ZERO_AMOUNT)
        assertProblem("Rice 0.0001 kg", LineProblem.ZERO_AMOUNT)
        assertProblem("A".repeat(InputValidation.MAX_NAME_LENGTH + 1), LineProblem.NAME_TOO_LONG)
        assertProblem("A".repeat(InputValidation.MAX_NAME_LENGTH + 1) + " 2 kg", LineProblem.NAME_TOO_LONG)
        assertProblem("Rice ".repeat(100), LineProblem.NAME_TOO_LONG)
    }

    @Test
    fun aLineKeepsItsTextForThePreview() {
        assertEquals("Rice 2-3 kg", PasteParser.parseLine("• *Rice 2-3 kg*")?.text)
        assertEquals("Atta - 10 kg", PasteParser.parseLine("• Atta - 10 kg")?.text)
    }

    @Test
    fun parsesEveryLineInOrder() {
        val lines = PasteParser.parse("Grocery list (3 items)\r\n\r\n• Rice 2 kg\n\n*Dairy, eggs & bread*\n• Dahi\r• Eggs 1 dozen\n")
        assertEquals(listOf("Rice", "Dahi", "Eggs"), lines.map { it.name })
    }

    @Test
    fun aPasteHasAtMost200LinesAnd10000Characters() {
        val lines200 = (1..200).joinToString("\n") { "Item $it" }
        assertFalse(PasteParser.isTooLong(lines200))
        assertEquals(200, PasteParser.parse(lines200).size)
        assertTrue(PasteParser.isTooLong(lines200 + "\nOne more"))
        // Blank lines don't count.
        assertFalse(PasteParser.isTooLong(lines200 + "\n\n   \n"))
        assertEquals(200, PasteParser.lineCount(lines200 + "\n\n   \n"))
        assertFalse(PasteParser.isTooLong("a".repeat(PasteParser.MAX_CHARS)))
        assertTrue(PasteParser.isTooLong("a".repeat(PasteParser.MAX_CHARS + 1)))
        // parse() itself never reads more than 200 lines.
        assertEquals(200, PasteParser.parse(lines200 + "\nOne more").size)
    }

    private companion object {
        /** The Devanagari letters with a nukta of their own (U+0958 to U+095F), by their letter + nukta. */
        val NUKTA_LETTERS = mapOf(
            "क़" to "क़", "ख़" to "ख़", "ग़" to "ग़", "ज़" to "ज़",
            "ड़" to "ड़", "ढ़" to "ढ़", "फ़" to "फ़", "य़" to "य़",
        )

        // 1.4 and 1.5's PasteParser, for olderVersionsRead (their SHARE_TITLE, AMOUNT, UNIT and PAREN_AMOUNT).
        val OLD_SHARE_TITLE = Regex("""grocery list\s*\(\s*\d+\s+items?\s*\)""", RegexOption.IGNORE_CASE)
        private const val OLD_NUMBER = """\d+(?:[.,]\d+)*|[.,]\d+"""
        private const val OLD_AMOUNT = """\d+\s\d+\s?/\s?\d+|\d+\s?/\s?\d+|$OLD_NUMBER"""
        private const val OLD_UNIT = """[\p{L}\p{M}][\p{L}\p{M}.]*"""
        val OLD_PAREN_AMOUNT =
            Regex("""^(.+?)\s?\(\s?(?:x\s?)?($OLD_AMOUNT)\s?($OLD_UNIT)?\s?\)$""", RegexOption.IGNORE_CASE)

        const val OLD_SKIPPED = "left out"
        const val OLD_HEADING = "a heading"
        const val OLD_ITEM = "an item"
    }
}
