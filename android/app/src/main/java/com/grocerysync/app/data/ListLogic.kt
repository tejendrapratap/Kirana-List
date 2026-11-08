package com.grocerysync.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import kotlin.math.roundToLong

enum class OpType(val wire: String) {
    UPSERT("upsert"),
    DELETE("delete");

    companion object {
        fun fromWire(s: String?): OpType? = entries.firstOrNull { it.wire == s }
    }
}

enum class OpStatus { PENDING, FAILED }

/**
 * A local change waiting in the outbox. [value] is set for [OpType.UPSERT] only (a quantity whose unit is
 * null keeps the item's unit, see [ItemValue.Quantity]).
 * [seq] orders ops by enqueue time; [id] is the change id sent to the server (used for de-duplication).
 *
 * [addedAt] (upserts only; 0 = none) is when the item was added, as far as this phone knows. It is sent as
 * "addedAt": when the upsert creates the row on the sheet (a rename, an Undo of a delete, or the row was
 * deleted elsewhere meanwhile), the sheet keeps that Added At instead of stamping the change's time, so the
 * item reads "Updated by" and not "Added by". The sheet ignores it for rows it has. See [ListLogic.addedAtHint].
 */
data class OutboxOp(
    val seq: Long,
    val id: String,
    val op: OpType,
    val key: String,
    val name: String,
    val value: ItemValue?,
    val time: Long,
    val status: OpStatus,
    val error: String? = null,
    val addedAt: Long = 0L,
)

/** The op to send for one key, plus the ids of older pending ops of the same key it makes redundant. */
data class CoalescedOp(val op: OutboxOp, val supersededIds: List<String>)

/** What a local edit expects to find under its item's key (see [ListLogic.editConflict]). */
enum class EditIntent {
    /**
     * A new item: the Add dialog, saving an item that was removed elsewhere meanwhile, the Undo of a delete,
     * the new name of a rename. Another item with that name must not be overwritten (user decision for 1.2).
     */
    CREATE,

    /**
     * Either: the Need switch, - / +, "Got it", an edit of the item itself. The newest change wins. (Their Undo
     * is checked against the value they set instead: [ListLogic.updateConflict].)
     */
    ANY,
}

/** The outcome of a local edit (see [GroceryRepository.add]). Anything but [Saved] queued nothing. */
sealed interface EditResult {
    data object Saved : EditResult

    /** Another item has that name ([existingName], as stored); saving would have overwritten it. */
    data class AlreadyOnList(val existingName: String) : EditResult

    /** The item isn't on the list any more (renamed or removed on the other phone meanwhile). */
    data object NotOnList : EditResult

    /** The item has another value than the one the change was planned on (changed on the other phone meanwhile). */
    data object ValueChanged : EditResult

    /** The name can't be sent ([InputValidation.validateName]). */
    data object InvalidName : EditResult
}

/** Android-free list logic shared by the repository and the sync engine (unit-tested on the JVM). */
object ListLogic {

    const val DEFAULT_FAILURE = "The sheet rejected this change"

    private val displayOrder: Comparator<GroceryItem> =
        compareBy<GroceryItem, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
            .thenBy { it.name }
            .thenBy { it.key }

    /**
     * The optimistic list: the server [mirror] with every PENDING and FAILED op applied in [OutboxOp.seq] order.
     * Upserts replace or create the item (marked [GroceryItem.pendingSync]); deletes remove it. A quantity
     * queued without a unit (from before units existed) shows the unit the item has, as the sheet keeps it.
     * Each upserted item gets the Added At the sheet will have once the change is in ([addedAtAfter]), so a
     * row says "Added by" or "Updated by" the same way before and after it syncs.
     */
    fun overlay(mirror: List<GroceryItem>, ops: List<OutboxOp>, localDevice: String): List<GroceryItem> {
        val onSheet = LinkedHashMap<String, GroceryItem>(mirror.size)
        for (item in mirror) onSheet.putIfAbsent(item.key, item)
        val byKey = LinkedHashMap<String, GroceryItem>(onSheet)
        for (op in ops.sortedBy { it.seq }) {
            when (op.op) {
                OpType.UPSERT -> {
                    val value = op.value?.withUnitOf(byKey[op.key]) ?: continue
                    byKey[op.key] = GroceryItem(
                        key = op.key,
                        name = op.name,
                        value = value,
                        updatedAt = op.time,
                        updatedBy = localDevice,
                        pendingSync = true,
                        addedAt = addedAtAfter(op, onSheet[op.key]),
                    )
                }
                OpType.DELETE -> byKey.remove(op.key)
            }
        }
        return sortForDisplay(byKey.values)
    }

