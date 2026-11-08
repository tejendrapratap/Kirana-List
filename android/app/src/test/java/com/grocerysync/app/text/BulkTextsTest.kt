package com.grocerysync.app.text

import com.grocerysync.app.data.Amount
import com.grocerysync.app.data.CatalogSet
import com.grocerysync.app.data.PasteParser
import com.grocerysync.app.data.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The texts of Add common items and Paste a list ([BulkTexts]) as a catalogue: the glossary's words, and every
 * text in Hindi in the Hindi one. How the screens put them together: StarterListTest and PastePlanTest.
 */
class BulkTextsTest {

    @Test
    fun setsAreNamedAsInTheGlossary() {
        assertEquals(
            listOf("Everyday", "Monthly kirana", "Diwali puja", "Vrat / fasting", "All"),
            CatalogSet.entries.map(EnglishBulkTexts::setName),
        )
        assertEquals(
            listOf("रोज़ का", "महीने का किराना", "दिवाली पूजा", "व्रत", "सब"),
            CatalogSet.entries.map(HindiBulkTexts::setName),
        )
    }

    @Test
    fun titlesAreTheGlossarys() {
        assertEquals("Add common items", EnglishBulkTexts.addCommonItemsTitle)
        assertEquals("आम सामान जोड़ें", HindiBulkTexts.addCommonItemsTitle)
        assertEquals("Paste a list", EnglishBulkTexts.pasteListTitle)
        assertEquals("लिस्ट पेस्ट करें", HindiBulkTexts.pasteListTitle)
    }

    /** Every text of the catalogue, with example parts for those that take some. */
    private fun everyText(texts: BulkTexts): List<String> = with(texts) {
        CatalogSet.entries.map(::setName) + listOf(
            addCommonItemsTitle, addCommonItemsHint, allOfSetOnList, selectAll, selectNone, onListAs("Tel"),
            onListAsSpoken("Oil", "Tel"), yesNoSpoken, addItemsButton(0), addItemsButton(1), addItemsButton(3),
            commonItemsAdded(0), commonItemsAdded(1), commonItemsAdded(3), commonItemsKept(1), commonItemsKept(3),
            connectFirst, waitForList, pasteListTitle, pasteHelp, paste, nothingToPaste, listField, listFieldExample,
            lineCount(1), lineCount(3), pasteTooLong(200, 10_000), next, editList, shareBeforeSetup, shareUnreadable,
            waitingForList, nothingInPaste, pasteSummary(1, 2, 3, 4), addRow("Rice", " - 2 kg"),
            markNeededRow("Dahi", ""), alreadyNeededRow("Oil"), changeAmountRow("Rice", "2 kg", "3 kg"),
            changeAmountRowSpoken("Rice", "2 kg", "3 kg"), skippedRow("Rice 2"), writtenAs("Curd"),
            amountLeftOut("Dahi"), amountLeftOutSpoken("Dahi"), looksLikeHeadingNotTicked, unreadable, zeroAmount,
            nameTooLong, looksLikeHeading, inOtherUnit("Eggs", "dozen"), listedTwice, pasteConfirm(0, 0),
            pasteConfirm(1, 0), pasteConfirm(0, 3), pasteConfirm(2, 3), pasteResult(0, 0, 0), pasteResult(1, 0, 0),
            pasteResult(3, 2, 1), pasteResult(0, 1, 2),
        )
    }

    @Test
    fun everyHindiTextIsInHindi() {
        val english = everyText(EnglishBulkTexts)
        val hindi = everyText(HindiBulkTexts)
        for ((englishText, hindiText) in english.zip(hindi)) {
            assertNotEquals(englishText, hindiText)
            assertTrue(hindiText, hindiText.any { it in 'ऀ'..'ॿ' })
        }
    }

    @Test
    fun theExamplesOfEachLanguagePasteAsTheyRead() {
        // The text field's example is a list Paste a list reads, Hindi unit words (किलो) included.
        for ((texts, names) in listOf(
            EnglishBulkTexts to listOf("Rice", "Dahi", "Onion"),
            HindiBulkTexts to listOf("चावल", "दही", "प्याज़"),
        )) {
            val lines = PasteParser.parse(texts.listFieldExample)
            assertEquals(names, lines.map { it.name })
            assertEquals(listOf(Amount.parse("2"), null, Amount.parse("1")), lines.map { it.amount })
            assertEquals(listOf(Units.KG, null, Units.KG), lines.map { it.unit })
        }
    }

    @Test
    fun theHelpNamesThePasteButton() {
        // The help says which button to tap, by the name it has on the screen.
        assertTrue(EnglishBulkTexts.pasteHelp.contains("tap ${EnglishBulkTexts.paste}."))
        assertTrue(HindiBulkTexts.pasteHelp.contains("\"${HindiBulkTexts.paste}\" दबाएँ"))
    }
}
