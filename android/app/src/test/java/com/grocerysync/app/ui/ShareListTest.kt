package com.grocerysync.app.ui

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import com.grocerysync.app.data.Amount
import com.grocerysync.app.data.GroceryItem
import com.grocerysync.app.data.ItemKeys
import com.grocerysync.app.data.ItemValue
import com.grocerysync.app.data.ShopSection
import com.grocerysync.app.data.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Share: the text of the To buy list ([buildShareText]), A to Z or by shop section, and when it is sent ([shareToBuy]). */
class ShareListTest {

    private fun item(name: String, value: ItemValue) = GroceryItem(
        key = ItemKeys.normalize(name),
        name = name,
        value = value,
        updatedAt = 1_000L,
        updatedBy = "Home",
        pendingSync = false,
    )

    private fun yesNo(name: String, needed: Boolean = true) = item(name, ItemValue.BoolValue(needed))

    private fun quantity(name: String, amount: String, unit: String) =
        item(name, ItemValue.Quantity(Amount.parse(amount)!!, unit))

    @Test
    fun listsEveryNeededItemAtoZ() {
        val items = listOf(
            yesNo("Dahi"),
            quantity("Milk", "1.5", Units.L),
            yesNo("Coffee", needed = false),
            quantity("atta", "10", Units.KG),
            quantity("Rice", "0", Units.KG),
            quantity("Eggs", "6", ""),
        )
        assertEquals(
            """
            Grocery list (4 items)

            • atta - 10 kg
            • Dahi
            • Eggs - × 6
            • Milk - 1.5 L
            """.trimIndent(),
            buildShareText(items, sectionOf = null),
        )
    }

    @Test
    fun groupsBySectionInWalkingOrder() {
        val items = listOf(
            quantity("Milk", "1", Units.L),
            yesNo("Phone charger"),
            quantity("Onion", "2", Units.KG),
            quantity("Kishmish", "1", Units.PACK),
            yesNo("Dahi"),
            quantity("Basmati rice", "5", Units.KG),
            quantity("Atta", "10", Units.KG),
            yesNo("Oil", needed = false),
        )
        // Only the sections with something to buy, each in WhatsApp bold; unknown names under Other, last.
        assertEquals(
            """
            Grocery list (7 items)

            *Sabzi & fruit*
            • Onion - 2 kg

            *Dairy, eggs & bread*
            • Dahi
            • Milk - 1 L

            *Atta, rice & dal*
            • Atta - 10 kg
            • Basmati rice - 5 kg

            *Dry fruits & nuts*
            • Kishmish - 1 pack

            *Other*
            • Phone charger
            """.trimIndent(),
            buildShareText(items, ::catalogSectionOf),
        )
    }

    @Test
    fun sectionsFollowTheGivenSectionsInWalkingOrder() {
        // A section chosen on this phone counts, and sections come in walking order, not in A to Z of their items.
        val chosen = mapOf("Agarbatti" to ShopSection.PUJA, "Zucchini" to ShopSection.SABZI_FRUIT, "Apples" to ShopSection.OTHER)
        val items = listOf(yesNo("Apples"), yesNo("Agarbatti"), quantity("Zucchini", "0.5", Units.KG))
        assertEquals(
            """
            Grocery list (3 items)

            *Sabzi & fruit*
            • Zucchini - 0.5 kg

            *Puja*
            • Agarbatti

            *Other*
            • Apples
            """.trimIndent(),
            buildShareText(items) { chosen.getValue(it.name) },
        )
    }

    @Test
    fun oneItemIsSaidInTheSingular() {
        assertEquals("Grocery list (1 item)\n\n• Dahi", buildShareText(listOf(yesNo("Dahi")), sectionOf = null))
        assertEquals("Grocery list (1 item)\n\n*Dairy, eggs & bread*\n• Dahi", buildShareText(listOf(yesNo("Dahi")), ::catalogSectionOf))
        assertEquals("Grocery list (2 items)", shareListTitle(2))
    }

    @Test
    fun nothingNeededIsJustTheTitle() {
        assertEquals("Grocery list (0 items)", buildShareText(listOf(yesNo("Dahi", needed = false)), ::catalogSectionOf))
        assertEquals("Grocery list (0 items)", buildShareText(emptyList(), sectionOf = null))
    }

    @Test
    fun aNameWithALineBreakStaysOnOneLine() {
        // Possible for a name typed into the sheet by hand.
        assertEquals("• Tea bags - 2 pack", shareLine(quantity("Tea\n bags", "2", Units.PACK)))
        assertEquals("• Haldi", shareLine(yesNo("Haldi")))
    }

    /** Records what [shareToBuy] starts; [fail] makes starting an activity throw like a phone without any app for it. */
    private class FakeContext(private val fail: Boolean = false) : ContextWrapper(null) {
        var started = 0

        override fun startActivity(intent: Intent?) {
            started++
            if (fail) throw ActivityNotFoundException()
        }
    }

    @Test
    fun nothingToBuyShowsAMessageInsteadOfTheShareSheet() {
        val context = FakeContext()
        val messages = mutableListOf<String>()
        shareToBuy(context, listOf(yesNo("Dahi", needed = false)), ::catalogSectionOf) { messages += it }
        shareToBuy(context, emptyList(), sectionOf = null) { messages += it }
        assertEquals(listOf(NOTHING_TO_SHARE_TEXT, NOTHING_TO_SHARE_TEXT), messages)
        assertEquals(0, context.started)
        assertEquals("Nothing to buy right now", NOTHING_TO_SHARE_TEXT)
    }

    @Test
    fun somethingToBuyOpensTheShareSheet() {
        val context = FakeContext()
        val messages = mutableListOf<String>()
        shareToBuy(context, listOf(yesNo("Dahi")), ::catalogSectionOf) { messages += it }
        assertEquals(1, context.started)
        assertTrue(messages.toString(), messages.isEmpty())

        val noApp = FakeContext(fail = true)
        shareToBuy(noApp, listOf(yesNo("Dahi")), sectionOf = null) { messages += it }
        assertEquals(1, noApp.started)
        assertEquals(listOf(NO_SHARE_APP_TEXT), messages)
    }
}