    /**
     * The Added At an item has once the upsert [op] (its newest queued change, the one [coalesce] sends) is on
     * the sheet; [sheetItem] is the item in the last list from the sheet (null = not there):
     * - The sheet has the row: an upsert never changes its Added At, so it stays as it is (0 = unknown).
     *   That includes a queued delete followed by its Undo: only the upsert is sent.
     * - Otherwise the upsert creates the row. The sheet takes the op's hint when 0 < hint <= the change's time
     *   (a rename or an Undo of a delete: "Updated by"), else it stamps the change's own time: an item added
     *   on this phone reads "Added by <me> - just now", also after more changes made before it syncs.
     */
    fun addedAtAfter(op: OutboxOp, sheetItem: GroceryItem?): Long = when {
        sheetItem != null -> sheetItem.addedAt
        op.addedAt in 1L..op.time -> op.addedAt
        else -> op.time
    }

    /**
     * The [OutboxOp.addedAt] to queue with a new upsert of [item] (as [overlay] shows it); [sheetItem] is the
     * item in the last list from the sheet (null = not there). Every upsert of an existing item carries its
     * known Added At, so:
     * - the hint of a rename or an Undo still reaches the sheet when a later edit of the item replaces that
     *   change before it is sent ([coalesce] sends only the newest op of an item);
     * - an edit that re-creates a row deleted on the other phone meanwhile keeps the Added At the row showed.
     *
     * An item on the sheet whose Added At isn't known (every item from before 1.2) sends the time of its last
     * change on the sheet instead: it existed at least since then. A rename or an Undo of such an item then
     * still reads "Updated by", as any change of an existing item should (user rule), rather than "Added by"
     * as if it were new; the sheet's Added At for the re-created row holds that time. (0 < hint <= the
     * change's time, so the script takes it: see the Added At contract.)
     *
     * 0 for an item added on this phone that isn't on the sheet yet: the sheet stamps that one itself, so the
     * item still reads "Added by".
     */
    fun addedAtHint(item: GroceryItem, sheetItem: GroceryItem?): Long = when {
        sheetItem != null -> sheetItem.addedAt.takeIf { it > 0L } ?: sheetItem.updatedAt.coerceAtLeast(0L)
        item.isUnchangedSinceAdded -> 0L
        else -> item.addedAt
    }

    /**
     * Null when an edit with [intent] may go ahead, given [existing] = the item shown under the edit's key right
     * before it is queued (null = none); otherwise why nothing may be queued. The repository checks this in
     * the same database transaction that queues the edit, so an item that arrived from the other phone after
     * the dialog's (or the Undo snackbar's) check is never overwritten: a CREATE finds it.
     */
    fun editConflict(intent: EditIntent, existing: GroceryItem?): EditResult? = when {
        intent == EditIntent.CREATE && existing != null -> EditResult.AlreadyOnList(existing.name)
        else -> null
    }

