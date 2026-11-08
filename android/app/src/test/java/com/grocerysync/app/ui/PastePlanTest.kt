package com.grocerysync.app.ui

import com.grocerysync.app.data.Amount
import com.grocerysync.app.data.GroceryItem
import com.grocerysync.app.data.ItemKeys
import com.grocerysync.app.data.ItemValue
import com.grocerysync.app.data.ItemValue.BoolValue
import com.grocerysync.app.data.ItemValue.Quantity
import com.grocerysync.app.data.PasteParser
import com.grocerysync.app.data.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What each line of a paste does to the list ([planPaste]) and what applying it does later ([pasteEdit]). */
class PastePlanTest {

    private fun item(name: String, value: ItemValue) =
        GroceryItem(ItemKeys.normalize(name), name, value, updatedAt = 1_000L, updatedBy = "Home", pendingSync = false)

    private fun qty(amount: String, unit: String) = Quantity(Amount.parse(amount)!!, unit)

    private fun actions(text: String, vararg items: GroceryItem): List<PasteAction> =
        planPaste(PasteParser.parse(text), items.toList()).map { it.action }

    private fun action(text: String, vararg items: GroceryItem): PasteAction = actions(text, *items).single()

    @Test
    fun anotherNameAddsANewItemAsNeeded() {
        assertEquals(
            listOf(
                PasteAction.Add("Rice", qty("2", Units.KG)),
                PasteAction.Add("Dahi", BoolValue(true)),
                // No unit: pieces.
                PasteAction.Add("Maggi", qty("4", Units.PCS)),
            ),
            actions("Rice 2 kg\nDahi\nmaggi 4", item("Oil", BoolValue(true))),
        )
    }

    @Test
    fun aYesNoItemIsMarkedNeededAndIgnoresAnAmount() {
        val dahi = item("Dahi", BoolValue(false))
        val marked = PasteAction.MarkNeeded("dahi", "Dahi", null, BoolValue(false), BoolValue(true))
        assertEquals(marked, action("dahi", dahi))
        assertEquals(marked, action("Dahi 2 kg", dahi))
        assertEquals(PasteAction.AlreadyNeeded("Dahi", null), action("Dahi", item("Dahi", BoolValue(true))))
    }

    @Test
    fun anotherNameOfAnItemOnTheListChangesThatItem() {
        val dahi = item("Dahi", BoolValue(false))
        assertEquals(PasteAction.MarkNeeded("dahi", "Dahi", "Curd", BoolValue(false), BoolValue(true)), action("Curd", dahi))
        val onion = item("Onion", qty("0", Units.KG))
        assertEquals(
            PasteAction.MarkNeeded("onion", "Onion", "pyaaz", qty("0", Units.KG), qty("1", Units.KG)),
            action("pyaaz 1 kg", onion),
        )
        assertEquals(PasteAction.AlreadyNeeded("Oil", "Cooking oil"), action("Cooking oil", item("Oil", qty("1", Units.L))))
        // Both names on the list: the one the dictionary calls it by.
        val curd = item("Curd", BoolValue(false))
        assertEquals("Dahi", (action("Yogurt", curd, dahi) as PasteAction.MarkNeeded).name)
        assertEquals("Curd", (action("Yogurt", curd) as PasteAction.MarkNeeded).name)
    }

    @Test
    fun aQuantityTakesTheAmountOfItsUnit() {
        val riceNone = item("Rice", qty("0", Units.KG))
        val rice2 = item("Rice", qty("2", Units.KG))
        assertEquals(PasteAction.MarkNeeded("rice", "Rice", null, qty("0", Units.KG), qty("2", Units.KG)), action("Rice 2 kg", riceNone))
        assertEquals(PasteAction.AlreadyNeeded("Rice", null), action("Rice 2 kg", rice2))
        assertEquals(PasteAction.AlreadyNeeded("Rice", null), action("Rice", rice2))
        assertEquals(PasteAction.ChangeAmount("rice", "Rice", null, qty("2", Units.KG), qty("3", Units.KG)), action("Rice 3 kg", rice2))
    }

    @Test
    fun withoutAnAmountAQuantityIsMarkedNeededWithOneUnitOrOneStep() {
        assertEquals(qty("1", Units.KG), (action("rice", item("Rice", qty("0", Units.KG))) as PasteAction.MarkNeeded).value)
        assertEquals(qty("100", Units.G), (action("Paneer", item("Paneer", qty("0", Units.G))) as PasteAction.MarkNeeded).value)
        assertEquals(qty("1", Units.PACK), (action("Maggi", item("Maggi", qty("0", Units.PACK))) as PasteAction.MarkNeeded).value)
        assertEquals(qty("1", "bunch"), (action("Dhaniya", item("Dhaniya", qty("0", "bunch"))) as PasteAction.MarkNeeded).value)
        assertEquals(Amount.ONE, neededAmount(""))
    }

