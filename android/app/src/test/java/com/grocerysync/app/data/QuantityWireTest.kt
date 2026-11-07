package com.grocerysync.app.data

import com.grocerysync.app.data.ItemValue.BoolValue
import com.grocerysync.app.data.ItemValue.Quantity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protocol 3 items and changes: exact amounts with units; protocol 2's whole-number "integer" still reads. */
class QuantityWireTest {

    private fun a(text: String) = Amount.parse(text)!!

    private fun parse(json: String) = ListLogic.parseItem(JSONObject(json))!!

    private fun upsert(seq: Long, name: String, value: ItemValue, time: Long = 1_000L + seq) =
        OutboxOp(seq, "id-$seq", OpType.UPSERT, ItemKeys.normalize(name), name, value, time, OpStatus.PENDING)

    // ---- items from the script ----

    @Test
    fun parsesQuantitiesWithUnitsAndDecimals() {
        val rice = parse("""{"name":"Rice","type":"quantity","value":2,"unit":"kg","updatedAt":5,"updatedBy":"Home"}""")
        assertEquals(GroceryItem("rice", "Rice", Quantity(a("2"), "kg"), 5L, "Home", false), rice)
        assertEquals(Quantity(a("1.5"), "L"), parse("""{"name":"Milk","type":"quantity","value":1.5,"unit":"L"}""").value)
        assertEquals(Quantity(a("0.25"), "kg"), parse("""{"name":"A","type":"quantity","value":0.25,"unit":"kg"}""").value)
        assertEquals(Quantity(a("3"), "bunch"), parse("""{"name":"A","type":"quantity","value":3,"unit":"bunch"}""").value)
    }

    @Test
    fun amountsAreRoundedHalfUpToThreeDecimalsAndClamped() {
        assertEquals(a("1.235"), (parse("""{"name":"A","type":"quantity","value":1.2345}""").value as Quantity).amount)
        assertEquals(a("0.3"), (parse("""{"name":"A","type":"quantity","value":0.30000000000000004}""").value as Quantity).amount)
        assertEquals(Amount.MAX, (parse("""{"name":"A","type":"quantity","value":1234567.5}""").value as Quantity).amount)
        assertEquals(Amount.ZERO, (parse("""{"name":"A","type":"quantity","value":-2}""").value as Quantity).amount)
    }

    @Test
    fun unitsAreCleanedLikeTheSheet() {
        assertEquals("kg", (parse("""{"name":"A","type":"quantity","value":2,"unit":" Kgs "}""").value as Quantity).unit)
        assertEquals("L", (parse("""{"name":"A","type":"quantity","value":2,"unit":"litre"}""").value as Quantity).unit)
        // Missing or null unit: none.
        assertEquals("", (parse("""{"name":"A","type":"quantity","value":2}""").value as Quantity).unit)
        assertEquals("", (parse("""{"name":"A","type":"quantity","value":2,"unit":null}""").value as Quantity).unit)
        // Yes/no items ignore a unit.
        assertEquals(BoolValue(true), parse("""{"name":"A","type":"boolean","value":true,"unit":"kg"}""").value)
    }

    @Test
    fun protocol2IntegersAreWholeQuantitiesWithoutUnit() {
        assertEquals(Quantity(a("2"), ""), parse("""{"name":"A","type":"integer","value":2}""").value)
        assertEquals(Quantity(a("3"), ""), parse("""{"name":"A","type":"integer","value":2.6}""").value)
    }

    @Test
    fun quantityTextIsReadConservatively() {
        assertEquals(Quantity(a("1.5"), ""), parse("""{"name":"A","type":"quantity","value":"1,5"}""").value)
        assertEquals(Quantity(a("2"), "kg"), parse("""{"name":"A","type":"quantity","value":"2 kg"}""").value)
        // A unit of its own wins over one typed into the amount.
        assertEquals(Quantity(a("2"), "g"), parse("""{"name":"A","type":"quantity","value":"2 kg","unit":"g"}""").value)
        assertEquals(Quantity(Amount.ZERO, ""), parse("""{"name":"A","type":"quantity","value":"about 2"}""").value)

        assertEquals(a("1.5") to "", ListLogic.parseQuantityText(" 1,5 "))
        assertEquals(a("500") to "g", ListLogic.parseQuantityText("500g"))
        assertEquals(a("2") to "kg", ListLogic.parseQuantityText("2 Kgs"))
        assertEquals(a("0.5") to "L", ListLogic.parseQuantityText(".5 litre"))
        assertEquals(a("1.235") to "", ListLogic.parseQuantityText("1.2345"))
        assertEquals(a("3") to "fl oz", ListLogic.parseQuantityText("3 fl oz"))
        assertNull(ListLogic.parseQuantityText("2-3"))
        assertNull(ListLogic.parseQuantityText("about 2"))
        assertNull(ListLogic.parseQuantityText("1.5.2"))
        assertNull(ListLogic.parseQuantityText("kg"))
        assertNull(ListLogic.parseQuantityText(""))
    }