    /**
     * Like [editConflict], for a change that was planned on the value the user saw or this phone set ([expected]):
     * the paste preview's updates ([GroceryRepository.updateIf]), every Undo of a value ("Got it", - / +, the Need
     * switch, an edit: [GroceryRepository.updateIf], [GroceryRepository.renameIf]), Ticked today's Put back and the
     * Undo of "Add common items" ([GroceryRepository.deleteIf]). Null when the item [existing] (as shown right
     * before the change is queued) still has that value; [EditResult.ValueChanged] when the other phone changed it
     * meanwhile, so it isn't overwritten; [EditResult.NotOnList] when it was renamed or removed, so it isn't
     * re-created under its old name.
     */
    fun updateConflict(expected: ItemValue, existing: GroceryItem?): EditResult? = when {
        existing == null -> EditResult.NotOnList
        existing.value != expected -> EditResult.ValueChanged
        else -> null
    }

    /** A quantity without a unit (keep the sheet's) gets the unit [existing] has; "" when there is none. */
    private fun ItemValue.withUnitOf(existing: GroceryItem?): ItemValue =
        if (this is ItemValue.Quantity && unit == null) {
            copy(unit = (existing?.value as? ItemValue.Quantity)?.unit.orEmpty())
        } else {
            this
        }

    /** Sorted by name ignoring case (ties broken deterministically). */
    fun sortForDisplay(items: Collection<GroceryItem>): List<GroceryItem> = items.sortedWith(displayOrder)

    /**
     * For each key keeps only the newest PENDING op; older PENDING ops of the same key become its
     * [CoalescedOp.supersededIds]. Kept ops stay in their original (seq) order. FAILED ops are ignored.
     *
     * Sending only the newest op is equivalent under last-write-wins: if the newest op is stale on the
     * server, every older op of that key would be stale too.
     */
    fun coalesce(ops: List<OutboxOp>): List<CoalescedOp> {
        val pending = ops.filter { it.status == OpStatus.PENDING }.sortedBy { it.seq }
        val newestByKey = LinkedHashMap<String, OutboxOp>()
        val supersededByKey = HashMap<String, MutableList<String>>()
        for (op in pending) {
            newestByKey.put(op.key, op)?.let { older ->
                supersededByKey.getOrPut(op.key) { mutableListOf() }.add(older.id)
            }
        }
        return newestByKey.values
            .sortedBy { it.seq }
            .map { CoalescedOp(it, supersededByKey[it.key].orEmpty()) }
    }

    /**
     * The name to send for a change of the item [name] refers to; [existing] is that item as shown (null
     * = a new item). New names must pass [InputValidation.validateName]. Names of existing items may come
     * from the sheet, typed by hand with line breaks or over the length limit: they are sent on one line
     * (same key) so the item can still be ticked off, changed or deleted. Null when nothing can be sent.
     */
    fun nameForChange(name: String, existing: GroceryItem?): String? {
        val clean = ItemKeys.clean(name)
        if (existing == null) return clean.takeIf { InputValidation.validateName(it) == null }
        return ItemKeys.singleLine(clean).takeIf { it.isNotEmpty() && it.length <= MAX_EXISTING_NAME_LENGTH }
    }

    /** What one Sheets cell can hold (Code.gs accepts names of existing rows up to this length). */
    const val MAX_EXISTING_NAME_LENGTH = 50_000

    /** Number of items with changes still waiting to be sent. */
    fun pendingItemCount(ops: List<OutboxOp>): Int =
        ops.asSequence().filter { it.status == OpStatus.PENDING }.map { it.key }.distinct().count()

    /**
     * One entry per item with FAILED changes (its newest one, which the older ones failed with), in the
     * order the items' newest changes were made.
     */
    fun failedChanges(ops: List<OutboxOp>): List<FailedChange> =
        ops.filter { it.status == OpStatus.FAILED }
            .sortedBy { it.seq }
            .associateBy { it.key }
            .values
            .sortedBy { it.seq }
            .map { FailedChange(it.id, it.name, it.error?.takeIf(String::isNotBlank) ?: DEFAULT_FAILURE) }

    // ---- Local storage encoding --------------------------------------------------------------