    @Test
    fun anAmountInAnotherUnitIsSkipped() {
        val rice = item("Rice", qty("0", Units.KG))
        val mismatch = PasteAction.Skipped(SkipReason.UNIT_MISMATCH, "Rice", Units.KG)
        assertEquals(mismatch, action("Rice 2 pcs", rice))
        // A bare number is never kg, g, L, ml or dozen.
        assertEquals(mismatch, action("Rice 2", rice))
        assertEquals(PasteAction.Skipped(SkipReason.UNIT_MISMATCH, "Eggs", Units.DOZEN), action("Eggs 12", item("Eggs", qty("0", Units.DOZEN))))
        // ...but it counts packs and units of your own.
        assertEquals(qty("4", Units.PACK), (action("Maggi 4", item("Maggi", qty("0", Units.PACK))) as PasteAction.MarkNeeded).value)
        assertEquals(qty("2", "Bunch"), (action("Dhaniya 2 bunch", item("Dhaniya", qty("0", "Bunch"))) as PasteAction.MarkNeeded).value)
    }

    @Test
    fun aQuantityFromBeforeUnitsTakesTheLinesUnit() {
        val eggs = item("Eggs", qty("0", ""))
        assertEquals(qty("2", Units.DOZEN), (action("Eggs 2 dozen", eggs) as PasteAction.MarkNeeded).value)
        assertEquals(qty("2", ""), (action("Eggs 2", eggs) as PasteAction.MarkNeeded).value)
        assertEquals(PasteAction.AlreadyNeeded("Eggs", null), action("Eggs x2", item("Eggs", qty("2", ""))))
    }

    @Test
    fun unitsFit() {
        assertTrue(unitFits(Units.KG, Units.KG))
        assertTrue(unitFits("bunch", "Bunch"))
        assertFalse(unitFits(Units.KG, Units.G))
        assertTrue(unitFits(Units.PCS, null))
        assertTrue(unitFits(Units.PACK, null))
        assertTrue(unitFits("bottle", null))
        for (measured in listOf(Units.KG, Units.G, Units.L, Units.ML, Units.DOZEN)) assertFalse(measured, unitFits(measured, null))
        assertTrue(unitFits("", Units.KG))
        assertTrue(unitFits("", null))
    }

    @Test
    fun aLaterLineForTheSameItemIsSkipped() {
        val repeated = PasteAction.Skipped(SkipReason.REPEATED)
        assertEquals(repeated, actions("Rice 2 kg\nrice 1 kg")[1])
        // Two names of one new item add it once.
        assertEquals(listOf(PasteAction.Add("Curd", BoolValue(true)), repeated), actions("Curd\nDahi"))
        assertEquals(repeated, actions("Dahi\nCurd", item("Dahi", BoolValue(false)))[1])
        // A skipped line doesn't take the item.
        val rice = item("Rice", qty("0", Units.KG))
        assertEquals(
            listOf(
                PasteAction.Skipped(SkipReason.UNIT_MISMATCH, "Rice", Units.KG),
                PasteAction.MarkNeeded("rice", "Rice", null, qty("0", Units.KG), qty("2", Units.KG)),
            ),
            actions("Rice 2 pcs\nRice 2 kg", rice),
        )
    }

    @Test
    fun linesTheParserCantUseAreSkipped() {
        assertEquals(
            listOf(SkipReason.UNREADABLE, SkipReason.ZERO_AMOUNT, SkipReason.HEADING, SkipReason.NAME_TOO_LONG),
            actions("2 kg\nRice 0 kg\nSabzi:\n" + "A".repeat(101)).map { (it as PasteAction.Skipped).reason },
        )
    }

    @Test
    fun onlyChangesHaveCheckBoxesAndANewAmountStartsUnticked() {
        val rows = planPaste(
            PasteParser.parse("Rice 3 kg\nDahi\nOil\nAtta 5 kg\n2 kg"),
            listOf(item("Rice", qty("2", Units.KG)), item("Dahi", BoolValue(false)), item("Oil", BoolValue(true))),
        )
        assertEquals(listOf(true, true, false, true, false), rows.map { it.selectable })
        assertEquals(listOf(false, true, false, true, false), rows.map { it.selectedByDefault })
        assertEquals(listOf(0, 1, 2, 3, 4), rows.map { it.index })
        assertEquals("2 kg", rows[4].line)
    }

