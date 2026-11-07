package com.grocerysync.app.data

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * An amount to buy with at most three decimals (2, 1.5, 0.25, 0.125), kept as whole thousandths so
 * that adding steps and reading the sheet never produce floating-point artefacts
 * (0.1 + 0.2 is exactly 0.3 here, never 0.30000000000000004).
 *
 * Valid amounts are 0..[MAX]; arithmetic doesn't clamp, [coerceValid] does.
 */
@JvmInline
value class Amount(val thousandths: Long) : Comparable<Amount> {

    val isPositive: Boolean get() = thousandths > 0L
    val isWhole: Boolean get() = thousandths % SCALE == 0L
    val isValid: Boolean get() = thousandths in 0L..MAX.thousandths

    override fun compareTo(other: Amount): Int = thousandths.compareTo(other.thousandths)

    operator fun plus(other: Amount): Amount = Amount(thousandths + other.thousandths)

    operator fun minus(other: Amount): Amount = Amount(thousandths - other.thousandths)

    /** Clamped into 0..[MAX]. */
    fun coerceValid(): Amount = if (isValid) this else Amount(thousandths.coerceIn(0L, MAX.thousandths))

    /** The smallest whole number at least this large (0.5 -> 1, 2 -> 2), for clients that only know whole numbers. */
    fun roundedUpToWhole(): Long = Math.floorDiv(thousandths + SCALE - 1, SCALE)

    /** Without trailing zeros and never in exponent notation: "2", "1.5", "0.25", "1000". */
    fun toBigDecimal(): BigDecimal {
        val stripped = BigDecimal.valueOf(thousandths, DECIMALS).stripTrailingZeros()
        return if (stripped.scale() < 0) stripped.setScale(0) else stripped
    }

    /** "2", "1.5", "0.25", "0.125": no trailing zeros, always '.' (the JSON and the sheet's numbers). */
    fun format(): String = toBigDecimal().toPlainString()

    /**
     * The amount as a JSON number: a Long for whole amounts (`2`, never `2.0`), else a BigDecimal whose text
     * is the exact decimal (`1.5`). Both Android's and the JVM's org.json write these as plain numbers.
     */
    fun toJsonNumber(): Number = if (isWhole) thousandths / SCALE else toBigDecimal()

    override fun toString(): String = format()

    companion object {
        const val DECIMALS = 3
        const val SCALE = 1_000L

        val ZERO = Amount(0L)
        val ONE = Amount(SCALE)
        val MAX = Amount(InputValidation.MAX_QUANTITY * SCALE)

        /** [whole] units (2 -> 2); not clamped. */
        fun whole(whole: Long): Amount = Amount(whole * SCALE)

        /** Rounded half-up to three decimals and clamped into 0..[MAX]. */
        fun of(value: BigDecimal): Amount {
            if (value.signum() <= 0) return ZERO
            val thousandths: BigInteger = value.setScale(DECIMALS, RoundingMode.HALF_UP).unscaledValue()
            return if (thousandths > BigInteger.valueOf(MAX.thousandths)) MAX else Amount(thousandths.toLong())
        }

        /**
         * A number read from JSON, rounded half-up to three decimals and clamped into 0..[MAX]. Android's
         * org.json reads `1.5` as a Double, the JVM's as a BigDecimal; a Double is taken by its shortest
         * decimal text, so 0.3 stays 0.3. Not a finite number: 0.
         */
        fun fromNumber(number: Number): Amount = when (number) {
            is BigDecimal -> of(number)
            is BigInteger -> of(BigDecimal(number))
            is Long, is Int, is Short, is Byte -> of(BigDecimal.valueOf(number.toLong()))
            else -> number.toDouble().takeIf { it.isFinite() }?.let { of(BigDecimal.valueOf(it)) } ?: ZERO
        }

        /**
         * Parses an amount as typed: ASCII digits with at most one decimal separator, '.' or ',' (so "1,5"
         * is 1.5), and at most three decimals; surrounding spaces allowed. "2", "1.5", ".5", "2." are
         * valid. Null for anything else or above [MAX].
         */
        fun parse(text: String): Amount? {
            val match = AMOUNT_TEXT.matchEntire(text.trim()) ?: return null
            val wholeDigits = match.groupValues[1].trimStart('0')
            val decimals = match.groupValues[3]
            if (match.groupValues[1].isEmpty() && decimals.isEmpty()) return null
            if (decimals.length > DECIMALS) return null
            if (wholeDigits.length > InputValidation.MAX_QUANTITY.toString().length) return null
            val whole = wholeDigits.ifEmpty { "0" }.toLong()
            val fraction = decimals.padEnd(DECIMALS, '0').toLong()
            return Amount(whole * SCALE + fraction).takeIf { it.isValid }
        }

        private val AMOUNT_TEXT = Regex("""([0-9]*)([.,]([0-9]*))?""")
    }
}
