package com.grocerysync.app.ui

import com.grocerysync.app.data.Amount
import com.grocerysync.app.data.GroceryItem
import com.grocerysync.app.data.ItemKeys
import com.grocerysync.app.data.ItemValue
import com.grocerysync.app.data.ItemValue.BoolValue
import com.grocerysync.app.data.ItemValue.Quantity
import com.grocerysync.app.data.ValueType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemSavePlanTest {

    private fun item(name: String, value: ItemValue) =
        GroceryItem(
            key = ItemKeys.normalize(name),
            name = name,
            value = value,
            updatedAt = 1_000L,
            updatedBy = "Home",
            pendingSync = false,
        )

    /** Saving with [current] the only item on the list (an empty list when adding). */
    private fun plan(current: GroceryItem?, openedValue: ItemValue?, name: String, draft: ItemValue) =
        planItemSave(current, openedValue, name, draft, onList = listOfNotNull(current))

    private val rice = item("Rice", Quantity.of(2, "kg"))
    private val bread = item("Bread", BoolValue(true))

    @Test
    fun addingANameAlreadyOnTheListSendsNothing() {
        // Capitals and extra spaces don't make another item; the stored spelling is reported.
        for (typed in listOf("Rice", "rice", "  RICE ", "Rice\u00A0")) {
            assertEquals(typed, ItemSavePlan.AlreadyOnList("Rice"), planItemSave(null, null, typed, Quantity.of(1), onList = listOf(bread, rice)))
        }
        assertEquals(
            ItemSavePlan.AlreadyOnList("Rice"),
            planItemSave(null, null, "rice", BoolValue(false), onList = listOf(item("Rice", BoolValue(true)))),
        )
        // A new name is added as before.
        assertEquals(
            ItemSavePlan.Upsert("Basmati rice", Quantity.of(1)),
            planItemSave(null, null, "Basmati rice", Quantity.of(1), onList = listOf(bread, rice)),
        )
    }

    @Test
    fun renamingToAnotherItemsNameSendsNothing() {
        // Bread renamed to "rice" would delete Bread and overwrite Rice with Bread's value.
        assertEquals(
            ItemSavePlan.AlreadyOnList("Rice"),
            planItemSave(bread, BoolValue(true), "rice", BoolValue(true), onList = listOf(bread, rice)),
        )
        // Also when the value was changed in the dialog too.
        assertEquals(
            ItemSavePlan.AlreadyOnList("Rice"),
            planItemSave(bread, BoolValue(true), "Rice", BoolValue(false), onList = listOf(bread, rice)),
        )
    }

    @Test
    fun reSpellingTheSameItemIsStillAllowed() {
        val lower = item("rice", Quantity.of(2, "kg"))
        assertEquals(
            ItemSavePlan.Upsert("Rice", Quantity.of(2, "kg")),
            planItemSave(lower, Quantity.of(2, "kg"), "Rice", Quantity.of(2, "kg"), onList = listOf(bread, lower)),
        )
        assertEquals(
            ItemSavePlan.Upsert("Rice", Quantity.of(3, "kg")),
            planItemSave(rice, Quantity.of(2, "kg"), " Rice ", Quantity.of(3, "kg"), onList = listOf(bread, rice)),
        )
    }

    @Test
    fun reAddingAnItemRemovedElsewhereChecksTheNameToo() {
        // Edit dialog of Milk, renamed away on the other phone; the user typed "bread" before saving.
        assertEquals(
            ItemSavePlan.AlreadyOnList("Bread"),
            planItemSave(null, Quantity.of(1), "bread", Quantity.of(1), onList = listOf(bread, rice)),
        )
        assertEquals(
            ItemSavePlan.Upsert("Milk", Quantity.of(1)),
            planItemSave(null, Quantity.of(1), "Milk", Quantity.of(1), onList = listOf(bread, rice)),
        )
    }

    @Test
    fun theDialogFindsTheItemThatHasTheName() {
        val names = mapOf("rice" to "Rice", "bread" to "Bread")
        assertEquals("Rice", nameTakenBy("rice", null, names))
        assertEquals("Rice", nameTakenBy("  RICE  ", null, names))
        assertEquals(null, nameTakenBy("Basmati rice", null, names))
        assertEquals(null, nameTakenBy("", null, names))
        assertEquals(null, nameTakenBy("   ", null, names))
        // Editing Rice: re-spelling it is fine, Bread's name is not.
        assertEquals(null, nameTakenBy("RICE", "rice", names))
        assertEquals("Bread", nameTakenBy("bread", "rice", names))
    }

    @Test
    fun duplicateTextsQuoteTheStoredName() {
        assertEquals("\"Rice\" is already on the list", alreadyOnListText("Rice"))
        assertEquals("Another item is already called \"Rice\"", anotherItemCalledText("Rice"))
        assertEquals("Open \"Rice\"", openItemText("Rice"))
    }

    @Test
    fun longOrMultiLineNamesAreShortenedInTexts() {
        val long = "Basmati rice extra long grain premium quality from the hills"
        assertEquals("\"Basmati rice extra long grain premium...\" is already on the list", alreadyOnListText(long))
        assertEquals("Open \"Basmati rice extra lo...\"", openItemText(long))
        assertEquals(QUOTED_NAME_MAX, quotedItemName(long).length)
        // Names typed into the sheet may span lines.
        assertEquals("Tea (x2)", quotedItemName("Tea\n(x2)"))
        // Never cut inside an emoji (a surrogate pair).
        val emoji = "a".repeat(QUOTED_NAME_MAX - 4) + "\uD83C\uDF4E" + "bcdef"
        val cut = quotedItemName(emoji)
        assertEquals("a".repeat(QUOTED_NAME_MAX - 4) + "...", cut)
        assertEquals("Rice", quotedItemName("  Rice "))
    }

    @Test
    fun addingUpsertsTheDraft() {
        assertEquals(ItemSavePlan.Upsert("Milk", Quantity.of(0)), plan(null, null, " Milk ", Quantity.of(0)))
    }

    @Test
    fun savingAnUntouchedDialogKeepsAChangeMadeOnTheOtherPhone() {
        // Opened at x2; meanwhile Home set Milk to 5; the draft still shows 2.
        val now = item("Milk", Quantity.of(5))
        assertEquals(ItemSavePlan.Nothing, plan(now, Quantity.of(2), "Milk", Quantity.of(2)))
    }

    @Test
    fun aRespellingKeepsTheCurrentValue() {
        val now = item("milk", Quantity.of(5))
        assertEquals(ItemSavePlan.Upsert("Milk", Quantity.of(5)), plan(now, Quantity.of(2), "Milk", Quantity.of(2)))
    }

    @Test
    fun aTypeChangeFromTheOtherPhoneIsNotReverted() {
        val now = item("Eggs", Quantity.of(12))
        assertEquals(ItemSavePlan.Nothing, plan(now, BoolValue(true), "Eggs", BoolValue(true)))
    }

    @Test
    fun aValueTheUserChangedIsSaved() {
        val now = item("Milk", Quantity.of(5))
        assertEquals(ItemSavePlan.Upsert("Milk", Quantity.of(3)), plan(now, Quantity.of(2), "Milk", Quantity.of(3)))
        val unchanged = item("Milk", Quantity.of(2))
        assertEquals(ItemSavePlan.Upsert("Milk", BoolValue(true)), plan(unchanged, Quantity.of(2), "Milk", BoolValue(true)))
    }

    @Test
    fun renamingToAnotherItemCarriesTheCurrentValueUnlessTheUserChangedIt() {
        val now = item("Milk", Quantity.of(5))
        assertEquals(ItemSavePlan.Rename("milk", "Oat milk", null), plan(now, Quantity.of(2), "Oat milk", Quantity.of(2)))
        assertEquals(ItemSavePlan.Rename("milk", "Oat milk", Quantity.of(1)), plan(now, Quantity.of(2), "Oat milk", Quantity.of(1)))
        // Changed to exactly what the item has now: the rename carries it already.
        assertEquals(ItemSavePlan.Rename("milk", "Oat milk", null), plan(now, Quantity.of(2), "Oat milk", Quantity.of(5)))
    }

    @Test
    fun anItemRemovedElsewhereIsAddedAgain() {
        assertEquals(ItemSavePlan.Upsert("Bread", BoolValue(true)), plan(null, BoolValue(true), "Bread", BoolValue(true)))
    }

    @Test
    fun quantityInputReplacesTheDefaultZero() {
        assertEquals("3", normalizeAmountInput("0", "03", 6))
        assertEquals("3", normalizeAmountInput("0", "30", 6)) // cursor was before the 0
        assertEquals("0", normalizeAmountInput("0", "00", 6))
        assertEquals("7", normalizeAmountInput("0", "7", 6)) // 0 selected and replaced
        assertEquals("", normalizeAmountInput("0", "", 6))
    }

    @Test
    fun aPasteOverTheSelectedZeroIsKeptWhole() {
        // The "0" selected (long press) and "250" pasted over it: 250, not 25.
        assertEquals("250", normalizeAmountInput("0", "250", previousSelection = 0 until 1))
        assertEquals("100", normalizeAmountInput("0", "100", previousSelection = 0 until 1))
        assertEquals("20", normalizeAmountInput("0", "20", previousSelection = 0 until 1))
        assertEquals("1000", normalizeAmountInput("0", "1000", previousSelection = 0 until 1))
        assertEquals("7", normalizeAmountInput("0", "7", previousSelection = 0 until 1))
        // Selection unknown: several digits at once are kept as they are too.
        assertEquals("250", normalizeAmountInput("0", "250"))
        assertEquals("100", normalizeAmountInput("0", "100"))
    }

    @Test
    fun whatIsTypedOrPastedBesideTheZeroReplacesIt() {
        // Cursor after the "0" (1 until 1) or before it (0 until 0).
        assertEquals("3", normalizeAmountInput("0", "03", previousSelection = 1 until 1))
        assertEquals("3", normalizeAmountInput("0", "30", previousSelection = 0 until 0))
        assertEquals("250", normalizeAmountInput("0", "0250", previousSelection = 1 until 1))
        assertEquals("250", normalizeAmountInput("0", "2500", previousSelection = 0 until 0))
        assertEquals("1.5", normalizeAmountInput("0", "1.50", previousSelection = 0 until 0))
        assertEquals("0", normalizeAmountInput("0", "00", previousSelection = 1 until 1))
        assertEquals("0.", normalizeAmountInput("0", "0.", previousSelection = 1 until 1))
        assertEquals("0.5", normalizeAmountInput("0", "0,5", previousSelection = 1 until 1))
        assertEquals("0", normalizeAmountInput("0", "0 ", previousSelection = 1 until 1)) // no digit came
        assertEquals("", normalizeAmountInput("0", "", previousSelection = 0 until 1))
        // Not the default "0": as before.
        assertEquals("20", normalizeAmountInput("2", "20", previousSelection = 1 until 1))
        assertEquals("102", normalizeAmountInput("12", "102", previousSelection = 1 until 1))
    }

    // ---- Save enabled only when every field has a value (user_changes.md section 8) ----

    private val names = mapOf("rice" to "Rice", "bread" to "Bread")

    private fun canSave(
        name: String = "Milk",
        type: ValueType = ValueType.QUANTITY,
        amount: String = "2",
        unit: String = "L",
        editedKey: String? = null,
    ) = canSaveItem(name, editedKey, names, type, amount, unit)

    @Test
    fun aQuantityNeedsANameAnAmountAndAUnit() {
        assertTrue(canSave())
        assertTrue(canSave(amount = "0")) // 0 = not needed is a value
        assertTrue(canSave(unit = "bunch")) // a custom unit
        assertFalse(canSave(unit = "")) // no default unit: the user must choose one
        assertFalse(canSave(unit = "   ")) // blank counts as none
        assertFalse(canSave(amount = "")) // half-typed amount
        assertFalse(canSave(amount = "1.2345"))
        assertFalse(canSave(name = ""))
        assertFalse(canSave(name = "   "))
        assertFalse(canSave(name = "x".repeat(101)))
        assertFalse(canSave(amount = "", unit = ""))
    }

    @Test
    fun aYesNoItemNeedsOnlyAName() {
        // Track as and Need to buy always have a value; the hidden amount and unit don't matter.
        assertTrue(canSave(type = ValueType.BOOLEAN, amount = "", unit = ""))
        assertTrue(canSave(type = ValueType.BOOLEAN, amount = "abc", unit = ""))
        assertFalse(canSave(type = ValueType.BOOLEAN, name = ""))
    }

    @Test
    fun aNameAnotherItemHasCanNotBeSaved() {
        assertFalse(canSave(name = "rice"))
        assertFalse(canSave(name = " BREAD ", type = ValueType.BOOLEAN))
        // Editing Rice itself: re-spelling is fine, renaming to Bread is not.
        assertTrue(canSave(name = "RICE", unit = "kg", editedKey = "rice"))
        assertFalse(canSave(name = "Bread", unit = "kg", editedKey = "rice"))
    }

    @Test
    fun aQuantityFromBeforeUnitsNeedsAUnitChosenBeforeSaving() {
        // "RICE x 3": the dialog opens with its stored unit "" and Save stays disabled until one is chosen.
        val legacy = item("RICE", Quantity.of(3, ""))
        val unit = (legacy.value as Quantity).unitText
        assertTrue(isUnitMissing(ValueType.QUANTITY, unit))
        assertFalse(canSave(name = legacy.name, amount = "3", unit = unit, editedKey = legacy.key))
        assertTrue(canSave(name = legacy.name, amount = "3", unit = "kg", editedKey = legacy.key))
        // A change queued before the app knew units (unit null) shows as no unit too.
        assertTrue(isUnitMissing(ValueType.QUANTITY, Quantity.of(3, null).unitText))
        // Switched to yes / no, it needs no unit.
        assertTrue(canSave(name = legacy.name, type = ValueType.BOOLEAN, unit = "", editedKey = legacy.key))
    }

    @Test
    fun theUnitPromptIsOnlyForQuantities() {
        assertTrue(isUnitMissing(ValueType.QUANTITY, ""))
        assertFalse(isUnitMissing(ValueType.QUANTITY, "pcs"))
        assertFalse(isUnitMissing(ValueType.BOOLEAN, ""))
        assertEquals("Choose a unit", UNIT_PROMPT)
    }

    @Test
    fun quantityInputKeepsDigitsWithoutLeadingZeros() {
        assertEquals("50", normalizeAmountInput("5", "50", 6))
        assertEquals("5", normalizeAmountInput("5", "05", 6))
        assertEquals("100", normalizeAmountInput("10", "100", 6))
        assertEquals("12", normalizeAmountInput("", "0012", 6))
        assertEquals("12", normalizeAmountInput("1", "1a2", 6))
        assertEquals("123456", normalizeAmountInput("123456", "1234567", 6))
        assertEquals("0", normalizeAmountInput("", "0", 6))
    }

    @Test
    fun amountInputTakesOneDecimalSeparatorAndAtMostThreeDecimals() {
        assertEquals("0.", normalizeAmountInput("0", "0."))
        assertEquals("0.5", normalizeAmountInput("0.", "0.5"))
        assertEquals("1.5", normalizeAmountInput("1", "1,5")) // comma typed: shown as a point
        assertEquals("1.5", normalizeAmountInput("1.5", "1.5.")) // a second separator is ignored
        assertEquals("1.255", normalizeAmountInput("1.255", "1.2555"))
        assertEquals(".5", normalizeAmountInput("", ".5"))
        assertEquals("0.25", normalizeAmountInput("0", "00.25"))
        assertEquals("2.", normalizeAmountInput("2", "2.")) // still typing
        assertEquals("999999.5", normalizeAmountInput("999999", "9999999.5"))
        assertEquals("12", normalizeAmountInput("12", "12 kg"))
    }

    @Test
    fun aUnitChangeIsAValueChange() {
        // Opened at 2 kg; the user picked g: saved even though the amount is the same.
        val now = item("Rice", Quantity.of(2, "kg"))
        assertEquals(
            ItemSavePlan.Upsert("Rice", Quantity.of(2, "g")),
            plan(now, Quantity.of(2, "kg"), "Rice", Quantity.of(2, "g")),
        )
        // Untouched while the other phone changed the unit: the newer unit stays.
        val changed = item("Rice", Quantity.of(2, "bag"))
        assertEquals(ItemSavePlan.Nothing, plan(changed, Quantity.of(2, "kg"), "Rice", Quantity.of(2, "kg")))
        // Decimals compare exactly.
        val half = Quantity(Amount(500L), "L")
        assertEquals(ItemSavePlan.Nothing, plan(item("Milk", half), Quantity(Amount(500L), "L"), "Milk", half))
    }

    @Test
    fun withoutAUnitThePromptIsALineBelowOrInThePickerOnAShortWindow() {
        // Portrait: "unit" in the picker and "Choose a unit" on a line below it.
        assertEquals(NO_UNIT_HINT, unitPickerText("", promptInPicker = false))
        assertTrue(showsUnitPromptLine("", promptInPicker = false))
        // Landscape: no room for that line (the dialog would scroll and hide it); the picker says "Choose".
        assertEquals(UNIT_PICKER_PROMPT, unitPickerText("", promptInPicker = true))
        assertFalse(showsUnitPromptLine("", promptInPicker = true))
        // A unit chosen: the unit, and no prompt anywhere.
        for (inPicker in listOf(false, true)) {
            assertEquals("kg", unitPickerText("kg", inPicker))
            assertEquals("bunch", unitPickerText("bunch", inPicker))
            assertFalse(showsUnitPromptLine("kg", inPicker))
        }
    }
}
