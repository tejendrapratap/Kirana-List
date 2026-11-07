package com.grocerysync.app.sync

import com.grocerysync.app.data.Amount
import com.grocerysync.app.data.GroceryItem
import com.grocerysync.app.data.ItemValue
import com.grocerysync.app.data.ItemValue.BoolValue
import com.grocerysync.app.data.ItemValue.Quantity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Answers exactly as apps-script/Code.gs writes them (captured from the script running in the node fake
 * of Apps Script, and from the script of protocol 2 in apps-script/test/fixtures/Code.v2.gs), read the
 * way the app reads them. Keeps the two halves of protocol 3 from drifting apart.
 */
class ServerAnswersContractTest {

    private fun ok(json: String): JSONObject = (Protocol.interpretResponse(200, "application/json", json) as ApiResult.Success).value

    private fun values(items: List<GroceryItem>): Map<String, ItemValue> = items.associate { it.name to it.value }

    @Test
    fun currentScriptAnsweringThisApp() {
        val ping = ok("""{"ok":true,"app":"grocery-sync","version":3,"spreadsheet":"Groceries"}""")
        assertEquals(ApiResult.Success("Groceries"), Protocol.parsePing(ping))
        assertFalse(Protocol.isOutdated(Protocol.versionOf(ping)))

        val list = (Protocol.parseList(ok(V3_LIST)) as ApiResult.Success).value
        assertEquals(3, list.serverVersion)
        assertTrue(Protocol.takesUnits(list.serverVersion))
        assertEquals("4ace38f49b5a8ae4f928d61cffa3e457", list.revision)
        assertEquals(
            mapOf(
                "Rice" to Quantity(Amount(1_500L), "kg"),
                "Milk" to Quantity(Amount(250L), ""),
                "Bread" to BoolValue(true),
                "Coriander" to Quantity(Amount.whole(2L), "bunch"),
            ),
            values(list.snapshot!!.items),
        )
        assertEquals(1794052740000L, list.snapshot!!.items.first().updatedAt)

        val apply = (Protocol.parseApply(ok(V3_APPLY)) as ApiResult.Success).value
        assertEquals(3, apply.serverVersion)
        assertEquals(listOf(ChangeStatus.APPLIED, ChangeStatus.APPLIED, ChangeStatus.APPLIED, ChangeStatus.APPLIED, ChangeStatus.ERROR), apply.results.map { it.status })
        assertEquals("value must be a number from 0 to 999999 for type quantity", apply.results.last().error)
        assertEquals(values(list.snapshot!!.items), values(apply.snapshot.items))
    }

    @Test
    fun currentScriptAnsweringAnAppFromBeforeUnits() {
        // What the old app on the other phone gets: whole numbers rounded up, no units, no version.
        val list = (Protocol.parseList(ok(V2_VIEW_LIST)) as ApiResult.Success).value
        assertEquals(2, list.serverVersion)
        assertEquals(
            mapOf(
                "Rice" to Quantity(Amount.whole(2L), ""),
                "Milk" to Quantity(Amount.whole(1L), ""),
                "Bread" to BoolValue(true),
                "Coriander" to Quantity(Amount.whole(2L), ""),
            ),
            values(list.snapshot!!.items),
        )
    }

    @Test
    fun scriptFromBeforeUnits() {
        val ping = ok("""{"ok":true,"app":"grocery-sync","version":2,"spreadsheet":"Groceries"}""")
        assertEquals(ApiResult.Success("Groceries"), Protocol.parsePing(ping))
        assertTrue(Protocol.isOutdated(Protocol.versionOf(ping)))

        val list = (Protocol.parseList(ok(OLD_SCRIPT_LIST)) as ApiResult.Success).value
        assertTrue(Protocol.isOutdated(list.serverVersion))
        assertEquals(mapOf("Rice" to Quantity(Amount.whole(2L), "")), values(list.snapshot!!.items))

        // Its answer to a protocol 3 change: no version, so the app doesn't take the error as final.
        val apply = (Protocol.parseApply(ok(OLD_SCRIPT_APPLY)) as ApiResult.Success).value
        assertFalse(Protocol.takesUnits(apply.serverVersion))
        assertEquals(ChangeStatus.ERROR, apply.results.single().status)
    }

    private companion object {
        const val V3_LIST =
            """{"ok":true,"version":3,"revision":"4ace38f49b5a8ae4f928d61cffa3e457","serverTime":1794052800000,"spreadsheet":"Groceries","items":[{"name":"Rice","type":"quantity","value":1.5,"unit":"kg","updatedAt":1794052740000,"updatedBy":"Home"},{"name":"Milk","type":"quantity","value":0.25,"unit":"","updatedAt":1794052740000,"updatedBy":"Home"},{"name":"Bread","type":"boolean","value":true,"updatedAt":1794052740000,"updatedBy":"Home"},{"name":"Coriander","type":"quantity","value":2,"unit":"bunch","updatedAt":1794052740000,"updatedBy":"Home"}]}"""

        const val V3_APPLY =
            """{"ok":true,"version":3,"results":[{"id":"c1","status":"applied"},{"id":"c2","status":"applied"},{"id":"c3","status":"applied"},{"id":"c4","status":"applied"},{"id":"c5","status":"error","error":"value must be a number from 0 to 999999 for type quantity"}],"revision":"4ace38f49b5a8ae4f928d61cffa3e457","serverTime":1794052800000,"spreadsheet":"Groceries","items":[{"name":"Rice","type":"quantity","value":1.5,"unit":"kg","updatedAt":1794052740000,"updatedBy":"Home"},{"name":"Milk","type":"quantity","value":0.25,"unit":"","updatedAt":1794052740000,"updatedBy":"Home"},{"name":"Bread","type":"boolean","value":true,"updatedAt":1794052740000,"updatedBy":"Home"},{"name":"Coriander","type":"quantity","value":2,"unit":"bunch","updatedAt":1794052740000,"updatedBy":"Home"}]}"""

        const val V2_VIEW_LIST =
            """{"ok":true,"revision":"3cd04194582bec0615ed5b7c209a3351","serverTime":1794052800000,"spreadsheet":"Groceries","items":[{"name":"Rice","type":"integer","value":2,"updatedAt":1794052740000,"updatedBy":"Home"},{"name":"Milk","type":"integer","value":1,"updatedAt":1794052740000,"updatedBy":"Home"},{"name":"Bread","type":"boolean","value":true,"updatedAt":1794052740000,"updatedBy":"Home"},{"name":"Coriander","type":"integer","value":2,"updatedAt":1794052740000,"updatedBy":"Home"}]}"""

        const val OLD_SCRIPT_LIST =
            """{"ok":true,"revision":"42b9aad120b3cf61b99c1ba177accc4e","serverTime":1794052800000,"spreadsheet":"Groceries","items":[{"name":"Rice","type":"integer","value":2,"updatedAt":1794052740000,"updatedBy":"Home"}]}"""

        const val OLD_SCRIPT_APPLY =
            """{"ok":true,"results":[{"id":"d2","status":"error","error":"invalid type"}],"revision":"42b9aad120b3cf61b99c1ba177accc4e","serverTime":1794052800000,"spreadsheet":"Groceries","items":[{"name":"Rice","type":"integer","value":2,"updatedAt":1794052740000,"updatedBy":"Home"}]}"""
    }
}
