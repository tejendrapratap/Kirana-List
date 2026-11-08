package com.grocerysync.app.notify

import com.grocerysync.app.data.Amount
import com.grocerysync.app.data.GroceryItem
import com.grocerysync.app.data.ItemKeys
import com.grocerysync.app.data.ItemValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What counts as someone else putting something on To buy since the list was last seen on this phone. */
class ListChangeWatchTest {

    private fun item(name: String, value: ItemValue, by: String = MUMMY, pending: Boolean = false) =
        GroceryItem(ItemKeys.normalize(name), name, value, updatedAt = 1_000L, updatedBy = by, pendingSync = pending)

    private fun yes(name: String, needed: Boolean = true, by: String = MUMMY) =
        item(name, ItemValue.BoolValue(needed), by)

    private fun qty(name: String, amount: String, unit: String = "kg", by: String = MUMMY) =
        item(name, ItemValue.Quantity(Amount.parse(amount)!!, unit), by)

    private fun changes(seen: List<GroceryItem>, now: List<GroceryItem>) =
        ListChangeWatch.newOnToBuy(ListChangeWatch.seen(seen), now, ME).map { it.item.name to it.isNew }

    @Test
    fun seenHoldsOnlyWhatIsNeeded() {
        val list = listOf(yes("Dahi"), yes("Salt", needed = false), qty("Rice", "2"), qty("Atta", "0"))
        val seen = ListChangeWatch.seen(list)
        assertEquals(setOf("dahi", "rice"), seen.keys)
    }

    @Test
    fun newItemsOnToBuyCount() {
        assertEquals(
            listOf("Dahi" to true, "Oil" to true),
            changes(seen = listOf(qty("Rice", "2")), now = listOf(yes("Dahi"), qty("Oil", "2", "L"), qty("Rice", "2"))),
        )
    }

    @Test
    fun itemsNeededAgainCount() {
        // On the list but not needed when seen (bought), needed again now.
        assertEquals(
            listOf("Dahi" to true, "Rice" to true),
            changes(
                seen = listOf(yes("Dahi", needed = false), qty("Rice", "0")),
                now = listOf(yes("Dahi"), qty("Rice", "1")),
            ),
        )
    }

    @Test
    fun amountChangesCountAsChanges() {
        assertEquals(
            listOf("Atta" to false, "Oil" to false, "Rice" to false),
            changes(
                seen = listOf(qty("Rice", "2"), qty("Oil", "1", "L"), yes("Atta")),
                // A new amount, a new unit, and a yes / no item that became a quantity.
                now = listOf(qty("Atta", "5"), qty("Oil", "1", "ml"), qty("Rice", "3")),
            ),
        )
    }

    @Test
    fun nothingChangedIsNothingNew() {
        val list = listOf(yes("Dahi"), qty("Rice", "2"), qty("Oil", "1.5", "L"))
        assertEquals(NONE, changes(seen = list, now = list.map { it.copy(updatedAt = 9_000L) }))
        // The same amount written another way is the same amount.
        assertEquals(NONE, changes(listOf(qty("Rice", "2")), listOf(qty("Rice", "2.000"))))
    }

    @Test
    fun itemsTakenOffToBuyOrDeletedDontCount() {
        assertEquals(
            NONE,
            changes(
                seen = listOf(yes("Dahi"), qty("Rice", "2"), yes("Oil")),
                now = listOf(yes("Dahi", needed = false), qty("Rice", "0")),
            ),
        )
    }

    @Test
    fun changesMadeOnThisPhoneDontCount() {
        val now = listOf(
            yes("Dahi", by = ME),
            // Compared as the list's highlight compares names: spaces around and capitals don't count.
            yes("Oil", by = "  shop "),
            // Waiting to be sent: this phone's change, whatever name the row shows.
            item("Rice", ItemValue.Quantity(Amount.whole(2), "kg"), by = MUMMY, pending = true),
            yes("Batti"),
        )
        assertEquals(listOf("Batti" to true), changes(seen = emptyList(), now = now))
    }

    @Test
    fun anotherPhoneWithThisPhonesNameCountsAsThisPhone() {
        // Two phones of the same model, neither with a name: both write the model's name to the sheet, so neither
        // can tell the other's changes from its own (why Settings asks for a name while notifications are on).
        val model = "23021RAAEG"
        val fromTheOtherPhone = listOf(yes("Dahi", by = model), yes("Oil", by = " 23021raaeg "))
        assertEquals(emptyList<ToBuyChange>(), ListChangeWatch.newOnToBuy(emptyMap(), fromTheOtherPhone, model))
    }

    @Test
    fun aNotificationTellsEachChangeWithItsValue() {
        val seen = ListChangeWatch.seen(listOf(qty("Rice", "2")))
        val now = listOf(yes("Dahi"), qty("Rice", "5"), yes("Salt", needed = false))
        assertEquals(
            mapOf("dahi" to "boolean true ", "rice" to "quantity 5 kg"),
            ListChangeWatch.told(ListChangeWatch.newOnToBuy(seen, now, ME)),
        )
        // The same items with the same values: the same thing told.
        assertEquals(ListChangeWatch.seen(now), ListChangeWatch.told(ListChangeWatch.newOnToBuy(emptyMap(), now, ME)))
    }

    @Test
    fun rowsWithoutANameAreNobodysChange() {
        assertFalse(ListChangeWatch.isByOthers(yes("Dahi", by = ""), ME))
        assertFalse(ListChangeWatch.isByOthers(yes("Dahi", by = "   "), ME))
        assertTrue(ListChangeWatch.isByOthers(yes("Dahi", by = "Home"), ME))
    }

    @Test
    fun aRenamedItemIsNewUnderItsNewName() {
        assertEquals(listOf("Dahi" to true), changes(seen = listOf(yes("Curd")), now = listOf(yes("Dahi"))))
    }

    @Test
    fun changesKeepTheListsOrder() {
        val now = listOf(yes("Atta"), qty("Dal", "1"), yes("Oil"), qty("Rice", "5"))
        assertEquals(
            listOf("Atta" to true, "Dal" to false, "Rice" to true),
            changes(seen = listOf(qty("Dal", "2"), yes("Oil")), now = now),
        )
    }

    private companion object {
        const val ME = "Shop"
        const val MUMMY = "Mummy"
        val NONE = emptyList<Pair<String, Boolean>>()
    }
}
