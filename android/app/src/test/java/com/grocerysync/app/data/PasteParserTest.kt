package com.grocerysync.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
