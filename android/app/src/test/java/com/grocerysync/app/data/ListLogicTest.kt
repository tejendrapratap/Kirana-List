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

class ListLogicTest {

    private val me = "Shop"

    private fun item(name: String, value: ItemValue, updatedAt: Long = 100L, by: String = "Home") =
        GroceryItem(ItemKeys.normalize(name), name, value, updatedAt, by, pendingSync = false)

    private fun upsert(seq: Long, name: String, value: ItemValue, status: OpStatus = OpStatus.PENDING, time: Long = 1_000L + seq) =
        OutboxOp(seq, "id-$seq", OpType.UPSERT, ItemKeys.normalize(name), name, value, time, status)

    private fun delete(seq: Long, name: String, status: OpStatus = OpStatus.PENDING) =
        OutboxOp(seq, "id-$seq", OpType.DELETE, ItemKeys.normalize(name), name, null, 1_000L + seq, status)

    // ---- overlay ----

    @Test
    fun overlayWithoutOpsReturnsMirrorSorted() {
        val mirror = listOf(item("milk", BoolValue(true)), item("Bread", Quantity.of(2)))
        val result = ListLogic.overlay(mirror, emptyList(), me)
        assertEquals(listOf("Bread", "milk"), result.map { it.name })
        assertTrue(result.none { it.pendingSync })
    }

    @Test
    fun overlayUpsertAddsNewItem() {
        val mirror = listOf(item("Milk", BoolValue(true)))
        val result = ListLogic.overlay(mirror, listOf(upsert(1, "Bread", Quantity.of(2))), me)
        assertEquals(listOf("Bread", "Milk"), result.map { it.name })
        val bread = result.first()
        assertTrue(bread.pendingSync)
        assertEquals(Quantity.of(2), bread.value)
        assertEquals("bread", bread.key)
        assertEquals(me, bread.updatedBy)
        assertEquals(1_001L, bread.updatedAt)
        assertFalse(result[1].pendingSync)
    }

    @Test
    fun overlayUpsertReplacesExistingAndMarksPending() {
        val mirror = listOf(item("Milk", BoolValue(true), updatedAt = 50L))
        val result = ListLogic.overlay(mirror, listOf(upsert(1, "MILK", Quantity.of(3), time = 900L)), me)
        assertEquals(1, result.size)
        with(result.single()) {
            assertEquals("milk", key)
            assertEquals("MILK", name)
            assertEquals(Quantity.of(3), value)
            assertEquals(900L, updatedAt)
            assertEquals(me, updatedBy)
            assertTrue(pendingSync)
        }
    }

    @Test
    fun overlayDeleteRemovesItem() {
        val mirror = listOf(item("Milk", BoolValue(true)), item("Eggs", Quantity.of(6)))
        val result = ListLogic.overlay(mirror, listOf(delete(1, "milk")), me)
        assertEquals(listOf("Eggs"), result.map { it.name })
    }

    @Test
    fun overlayDeleteOfUnknownItemIsHarmless() {
        val mirror = listOf(item("Eggs", Quantity.of(6)))
        assertEquals(mirror, ListLogic.overlay(mirror, listOf(delete(1, "Milk")), me))
    }

    @Test
    fun overlayAppliesOpsInSeqOrder() {
        val mirror = listOf(item("Milk", BoolValue(true)))
        // Given out of order on purpose: seq decides.
        val deleteThenUpsert = listOf(upsert(2, "Milk", Quantity.of(4)), delete(1, "Milk"))
        assertEquals(Quantity.of(4), ListLogic.overlay(mirror, deleteThenUpsert, me).single().value)

        val upsertThenDelete = listOf(delete(2, "Milk"), upsert(1, "Milk", Quantity.of(4)))
        assertTrue(ListLogic.overlay(mirror, upsertThenDelete, me).isEmpty())

        val twoUpserts = listOf(upsert(5, "Milk", Quantity.of(5)), upsert(3, "Milk", Quantity.of(3)))
        assertEquals(Quantity.of(5), ListLogic.overlay(mirror, twoUpserts, me).single().value)
    }

