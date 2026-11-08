package com.grocerysync.app.ui

import com.grocerysync.app.data.Amount
import com.grocerysync.app.data.GroceryItem
import com.grocerysync.app.data.ItemValue.BoolValue
import com.grocerysync.app.data.ItemValue.Quantity
import com.grocerysync.app.data.TickedEntry
import com.grocerysync.app.data.Units
import com.grocerysync.app.data.stepped
import com.grocerysync.app.text.AppTexts
import com.grocerysync.app.text.withTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The list screen's Undo snackbars: which - / + steps are one change, which changes tick an item off, their texts. */
class ListUndoTest {

    private fun kg(amount: String) = Quantity(Amount.parse(amount)!!, Units.KG)

    @Test
    fun stepsOfOneItemWhileItsSnackbarIsShownAreOneChange() {
        val first = stepEvent(shown = null, "rice", "Rice", current = kg("2"), next = kg("2.5"))
        assertEquals(ListEvent.Stepped("rice", "Rice", kg("2"), kg("2.5")), first)
        // The next step starts where the first ended: its Undo goes back to before the first.
        val second = stepEvent(shown = first, "rice", "Rice", current = kg("2.5"), next = kg("3"))
        assertEquals(ListEvent.Stepped("rice", "Rice", kg("2"), kg("3")), second)
        val third = stepEvent(shown = second, "rice", "Rice", current = kg("3"), next = kg("2.5"))
        assertEquals(kg("2"), third.before)
    }

    @Test
    fun aStepIsAChangeOfItsOwnAfterTheSnackbarHasGone() {
        assertEquals(kg("2.5"), stepEvent(shown = null, "rice", "Rice", current = kg("2.5"), next = kg("3")).before)
    }

    @Test
    fun aStepOfAnotherItemIsAChangeOfItsOwn() {
        val rice = stepEvent(shown = null, "rice", "Rice", current = kg("2"), next = kg("2.5"))
        assertEquals(kg("1"), stepEvent(shown = rice, "dal", "Dal", current = kg("1"), next = kg("1.5")).before)
    }

    @Test
    fun aStepAfterAChangeFromTheOtherPhoneIsAChangeOfItsOwn() {
        // The snackbar says 2 -> 2.5 kg, but the other phone has set Rice to 5 kg since: + makes it 5.5 kg, and its
        // Undo gives back the other phone's 5 kg, not this phone's 2 kg.
        val rice = stepEvent(shown = null, "rice", "Rice", current = kg("2"), next = kg("2.5"))
        assertEquals(kg("5"), stepEvent(shown = rice, "rice", "Rice", current = kg("5"), next = kg("5.5")).before)
    }

    @Test
    fun aChangeTicksOffWhenItTakesANeededItemOffToBuy() {
        assertTrue(ListEvent.GotIt("rice", "Rice", kg("2"), kg("0")).ticksOff)
        assertTrue(ListEvent.NeedChanged("dahi", "Dahi", needed = false).ticksOff)
        assertFalse(ListEvent.NeedChanged("dahi", "Dahi", needed = true).ticksOff)
        assertTrue(ListEvent.Stepped("rice", "Rice", kg("1"), kg("0")).ticksOff)
        assertFalse(ListEvent.Stepped("rice", "Rice", kg("2"), kg("1.5")).ticksOff)
        // + then - back to 0 while one snackbar is shown: nothing was ticked off.
        assertFalse(ListEvent.Stepped("rice", "Rice", kg("0"), kg("0")).ticksOff)
    }

    @Test
    fun neededAgainAYesNoItemBecomesYes() {
        val dahi = GroceryItem("dahi", "Dahi", BoolValue(false), 0L, "Home", pendingSync = false, addedAt = 0L)
        assertEquals(ListEvent.NeedChanged("dahi", "Dahi", needed = true), neededAgainChange(dahi, ticked = null))
    }

