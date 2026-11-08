package com.grocerysync.app.ui

import com.grocerysync.app.data.Amount
import com.grocerysync.app.data.GroceryItem
import com.grocerysync.app.data.ItemKeys
import com.grocerysync.app.data.ItemValue
import com.grocerysync.app.data.PasteParser
import com.grocerysync.app.data.ShopSection
import com.grocerysync.app.data.ShopSectionSettings
import com.grocerysync.app.data.Units
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A list shared from one phone ([buildShareText]) and pasted on the other ([PasteParser], [planPaste]) gives the
 * same items and amounts, grouped by shop section or A to Z.
 */
class ShareRoundTripTest {

    private fun item(name: String, value: ItemValue) =
        GroceryItem(ItemKeys.normalize(name), name, value, updatedAt = 1_000L, updatedBy = "Home", pendingSync = false)

    private fun yesNo(name: String, needed: Boolean = true) = item(name, ItemValue.BoolValue(needed))

    private fun quantity(name: String, amount: String, unit: String) =
        item(name, ItemValue.Quantity(Amount.parse(amount)!!, unit))

    private fun pasted(text: String, onList: List<GroceryItem> = emptyList()): List<PasteAction> =
        planPaste(PasteParser.parse(text), onList).map { it.action }

    /** A shared list of every kind of item: yes / no, each standard unit, a unit of the family's own. */
    private val shared = listOf(
        quantity("Atta", "10", Units.KG),
        quantity("Milk", "1.5", Units.L),
        yesNo("Dahi"),
        quantity("Haldi", "1", Units.PACK),
        quantity("Paneer", "250", Units.G),
        quantity("Eggs", "2", Units.DOZEN),
        quantity("Coriander", "1", "bunch"),
        quantity("Bananas", "6", Units.PCS),
        quantity("Rose water", "200", Units.ML),
        quantity("Parle-G", "3", Units.PACK),
        yesNo("Agarbatti"),
        yesNo("Batteries"),
        // Not needed: not in the message.
        yesNo("Coffee", needed = false),
        quantity("Rice", "0", Units.KG),
    )

    private val expected = shared.filter { it.value.isNeeded }.map { PasteAction.Add(it.name, it.value) }

    @Test
    fun aGroupedListComesBackAsTheSameItems() {
        // This phone moved Paneer to Other: the message follows the screen, and the paste doesn't care.
        val settings = ShopSectionSettings(chosen = mapOf("paneer" to ShopSection.OTHER))
        val text = buildShareText(shared, settings::sectionOf)
        assertEquals(expected.sortedBy { it.name }, pasted(text).map { it as PasteAction.Add }.sortedBy { it.name })
    }

    @Test
    fun anAtoZListComesBackInTheSameOrder() {
        val text = buildShareText(shared, null)
        assertEquals(expected.sortedBy { it.name.lowercase() }, pasted(text))
    }

    @Test
    fun oneItemHasASingularTitleThatIsSkippedToo() {
        val text = buildShareText(listOf(yesNo("Dahi")), ::catalogSectionOf)
        assertEquals("Grocery list (1 item)\n\n*Dairy, eggs & bread*\n• Dahi", text)
        assertEquals(listOf(PasteAction.Add("Dahi", ItemValue.BoolValue(true))), pasted(text))
    }

    @Test
    fun aQuantityWithoutAUnitComesBackAsPieces() {
        // From before units: shared as "× 6"; a new quantity needs a unit, so it is pieces.
        val text = buildShareText(listOf(item("Eggs", ItemValue.Quantity(Amount.whole(6L), ""))), null)
        assertEquals("Grocery list (1 item)\n\n• Eggs - × 6", text)
        assertEquals(listOf(PasteAction.Add("Eggs", ItemValue.Quantity(Amount.whole(6L), Units.PCS))), pasted(text))
    }

    @Test
    fun onAPhoneThatHasTheSameListNothingChanges() {
        val text = buildShareText(shared, ::catalogSectionOf)
        val actions = pasted(text, onList = shared)
        assertEquals(expected.size, actions.size)
        assertEquals(emptyList<PasteAction>(), actions.filterNot { it is PasteAction.AlreadyNeeded })
    }
}
