package com.grocerysync.app.notify

import com.grocerysync.app.data.Amount
import com.grocerysync.app.data.GroceryItem
import com.grocerysync.app.data.ItemKeys
import com.grocerysync.app.data.ItemValue
import com.grocerysync.app.text.AppTexts
import org.junit.Assert.assertEquals
import org.junit.Test

/** The change notification's title and text, in English and Hindi. */
class ChangeNoticeTest {

    private fun added(name: String, by: String = "Mummy", value: ItemValue = ItemValue.BoolValue(true)) =
        ToBuyChange(GroceryItem(ItemKeys.normalize(name), name, value, 1_000L, by, pendingSync = false), isNew = true)

    private fun changed(name: String, amount: Long, unit: String, by: String = "Mummy") =
        added(name, by, ItemValue.Quantity(Amount.whole(amount), unit)).copy(isNew = false)

    private fun english(vararg changes: ToBuyChange) = ChangeNotice.of(changes.toList(), AppTexts.English)

    private fun hindi(vararg changes: ToBuyChange) = ChangeNotice.of(changes.toList(), AppTexts.Hindi)

    @Test
    fun oneSomeoneAddedItems() {
        assertEquals(
            ChangeNotice("Mummy added 3 items to To buy", "Dahi, Oil 2 L, Batti"),
            english(added("Dahi"), added("Oil", value = ItemValue.Quantity(Amount.whole(2), "L")), added("Batti")),
        )
        assertEquals(ChangeNotice("Mummy added 1 item to To buy", "Dahi"), english(added("Dahi")))
    }

    @Test
    fun amountsAreShownAsTheListShowsThem() {
        val eggs = added("Eggs", value = ItemValue.Quantity(Amount.whole(12), ""))
        val oil = added("Oil", value = ItemValue.Quantity(Amount.parse("1.5")!!, "L"))
        assertEquals("Eggs × 12, Oil 1.5 L", english(eggs, oil).text)
    }

    @Test
    fun severalPeopleAreNamedInTheTitle() {
        assertEquals(
            "Mummy and Shop added 5 items to To buy",
            english(added("A"), added("B", by = "Shop"), added("C"), added("D", by = " shop"), added("E")).title,
        )
        assertEquals(
            "Mummy, Shop and Papa added 3 items to To buy",
            english(added("A"), added("B", by = "Shop"), added("C", by = "Papa")).title,
        )
        assertEquals(
            "Mummy, Shop and 2 others added 4 items to To buy",
            english(added("A"), added("B", by = "Shop"), added("C", by = "Papa"), added("D", by = "Dadi")).title,
        )
        // Spaces as the sheet may have them don't make another person, and are tidied in the title.
        assertEquals(
            "Dadi Ji added 2 items to To buy",
            english(added("A", by = " Dadi  Ji"), added("B", by = "Dadi Ji")).title,
        )
    }

    @Test
    fun onlyAmountChangesSayChanged() {
        assertEquals(ChangeNotice("Mummy changed To buy", "Rice 5 kg"), english(changed("Rice", 5, "kg")))
        // With something new among them, the title says added and counts only what was added; the text lists both.
        assertEquals(
            ChangeNotice("Mummy added 1 item to To buy", "Dahi, Rice 5 kg"),
            english(added("Dahi"), changed("Rice", 5, "kg")),
        )
    }

    @Test
    fun onlyWhoAddedIsNamedAsAdding() {
        // Shop only lowered Rice's amount: Mummy added the one new item.
        assertEquals(
            ChangeNotice("Mummy added 1 item to To buy", "Dahi, Rice 1 kg"),
            english(added("Dahi"), changed("Rice", 1, "kg", by = "Shop")),
        )
        assertEquals(
            ChangeNotice("Mummy added 1 item to To buy", "Rice 1 kg, Dahi"),
            english(changed("Rice", 1, "kg", by = "Shop"), added("Dahi")),
        )
        assertEquals(
            ChangeNotice("Mummy ने \"ख़रीदना है\" में 1 चीज़ जोड़ी", "Dahi, Rice 1 kg"),
            hindi(added("Dahi"), changed("Rice", 1, "kg", by = "Shop")),
        )
    }

    @Test
    fun aLongListEndsWithHowManyMore() {
        val many = (1..11).map { added("Item $it") }.toTypedArray()
        assertEquals("Mummy added 11 items to To buy", english(*many).title)
        assertEquals(
            "Item 1, Item 2, Item 3, Item 4, Item 5, Item 6, Item 7, Item 8 +3 more",
            english(*many).text,
        )
        // Exactly the most it lists: no "+0 more".
        val eight = many.take(ChangeNotice.MAX_NAMES).toTypedArray()
        assertEquals("Item 1, Item 2, Item 3, Item 4, Item 5, Item 6, Item 7, Item 8", english(*eight).text)
    }

    @Test
    fun hindiTitlesWithTheirPlurals() {
        assertEquals(
            ChangeNotice("Mummy ने \"ख़रीदना है\" में 3 चीज़ें जोड़ीं", "Dahi, Oil 2 L, Batti"),
            hindi(added("Dahi"), added("Oil", value = ItemValue.Quantity(Amount.whole(2), "L")), added("Batti")),
        )
        assertEquals("Mummy ने \"ख़रीदना है\" में 1 चीज़ जोड़ी", hindi(added("Dahi")).title)
        assertEquals("Mummy ने \"ख़रीदना है\" में बदलाव किया", hindi(changed("Rice", 5, "kg")).title)
    }

    @Test
    fun hindiNamesSeveralPeopleAndHowManyMore() {
        assertEquals(
            "Mummy और Shop ने \"ख़रीदना है\" में 2 चीज़ें जोड़ीं",
            hindi(added("A"), added("B", by = "Shop")).title,
        )
        assertEquals(
            "Mummy, Shop और Papa ने \"ख़रीदना है\" में 3 चीज़ें जोड़ीं",
            hindi(added("A"), added("B", by = "Shop"), added("C", by = "Papa")).title,
        )
        assertEquals(
            "Mummy, Shop और 2 अन्य ने \"ख़रीदना है\" में 4 चीज़ें जोड़ीं",
            hindi(added("A"), added("B", by = "Shop"), added("C", by = "Papa"), added("D", by = "Dadi")).title,
        )
        // Item names and amounts are data: the same in Hindi; only "+3 more" is said in Hindi.
        val many = (1..11).map { added("Item $it") }.toTypedArray()
        assertEquals("Item 1, Item 2, Item 3, Item 4, Item 5, Item 6, Item 7, Item 8 +3 और", hindi(*many).text)
    }
}