    @Test
    fun overlayIncludesFailedOps() {
        val mirror = listOf(item("Milk", BoolValue(false)))
        val ops = listOf(upsert(1, "Milk", BoolValue(true), status = OpStatus.FAILED), delete(2, "Ghost", OpStatus.FAILED))
        val milk = ListLogic.overlay(mirror, ops, me).single()
        assertEquals(BoolValue(true), milk.value)
        assertTrue(milk.pendingSync)
    }

    @Test
    fun overlayKeepsFirstOfDuplicateMirrorKeys() {
        val mirror = listOf(item("Milk", Quantity.of(1)), item("milk", Quantity.of(9)))
        assertEquals(Quantity.of(1), ListLogic.overlay(mirror, emptyList(), me).single().value)
    }

    // ---- coalesce ----

    @Test
    fun coalesceKeepsNewestPendingOpPerKey() {
        val ops = listOf(
            upsert(1, "Milk", Quantity.of(1)),
            upsert(2, "Bread", BoolValue(true)),
            upsert(3, "milk", Quantity.of(2)),
            upsert(4, "MILK", Quantity.of(3)),
        )
        val result = ListLogic.coalesce(ops)
        assertEquals(listOf("id-2", "id-4"), result.map { it.op.id })
        assertEquals(emptyList<String>(), result[0].supersededIds)
        assertEquals(listOf("id-1", "id-3"), result[1].supersededIds)
    }

    @Test
    fun coalescePreservesOriginalOrderOfKeptOps() {
        val ops = listOf(
            upsert(1, "A", BoolValue(true)),
            upsert(2, "B", BoolValue(true)),
            upsert(3, "C", BoolValue(true)),
            upsert(4, "A", BoolValue(false)),
        )
        assertEquals(listOf("id-2", "id-3", "id-4"), ListLogic.coalesce(ops.shuffled()).map { it.op.id })
    }

    @Test
    fun coalesceDeleteAfterUpsertSendsOnlyDelete() {
        val ops = listOf(upsert(1, "Milk", Quantity.of(2)), delete(2, "Milk"))
        val result = ListLogic.coalesce(ops).single()
        assertEquals(OpType.DELETE, result.op.op)
        assertEquals(listOf("id-1"), result.supersededIds)
    }

    @Test
    fun coalesceIgnoresFailedOps() {
        val ops = listOf(
            upsert(1, "Milk", Quantity.of(2), status = OpStatus.FAILED),
            upsert(2, "Milk", Quantity.of(3)),
            upsert(3, "Eggs", Quantity.of(6), status = OpStatus.FAILED),
        )
        val result = ListLogic.coalesce(ops).single()
        assertEquals("id-2", result.op.id)
        assertTrue(result.supersededIds.isEmpty())
    }

    @Test
    fun coalesceOfNothing() {
        assertTrue(ListLogic.coalesce(emptyList()).isEmpty())
    }

    // ---- sorting, counts ----

    @Test
    fun sortsByNameIgnoringCase() {
        val items = listOf("banana", "Apple", "cherry", "apple pie", "Äpfel", "10 eggs").map { item(it, BoolValue(true)) }
        assertEquals(
            listOf("10 eggs", "Apple", "apple pie", "banana", "cherry", "Äpfel"),
            ListLogic.sortForDisplay(items).map { it.name },
        )
    }

    @Test
    fun pendingCountAndFailedChanges() {
        val ops = listOf(
            upsert(1, "Milk", Quantity.of(1)),
            upsert(2, "milk", Quantity.of(2)),
            delete(3, "Bread"),
            upsert(4, "Eggs", Quantity.of(6), status = OpStatus.FAILED).copy(error = "name: too long"),
            delete(5, "Jam", OpStatus.FAILED),
        )
        assertEquals(2, ListLogic.pendingItemCount(ops))
        assertEquals(
            listOf(
                FailedChange("id-4", "Eggs", "name: too long"),
                FailedChange("id-5", "Jam", ListLogic.DEFAULT_FAILURE),
            ),
            ListLogic.failedChanges(ops),
        )
    }