    /** The value column: "true" / "false", or the exact amount ("2", "1.5"); the unit has a column of its own. */
    fun encodeValue(value: ItemValue): String = when (value) {
        is ItemValue.BoolValue -> value.value.toString()
        is ItemValue.Quantity -> value.amount.format()
    }

    /** The unit column: null for yes/no items and for quantities that keep the sheet's unit. */
    fun encodeUnit(value: ItemValue?): String? = (value as? ItemValue.Quantity)?.unit

    /**
     * Reads what [encodeValue] / [encodeUnit] stored, also rows from before units existed (type "integer",
     * whole numbers). [unit] null: a quantity that keeps the sheet's unit. Null for an unreadable value.
     */
    fun decodeValue(type: ValueType, text: String?, unit: String? = ""): ItemValue? = when (type) {
        ValueType.BOOLEAN -> when (text) {
            "true" -> ItemValue.BoolValue(true)
            "false" -> ItemValue.BoolValue(false)
            else -> null
        }
        ValueType.QUANTITY -> text?.let(Amount::parse)?.let { ItemValue.Quantity(it, unit?.let(Units::normalize)) }
    }

    // ---- Wire format -------------------------------------------------------------------------

    /**
     * Parses one ItemJson: `{"name","type","value","unit","updatedAt","updatedBy","addedAt"}`. Type "quantity"
     * carries an exact amount (rounded half-up to 3 decimals) and a unit; protocol 2's "integer" is a
     * whole-number quantity without a unit. "addedAt" (ms) is there only when the sheet's Added At holds a
     * date; without it [GroceryItem.addedAt] is 0 (unknown). Tolerant of loosely typed values; returns null
     * for entries without a usable name or value.
     */
    fun parseItem(json: JSONObject): GroceryItem? {
        val name = (json.opt("name") as? String)?.let(ItemKeys::clean)
        if (name.isNullOrEmpty()) return null
        val raw = json.opt("value")
        val wireType = json.opt("type") as? String
        val type = ValueType.fromWire(wireType)
            ?: when (raw) {
                is Boolean -> ValueType.BOOLEAN
                is Number -> ValueType.QUANTITY
                else -> return null
            }
        val wholeNumbersOnly = wireType?.trim().equals(ValueType.LEGACY_QUANTITY_WIRE, ignoreCase = true)
        val unit = Units.normalize(json.opt("unit") as? String)
        val value = parseValue(type, raw, unit, wholeNumbersOnly) ?: return null
        val updatedBy = json.opt("updatedBy").takeUnless { it == null || it == JSONObject.NULL }?.toString().orEmpty()
        return GroceryItem(
            key = ItemKeys.normalize(name),
            name = name,
            value = value,
            updatedAt = timeOf(json.opt("updatedAt")),
            updatedBy = updatedBy,
            pendingSync = false,
            addedAt = timeOf(json.opt("addedAt")),
        )
    }

    /** Parses an ItemJson array; skips malformed entries and later duplicates of a key (first wins, like the sheet). */
    fun parseItems(array: JSONArray): List<GroceryItem> {
        val seen = HashSet<String>()
        val result = ArrayList<GroceryItem>(array.length())
        for (i in 0 until array.length()) {
            val item = (array.opt(i) as? JSONObject)?.let(::parseItem) ?: continue
            if (seen.add(item.key)) result += item
        }
        return result
    }

    /** Protocol version this app speaks (amounts with decimals, units); sent as "protocol" in apply requests. */
    const val PROTOCOL_VERSION = 3

