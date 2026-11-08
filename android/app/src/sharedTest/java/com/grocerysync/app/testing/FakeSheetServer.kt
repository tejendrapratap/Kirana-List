package com.grocerysync.app.testing

import com.grocerysync.app.data.ItemKeys
import com.grocerysync.app.data.Units
import okhttp3.HttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.Closeable
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.InetAddress
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.floor
import kotlin.math.min

/**
 * A small in-memory stand-in for the Apps Script web app (apps-script/Code.gs), served by MockWebServer on
 * 127.0.0.1. The UI tests run it inside the test APK (debug builds allow cleartext to 127.0.0.1); the JVM tests
 * check it against the app's real SheetsClient and SyncEngine (FakeSheetServerTest).
 *
 * It speaks the protocol the app uses (see the header of Code.gs):
 * - GET  ?action=ping&token=T[&v=3]
 * - GET  ?action=list&token=T[&since=REV][&v=3]: the whole list, or "unchanged" when REV is still current
 * - POST {"token","action":"apply","device","protocol":3,"changes":[...]}: one result per change ("applied",
 *   "stale", "duplicate" or "error"), then the whole list
 *
 * with the rules of Code.gs the app relies on: items keyed by their name (trimmed, spaces collapsed, lower case);
 * the newest change wins by its time, capped at the server's clock ("stale" otherwise); a delete is remembered
 * (tombstone), so an older upsert can't bring the item back; change ids are de-duplicated; Added At is written once,
 * when a row is created (the change's "addedAt" hint when 0 < hint <= its time, else its time); a quantity's
 * missing unit keeps the row's unit; units are cleaned like the sheet does; "version":3 and units for clients that
 * say v=3 / "protocol":3, whole numbers without units for older ones; the revision is a hash of the list as the
 * client gets it. Errors answer {"ok":false,"error":"unauthorized" | "bad_request: ..." | ...} with HTTP 200, as
 * Apps Script does.
 *
 * By default it answers at the /exec URL itself (the redirect-free path). With [googleRedirects] it answers like
 * Google: a 302 to an echo URL that serves the script's output (a POST is followed with a GET).
 *
 * The other phone: [upsertAs] and [deleteAs] send a change through the same apply rules, as another device
 * ("Home"); [seed] puts a row on the sheet as if it had been added earlier. [mode] simulates outages.
 *
 * Thread-safe: MockWebServer answers on its own threads while a test changes and reads the state.
 */