    @Test
    fun failedChangesListEachItemOnceWithItsNewestChange() {
        val ops = listOf(
            upsert(1, "Milk", Quantity.of(1), status = OpStatus.FAILED).copy(error = "refused"),
            upsert(2, "Bread", BoolValue(true), status = OpStatus.FAILED).copy(error = "refused too"),
            upsert(3, "milk", Quantity.of(2), status = OpStatus.FAILED).copy(error = "refused"),
            upsert(4, "Eggs", Quantity.of(6)),
        )
        assertEquals(
            listOf(FailedChange("id-2", "Bread", "refused too"), FailedChange("id-3", "milk", "refused")),
            ListLogic.failedChanges(ops),
        )
    }

    // ---- local storage encoding ----

    @Test
    fun valueEncodingRoundTrips() {
        listOf(
            BoolValue(true), BoolValue(false), Quantity.of(0), Quantity.of(42), Quantity.of(999_999),
            Quantity(Amount(1_500L), "kg"), Quantity(Amount(250L), "L"), Quantity(Amount(123_456_789L), "bunch"),
            Quantity.of(3, unit = null),
        ).forEach {
            assertEquals(it, ListLogic.decodeValue(it.type, ListLogic.encodeValue(it), ListLogic.encodeUnit(it)))
        }
        // Exact decimals: no floating-point text in the database.
        assertEquals("0.3", ListLogic.encodeValue(Quantity(Amount(100L) + Amount(200L), "kg")))
        assertEquals("1000", ListLogic.encodeValue(Quantity.of(1_000)))
    }

    @Test
    fun corruptStoredValuesDecodeToNull() {
        // Changed with units: "2.5" is a valid amount now (decimals); more than 3 decimals is not.
        assertNull(ListLogic.decodeValue(ValueType.BOOLEAN, "yes"))
        assertNull(ListLogic.decodeValue(ValueType.BOOLEAN, null))
        assertNull(ListLogic.decodeValue(ValueType.QUANTITY, "2.5555"))
        assertNull(ListLogic.decodeValue(ValueType.QUANTITY, "-1"))
        assertNull(ListLogic.decodeValue(ValueType.QUANTITY, "1000000"))
        assertNull(ListLogic.decodeValue(ValueType.QUANTITY, null))
        assertEquals(Quantity(Amount(2_500L), ""), ListLogic.decodeValue(ValueType.QUANTITY, "2.5"))
    }

    @Test
    fun rowsFromBeforeUnitsDecodeAsQuantitiesThatKeepTheSheetsUnit() {
        // Database version 1 stored type "integer" and had no unit column (null after the migration).
        val type = ValueType.fromWire("integer")!!
        assertEquals(ValueType.QUANTITY, type)
        assertEquals(Quantity(Amount.whole(3L), unit = null), ListLogic.decodeValue(type, "3", unit = null))
        // Mirror rows get "" (no unit) from the column default.
        assertEquals(Quantity.of(3), ListLogic.decodeValue(type, "3", unit = ""))
        // Units are read back cleaned, the way the sheet stores them.
        assertEquals(Quantity(Amount.whole(2L), "kg"), ListLogic.decodeValue(ValueType.QUANTITY, "2", "Kgs"))
    }

    // ---- ItemJson parsing ----

    @Test
    fun parsesItemJson() {
        val milk = ListLogic.parseItem(
            JSONObject("""{"name":"Milk","type":"integer","value":2,"updatedAt":1762500000000,"updatedBy":"Home"}"""),
        )!!
        assertEquals(GroceryItem("milk", "Milk", Quantity.of(2), 1_762_500_000_000L, "Home", false), milk)

        val bread = ListLogic.parseItem(
            JSONObject("""{"name":" Oat  Bread ","type":"boolean","value":true,"updatedAt":5,"updatedBy":"Sheet"}"""),
        )!!
        assertEquals(GroceryItem("oat bread", "Oat  Bread", BoolValue(true), 5L, "Sheet", false), bread)
    }

