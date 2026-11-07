package com.grocerysync.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit cleaning and aliases: the same rules as Code.gs, so both sides store the same text. */
class UnitsTest {

    @Test
    fun standardList() {
        assertEquals(listOf("pcs", "kg", "g", "L", "ml", "dozen", "pack"), Units.STANDARD)
    }

    @Test
    fun aliasesBecomeTheStandardUnitInAnyCase() {
        val aliases = mapOf(
            "pcs" to listOf("pc", "pcs", "piece", "pieces", "nos", "no", "PCS", "Pieces", "NOS"),
            "kg" to listOf("kg", "kgs", "kilo", "kilos", "kilogram", "kilograms", "KG", "Kgs", "Kilo"),
            "g" to listOf("g", "gm", "gms", "gram", "grams", "gr", "G", "GMS", "Grams"),
            "L" to listOf("l", "ltr", "ltrs", "litre", "litres", "liter", "liters", "L", "LTR", "Litre"),
            "ml" to listOf("ml", "millilitre", "millilitres", "milliliter", "milliliters", "ML", "mL", "Ml"),
            "dozen" to listOf("dozen", "doz", "dz", "Dozen", "DOZ"),
            "pack" to listOf("pack", "packs", "pkt", "packet", "packets", "PKT", "Packet"),
        )
        for ((unit, spellings) in aliases) {
            for (spelling in spellings) assertEquals(spelling, unit, Units.normalize(spelling))
        }
    }

    @Test
    fun unitsAreTrimmedAndWhitespaceCollapsed() {
        assertEquals("kg", Units.normalize("  kg  "))
        assertEquals("kg", Units.normalize("\tKgs\n"))
        assertEquals("fl oz", Units.normalize(" fl   oz "))
        assertEquals("big bag", Units.normalize("big\nbag")) // no line breaks
        assertEquals("big bag", Units.normalize("big\u00A0bag"))
        assertEquals("", Units.normalize("   "))
        assertEquals("", Units.normalize(""))
        assertEquals("", Units.normalize(null))
    }

    @Test
    fun customUnitsAreKeptAsTyped() {
        assertEquals("bunch", Units.normalize("bunch"))
        assertEquals("Box", Units.normalize("Box"))
        assertEquals("bottle", Units.normalize(" bottle "))
        assertEquals("kg.", Units.normalize("kg.")) // conservative: only exact spellings are aliases
        assertEquals("tin (400g)", Units.normalize("tin (400g)"))
        assertFalse(Units.isStandard("bunch"))
        assertTrue(Units.isStandard("kg"))
    }

    @Test
    fun atMostSixteenCharacters() {
        assertEquals("bottles of water", Units.normalize("bottles of water")) // 16
        assertEquals("bottles of water", Units.normalize("bottles of water please"))
        assertEquals("abcdefghijklmno", Units.normalize("abcdefghijklmno  x")) // cut, then trimmed
        // A cut never splits a character made of two UTF-16 units.
        val emoji = "🥕" // carrot
        assertEquals("a".repeat(15), Units.normalize("a".repeat(15) + emoji))
    }

    @Test
    fun stepsFitTheUnit() {
        assertEquals("0.5", Units.step("kg").format())
        assertEquals("0.5", Units.step("L").format())
        assertEquals("0.5", Units.step("litre").format())
        assertEquals("100", Units.step("g").format())
        assertEquals("100", Units.step("ml").format())
        for (unit in listOf("pcs", "dozen", "pack", "bunch", "box", "", null)) {
            assertEquals(unit.toString(), "1", Units.step(unit).format())
        }
    }
}
