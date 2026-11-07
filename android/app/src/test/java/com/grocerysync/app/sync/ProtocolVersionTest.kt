package com.grocerysync.app.sync

import com.grocerysync.app.data.Amount
import com.grocerysync.app.data.GroceryItem
import com.grocerysync.app.data.ItemValue.BoolValue
import com.grocerysync.app.data.ItemValue.Quantity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which protocol a script answer comes from, and how answers of both versions read. */
class ProtocolVersionTest {

    private fun ok(json: String): JSONObject = (Protocol.interpretResponse(200, "application/json", json) as ApiResult.Success).value

    @Test
    fun versionOfAnAnswer() {
        assertEquals(3, Protocol.versionOf(JSONObject("""{"ok":true,"version":3}""")))
        assertEquals(4, Protocol.versionOf(JSONObject("""{"ok":true,"version":4}""")))
        assertEquals(2, Protocol.versionOf(JSONObject("""{"ok":true,"version":2}""")))
        assertEquals(3, Protocol.versionOf(JSONObject("""{"ok":true,"version":"3"}""")))
        // Scripts before protocol 3 only put a version in ping answers.
        assertEquals(2, Protocol.versionOf(JSONObject("""{"ok":true,"revision":"r"}""")))
        assertEquals(2, Protocol.versionOf(JSONObject("""{"ok":true,"version":"new"}""")))
    }

    @Test
    fun onlyProtocol3AndLaterTakeUnits() {
        assertTrue(Protocol.takesUnits(3))
        assertTrue(Protocol.takesUnits(4))
        assertFalse(Protocol.takesUnits(2))
        assertFalse(Protocol.takesUnits(null))
        assertTrue(Protocol.isOutdated(2))
        assertTrue(Protocol.isOutdated(1))
        assertFalse(Protocol.isOutdated(3))
        assertFalse("not known yet is not out of date", Protocol.isOutdated(null))
    }

    @Test
    fun protocol3ListWithUnits() {
        val json = ok(
            """{"ok":true,"version":3,"revision":"r3","serverTime":9,"spreadsheet":"Groceries",
                "items":[{"name":"Rice","type":"quantity","value":2.5,"unit":"kg","updatedAt":5,"updatedBy":"Home"},
                         {"name":"Bread","type":"boolean","value":true,"updatedAt":6,"updatedBy":"Shop"}]}""",
        )
        val result = (Protocol.parseList(json) as ApiResult.Success).value
        assertEquals(3, result.serverVersion)
        assertEquals(
            listOf(
                GroceryItem("rice", "Rice", Quantity(Amount(2_500L), "kg"), 5L, "Home", false),
                GroceryItem("bread", "Bread", BoolValue(true), 6L, "Shop", false),
            ),
            result.snapshot!!.items,
        )
        val unchanged = (Protocol.parseList(ok("""{"ok":true,"version":3,"revision":"r3","serverTime":9,"unchanged":true}""")) as ApiResult.Success).value
        assertEquals(3, unchanged.serverVersion)
    }

    @Test
    fun protocol2ListReadsAsQuantitiesWithoutUnit() {
        val json = ok(
            """{"ok":true,"revision":"r2","serverTime":9,"spreadsheet":"Groceries",
                "items":[{"name":"Eggs","type":"integer","value":6,"updatedAt":5,"updatedBy":"Home"}]}""",
        )
        val result = (Protocol.parseList(json) as ApiResult.Success).value
        assertEquals(2, result.serverVersion)
        assertEquals(Quantity(Amount.whole(6L), ""), result.snapshot!!.items.single().value)
    }

    @Test
    fun applyAnswerSaysItsVersion() {
        val v3 = ok("""{"ok":true,"version":3,"results":[{"id":"a","status":"applied"}],"revision":"r","serverTime":1,"items":[]}""")
        assertEquals(3, (Protocol.parseApply(v3) as ApiResult.Success).value.serverVersion)
        val v2 = ok("""{"ok":true,"results":[{"id":"a","status":"error","error":"invalid type"}],"revision":"r","serverTime":1,"items":[]}""")
        assertEquals(2, (Protocol.parseApply(v2) as ApiResult.Success).value.serverVersion)
    }

    @Test
    fun outdatedScriptMessageSaysWhatToDo() {
        assertTrue(Protocol.MSG_SCRIPT_OUTDATED.contains("Code.gs"))
        assertTrue(Protocol.MSG_SCRIPT_OUTDATED.contains("deploy a new version"))
        assertTrue(Protocol.MSG_SCRIPT_OUTDATED.contains("units"))
    }
}