    /** Feature 1's text pasted on another phone gives the same items and amounts. */
    @Test
    fun aSharedListPastedOnAnotherPhoneGivesTheSameItems() {
        val grouped = """
            Grocery list (5 items)

            *Sabzi & fruit*
            • Coriander - 1 bunch

            *Dairy, eggs & bread*
            • Dahi
            • Milk - 1.5 L

            *Atta, rice & dal*
            • Atta - 10 kg

            *Oil & ghee*
            • Oil - 0.5 L
        """.trimIndent()
        val expected = listOf(
            PasteAction.Add("Coriander", qty("1", "bunch")),
            PasteAction.Add("Dahi", BoolValue(true)),
            PasteAction.Add("Milk", qty("1.5", Units.L)),
            PasteAction.Add("Atta", qty("10", Units.KG)),
            PasteAction.Add("Oil", qty("0.5", Units.L)),
        )
        assertEquals(expected, actions(grouped))
        val aToZ = "Grocery list (5 items)\n\n• Atta - 10 kg\n• Coriander - 1 bunch\n• Dahi\n• Milk - 1.5 L\n• Oil - 0.5 L"
        assertEquals(expected.sortedBy { (it as PasteAction.Add).name }, actions(aToZ))
        // On a phone that has them, not needed: each is marked needed with the same amount.
        val there = listOf(
            item("Atta", qty("0", Units.KG)), item("Coriander", qty("0", "bunch")), item("Dahi", BoolValue(false)),
            item("Milk", qty("0", Units.L)), item("Oil", qty("0", Units.L)),
        )
        val marked = actions(aToZ, *there.toTypedArray()).map { (it as PasteAction.MarkNeeded).value }
        assertEquals(expected.sortedBy { (it as PasteAction.Add).name }.map { (it as PasteAction.Add).value }, marked)
        // A quantity without a unit comes back as pieces (a new quantity needs a unit).
        assertEquals(PasteAction.Add("Eggs", qty("2", Units.PCS)), action("• Eggs - × 2"))
    }

    @Test
    fun applyingChecksTheListAsItIsThen() {
        val dahi = item("Dahi", BoolValue(false))
        val add = PasteAction.Add("Rice", qty("2", Units.KG))
        assertEquals(PasteEdit.Add("Rice", qty("2", Units.KG)), pasteEdit(add, null))
        assertEquals(PasteEdit.ChangedMeanwhile, pasteEdit(add, item("rice", BoolValue(true))))

        val mark = PasteAction.MarkNeeded("dahi", "Dahi", "Curd", BoolValue(false), BoolValue(true))
        assertEquals(PasteEdit.Update("Dahi", BoolValue(true)), pasteEdit(mark, dahi))
        assertEquals(PasteEdit.Unchanged, pasteEdit(mark, item("Dahi", BoolValue(true))))
        assertEquals(PasteEdit.ChangedMeanwhile, pasteEdit(mark, item("Dahi", qty("0", Units.KG))))
        assertEquals(PasteEdit.ChangedMeanwhile, pasteEdit(mark, null))

        val change = PasteAction.ChangeAmount("rice", "Rice", null, qty("2", Units.KG), qty("3", Units.KG))
        assertEquals(PasteEdit.Update("Rice", qty("3", Units.KG)), pasteEdit(change, item("Rice", qty("2", Units.KG))))
        assertEquals(PasteEdit.ChangedMeanwhile, pasteEdit(change, item("Rice", qty("2.5", Units.KG))))
        assertEquals(PasteEdit.ChangedMeanwhile, pasteEdit(change, null))

        assertEquals(PasteEdit.Unchanged, pasteEdit(PasteAction.AlreadyNeeded("Oil", null), null))
        assertEquals(PasteEdit.Unchanged, pasteEdit(PasteAction.Skipped(SkipReason.REPEATED), null))
        assertEquals("rice", add.itemKey)
        assertNull(PasteAction.AlreadyNeeded("Oil", null).itemKey)
    }

    @Test
    fun theDraftIsKeptAsOneValue() {
        val draft = PasteDraft(text = "Rice 2 kg\n\nP+1\nDahi", previewing = true, choices = mapOf(1 to true, 3 to false))
        assertEquals(draft, PasteDraft.decode(draft.encode()))
        assertEquals(PasteDraft("", previewing = false), PasteDraft.decode(PasteDraft("", previewing = false).encode()))
        assertNull(PasteDraft.decode(null))
        assertNull(PasteDraft.decode(""))
        assertNull(PasteDraft.decode("Rice"))
    }

    @Test
    fun theStateTicksDefaultsAndTheUsersChoices() {
        val items = listOf(item("Rice", qty("2", Units.KG)))
        val text = "Rice 3 kg\nDahi\nOil"
        val state = PasteListState.of(PasteDraft(text, previewing = true), items)
        assertEquals(setOf(1, 2), state.selected)
        assertEquals(listOf(PasteAction.Add("Dahi", BoolValue(true)), PasteAction.Add("Oil", BoolValue(true))), state.chosen)
        val chosen = PasteListState.of(PasteDraft(text, previewing = true, choices = mapOf(0 to true, 2 to false)), items)
        assertEquals(setOf(0, 1), chosen.selected)
        assertEquals(
            listOf(PasteAction.ChangeAmount("rice", "Rice", null, qty("2", Units.KG), qty("3", Units.KG)), PasteAction.Add("Dahi", BoolValue(true))),
            chosen.chosen,
        )
        // The text step plans nothing.
        assertEquals(PasteListState(text, previewing = false), PasteListState.of(PasteDraft(text, previewing = false), items))
    }

