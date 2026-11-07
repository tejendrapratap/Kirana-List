package com.grocerysync.app.sync

import com.grocerysync.app.data.GroceryItem
import com.grocerysync.app.data.ItemValue.BoolValue
import com.grocerysync.app.data.ItemValue.Quantity
import com.grocerysync.app.data.ListLogic
import com.grocerysync.app.data.OpStatus
import com.grocerysync.app.data.OpType
import com.grocerysync.app.data.OutboxOp
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    private fun ok(json: String): JSONObject {
        val result = Protocol.interpretResponse(200, "application/json; charset=utf-8", json)
        return (result as ApiResult.Success).value
    }

    private fun failure(result: ApiResult<*>): ApiResult.Failure = result as ApiResult.Failure

    // ---- list ----

    @Test
    fun parsesFullList() {
        val json = ok(
            """
            {"ok":true,"revision":"0123456789abcdef0123456789abcdef","serverTime":1762500009000,
             "spreadsheet":"Family groceries",
             "items":[
               {"name":"Milk","type":"integer","value":2,"updatedAt":1762500000000,"updatedBy":"Home"},
               {"name":"Bread","type":"boolean","value":true,"updatedAt":1762500001000,"updatedBy":"Shop"},
               {"name":"Jam","type":"boolean","value":false,"updatedAt":0,"updatedBy":"Sheet"}
             ]}
            """.trimIndent(),
        )
        val result = (Protocol.parseList(json) as ApiResult.Success).value
        assertFalse(result.unchanged)
        assertEquals("0123456789abcdef0123456789abcdef", result.revision)
        assertEquals(1_762_500_009_000L, result.serverTime)
        val snapshot = result.snapshot!!
        assertEquals("Family groceries", snapshot.spreadsheetName)
        assertEquals("0123456789abcdef0123456789abcdef", snapshot.revision)
        assertEquals(
            listOf(
                GroceryItem("milk", "Milk", Quantity.of(2), 1_762_500_000_000L, "Home", false),
                GroceryItem("bread", "Bread", BoolValue(true), 1_762_500_001_000L, "Shop", false),
                GroceryItem("jam", "Jam", BoolValue(false), 0L, "Sheet", false),
            ),
            snapshot.items,
        )
    }

    @Test
    fun parsesUnchangedList() {
        val json = ok("""{"ok":true,"revision":"abc","serverTime":42,"unchanged":true}""")
        val result = (Protocol.parseList(json) as ApiResult.Success).value
        assertTrue(result.unchanged)
        assertEquals("abc", result.revision)
        assertEquals(42L, result.serverTime)
        assertNull(result.snapshot)
    }

    @Test
    fun parsesEmptyList() {
        val json = ok("""{"ok":true,"revision":"abc","serverTime":42,"spreadsheet":"S","items":[]}""")
        assertEquals(emptyList<GroceryItem>(), (Protocol.parseList(json) as ApiResult.Success).value.snapshot!!.items)
    }

    @Test
    fun malformedListIsTreatedAsTransient() {
        val noItems = failure(Protocol.parseList(ok("""{"ok":true,"revision":"abc","serverTime":1}""")))
        assertEquals(FailureKind.OFFLINE, noItems.kind)
        val noRevision = failure(Protocol.parseList(ok("""{"ok":true,"serverTime":1,"items":[]}""")))
        assertEquals(FailureKind.OFFLINE, noRevision.kind)
        val itemsNotArray = failure(Protocol.parseList(ok("""{"ok":true,"revision":"r","items":{}}""")))
        assertEquals(FailureKind.OFFLINE, itemsNotArray.kind)
    }

    // ---- apply request ----

    private val ops = listOf(
        OutboxOp(1, "11111111-1111-4111-8111-111111111111", OpType.UPSERT, "milk", "Milk", Quantity.of(2), 1_762_500_000_000L, OpStatus.PENDING),
        OutboxOp(2, "22222222-2222-4222-8222-222222222222", OpType.UPSERT, "bread", "Bread", BoolValue(true), 1_762_500_000_500L, OpStatus.PENDING),
        OutboxOp(3, "33333333-3333-4333-8333-333333333333", OpType.DELETE, "jam", "Jam", null, 1_762_500_001_000L, OpStatus.PENDING),
    )

    @Test
    fun buildsApplyRequest() {
        val body = ListLogic.buildApplyRequest("T0k3n", "Home", ops)
        val json = JSONObject(body)
        assertEquals("T0k3n", json.getString("token"))
        assertEquals("apply", json.getString("action"))
        assertEquals("Home", json.getString("device"))
        assertEquals(3, json.getInt("protocol"))

        val changes = json.getJSONArray("changes")
        assertEquals(3, changes.length())

        val milk = changes.getJSONObject(0)
        assertEquals("11111111-1111-4111-8111-111111111111", milk.getString("id"))
        assertEquals("upsert", milk.getString("op"))
        assertEquals("Milk", milk.getString("name"))
        // Protocol 3 (changed with units): quantities are "quantity" with their unit ("" = none).
        assertEquals("quantity", milk.getString("type"))
        assertEquals(2, (milk.get("value") as Number).toInt())
        assertEquals("", milk.getString("unit"))
        assertEquals(1_762_500_000_000L, (milk.get("time") as Number).toLong())

        val bread = changes.getJSONObject(1)
        assertEquals("boolean", bread.getString("type"))
        assertEquals(true, bread.get("value"))
        assertFalse(bread.has("unit"))

        val jam = changes.getJSONObject(2)
        assertEquals("delete", jam.getString("op"))
        assertEquals("Jam", jam.getString("name"))
        assertFalse(jam.has("type"))
        assertFalse(jam.has("value"))
        assertEquals(1_762_500_001_000L, (jam.get("time") as Number).toLong())
    }

    @Test
    fun applyRequestUsesJsonIntegersAndBooleans() {
        val body = ListLogic.buildApplyRequest("t", "Home", ops).replace(" ", "")
        assertTrue(body, body.contains("\"value\":2"))
        assertFalse(body, body.contains("2.0"))
        assertTrue(body, body.contains("\"value\":true"))
        assertFalse(body, body.contains("\"value\":\"true\""))
        assertTrue(body, body.contains("\"time\":1762500000000"))
        assertFalse(body, body.contains("E12"))
    }

    // ---- apply response ----

    @Test
    fun parsesApplyResults() {
        val json = ok(
            """
            {"ok":true,
             "results":[
               {"id":"a","status":"applied"},
               {"id":"b","status":"stale"},
               {"id":"c","status":"duplicate"},
               {"id":"d","status":"error","error":"name too long"},
               {"id":"e","status":"error"},
               {"id":"f","status":"weird"},
               {"status":"applied"}
             ],
             "revision":"rev2","serverTime":99,"spreadsheet":"Groceries",
             "items":[{"name":"Milk","type":"integer","value":3,"updatedAt":98,"updatedBy":"Home"}]}
            """.trimIndent(),
        )
        val result = (Protocol.parseApply(json) as ApiResult.Success).value
        assertEquals(
            listOf(
                ChangeResult("a", ChangeStatus.APPLIED, null),
                ChangeResult("b", ChangeStatus.STALE, null),
                ChangeResult("c", ChangeStatus.DUPLICATE, null),
                ChangeResult("d", ChangeStatus.ERROR, "name too long"),
                ChangeResult("e", ChangeStatus.ERROR, ListLogic.DEFAULT_FAILURE),
            ),
            result.results,
        )
        assertEquals(99L, result.serverTime)
        assertEquals("rev2", result.snapshot.revision)
        assertEquals("Groceries", result.snapshot.spreadsheetName)
        assertEquals(listOf(GroceryItem("milk", "Milk", Quantity.of(3), 98L, "Home", false)), result.snapshot.items)
    }

    @Test
    fun malformedApplyIsTreatedAsTransient() {
        val noResults = Protocol.parseApply(ok("""{"ok":true,"revision":"r","serverTime":1,"items":[]}"""))
        assertEquals(FailureKind.OFFLINE, failure(noResults).kind)
        val noItems = Protocol.parseApply(ok("""{"ok":true,"revision":"r","serverTime":1,"results":[]}"""))
        assertEquals(FailureKind.OFFLINE, failure(noItems).kind)
    }

    // ---- ping ----

    @Test
    fun parsesPing() {
        val json = ok("""{"ok":true,"app":"grocery-sync","version":2,"spreadsheet":"Groceries"}""")
        assertEquals(ApiResult.Success("Groceries"), Protocol.parsePing(json))
    }

    @Test
    fun pingFromAnotherScriptFails() {
        val json = ok("""{"ok":true,"app":"something-else"}""")
        assertEquals(Protocol.actionNeeded(Protocol.MSG_NOT_OUR_SCRIPT), Protocol.parsePing(json))
    }

    // ---- response classification ----

    @Test
    fun okJsonIsSuccess() {
        val result = Protocol.interpretResponse(200, "application/json", "\uFEFF {\"ok\":true,\"x\":1}")
        assertEquals(1, (result as ApiResult.Success).value.getInt("x"))
    }

    @Test
    fun htmlIsAMisconfiguration() {
        val signIn = Protocol.interpretResponse(200, "text/html; charset=utf-8", "<!DOCTYPE html><html>Sign in</html>")
        assertEquals(Protocol.actionNeeded(Protocol.MSG_HTML), signIn)
        assertEquals(
            "Google returned a web page instead of data. In Apps Script use Deploy > Manage deployments and set 'Who has access' to 'Anyone'.",
            Protocol.MSG_HTML,
        )
        // Not JSON even though the content type claims it is.
        assertEquals(Protocol.actionNeeded(Protocol.MSG_HTML), Protocol.interpretResponse(200, "application/json", "<html></html>"))
        assertEquals(Protocol.actionNeeded(Protocol.MSG_HTML), Protocol.interpretResponse(200, null, ""))
        // HTML that happens to contain JSON-looking text is still HTML.
        assertEquals(Protocol.actionNeeded(Protocol.MSG_HTML), Protocol.interpretResponse(200, "text/html", "{\"ok\":true}"))
    }

    @Test
    fun missingDoGetIsExplained() {
        val result = Protocol.interpretResponse(200, "text/html", "<html><div>Script function not found: doGet</div></html>")
        assertEquals(Protocol.actionNeeded(Protocol.MSG_NO_DOGET), result)
    }

    @Test
    fun httpErrorsAreClassified() {
        assertEquals(Protocol.actionNeeded(Protocol.MSG_ACCESS_DENIED), Protocol.interpretResponse(401, "text/html", ""))
        assertEquals(Protocol.actionNeeded(Protocol.MSG_ACCESS_DENIED), Protocol.interpretResponse(403, "text/html", ""))
        assertEquals(Protocol.actionNeeded(Protocol.MSG_NOT_FOUND), Protocol.interpretResponse(404, "text/html", ""))
        assertEquals(
            "Web app not found (404). Check the URL, or deploy the script as a Web app.",
            Protocol.MSG_NOT_FOUND,
        )
        for (code in listOf(408, 429, 500, 502, 503)) {
            assertEquals("HTTP $code", FailureKind.OFFLINE, failure(Protocol.interpretResponse(code, "text/html", "")).kind)
        }
        assertEquals(FailureKind.ERROR, failure(Protocol.interpretResponse(400, "text/html", "")).kind)
    }

    @Test
    fun brokenJsonIsTransient() {
        assertEquals(FailureKind.OFFLINE, failure(Protocol.interpretResponse(200, "application/json", "{\"ok\":tru")).kind)
    }

    @Test
    fun jsonWithoutOkFlagIsNotOurScript() {
        assertEquals(
            Protocol.actionNeeded(Protocol.MSG_NOT_OUR_SCRIPT),
            Protocol.interpretResponse(200, "application/json", "{\"status\":\"success\"}"),
        )
    }

    @Test
    fun serverErrorsAreMapped() {
        fun interpret(error: String) = Protocol.interpretResponse(200, "application/json", "{\"ok\":false,\"error\":\"$error\"}")

        assertEquals(Protocol.actionNeeded(Protocol.MSG_UNAUTHORIZED), interpret("unauthorized"))
        assertEquals("The secret token doesn't match SECRET_TOKEN in the script.", Protocol.MSG_UNAUTHORIZED)

        assertEquals(Protocol.actionNeeded(Protocol.MSG_NOT_CONFIGURED), interpret("server_not_configured"))
        assertEquals(
            "The script still has SECRET_TOKEN = 'CHANGE_ME'. Set your own token in Code.gs and deploy a new version.",
            Protocol.MSG_NOT_CONFIGURED,
        )

        assertEquals(Protocol.offline(Protocol.MSG_BUSY), interpret("busy"))
        assertEquals(FailureKind.OFFLINE, failure(interpret("internal: Exception: Service Spreadsheets failed")).kind)

        val badRequest = failure(interpret("bad_request: unknown action"))
        assertEquals(FailureKind.ERROR, badRequest.kind)
        assertTrue(badRequest.message, badRequest.message.contains("unknown action"))

        val unknown = failure(interpret("something_new"))
        assertEquals(FailureKind.ERROR, unknown.kind)
        assertTrue(unknown.message, unknown.message.contains("something_new"))

        val missing = failure(Protocol.interpretResponse(200, "application/json", "{\"ok\":false}"))
        assertEquals(FailureKind.ERROR, missing.kind)
    }

    @Test
    fun offlineMessage() {
        assertEquals(
            "Can't reach Google right now. Changes are saved on this phone and will sync automatically.",
            Protocol.offline().message,
        )
    }

    // ---- failure sources (decide whether a failed push still lets the pull run) ----

    @Test
    fun failuresSayWhereTheyComeFrom() {
        fun interpret(error: String) = failure(Protocol.interpretResponse(200, "application/json", "{\"ok\":false,\"error\":\"$error\"}"))

        assertEquals(FailureSource.NO_RESPONSE, Protocol.offline().source)
        assertFalse(Protocol.offline().serverAnswered)

        assertEquals(FailureSource.SETUP, interpret("unauthorized").source)
        assertEquals(FailureSource.SETUP, interpret("server_not_configured").source)
        assertEquals(FailureSource.SETUP, failure(Protocol.interpretResponse(200, "application/json", "{\"status\":1}")).source)
        assertEquals(FailureSource.SETUP, failure(Protocol.interpretResponse(403, "text/html", "")).source)
        assertEquals(FailureSource.SETUP, failure(Protocol.interpretResponse(404, "text/html", "")).source)
        assertEquals(FailureSource.SETUP, failure(Protocol.interpretResponse(200, "text/html", "<html>Sign in</html>")).source)

        assertEquals(FailureSource.GLITCH, interpret("busy").source)
        assertEquals(FailureSource.GLITCH, failure(Protocol.interpretResponse(503, "text/html", "")).source)
        assertEquals(FailureSource.GLITCH, failure(Protocol.interpretResponse(200, "application/json", "{\"ok\":tru")).source)

        assertEquals(FailureSource.SCRIPT, interpret("internal: Choose Boolean or Integer.").source)
        assertEquals(FailureSource.SCRIPT, interpret("bad_request: unknown action").source)
        assertEquals(FailureSource.SCRIPT, interpret("something_new").source)
        assertTrue(interpret("busy").serverAnswered)
    }

    // ---- internal: <detail> ----

    @Test
    fun internalErrorKeepsTheScriptsDetailAndStaysRetryable() {
        val result = Protocol.mapServerError("internal: Choose Boolean or Integer.")
        assertEquals(
            ApiResult.Failure(
                FailureKind.OFFLINE,
                "The sheet's script reported an error: Choose Boolean or Integer.",
                FailureSource.SCRIPT,
            ),
            result,
        )
        assertEquals("Choose Boolean or Integer.", Protocol.scriptErrorDetail(result.message))

        // Made into one sentence on one line.
        assertEquals(
            "The sheet's script reported an error: Service Spreadsheets timed out while accessing document with id abc.",
            Protocol.mapServerError("internal:  Service Spreadsheets timed out\nwhile accessing document with id abc").message,
        )
        // Long details are cut.
        val long = Protocol.mapServerError("internal: " + "x".repeat(500)).message
        assertTrue(long, long.length < 260)
        assertTrue(long, long.endsWith("..."))
        // Nothing to show: the generic text.
        assertEquals(Protocol.offline(Protocol.MSG_GOOGLE_TROUBLE), Protocol.mapServerError("internal"))
        assertEquals(Protocol.offline(Protocol.MSG_GOOGLE_TROUBLE), Protocol.mapServerError("internal:  "))
    }

    @Test
    fun scriptErrorDetailOnlyForScriptMessages() {
        assertEquals("boom", Protocol.scriptErrorDetail(Protocol.SCRIPT_FAILED_PREFIX + "boom"))
        assertNull(Protocol.scriptErrorDetail(Protocol.MSG_GOOGLE_TROUBLE))
        assertNull(Protocol.scriptErrorDetail(null))
    }

    // ---- per-change sheet_rejected ----

    @Test
    fun sheetRejectedChangeGetsAnExplanation() {
        assertEquals(
            "The sheet refused this change: Choose Boolean or Integer. (data validation on the Type column of the Items sheet). " +
                "Remove or relax that rule in the sheet (Data > Data validation), then tap Retry.",
            Protocol.changeErrorText("sheet_rejected: Choose Boolean or Integer. (data validation on the Type column of the Items sheet)"),
        )
        assertEquals(
            "The sheet refused this change: Choose Boolean or Integer. " +
                "Remove or relax that rule in the sheet (Data > Data validation), then tap Retry.",
            Protocol.changeErrorText("sheet_rejected: Choose Boolean or Integer."),
        )
        assertEquals(
            "The sheet refused this change: You are trying to edit a protected cell or object. " +
                "Remove that protection in the sheet (Data > Protect sheets and ranges), then tap Retry.",
            Protocol.changeErrorText("sheet_rejected: You are trying to edit a protected cell or object."),
        )
        assertEquals(
            "The sheet refused this change. Remove or relax that rule in the sheet (Data > Data validation), then tap Retry.",
            Protocol.changeErrorText("sheet_rejected:"),
        )
        // Other errors are shown as the server sent them.
        assertEquals("name: too long", Protocol.changeErrorText("name: too long"))
        assertEquals(ListLogic.DEFAULT_FAILURE, Protocol.changeErrorText(null))
        assertEquals(ListLogic.DEFAULT_FAILURE, Protocol.changeErrorText(" "))
    }

    @Test
    fun parseApplyExplainsRefusedChanges() {
        val json = ok(
            """
            {"ok":true,"results":[
               {"id":"a","status":"applied"},
               {"id":"b","status":"error","error":"sheet_rejected: Choose Boolean or Integer. (data validation on the Type column of the Items sheet)"},
               {"id":"c","status":"error","error":"time: must be a number"}],
             "revision":"r","serverTime":1,"items":[]}
            """.trimIndent(),
        )
        val results = (Protocol.parseApply(json) as ApiResult.Success).value.results
        assertEquals(ChangeResult("a", ChangeStatus.APPLIED, null), results[0])
        assertTrue(results[1].error!!, results[1].error!!.startsWith("The sheet refused this change: Choose Boolean or Integer. (data validation"))
        assertTrue(results[1].error!!, results[1].error!!.endsWith("then tap Retry."))
        assertEquals(ChangeResult("c", ChangeStatus.ERROR, "time: must be a number"), results[2])
    }

    // ---- web pages instead of JSON ----

    private fun page(html: String, code: Int = 200, finalHost: String? = null) =
        failure(Protocol.interpretResponse(code, "text/html; charset=utf-8", html, finalHost))

    /** Shape of the page Apps Script serves when a web app's code throws outside its own error handling. */
    private fun appsScriptErrorPage(error: String) =
        """<!DOCTYPE html><html><head><link rel="shortcut icon" href="//ssl.gstatic.com/docs/script/images/favicon.ico">""" +
            """<title>Error</title><style type="text/css" nonce="n0nce">body {background-color: #fff; margin: 0; padding: 0;}""" +
            """.errorMessage {font-family: Arial,sans-serif; font-size: 12pt; font-weight: bold; line-height: 150%; padding-top: 25px;}""" +
            """</style></head><body style="margin:20px"><div><img alt="Google Apps Script" """ +
            """src="//ssl.gstatic.com/docs/script/images/logo.png"></div><div style="text-align:center;font-family:monospace;""" +
            """margin:50px auto 0;max-width:600px">$error</div></body></html>"""

    /** Shape of Drive's page for a deployment id that doesn't exist (deleted deployment, cut-off URL). */
    private val driveFileNotFoundPage =
        """<!DOCTYPE html><html lang="en"><head><meta name="description" content="Web word processing, presentations and spreadsheets">""" +
            """<link rel="shortcut icon" href="//docs.google.com/favicon.ico"><title>Google Drive - Page Not Found</title>""" +
            """<style nonce="n0nce">#drive-logo{margin:18px 0;position:absolute;white-space:nowrap}</style>""" +
            """<script nonce="n0nce">_docs_flag_initialData={"docs-ccdom":"https://accounts.google.com/ServiceLogin"};</script></head>""" +
            """<body><div id="outerContainer"><div id="innerContainer"><div id="drive-logo"><a href="/">""" +
            """<span class="docs-drivelogo-img" title="Google logo"></span><span class="docs-drivelogo-text">&nbsp;Drive</span></a></div>""" +
            """<div align="center"><p class="errorMessage" style="padding-top: 50px">Sorry, unable to open the file at this time.</p>""" +
            """<p> Please check the address and try again. </p></div></div></div></body></html>"""

    @Test
    fun googleSideTroubleTheScriptReportsIsMomentaryAndKeepsItsWords() {
        // Code.gs reports Google-side trouble as "internal: <Apps Script's message>" so the app retries.
        val timedOut = Protocol.mapServerError(
            "internal: Service Spreadsheets timed out while accessing document with id 1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms.",
        )
        assertEquals(
            Protocol.offline(
                "The sheet's script reported an error: Service Spreadsheets timed out while accessing document.",
                FailureSource.GLITCH,
            ),
            timedOut,
        )
        for (detail in listOf(
            "Too many simultaneous invocations: Spreadsheets",
            "We're sorry, a server error occurred. Please wait a bit and try again.",
            "Service invoked too many times in a short time: spreadsheets. Try Utilities.sleep(1000) between calls.",
            "Unexpected error while getting the method or property setValues on object SpreadsheetApp.Range.",
            "Service Spreadsheets failed while accessing document with id 1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms.",
            "Lock timeout: another process was holding the lock for too long.",
        )) {
            val result = Protocol.mapServerError("internal: $detail")
            assertEquals(detail, FailureKind.OFFLINE, result.kind)
            assertEquals(detail, FailureSource.GLITCH, result.source)
            assertTrue(result.message, result.message.startsWith(Protocol.SCRIPT_ERROR_PREFIX))
            assertFalse(result.message, result.message.contains("1BxiMVs0"))
        }
        // The sheet's own refusal (the user's validation rule) is the script's error: worth a warning at once.
        assertEquals(FailureSource.SCRIPT, Protocol.mapServerError("internal: Choose Boolean or Integer.").source)
    }

    /** Shape of the Google sign-in page a web app redirects to when 'Who has access' isn't 'Anyone'. */
    private val signInPage =
        """<!doctype html><html lang="en" dir="ltr"><head><base href="https://accounts.google.com/v3/signin/">""" +
            """<meta name="referrer" content="origin"><title>Sign in - Google Accounts</title><style nonce="n">body{margin:0}</style>""" +
            """</head><body><div class="H2SoFe"><form method="post" action="/v3/signin/_/AccountsSignInUi/data/batchexecute">""" +
            """<h1 id="headingText">Sign in</h1><div id="headingSubtext">to continue to Google Drive</div>""" +
            """<input type="email" name="identifier" autocomplete="username"></form></div></body></html>"""

    @Test
    fun signInPageAsksForAccessAnyone() {
        assertEquals(Protocol.actionNeeded(Protocol.MSG_HTML), page(signInPage))
        // Localised pages are recognised by where they come from.
        val german = "<html><head><title>Anmelden - Google Konten</title></head><body><h1>Anmelden</h1></body></html>"
        assertEquals(Protocol.actionNeeded(Protocol.MSG_HTML), page(german, finalHost = "accounts.google.com"))
        val needAccess = "<html><head><title>Google Drive</title></head><body><h1>You need access</h1>" +
            "<p>Request access, or switch to an account with access.</p></body></html>"
        assertEquals(Protocol.actionNeeded(Protocol.MSG_HTML), page(needAccess))
    }

    @Test
    fun appsScriptErrorPageShowsTheScriptsError() {
        val result = page(appsScriptErrorPage("TypeError: Cannot read properties of undefined (reading &#39;getRange&#39;) (line 214, file &quot;Code&quot;)"))
        assertEquals(
            Protocol.actionNeeded(
                "The sheet's script failed: TypeError: Cannot read properties of undefined (reading 'getRange') (line 214, file \"Code\")",
                FailureSource.SCRIPT,
            ),
            result,
        )
        assertEquals(
            Protocol.actionNeeded("The sheet's script failed: Authorization is required to perform that action.", FailureSource.SCRIPT),
            page(appsScriptErrorPage("Authorization is required to perform that action.")),
        )
        // The existing explanation for a URL that runs another script stays.
        assertEquals(Protocol.actionNeeded(Protocol.MSG_NO_DOGET), page(appsScriptErrorPage("Script function not found: doGet")))
    }

    @Test
    fun appsScriptErrorsThatPassByThemselvesAreRetried() {
        for (error in listOf(
            "We&#39;re sorry, a server error occurred. Please wait a bit and try again.",
            "Exceeded maximum execution time",
            "Service invoked too many times for one day: urlfetch.",
            "Service Spreadsheets timed out while accessing document with id 1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms.",
        )) {
            val result = page(appsScriptErrorPage(error))
            assertEquals(error, FailureKind.OFFLINE, result.kind)
            // Google-side trouble: often gone by the next try, so a single one is no reason for a warning.
            assertEquals(error, FailureSource.GLITCH, result.source)
            assertTrue(result.message, result.message.startsWith(Protocol.SCRIPT_FAILED_PREFIX))
        }
        assertEquals(
            "The sheet's script failed: We're sorry, a server error occurred. Please wait a bit and try again.",
            page(appsScriptErrorPage("We&#39;re sorry, a server error occurred. Please wait a bit and try again.")).message,
        )
        // Also when Google sends the error page with a 5xx status.
        val serverError = page(appsScriptErrorPage("Exception: Service Spreadsheets failed while accessing document"), code = 500)
        assertEquals(
            Protocol.offline("The sheet's script failed: Exception: Service Spreadsheets failed while accessing document", FailureSource.GLITCH),
            serverError,
        )
        // A 5xx page that isn't from Apps Script is the usual Google hiccup.
        assertEquals(
            Protocol.offline(Protocol.MSG_GOOGLE_TROUBLE),
            page("<html><head><title>Error 502 (Server Error)!!1</title></head><body><p><b>502.</b> That's an error.</p></body></html>", 502),
        )
    }

    @Test
    fun missingDeploymentAsksToCopyTheUrlAgain() {
        assertEquals(Protocol.actionNeeded(Protocol.MSG_DEPLOYMENT_GONE), page(driveFileNotFoundPage, code = 404))
        assertEquals(Protocol.actionNeeded(Protocol.MSG_DEPLOYMENT_GONE), page(driveFileNotFoundPage, code = 200))
        val doesNotExist = "<html><head><title>Google Drive - Page Not Found</title></head><body>" +
            "<p>Sorry, the file you have requested does not exist.</p></body></html>"
        assertEquals(Protocol.actionNeeded(Protocol.MSG_DEPLOYMENT_GONE), page(doesNotExist))
        assertTrue(Protocol.MSG_DEPLOYMENT_GONE, Protocol.MSG_DEPLOYMENT_GONE.contains("Deploy > Manage deployments"))
        assertTrue(Protocol.MSG_DEPLOYMENT_GONE, Protocol.MSG_DEPLOYMENT_GONE.contains("/exec"))
        // Other 404 pages keep the existing text.
        assertEquals(
            Protocol.actionNeeded(Protocol.MSG_NOT_FOUND),
            page("<html><head><title>Error 404 (Not Found)!!1</title></head><body>The requested URL was not found.</body></html>", 404),
        )
    }

    @Test
    fun otherPagesAreNamedByTheirTitle() {
        val blocked = "<!DOCTYPE html><html><head><title>Web Page  Blocked &amp; Logged</title></head>" +
            "<body><p>Access to this site is blocked by your network administrator.</p></body></html>"
        assertEquals(
            Protocol.actionNeeded(
                "Google returned a web page (\"Web Page Blocked & Logged\") instead of data. " +
                    "In Apps Script use Deploy > Manage deployments and set 'Who has access' to 'Anyone'.",
            ),
            page(blocked),
        )
        // Without a title: the existing text.
        assertEquals(Protocol.actionNeeded(Protocol.MSG_HTML), page("<html><body><p>Something else</p></body></html>"))
    }
}