    /**
     * Body of the `apply` POST (protocol [PROTOCOL_VERSION]). Values are JSON booleans or plain JSON numbers
     * (`2`, never `2.0`; `1.5`, never `1.50000001`); a quantity carries its "unit" unless it keeps the sheet's.
     * An upsert with a known [OutboxOp.addedAt] carries it as "addedAt" (ms); scripts without Added At ignore it.
     */
    fun buildApplyRequest(token: String, device: String, ops: List<OutboxOp>): String {
        val changes = JSONArray()
        for (op in ops) {
            val change = JSONObject()
                .put("id", op.id)
                .put("op", op.op.wire)
                .put("name", op.name)
            if (op.op == OpType.UPSERT) {
                val value = op.value ?: continue
                change.put("type", value.type.wire)
                when (value) {
                    is ItemValue.BoolValue -> change.put("value", value.value)
                    is ItemValue.Quantity -> {
                        change.put("value", value.amount.toJsonNumber())
                        value.unit?.let { change.put("unit", it) }
                    }
                }
            }
            change.put("time", op.time)
            if (op.op == OpType.UPSERT && op.addedAt > 0L) change.put("addedAt", op.addedAt)
            changes.put(change)
        }
        return JSONObject()
            .put("token", token)
            .put("action", "apply")
            .put("protocol", PROTOCOL_VERSION)
            .put("device", device)
            .put("changes", changes)
            .toString()
    }

    private fun parseValue(type: ValueType, raw: Any?, unit: String, wholeNumbersOnly: Boolean): ItemValue? = when (type) {
        ValueType.BOOLEAN -> when (raw) {
            is Boolean -> ItemValue.BoolValue(raw)
            is Number -> ItemValue.BoolValue(toWholeNumber(raw) > 0)
            is String -> ItemValue.BoolValue(raw.trim().lowercase() !in FALSE_WORDS)
            else -> null
        }
        ValueType.QUANTITY -> when (raw) {
            is Number ->
                if (wholeNumbersOnly) ItemValue.Quantity(Amount.whole(clampQuantity(toWholeNumber(raw))), unit)
                else ItemValue.Quantity(Amount.fromNumber(raw), unit)
            is Boolean -> ItemValue.Quantity(if (raw) Amount.ONE else Amount.ZERO, unit)
            is String -> parseQuantityText(raw)
                ?.let { (amount, typedUnit) -> ItemValue.Quantity(amount, unit.ifEmpty { typedUnit }) }
                ?: ItemValue.Quantity(Amount.ZERO, unit)
            else -> null
        }
    }

    /**
     * Reads quantity text the way Code.gs reads a Need cell typed by hand, conservatively: a number with
     * '.' or ',' as decimal separator ("1,5" is 1.5), rounded half-up to 3 decimals and clamped, optionally
     * followed by a unit word ("2 kg", "500g"). Returns the amount and the cleaned unit ("" without one);
     * null for anything else ("2-3", "about 2", "1.5.2").
     */
    fun parseQuantityText(text: String): Pair<Amount, String>? {
        val match = QUANTITY_TEXT.matchEntire(ItemKeys.clean(text)) ?: return null
        val number = match.groupValues[1].replace(',', '.').let { if (it.startsWith(".")) "0$it" else it }
        return Amount.of(BigDecimal(number)) to Units.normalize(match.groupValues[2])
    }

    /** A number, then optionally a unit of letters (and dots, "pkt."), possibly several words ("fl oz"). */
    private val QUANTITY_TEXT =
        Regex("""([0-9]+(?:[.,][0-9]+)?|[.,][0-9]+)\s*(\p{L}[\p{L}.]*(?:\s+\p{L}[\p{L}.]*)*)?""")

    /** A time in ms from the wire: a number (a fraction is rounded); 0 for anything else and for negative ones. */
    private fun timeOf(raw: Any?): Long = (raw as? Number)?.let(::toWholeNumber)?.coerceAtLeast(0L) ?: 0L

    private fun toWholeNumber(n: Number): Long = when (n) {
        is Int, is Long, is Short, is Byte -> n.toLong()
        else -> n.toDouble().takeIf { it.isFinite() }?.roundToLong() ?: 0L
    }

    private fun clampQuantity(v: Long): Long = v.coerceIn(0L, InputValidation.MAX_QUANTITY)

    /** Same text rules as Code.gs: these words mean "not needed", any other text means "needed". */
    private val FALSE_WORDS = setOf("false", "no", "n", "0", "")
}
