package com.grocerysync.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** Exact amounts: no floating-point artefacts anywhere between the dialog, the database and the sheet. */
class AmountTest {

    private fun a(text: String) = Amount.parse(text)!!

    @Test
    fun sumsAreExact() {
        assertEquals(a("0.3"), a("0.1") + a("0.2"))
        assertEquals("0.3", (a("0.1") + a("0.2")).format())
        var total = Amount.ZERO
        repeat(10) { total += a("0.1") }
        assertEquals(a("1"), total)
        assertEquals("1", total.format())
        assertEquals(a("0.75"), a("1.25") - a("0.5"))
    }

    @Test
    fun formatsWithoutTrailingZerosOrExponents() {
        assertEquals("2", a("2").format())
        assertEquals("1.5", a("1.50").format())
        assertEquals("0.25", a("0.25").format())
        assertEquals("0.125", a("0.125").format())
        assertEquals("0.001", a("0.001").format())
        assertEquals("1000", a("1000").format())
        assertEquals("100", a("100.000").format())
        assertEquals("123456.789", a("123456.789").format())
        assertEquals("0", Amount.ZERO.format())
        assertEquals("1.5", a("1.5").toString())
    }

    @Test
    fun jsonNumbersArePlain() {
        assertEquals(2L, a("2").toJsonNumber())
        assertEquals(1000L, a("1000").toJsonNumber())
        assertEquals(BigDecimal("1.5"), a("1.5").toJsonNumber())
        assertEquals("0.3", (a("0.1") + a("0.2")).toJsonNumber().toString())
        assertEquals("0.125", a("0.125").toJsonNumber().toString())
    }

    @Test
    fun numbersFromJsonAreRoundedHalfUpToThreeDecimals() {
        // Android's org.json gives Doubles: taken by their shortest decimal text.
        assertEquals(a("0.3"), Amount.fromNumber(0.1 + 0.2))
        assertEquals(a("1.5"), Amount.fromNumber(1.5))
        assertEquals(a("2.001"), Amount.fromNumber(2.0005))
        assertEquals(a("2"), Amount.fromNumber(2.0004))
        assertEquals(a("0.333"), Amount.fromNumber(1.0 / 3.0))
        // The JVM's org.json gives BigDecimals and Integers / Longs.
        assertEquals(a("1.235"), Amount.fromNumber(BigDecimal("1.2345")))
        assertEquals(a("1.234"), Amount.fromNumber(BigDecimal("1.23449")))
        assertEquals(a("7"), Amount.fromNumber(7))
        assertEquals(a("7"), Amount.fromNumber(7L))
        // Clamped into 0..999999.
        assertEquals(Amount.ZERO, Amount.fromNumber(-4))
        assertEquals(Amount.ZERO, Amount.fromNumber(-0.5))
        assertEquals(Amount.MAX, Amount.fromNumber(12_345_678))
        assertEquals(Amount.MAX, Amount.fromNumber(1e300))
        assertEquals(Amount.ZERO, Amount.fromNumber(Double.NaN))
        assertEquals(Amount.ZERO, Amount.fromNumber(Double.POSITIVE_INFINITY))
        // Tiny amounts round to 0, which means "not needed".
        assertEquals(Amount.ZERO, Amount.fromNumber(0.0004))
        assertEquals(a("0.001"), Amount.fromNumber(0.0005))
    }

    @Test
    fun roundedUpToWholeForOldClients() {
        assertEquals(1L, a("0.5").roundedUpToWhole())
        assertEquals(1L, a("0.001").roundedUpToWhole())
        assertEquals(2L, a("2").roundedUpToWhole())
        assertEquals(3L, a("2.25").roundedUpToWhole())
        assertEquals(0L, Amount.ZERO.roundedUpToWhole())
    }

    @Test
    fun rangeAndOrder() {
        assertTrue(a("0.001").isPositive)
        assertFalse(Amount.ZERO.isPositive)
        assertTrue(a("2").isWhole)
        assertFalse(a("2.5").isWhole)
        assertTrue(a("1.5") < a("2"))
        assertEquals(Amount.MAX, Amount(Amount.MAX.thousandths + 1).coerceValid())
        assertEquals(Amount.ZERO, Amount(-1L).coerceValid())
        assertEquals("999999", Amount.MAX.format())
        assertNull(Amount.parse("1000000"))
    }
}
