package com.grocerysync.app.data

import com.grocerysync.app.data.ItemValue.BoolValue
import com.grocerysync.app.data.ItemValue.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemValueTest {

    private fun qty(text: String, unit: String? = "") = Quantity(Amount.parse(text)!!, unit)

    @Test
    fun isNeeded() {
        assertTrue(BoolValue(true).isNeeded)
        assertFalse(BoolValue(false).isNeeded)
        assertTrue(Quantity.of(1).isNeeded)
        assertTrue(Quantity.of(InputValidation.MAX_QUANTITY).isNeeded)
        assertFalse(Quantity.of(0).isNeeded)
        // Any amount above 0 is needed, also below one unit.
        assertTrue(qty("0.001", "kg").isNeeded)
        assertTrue(qty("0.5", "L").isNeeded)
        assertFalse(qty("0", "kg").isNeeded)
    }

    @Test
    fun typeMatchesKind() {
        assertEquals(ValueType.BOOLEAN, BoolValue(false).type)
        assertEquals(ValueType.QUANTITY, Quantity.of(3).type)
    }

    @Test
    fun convertBooleanToQuantityUsesTheGivenUnit() {
        // No default unit: the dialog passes "" until the user chooses one.
        assertEquals(Quantity.of(1, ""), BoolValue(true).convertTo(ValueType.QUANTITY, unit = ""))
        assertEquals(Quantity.of(0, ""), BoolValue(false).convertTo(ValueType.QUANTITY, unit = " "))
        assertEquals(Quantity.of(1, "pcs"), BoolValue(true).convertTo(ValueType.QUANTITY, unit = "pieces"))
        assertEquals(Quantity.of(1, "kg"), BoolValue(true).convertTo(ValueType.QUANTITY, unit = "kg"))
        assertEquals(Quantity.of(1, "kg"), BoolValue(true).convertTo(ValueType.QUANTITY, unit = "Kilos"))
        assertEquals(Quantity.of(0, "bunch"), BoolValue(false).convertTo(ValueType.QUANTITY, unit = "bunch"))
    }

    @Test
    fun convertQuantityToBoolean() {
        assertEquals(BoolValue(true), Quantity.of(5).convertTo(ValueType.BOOLEAN, unit = ""))
        assertEquals(BoolValue(true), Quantity.of(1).convertTo(ValueType.BOOLEAN, unit = ""))
        assertEquals(BoolValue(true), qty("0.25", "kg").convertTo(ValueType.BOOLEAN, unit = "kg"))
        assertEquals(BoolValue(false), Quantity.of(0, "kg").convertTo(ValueType.BOOLEAN, unit = "kg"))
    }

    @Test
    fun convertToSameTypeKeepsValue() {
        val quantity = qty("1.5", "kg")
        val flag = BoolValue(true)
        assertSame(quantity, quantity.convertTo(ValueType.QUANTITY, unit = "g"))
        assertSame(flag, flag.convertTo(ValueType.BOOLEAN, unit = ""))
    }

    @Test
    fun notNeededKeepsTypeAndUnit() {
        assertEquals(BoolValue(false), BoolValue(true).notNeeded())
        assertEquals(BoolValue(false), BoolValue(false).notNeeded())
        assertEquals(Quantity.of(0), Quantity.of(12).notNeeded())
        assertFalse(Quantity.of(12).notNeeded().isNeeded)
        // "Got it" on 2 kg rice: 0 kg, so Undo and the next + still know the unit.
        assertEquals(Quantity.of(0, "kg"), Quantity.of(2, "kg").notNeeded())
        assertEquals(Quantity.of(0, "bunch"), qty("1.5", "bunch").notNeeded())
    }

    @Test
    fun sanitizedClampsAmountsAndCleansUnits() {
        assertEquals(Quantity.of(0), Quantity(Amount(-3_000L)).sanitized())
        assertEquals(Quantity.of(InputValidation.MAX_QUANTITY), Quantity.of(5_000_000).sanitized())
        assertEquals(Quantity.of(42), Quantity.of(42).sanitized())
        assertEquals(Quantity.of(2, "kg"), Quantity.of(2, " KGS ").sanitized())
        assertEquals(Quantity.of(2, null), Quantity.of(2, null).sanitized())
        assertEquals(BoolValue(true), BoolValue(true).sanitized())
    }

    @Test
    fun stepsFitTheUnit() {
        assertEquals(qty("2", "kg"), qty("1.5", "kg").stepped(up = true))
        assertEquals(qty("1", "kg"), qty("1.5", "kg").stepped(up = false))
        assertEquals(qty("1", "L"), qty("0.5", "L").stepped(up = true))
        assertEquals(qty("600", "g"), qty("500", "g").stepped(up = true))
        assertEquals(qty("400", "ml"), qty("500", "ml").stepped(up = false))
        assertEquals(qty("3", "pcs"), qty("2", "pcs").stepped(up = true))
        assertEquals(qty("2", "dozen"), qty("1", "dozen").stepped(up = true))
        assertEquals(qty("4", "pack"), qty("5", "pack").stepped(up = false))
        assertEquals(qty("3", "bunch"), qty("2", "bunch").stepped(up = true))
        assertEquals(qty("3"), qty("2").stepped(up = true))
        assertEquals(qty("2.25", "bottle"), qty("1.25", "bottle").stepped(up = true))
        // Exact: 0.1 + 0.5 is 0.6, not 0.6000000000000001.
        assertEquals("0.6", qty("0.1", "kg").stepped(up = true).amount.format())
    }

    @Test
    fun minusNeverGoesBelowZeroAndPlusStopsAtTheMaximum() {
        assertEquals(qty("0", "kg"), qty("0.3", "kg").stepped(up = false))
        assertEquals(qty("0", "g"), qty("50", "g").stepped(up = false))
        assertEquals(qty("0", "pcs"), qty("0", "pcs").stepped(up = false))
        assertEquals(qty("0", "pcs"), qty("0.5", "pcs").stepped(up = false))
        assertEquals(Quantity(Amount.MAX, "g"), qty("999950", "g").stepped(up = true))
        assertEquals(Quantity(Amount.MAX, "kg"), Quantity(Amount.MAX, "kg").stepped(up = true))
    }

    @Test
    fun displayText() {
        assertEquals("2 kg", qty("2", "kg").displayText())
        assertEquals("1.5 L", qty("1.5", "L").displayText())
        assertEquals("0.25 kg", qty("0.25", "kg").displayText())
        assertEquals("0.125 kg", qty("0.125", "kg").displayText())
        assertEquals("500 g", qty("500", "g").displayText())
        assertEquals("1000 ml", qty("1000", "ml").displayText())
        assertEquals("3 bunch", qty("3", "bunch").displayText())
        assertEquals("× 2", qty("2").displayText())
        assertEquals("× 2", qty("2", null).displayText())
    }

    @Test
    fun fromWire() {
        assertEquals(ValueType.BOOLEAN, ValueType.fromWire("boolean"))
        assertEquals(ValueType.QUANTITY, ValueType.fromWire("quantity"))
        assertEquals(ValueType.QUANTITY, ValueType.fromWire(" Quantity "))
        // Protocol 2 and database version 1 called quantities "integer".
        assertEquals(ValueType.QUANTITY, ValueType.fromWire("integer"))
        assertEquals(ValueType.QUANTITY, ValueType.fromWire(" Integer "))
        assertNull(ValueType.fromWire(null))
        assertNull(ValueType.fromWire(""))
        assertNull(ValueType.fromWire("bool"))
        assertNull(ValueType.fromWire("number"))
    }

    @Test
    fun wireNames() {
        assertEquals("boolean", ValueType.BOOLEAN.wire)
        assertEquals("quantity", ValueType.QUANTITY.wire)
    }
}