    @Test
    fun neededAgainAQuantityGetsTheAmountItWasTickedOffWithElseOneStep() {
        val rice = GroceryItem("rice", "Rice", kg("0"), 0L, "Home", pendingSync = false, addedAt = 0L)
        val tickedRice = TickedEntry("rice", "Rice", before = kg("2"), set = kg("0"), at = 0L)
        assertEquals(ListEvent.Stepped("rice", "Rice", kg("0"), kg("2")), neededAgainChange(rice, tickedRice))
        val oneStep = kg("0").stepped(up = true)
        assertEquals(ListEvent.Stepped("rice", "Rice", kg("0"), oneStep), neededAgainChange(rice, ticked = null))
        // An entry the item no longer matches (changed on the other phone since: a new unit) doesn't count.
        val inGrams = rice.copy(value = Quantity.of(0, Units.G))
        assertEquals(
            ListEvent.Stepped("rice", "Rice", Quantity.of(0, Units.G), Quantity.of(0, Units.G).stepped(up = true)),
            neededAgainChange(inGrams, tickedRice),
        )
        // Nor does another item's.
        assertEquals(oneStep, neededAgainChange(rice, tickedRice.copy(key = "dal")).after)
    }

    @Test
    fun snackbarTexts() {
        assertEquals("Got Rice", ListEvent.GotIt("rice", "Rice", kg("2"), kg("0")).snackbarText())
        assertEquals("Rice: 2 kg → 3 kg", ListEvent.Stepped("rice", "Rice", kg("2"), kg("3")).snackbarText())
        assertEquals("Eggs: 6 → 7", ListEvent.Stepped("eggs", "Eggs", Quantity.of(6, ""), Quantity.of(7, "")).snackbarText())
        assertEquals("Dahi: needed", ListEvent.NeedChanged("dahi", "Dahi", needed = true).snackbarText())
        assertEquals("Dahi: not needed", ListEvent.NeedChanged("dahi", "Dahi", needed = false).snackbarText())
        assertEquals("Renamed Curd to Dahi", ListEvent.Edited("dahi", "Curd", "Dahi", BoolValue(true), BoolValue(true)).snackbarText())
        assertEquals("Changed Rice", ListEvent.Edited("rice", "Rice", "Rice", kg("1"), kg("2")).snackbarText())
        assertEquals(
            "Changed Curd, now called Dahi",
            ListEvent.Edited("dahi", "Curd", "Dahi", kg("1"), kg("2")).snackbarText(),
        )
        // New capitals are a new name too.
        assertEquals("Renamed rice to Rice", ListEvent.Edited("rice", "rice", "Rice", kg("1"), kg("1")).snackbarText())
    }

    @Test
    fun snackbarTextsInHindiKeepTheNamesAndAmounts() = withTexts(AppTexts.Hindi) {
        assertEquals("Rice ले लिया", ListEvent.GotIt("rice", "Rice", kg("2"), kg("0")).snackbarText())
        assertEquals("Rice: 2 kg → 3 kg", ListEvent.Stepped("rice", "Rice", kg("2"), kg("3")).snackbarText())
        assertEquals("Dahi: चाहिए", ListEvent.NeedChanged("dahi", "Dahi", needed = true).snackbarText())
        assertEquals("Dahi: नहीं चाहिए", ListEvent.NeedChanged("dahi", "Dahi", needed = false).snackbarText())
        assertEquals(
            "Curd का नाम बदलकर Dahi कर दिया",
            ListEvent.Edited("dahi", "Curd", "Dahi", BoolValue(true), BoolValue(true)).snackbarText(),
        )
        assertEquals("Rice बदल दिया", ListEvent.Edited("rice", "Rice", "Rice", kg("1"), kg("2")).snackbarText())
        assertEquals(
            "Curd बदल दिया, अब इसका नाम Dahi है",
            ListEvent.Edited("dahi", "Curd", "Dahi", kg("1"), kg("2")).snackbarText(),
        )
    }
}
