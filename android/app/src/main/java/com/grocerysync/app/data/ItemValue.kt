package com.grocerysync.app.data

import java.util.Locale

/** How an item's "Need" cell is interpreted. [wire] is the value used in the sheet and the protocol. */
enum class ValueType(val wire: String) {
    BOOLEAN("boolean"),
    QUANTITY("quantity");

    companion object {
        /** What protocol 2 (and this app's database before units) called a quantity: whole numbers only. */
        const val LEGACY_QUANTITY_WIRE = "integer"

        fun fromWire(s: String?): ValueType? {
            val normalized = s?.trim()?.lowercase(Locale.ROOT) ?: return null
            if (normalized == LEGACY_QUANTITY_WIRE) return QUANTITY
            return entries.firstOrNull { it.wire == normalized }
        }
    }
}

/** What an item needs: either a yes/no flag or an amount to buy (0 = not needed). */
sealed interface ItemValue {
    val type: ValueType
    val isNeeded: Boolean

    data class BoolValue(val value: Boolean) : ItemValue {
        override val type: ValueType get() = ValueType.BOOLEAN
        override val isNeeded: Boolean get() = value
    }

    /**
     * [amount] (0..[Amount.MAX]) of [unit] ("" = no unit, shown as "× 2"; see [Units.normalize]).
     *
     * [unit] is null only in changes queued before the app knew units: such a change keeps the unit the
     * sheet has for the item (the server reads a missing unit that way).
     */
    data class Quantity(val amount: Amount, val unit: String? = "") : ItemValue {
        override val type: ValueType get() = ValueType.QUANTITY
        override val isNeeded: Boolean get() = amount.isPositive

        /** The unit to show; "" for none. */
        val unitText: String get() = unit.orEmpty()

        companion object {
            fun of(whole: Long, unit: String? = ""): Quantity = Quantity(Amount.whole(whole), unit)
        }
    }
}

/**
 * The value as [type]: yes/no becomes 1 (needed) or 0 [unit] ("" = no unit chosen yet; there is no
 * default unit); a quantity becomes "needed" when its amount is above 0.
 */
fun ItemValue.convertTo(type: ValueType, unit: String): ItemValue {
    if (this.type == type) return this
    return when (this) {
        is ItemValue.BoolValue -> ItemValue.Quantity(if (value) Amount.ONE else Amount.ZERO, Units.normalize(unit))
        is ItemValue.Quantity -> ItemValue.BoolValue(amount.isPositive)
    }
}

/** "Got it": not needed any more; a quantity keeps its unit. */
fun ItemValue.notNeeded(): ItemValue = when (this) {
    is ItemValue.BoolValue -> ItemValue.BoolValue(false)
    is ItemValue.Quantity -> copy(amount = Amount.ZERO)
}

/** One step of the unit ([Units.step]) up or down; never below 0 or above [Amount.MAX]. */
fun ItemValue.Quantity.stepped(up: Boolean): ItemValue.Quantity {
    val step = Units.step(unit)
    return copy(amount = (if (up) amount + step else amount - step).coerceValid())
}

/** "2 kg", "1.5 L"; without a unit "× 2". */
fun ItemValue.Quantity.displayText(): String =
    if (unitText.isEmpty()) "× ${amount.format()}" else "${amount.format()} $unitText"

/** Brings values into the range and form the server accepts (amount clamped, unit cleaned); booleans unchanged. */
internal fun ItemValue.sanitized(): ItemValue = when (this) {
    is ItemValue.BoolValue -> this
    is ItemValue.Quantity -> {
        val cleanUnit = unit?.let(Units::normalize)
        val cleanAmount = amount.coerceValid()
        if (cleanUnit == unit && cleanAmount == amount) this else ItemValue.Quantity(cleanAmount, cleanUnit)
    }
}