class FakeSheetServer(
    val token: String = DEFAULT_TOKEN,
    val spreadsheetName: String = DEFAULT_SPREADSHEET,
    private val clock: () -> Long = System::currentTimeMillis,
) : Closeable {

    enum class Mode {
        /** Answers normally. */
        UP,

        /** Nothing answers: every connection is dropped, so the phone sees no response at all (offline). */
        UNREACHABLE,

        /** Google's front end fails every request with HTTP 500 (the app says Google isn't responding). */
        SERVER_ERROR,
    }

    /** What an item needs: yes / no, or an amount of a unit ("" = no unit). */
    sealed interface Need {
        data class YesNo(val needed: Boolean) : Need

        data class Quantity(val amount: BigDecimal, val unit: String) : Need {
            /** True when [amount] is the same number as [text] ("1.5" equals 1.500). */
            fun amountIs(text: String): Boolean = amount.compareTo(BigDecimal(text)) == 0
        }
    }

    /** One row of the Items sheet. [addedAt] 0 = not known (a row from before the Added At column). */
    data class Item(val name: String, val need: Need, val updatedAt: Long, val updatedBy: String, val addedAt: Long) {
        val isNeeded: Boolean
            get() = when (need) {
                is Need.YesNo -> need.needed
                is Need.Quantity -> need.amount.signum() > 0
            }
    }

    /** One change of an apply request as it was sent (by the app, or by [upsertAs] / [deleteAs]), with its result. */
    data class SentChange(
        val device: String,
        val protocol: Int,
        val id: String,
        val op: String,
        val name: String,
        val type: String?,
        /** A Boolean for yes / no, a Number for quantities; null for deletes. */
        val value: Any?,
        /** Null when the change has no "unit" (a quantity that keeps the row's unit) or isn't a quantity. */
        val unit: String?,
        val time: Long?,
        val addedAt: Long?,
        /** What the server answered: applied, stale, duplicate or error. */
        val status: String,
    ) {
        /** True when [value] is the number [text] ("2" equals 2.0). */
        fun amountIs(text: String): Boolean =
            (value as? Number)?.let { BigDecimal(it.toString()).compareTo(BigDecimal(text)) == 0 } == true
    }

    /** Simulated outages; switch at any time. */
    @Volatile
    var mode: Mode = Mode.UP

    /** Answer through a 302 to an echo URL, as Google does, instead of at the /exec URL itself. */
    @Volatile
    var googleRedirects: Boolean = false

    private val server = MockWebServer()
    private val execPath = "/macros/s/TEST-${UUID.randomUUID().toString().take(8)}/exec"
    private val lock = Any()

    // Guarded by lock. Rows in sheet order, by key.
    private val rows = LinkedHashMap<String, Item>()
    private val tombstones = HashMap<String, Long>()
    private val appliedIds = HashSet<String>()

    private val echoes = ConcurrentHashMap<String, String>()
    private val sent = CopyOnWriteArrayList<SentChange>()
    private val pings = AtomicInteger()
    private val lists = AtomicInteger()
    private val applies = AtomicInteger()

    /** The web app URL to give the app (http://127.0.0.1:<port>/macros/s/TEST-.../exec). Valid after [start]. */
    val url: String get() = "http://$HOST:${server.port}$execPath"

    // Requests that reached the script (not those dropped or failed by [mode]), with or without the right token.
    val pingRequests: Int get() = pings.get()
    val listRequests: Int get() = lists.get()
    val applyRequests: Int get() = applies.get()

    fun start(): FakeSheetServer {
        server.dispatcher = object : Dispatcher() {
            // A connection opened while unreachable is closed before the request is read.
            override fun peek(): MockResponse =
                if (mode == Mode.UNREACHABLE) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) else super.peek()

            override fun dispatch(request: RecordedRequest): MockResponse = respond(request)
        }
        server.start(InetAddress.getByName(HOST), 0)
        return this
    }

    override fun close() {
        server.shutdown()
    }

    // ---- The other phone and the sheet ----------------------------------------------------------------

    /**
     * Puts [name] on the sheet as an item [device] added [agoMs] ago and nobody changed since ("Added by"), or,
     * with [addedAtKnown] false, as a row from before Added At ("Updated by").
     */
    fun seed(name: String, need: Need, device: String = HOME, agoMs: Long = 2 * HOUR_MS, addedAtKnown: Boolean = true) {
        synchronized(lock) {
            val time = clock() - agoMs
            val clean = ItemKeys.clean(name)
            rows[ItemKeys.normalize(clean)] = Item(clean, need, time, device, if (addedAtKnown) time else 0L)
        }
    }

    /** [device] adds or changes [name] now, like an apply from that phone. Returns the result ("applied", ...). */
    fun upsertAs(device: String, name: String, need: Need): String {
        val change = JSONObject().put("id", "fake-" + UUID.randomUUID()).put("op", "upsert").put("name", name)
        when (need) {
            is Need.YesNo -> change.put("type", TYPE_BOOLEAN).put("value", need.needed)
            is Need.Quantity -> change.put("type", TYPE_QUANTITY).put("value", jsonNumber(need.amount)).put("unit", need.unit)
        }
        return applyAs(device, change)
    }

    /** [device] deletes [name] now, like an apply from that phone. Returns the result. */
    fun deleteAs(device: String, name: String): String =
        applyAs(device, JSONObject().put("id", "fake-" + UUID.randomUUID()).put("op", "delete").put("name", name))

    private fun applyAs(device: String, change: JSONObject): String = synchronized(lock) {
        val now = clock()
        change.put("time", now)
        val result = settleOrApply(change, HashSet(), cleanDeviceName(device), now)
        record(change, cleanDeviceName(device), PROTOCOL_VERSION, result)
        result.getString("status")
    }

    /** The item called [name] (capitals and spaces don't count), or null. */
    fun item(name: String): Item? = synchronized(lock) { rows[ItemKeys.normalize(name)] }

    /** Every item, in sheet order. */
    fun items(): List<Item> = synchronized(lock) { rows.values.toList() }

    /** Every change sent so far, in order, with its result. */
    fun sentChanges(): List<SentChange> = sent.toList()

    /** The changes the app (any device but [HOME]) sent for [name] (capitals and spaces don't count). */
    fun changesFromApp(name: String): List<SentChange> =
        sent.filter { it.device != HOME && ItemKeys.normalize(it.name) == ItemKeys.normalize(name) }

    // ---- HTTP ---------------------------------------------------------------------------------------------

    private fun respond(request: RecordedRequest): MockResponse {
        when (mode) {
            // Read the request, then close the connection without an answer.
            Mode.UNREACHABLE -> return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            Mode.SERVER_ERROR -> return MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "text/plain; charset=utf-8")
                .setBody("Internal Server Error")
            Mode.UP -> Unit
        }
        // MockWebServer's bookkeeping entry for a dropped connection has no URL.
        val url = request.requestUrl ?: return notFound()
        if (url.encodedPath == ECHO_PATH && request.method == "GET") {
            val output = url.queryParameter("user_content_key")?.let(echoes::remove) ?: return notFound()
            return json(output)
        }
        if (url.encodedPath != execPath) return notFound()

        val answer = try {
            when (request.method) {
                "GET" -> doGet(url)
                "POST" -> doPost(request.body.readUtf8())
                else -> return MockResponse().setResponseCode(405)
            }
        } catch (e: ApiError) {
            JSONObject().put("ok", false).put("error", e.message)
        }
        val output = answer.toString()
        if (!googleRedirects) return json(output)
        val key = UUID.randomUUID().toString()
        echoes[key] = output
        return MockResponse().setResponseCode(302).setHeader("Location", "$ECHO_PATH?user_content_key=$key")
    }

    private fun json(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    private fun notFound() = MockResponse()
        .setResponseCode(404)
        .setHeader("Content-Type", "text/html; charset=utf-8")
        .setBody("<html><head><title>Not Found</title></head><body>Not Found</body></html>")

    // ---- Code.gs: doGet / doPost ----------------------------------------------------------------------------

    private fun doGet(url: HttpUrl): JSONObject {
        val action = url.queryParameter("action")
        when (action) {
            "ping" -> pings.incrementAndGet()
            "list" -> lists.incrementAndGet()
        }
        authorize(url.queryParameter("token"))
        return when (action) {
            "ping" -> JSONObject().put("ok", true).put("app", APP_ID).put("version", PROTOCOL_VERSION).put("spreadsheet", spreadsheetName)
            "list" -> list(url.queryParameter("since"), clientVersion(url.queryParameter("v")))
            else -> throw badRequest("unknown action " + describe(action))
        }
    }

    private fun list(since: String?, version: Int): JSONObject = synchronized(lock) {
        val items = clientItems(version)
        val revision = revisionOf(items)
        val answer = answer(version).put("revision", revision).put("serverTime", clock())
        if (!since.isNullOrEmpty() && since == revision) answer.put("unchanged", true)
        else answer.put("spreadsheet", spreadsheetName).put("items", items)
    }

    private fun doPost(body: String): JSONObject {
        applies.incrementAndGet()
        requireConfigured()
        val request = try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw badRequest("malformed JSON")
        }
        authorize(request.opt("token") as? String)
        if (request.opt("action") != "apply") throw badRequest("unknown action " + describe(request.opt("action")))
        val changes = request.opt("changes") as? JSONArray ?: throw badRequest("changes must be an array")
        if (changes.length() == 0) throw badRequest("changes must not be empty")
        if (changes.length() > MAX_CHANGES) throw badRequest("too many changes (max $MAX_CHANGES)")
        val device = cleanDeviceName(request.opt("device"))
        val version = clientVersion(request.opt("protocol"))
        return synchronized(lock) {
            val now = clock()
            val seen = HashSet<String>()
            val results = JSONArray()
            for (i in 0 until changes.length()) {
                val change = changes.opt(i)
                val result = settleOrApply(change, seen, device, now)
                results.put(result)
                if (change is JSONObject) record(change, device, version, result)
            }
            val items = clientItems(version)
            answer(version)
                .put("results", results)
                .put("revision", revisionOf(items))
                .put("serverTime", now)
                .put("spreadsheet", spreadsheetName)
                .put("items", items)
        }
    }

    /** {"ok":true} with "version":3 for a client of protocol 3 (versioned_). */
    private fun answer(version: Int): JSONObject {
        val answer = JSONObject().put("ok", true)
        if (version >= PROTOCOL_VERSION) answer.put("version", PROTOCOL_VERSION)
        return answer
    }

    // ---- Code.gs: applying changes (call with lock held) -------------------------------------------------

    /** The result of one change: malformed or duplicate without touching the sheet, else applied, stale or error. */
    private fun settleOrApply(change: Any?, seen: MutableSet<String>, device: String, now: Long): JSONObject {
        val id = ((change as? JSONObject)?.opt("id") as? String).orEmpty()
        validateChange(change)?.let { return result(id, "error", it) }
        if (id in seen || id in appliedIds) return result(id, "duplicate")
        seen += id
        change as JSONObject
        val name = ItemKeys.clean(change.getString("name"))
        val key = ItemKeys.normalize(name)
        val time = effectiveTime(change.opt("time"), now)
        val (status, error) = if (change.getString("op") == "upsert") {
            applyUpsert(change, name, key, time, device, now)
        } else {
            applyDelete(key, time, now)
        }
        if (status == "applied") appliedIds += id
        return result(id, status, error)
    }

    private fun applyUpsert(change: JSONObject, name: String, key: String, time: Long, device: String, now: Long): Pair<String, String?> {
        val record = rows[key]
        if (record == null) validateNewName(name)?.let { return "error" to it }
        val tombstone = tombstones[key]
        if (tombstone != null && min(tombstone, now) > time) return "stale" to null
        if (record != null && time < min(record.updatedAt, now)) return "stale" to null
        val need = upsertValue(change, record)
        // Added At is written once, when the row is created; a row that exists keeps its own.
        val addedAt = record?.addedAt ?: createdAt(change.opt("addedAt"), time)
        // An existing row keeps its place (LinkedHashMap keeps the position of a key put again).
        rows[key] = Item(name, need, time, device, addedAt)
        tombstones.remove(key)
        return "applied" to null
    }

    private fun applyDelete(key: String, time: Long, now: Long): Pair<String, String?> {
        val record = rows[key]
        if (record != null && time < min(record.updatedAt, now)) return "stale" to null
        rows.remove(key)
        val existing = tombstones[key]
        if (time >= now - TOMBSTONE_TTL_MS && (existing == null || existing < time)) tombstones[key] = time
        return "applied" to null
    }

    /**
     * What a (valid) upsert stores (upsertValue_): a quantity's "unit" absent or null keeps the unit the row has
     * (none for a new row or one that was yes / no); the protocol 2 type "integer" keeps it too.
     */
    private fun upsertValue(change: JSONObject, current: Item?): Need {
        val value = change.get("value")
        if (change.getString("type") == TYPE_BOOLEAN) return Need.YesNo(value as Boolean)
        val keep = (current?.need as? Need.Quantity)?.unit.orEmpty()
        if (change.getString("type") == TYPE_INTEGER) return Need.Quantity(BigDecimal(value.toString()), keep)
        val unit = if (change.isNull("unit")) keep else Units.normalize(change.getString("unit"))
        return Need.Quantity(roundAmount(value as Number), unit)
    }

    private fun validateChange(change: Any?): String? {
        if (change !is JSONObject) return "change must be an object"
        val id = change.opt("id")
        if (id !is String || id.isEmpty() || id.length > MAX_CHANGE_ID_LENGTH) return "invalid id"
        val op = change.opt("op")
        if (op != "upsert" && op != "delete") return "invalid op"
        val name = change.opt("name")
        if (name !is String || ItemKeys.clean(name).isEmpty()) return "name is required"
        if (name.length > MAX_CELL_TEXT_LENGTH) return "name is too long"
        if (op == "delete") return null
        val value = change.opt("value")
        return when (change.opt("type")) {
            TYPE_BOOLEAN -> if (value is Boolean) null else "value must be true or false for type boolean"
            TYPE_QUANTITY -> if (!isAmount(value)) {
                "value must be a number from 0 to $MAX_QUANTITY for type quantity"
            } else {
                unitError(change)
            }
            TYPE_INTEGER -> {
                val whole = (value as? Number)?.let { BigDecimal(it.toString()) }
                if (whole != null && whole.signum() >= 0 && whole <= BigDecimal(MAX_QUANTITY) && whole.stripTrailingZeros().scale() <= 0) {
                    null
                } else {
                    "value must be a whole number from 0 to $MAX_QUANTITY for type integer"
                }
            }
            else -> "invalid type"
        }
    }

    private fun isAmount(value: Any?): Boolean {
        val n = (value as? Number)?.toDouble() ?: return false
        return n.isFinite() && n >= 0 && n <= MAX_QUANTITY
    }

    private fun unitError(change: JSONObject): String? {
        if (change.isNull("unit")) return null
        val unit = change.opt("unit") as? String ?: return "unit must be text"
        return if (ItemKeys.collapseWhitespace(unit).length > Units.MAX_LENGTH) "unit is longer than ${Units.MAX_LENGTH} characters" else null
    }

    private fun validateNewName(name: String): String? = when {
        name.length > MAX_NAME_LENGTH -> "name is longer than $MAX_NAME_LENGTH characters"
        name.any { it == '\r' || it == '\n' || it == ' ' || it == ' ' } -> "name contains a line break"
        else -> null
    }

    private fun record(change: JSONObject, device: String, protocol: Int, result: JSONObject) {
        sent += SentChange(
            device = device,
            protocol = protocol,
            id = change.opt("id") as? String ?: "",
            op = change.opt("op") as? String ?: "",
            name = change.opt("name") as? String ?: "",
            type = change.opt("type") as? String,
            value = change.opt("value").takeUnless { it == JSONObject.NULL },
            unit = if (change.isNull("unit")) null else change.opt("unit") as? String,
            time = (change.opt("time") as? Number)?.toLong(),
            addedAt = (change.opt("addedAt") as? Number)?.toLong(),
            status = result.getString("status"),
        )
    }

    private fun result(id: String, status: String, error: String? = null): JSONObject {
        val result = JSONObject().put("id", id).put("status", status)
        if (error != null) result.put("error", error)
        return result
    }

    // ---- Code.gs: what a client gets ---------------------------------------------------------------------

    /** The items as a client of [version] gets them (clientItems_). Call with lock held. */
    private fun clientItems(version: Int): JSONArray {
        val items = JSONArray()
        for (row in rows.values) {
            val item = JSONObject().put("name", row.name)
            when (val need = row.need) {
                is Need.YesNo -> item.put("type", TYPE_BOOLEAN).put("value", need.needed)
                is Need.Quantity -> if (version >= PROTOCOL_VERSION) {
                    item.put("type", TYPE_QUANTITY).put("value", jsonNumber(need.amount)).put("unit", need.unit)
                } else {
                    item.put("type", TYPE_INTEGER).put("value", need.amount.setScale(0, RoundingMode.CEILING).longValueExact())
                }
            }
            item.put("updatedAt", row.updatedAt).put("updatedBy", row.updatedBy)
            if (version >= PROTOCOL_VERSION && row.addedAt > 0L) item.put("addedAt", row.addedAt)
            items.put(item)
        }
        return items
    }

    private fun authorize(token: String?) {
        requireConfigured()
        if (token == null || token.trim() != this.token.trim()) throw ApiError("unauthorized")
    }

    private fun requireConfigured() {
        if (token.isBlank() || token.trim() == UNSET_TOKEN) throw ApiError("server_not_configured")
    }

    private class ApiError(message: String) : Exception(message)

    companion object {
        const val DEFAULT_TOKEN = "test-token-0123456789"
        const val DEFAULT_SPREADSHEET = "Test Groceries"

        /** The other phone. */
        const val HOME = "Home"

        const val HOUR_MS = 60 * 60 * 1_000L

        private const val HOST = "127.0.0.1"
        private const val ECHO_PATH = "/macros/echo"
        private const val APP_ID = "grocery-sync"
        private const val PROTOCOL_VERSION = 3
        private const val LEGACY_PROTOCOL_VERSION = 2
        private const val UNSET_TOKEN = "CHANGE_ME"
        private const val TYPE_BOOLEAN = "boolean"
        private const val TYPE_QUANTITY = "quantity"
        private const val TYPE_INTEGER = "integer"
        private const val MAX_CHANGES = 100
        private const val MAX_CHANGE_ID_LENGTH = 64
        private const val MAX_NAME_LENGTH = 100
        private const val MAX_CELL_TEXT_LENGTH = 50_000
        private const val MAX_QUANTITY = 999_999L
        private const val MAX_DEVICE_LENGTH = 40
        private const val TOMBSTONE_TTL_MS = 30 * 24 * HOUR_MS

        fun yesNo(needed: Boolean): Need = Need.YesNo(needed)

        fun quantity(amount: String, unit: String): Need = Need.Quantity(BigDecimal(amount), unit)

        private fun badRequest(detail: String) = ApiError("bad_request: $detail")

        private fun describe(value: Any?): String =
            if (value == null || value == JSONObject.NULL || value == "") "(none)" else JSONObject.quote(value.toString().take(40))

        /** 3 when the client says so (v=3, "protocol":3), else 2 (an app from before units). */
        private fun clientVersion(value: Any?): Int {
            val n = when (value) {
                is Number -> value.toDouble()
                is String -> value.trim().toDoubleOrNull()?.takeIf { value.trim().all(Char::isDigit) } ?: 0.0
                else -> 0.0
            }
            return if (n >= PROTOCOL_VERSION) PROTOCOL_VERSION else LEGACY_PROTOCOL_VERSION
        }

        private fun cleanDeviceName(device: Any?): String {
            val name = (device as? String)?.let(ItemKeys::collapseWhitespace).orEmpty()
            return name.take(MAX_DEVICE_LENGTH).trim().ifEmpty { "Phone" }
        }

        /** The change's time, at most the server's clock; the server's clock when it has none. */
        private fun effectiveTime(time: Any?, now: Long): Long {
            val t = (time as? Number)?.toDouble() ?: return now
            if (!t.isFinite() || t < 0) return now
            return min(floor(t).toLong(), now)
        }

        /** Added At of a created row: the hint when 0 < hint <= time, else time (createdAt_). */
        private fun createdAt(hint: Any?, time: Long): Long {
            val h = (hint as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: return time
            val ms = floor(h).toLong()
            return if (ms in 1..time) ms else time
        }

        /** 0..999999, rounded half up to 3 decimals (roundAmount_). */
        private fun roundAmount(value: Number): BigDecimal {
            val n = BigDecimal(value.toString())
            if (n.signum() <= 0) return BigDecimal.ZERO
            if (n >= BigDecimal(MAX_QUANTITY)) return BigDecimal(MAX_QUANTITY)
            return plain(n.setScale(3, RoundingMode.HALF_UP))
        }

        private fun plain(n: BigDecimal): BigDecimal {
            val stripped = n.stripTrailingZeros()
            return if (stripped.scale() < 0) stripped.setScale(0) else stripped
        }

        /** 2 (not 2.0) for whole amounts, 1.5 for others. */
        private fun jsonNumber(amount: BigDecimal): Number {
            val n = plain(amount)
            return if (n.scale() == 0) n.longValueExact() else n
        }

        /** Lowercase hex MD5 of the items' JSON (revisionOf_). */
        private fun revisionOf(items: JSONArray): String =
            MessageDigest.getInstance("MD5").digest(items.toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