    @Test
    fun parsesLooselyTypedItemJson() {
        fun parse(json: String) = ListLogic.parseItem(JSONObject(json))
        assertEquals(Quantity.of(3), parse("""{"name":"A","type":"integer","value":2.6}""")!!.value)
        assertEquals(Quantity.of(999_999), parse("""{"name":"A","type":"integer","value":12345678}""")!!.value)
        assertEquals(Quantity.of(0), parse("""{"name":"A","type":"integer","value":-4}""")!!.value)
        assertEquals(Quantity.of(1), parse("""{"name":"A","type":"integer","value":true}""")!!.value)
        assertEquals(BoolValue(true), parse("""{"name":"A","value":true}""")!!.value)
        assertEquals(Quantity.of(4), parse("""{"name":"A","value":4}""")!!.value)
        assertEquals(BoolValue(false), parse("""{"name":"A","type":"boolean","value":"no"}""")!!.value)
        assertEquals(BoolValue(true), parse("""{"name":"A","type":"boolean","value":"x"}""")!!.value)
        val noMeta = parse("""{"name":"A","type":"boolean","value":false}""")!!
        assertEquals(0L, noMeta.updatedAt)
        assertEquals("", noMeta.updatedBy)
    }

    @Test
    fun rejectsUnusableItemJson() {
        fun parse(json: String) = ListLogic.parseItem(JSONObject(json))
        assertNull(parse("""{"type":"boolean","value":true}"""))
        assertNull(parse("""{"name":"   ","type":"boolean","value":true}"""))
        assertNull(parse("""{"name":null,"type":"boolean","value":true}"""))
        assertNull(parse("""{"name":"A","type":"integer"}"""))
        assertNull(parse("""{"name":"A","value":"maybe"}"""))
    }

    @Test
    fun parseItemsSkipsMalformedAndDuplicateEntries() {
        val array = JSONArray(
            """[
              {"name":"Milk","type":"integer","value":2,"updatedAt":1,"updatedBy":"Home"},
              "garbage",
              {"name":"","type":"boolean","value":true},
              {"name":"MILK","type":"integer","value":9,"updatedAt":2,"updatedBy":"Home"},
              {"name":"Bread","type":"boolean","value":false,"updatedAt":3,"updatedBy":"Shop"}
            ]""",
        )
        val items = ListLogic.parseItems(array)
        assertEquals(listOf("Milk", "Bread"), items.map { it.name })
        assertEquals(Quantity.of(2), items[0].value)
    }

    // ---- names to send ----

    @Test
    fun newNamesMustPassTheAppRules() {
        assertEquals("Milk", ListLogic.nameForChange("  Milk ", existing = null))
        assertNull(ListLogic.nameForChange("Milk\n2%", existing = null))
        assertNull(ListLogic.nameForChange("x".repeat(101), existing = null))
        assertNull(ListLogic.nameForChange("   ", existing = null))
    }

    @Test
    fun handTypedNamesOfExistingItemsAreSentOnOneLineWithTheSameKey() {
        val multiLine = item("Milk\n2%", BoolValue(true))
        val sent = ListLogic.nameForChange(multiLine.name, multiLine)
        assertEquals("Milk 2%", sent)
        assertEquals(multiLine.key, ItemKeys.normalize(sent!!))

        val long = item("Very long name ".repeat(10).trim(), Quantity.of(3))
        assertEquals(long.name, ListLogic.nameForChange(long.name, long))
        // Double spaces are kept when there is no line break (the sheet keeps its spelling).
        val spaced = item("Oat  Milk", BoolValue(true))
        assertEquals("Oat  Milk", ListLogic.nameForChange(spaced.name, spaced))
    }
}