    @Test
    fun rowTexts() {
        fun row(action: PasteAction, line: String = "") = PasteRow(0, line, action)
        assertEquals("Add Rice - 2 kg", pasteRowText(row(PasteAction.Add("Rice", qty("2", Units.KG)))))
        assertEquals("Add Dahi", pasteRowText(row(PasteAction.Add("Dahi", BoolValue(true)))))
        val curd = row(PasteAction.MarkNeeded("dahi", "Dahi", "Curd", BoolValue(false), BoolValue(true)))
        assertEquals("Mark Dahi needed", pasteRowText(curd))
        assertEquals("Curd → Dahi", pasteRowDetail(curd))
        assertEquals("Mark Rice needed - 2 kg", pasteRowText(row(PasteAction.MarkNeeded("rice", "Rice", null, qty("0", Units.KG), qty("2", Units.KG)))))
        assertEquals("Already needed: Oil", pasteRowText(row(PasteAction.AlreadyNeeded("Oil", null))))
        assertNull(pasteRowDetail(row(PasteAction.AlreadyNeeded("Oil", null))))
        assertEquals(
            "Change Rice 2 kg → 3 kg",
            pasteRowText(row(PasteAction.ChangeAmount("rice", "Rice", null, qty("2", Units.KG), qty("3", Units.KG)))),
        )
        val mismatch = row(PasteAction.Skipped(SkipReason.UNIT_MISMATCH, "Rice", Units.KG), line = "Rice 2")
        assertEquals("Skipped: Rice 2", pasteRowText(mismatch))
        assertEquals("Rice is in kg", pasteRowDetail(mismatch))
        assertEquals("Couldn't read", pasteRowDetail(row(PasteAction.Skipped(SkipReason.UNREADABLE))))
        assertEquals("Listed twice", pasteRowDetail(row(PasteAction.Skipped(SkipReason.REPEATED))))
        // A long line is cut.
        assertEquals("Skipped: " + "A".repeat(37) + "...", pasteRowText(row(PasteAction.Skipped(SkipReason.NAME_TOO_LONG), "A".repeat(140))))
    }

    @Test
    fun summaryButtonAndResultTexts() {
        val rows = planPaste(PasteParser.parse("Rice 3 kg\nDahi\nOil\nMilk\n2 kg"), listOf(item("Rice", qty("2", Units.KG)), item("Oil", BoolValue(true))))
        assertEquals("2 new, 1 to update, 1 already needed, 1 skipped", pasteSummaryText(rows))
        assertEquals("Nothing to add from this text.", pasteSummaryText(emptyList()))

        val add = PasteAction.Add("Dahi", BoolValue(true))
        val mark = PasteAction.MarkNeeded("oil", "Oil", null, BoolValue(false), BoolValue(true))
        assertEquals("Add 1 item", pasteConfirmText(listOf(add)))
        assertEquals("Add 5 items", pasteConfirmText(List(5) { add }))
        assertEquals("Update 3 items", pasteConfirmText(List(3) { mark }))
        assertEquals("Apply 8 changes", pasteConfirmText(List(5) { add } + List(3) { mark }))
        assertEquals("Nothing to add", pasteConfirmText(emptyList()))

        assertEquals("Added 5 items", pasteResultText(added = 5, updated = 0, leftOut = 0))
        assertEquals("Added 1 item, updated 3", pasteResultText(added = 1, updated = 3, leftOut = 0))
        assertEquals("Updated 1 item. 2 skipped: changed on another phone", pasteResultText(added = 0, updated = 1, leftOut = 2))
        assertEquals("Nothing to change", pasteResultText(added = 0, updated = 0, leftOut = 0))
    }

    @Test
    fun theTextField() {
        assertEquals("Rice", withPastedText("", "Rice"))
        assertEquals("Dahi\nRice", withPastedText("Dahi\n\n", "Rice"))
        assertEquals(MAX_PASTE_FIELD_CHARS, withPastedText("", "a".repeat(MAX_PASTE_FIELD_CHARS + 5)).length)
        assertEquals("", pasteLineCountText("  \n"))
        assertEquals("1 line", pasteLineCountText("Rice\n\n"))
        assertEquals("2 lines", pasteLineCountText("Rice\nDahi"))
        assertEquals("Too long: paste up to 200 lines and 10,000 characters.", PASTE_TOO_LONG_TEXT)
    }
}
