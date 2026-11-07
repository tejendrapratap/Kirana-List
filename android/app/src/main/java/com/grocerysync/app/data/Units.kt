package com.grocerysync.app.data

import java.util.Locale

/**
 * Units of quantity items: the standard list plus any unit the user types (bunch, box, bottle, ...).
 * The cleaning and alias rules are the same as in Code.gs, so both sides store the same text.
 */
object Units {
    const val MAX_LENGTH = 16

    const val PCS = "pcs"
    const val KG = "kg"
    const val G = "g"
    const val L = "L"
    const val ML = "ml"
    const val DOZEN = "dozen"
    const val PACK = "pack"

    /** Offered in the unit picker (and as the sheet's Unit dropdown), in this order. */
    val STANDARD: List<String> = listOf(PCS, KG, G, L, ML, DOZEN, PACK)

    private val HALF = Amount(500L)
    private val HUNDRED = Amount.whole(100L)

    /** Lower-case spellings (after cleaning) that mean a standard unit. */
    private val ALIASES: Map<String, String> = buildMap {
        fun add(unit: String, vararg spellings: String) = spellings.forEach { put(it, unit) }
        add(PCS, "pc", "pcs", "piece", "pieces", "nos", "no")
        add(KG, "kg", "kgs", "kilo", "kilos", "kilogram", "kilograms")
        add(G, "g", "gm", "gms", "gram", "grams", "gr")
        add(L, "l", "ltr", "ltrs", "litre", "litres", "liter", "liters")
        add(ML, "ml", "millilitre", "millilitres", "milliliter", "milliliters")
        add(DOZEN, "dozen", "doz", "dz")
        add(PACK, "pack", "packs", "pkt", "packet", "packets")
    }

    /**
     * The unit as stored: trimmed, whitespace runs (line breaks included) collapsed to one space, at most
     * [MAX_LENGTH] characters; a known spelling in any case becomes its standard unit ("Kgs" -> "kg",
     * "litre" -> "L"); anything else is a custom unit kept as typed. Null or blank: "" (no unit).
     */
    fun normalize(raw: String?): String {
        if (raw == null) return ""
        val clean = limit(ItemKeys.collapseWhitespace(raw))
        return ALIASES[clean.lowercase(Locale.ROOT)] ?: clean
    }

    fun isStandard(unit: String): Boolean = unit in STANDARD

    /** What - / + change: 0.5 for kg and L, 100 for g and ml, 1 for everything else. */
    fun step(unit: String?): Amount = when (normalize(unit)) {
        KG, L -> HALF
        G, ML -> HUNDRED
        else -> Amount.ONE
    }

    /** Cut to [MAX_LENGTH] characters without splitting a surrogate pair, then trimmed again. */
    private fun limit(text: String): String {
        if (text.length <= MAX_LENGTH) return text
        var cut = text.take(MAX_LENGTH)
        if (cut.last().isHighSurrogate()) cut = cut.dropLast(1)
        return ItemKeys.clean(cut)
    }
}