    // ---- changes to the script ----

    @Test
    fun applyRequestCarriesProtocol3AndExactAmounts() {
        val ops = listOf(
            upsert(1, "Rice", Quantity(a("1.5"), "kg")),
            upsert(2, "Milk", Quantity(a("0.1") + a("0.2"), "L")),
            upsert(3, "Eggs", Quantity(a("12"), "")),
            upsert(4, "Flour", Quantity(a("1000"), "g")),
            upsert(5, "Old", Quantity(a("3"), unit = null)),
        )
        val body = ListLogic.buildApplyRequest("t", "Home", ops)
        val json = JSONObject(body)
        assertEquals(3, json.getInt("protocol"))
        val changes = json.getJSONArray("changes")

        val rice = changes.getJSONObject(0)
        assertEquals("quantity", rice.getString("type"))
        assertEquals("kg", rice.getString("unit"))
        val compact = body.replace(" ", "")
        for (value in listOf("1.5", "0.3", "12", "1000")) {
            assertTrue(compact, Regex(""""value":${Regex.escape(value)}[,}]""").containsMatchIn(compact))
        }
        assertFalse(compact, compact.contains("0.30000000000000004"))
        assertFalse(compact, compact.contains("12.0"))
        assertFalse(compact, compact.contains("E+"))

        assertEquals("", changes.getJSONObject(2).getString("unit")) // "" = no unit
        // A change queued before units existed keeps the sheet's unit: no "unit" at all.
        val old = changes.getJSONObject(4)
        assertEquals("quantity", old.getString("type"))
        assertEquals(3, (old.get("value") as Number).toInt())
        assertFalse(old.has("unit"))
    }

    // ---- the list shown ----

    @Test
    fun aChangeQueuedWithoutUnitShowsTheItemsUnit() {
        val mirror = listOf(GroceryItem("rice", "Rice", Quantity(a("2"), "kg"), 1L, "Home", false))
        val shown = ListLogic.overlay(mirror, listOf(upsert(1, "Rice", Quantity(a("3"), unit = null))), "Shop").single()
        assertEquals(Quantity(a("3"), "kg"), shown.value)
        assertTrue(shown.pendingSync)

        // A new item (or one that was yes/no) has no unit to keep.
        val added = ListLogic.overlay(emptyList(), listOf(upsert(1, "Tea", Quantity(a("1"), unit = null))), "Shop").single()
        assertEquals(Quantity(a("1"), ""), added.value)
        val wasFlag = listOf(GroceryItem("tea", "Tea", BoolValue(true), 1L, "Home", false))
        assertEquals(Quantity(a("1"), ""), ListLogic.overlay(wasFlag, listOf(upsert(1, "Tea", Quantity(a("1"), unit = null))), "Shop").single().value)

        // An explicit unit (also "") replaces the item's.
        assertEquals(
            Quantity(a("3"), ""),
            ListLogic.overlay(mirror, listOf(upsert(1, "Rice", Quantity(a("3"), ""))), "Shop").single().value,
        )
    }

    @Test
    fun parseItemsKeepsUnitsPerItem() {
        val items = ListLogic.parseItems(
            JSONArray(
                """[{"name":"Rice","type":"quantity","value":2,"unit":"kg"},
                    {"name":"Milk","type":"quantity","value":1.5,"unit":"L"},
                    {"name":"Bread","type":"boolean","value":true}]""",
            ),
        )
        assertEquals(listOf("2 kg", "1.5 L"), items.mapNotNull { (it.value as? Quantity)?.displayText() })
    }
}
