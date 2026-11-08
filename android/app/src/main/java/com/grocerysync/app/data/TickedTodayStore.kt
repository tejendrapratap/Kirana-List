package com.grocerysync.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * A needed item this phone took off To buy today ([TickedTodayStore]): "Got it", the Need switch turned off, or
 * - down to 0. To buy lists these under "Ticked today", each with a Put back button.
 */
data class TickedEntry(
    /** The item's key ([ItemKeys.normalize]). */
    val key: String,
    /** The item's name when it was ticked (the list shows its name as it is now, see [shownTickedToday]). */
    val name: String,
    /** The value it had before it was ticked: what Put back gives it again. */
    val before: ItemValue,
    /** The value this phone set (not needed): Put back goes ahead only while the item still has it. */
    val set: ItemValue,
    /** When it was ticked, on this phone's clock (ms). It also tells this tick from a later one of the item. */
    val at: Long,
    /**
     * This phone's name when it ticked ([SettingsStore.effectiveDeviceName]), which the sheet keeps as the tick's
     * Updated By. "" when unknown (an entry kept by an earlier build of 1.6).
     */
    val by: String = "",
) {
    /**
     * The item is still as this phone left it: [item] (the item under [key] now; null when it isn't on the list)
     * still has [set], and its last change is this phone's ([GroceryItem.isChangedLastByOthers]) under its name now
     * ([me]) or the one it ticked with ([by]): someone who types Your name in Settings after ticking (as the line
     * under the notifications switch asks) keeps the ticks already sent to the sheet under the old name. Not when it
     * is needed again, was renamed (its key is gone) or removed, or the other phone changed it otherwise. Nor when
     * the other phone needed it again and then ticked it off itself: the item has [set] again, but that tick isn't
     * this phone's, and Put back would bring back this phone's old amount for something just bought. The entry isn't
     * shown then (and is pruned).
     *
     * Two phones with the same name (both left at the phone's model name, say) can't be told apart: then only the
     * value counts.
     */
    fun isStillTicked(item: GroceryItem?, me: String): Boolean =
        item != null &&
            item.value.sanitized() == set &&
            (!item.isChangedLastByOthers(me) || by.isNotBlank() && !item.isChangedLastByOthers(by))
}

/**
 * The entries of [entries] (today's, see [TickedTodayStore.today]) whose item is still ticked by this phone ([me],
 * see [TickedEntry.isStillTicked]) on the list [items], with the item's name as it is now (the other phone may have
 * changed its capitals). In the order of [entries].
 */
fun shownTickedToday(entries: List<TickedEntry>, items: List<GroceryItem>, me: String): List<TickedEntry> {
    if (entries.isEmpty()) return emptyList()
    val byKey = items.associateBy { it.key }
    return entries.mapNotNull { entry ->
        val item = byKey[entry.key]
        if (entry.isStillTicked(item, me)) entry.copy(name = item!!.name) else null
    }
}

/**
 * "Ticked today": the needed items THIS phone took off To buy today, so a tick made by mistake (or a shop that
 * didn't have it after all) can be put back later, when the Undo snackbar has long gone. This phone only, never
 * synced; kept in private SharedPreferences of its own (no database change).
 *
 * Rules:
 * - One entry per item: an item ticked again later the same day keeps one entry, from its newest tick ([record]).
 * - Entries from before local midnight (of this phone's time zone) are dropped when read ([today]); so is one
 *   dated in a later day (the phone's clock was set back).
 * - The Undo of a tick removes its entry ([remove]); so does Put back.
 * - Entries whose item is needed again, renamed or gone, or changed last on the other phone, are not shown
 *   ([shownTickedToday]) and are pruned ([retain]) by the list screen's next change of the list.
 * - The Undo of a change that took an entry away (+ or the Need switch on a ticked item, an edit, a delete, Put
 *   back) puts it back as it was ([restore]).
 *
 * Each entry is stored as JSON under [ENTRY_PREFIX] + its item key; one that can't be read is dropped.
 *
 * @param prefs where the entries are kept; null keeps them in memory only (until the process ends).
 * @param clock this phone's clock (ms): when an entry is made, and which day is today.
 * @param zone this phone's time zone: where its midnight is.
 */
class TickedTodayStore internal constructor(
    private val prefs: SharedPreferences?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {

    constructor(context: Context) :
        this(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    /** Every stored entry by item key, as last written. Guarded by this. */
    private val entries = LinkedHashMap<String, TickedEntry>()

    private val version = MutableStateFlow(0L)

    /**
     * Changes after every [record], [restore], [remove] and [retain] that changed something: read [today] again
     * then.
     */
    val changes: StateFlow<Long> = version.asStateFlow()

    init {
        load()
    }

    /** Today's entries, newest first. Older ones (and any dated after today) are dropped, here and in storage. */
    @Synchronized
    fun today(): List<TickedEntry> {
        dropOtherDays()
        // The map has the newest tick last; reversed first, so ticks of the same millisecond are newest first too.
        return entries.values.reversed().sortedByDescending { it.at }
    }

    /** Today's entry of the item [key]; null when it has none. */
    @Synchronized
    fun entryOf(key: String): TickedEntry? {
        dropOtherDays()
        return entries[key]
    }

    /**
     * [entry] is still its item's entry: not put back, undone or pruned since, and the item wasn't ticked again
     * (compared by key and time, as the list shows the entry with the item's name as it is now).
     */
    @Synchronized
    fun holds(entry: TickedEntry): Boolean {
        dropOtherDays()
        return entries[entry.key]?.at == entry.at
    }

    /**
     * This phone, named [by] (see [TickedEntry.by]), took the needed item [key] ([name]) off To buy: it had [before]
     * and now has [set]. Replaces the item's entry from an earlier tick today.
     */
    @Synchronized
    fun record(key: String, name: String, before: ItemValue, set: ItemValue, by: String = "") {
        dropOtherDays()
        val entry = TickedEntry(key, name, before.sanitized(), set.sanitized(), clock(), by)
        // Re-inserted, so the map keeps the newest tick last.
        entries.remove(key)
        entries[key] = entry
        prefs?.edit { putString(ENTRY_PREFIX + key, encode(entry)) }
        version.value++
    }

    /** The tick of the item [key] was undone or put back: its entry goes. */
    @Synchronized
    fun remove(key: String) {
        if (entries.remove(key) == null) return
        prefs?.edit { remove(ENTRY_PREFIX + key) }
        version.value++
    }

    /**
     * Puts [entry] back, as it was (its time too, so it keeps its place): a change took it away and that change was
     * undone. Not when it is from another day by now, or when the item has an entry again (a newer tick).
     */
    @Synchronized
    fun restore(entry: TickedEntry) {
        dropOtherDays()
        if (entry.key in entries || entry.at !in todayRange()) return
        val sorted = (entries.values + entry).sortedBy { it.at }
        entries.clear()
        sorted.forEach { entries[it.key] = it }
        prefs?.edit { putString(ENTRY_PREFIX + entry.key, encode(entry)) }
        version.value++
    }

    /** Keeps only the entries [keep] says yes to (e.g. [TickedEntry.isStillTicked] on the list as it is now). */
    @Synchronized
    fun retain(keep: (TickedEntry) -> Boolean) {
        val gone = entries.values.filterNot(keep).map { it.key }
        if (gone.isEmpty()) return
        drop(gone)
        version.value++
    }

    /** True when nothing is stored (of any day): nothing to show or prune. */
    @get:Synchronized
    val isEmpty: Boolean
        get() = entries.isEmpty()

    private fun dropOtherDays() {
        if (entries.isEmpty()) return
        val today = todayRange()
        val old = entries.values.filter { it.at !in today }.map { it.key }
        if (old.isNotEmpty()) drop(old)
    }

    /** Today on this phone, from local midnight to the next (ms). */
    private fun todayRange(): LongRange {
        val today = Instant.ofEpochMilli(clock()).atZone(zone()).toLocalDate()
        val start = today.atStartOfDay(zone()).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli()
        return start until end
    }

    private fun drop(keys: List<String>) {
        keys.forEach(entries::remove)
        prefs?.edit { keys.forEach { remove(ENTRY_PREFIX + it) } }
    }

    private fun load() {
        val stored = prefs?.all ?: return
        val unreadable = ArrayList<String>()
        for ((name, value) in stored) {
            if (!name.startsWith(ENTRY_PREFIX)) continue
            val key = name.removePrefix(ENTRY_PREFIX)
            val entry = (value as? String)?.let { decode(key, it) }
            if (entry == null) unreadable += name else entries[key] = entry
        }
        if (unreadable.isNotEmpty()) prefs.edit { unreadable.forEach { remove(it) } }
        // Oldest first, as record() keeps them.
        val sorted = entries.values.sortedBy { it.at }
        entries.clear()
        sorted.forEach { entries[it.key] = it }
    }

    private companion object {
        const val TAG = "TickedTodayStore"
        const val PREFS_NAME = "grocery_sync_ticked_today"
        const val ENTRY_PREFIX = "entry:"

        fun encode(entry: TickedEntry): String = JSONObject()
            .put("name", entry.name)
            .put("before", encodeValue(entry.before))
            .put("set", encodeValue(entry.set))
            .put("at", entry.at)
            .put("by", entry.by)
            .toString()

        fun decode(key: String, text: String): TickedEntry? = try {
            val json = JSONObject(text)
            val name = json.getString("name")
            val before = decodeValue(json.getJSONObject("before"))
            val set = decodeValue(json.getJSONObject("set"))
            if (key.isEmpty() || name.isEmpty() || before == null || set == null) {
                null
            } else {
                TickedEntry(key, name, before, set, json.getLong("at"), json.optString("by"))
            }
        } catch (e: JSONException) {
            Log.w(TAG, "Dropping an unreadable entry", e)
            null
        }

        /** The value as the database stores it: its type, its value column and its unit ([ListLogic.encodeValue]). */
        fun encodeValue(value: ItemValue): JSONObject = JSONObject()
            .put("type", value.type.wire)
            .put("value", ListLogic.encodeValue(value))
            .put("unit", ListLogic.encodeUnit(value).orEmpty())

        fun decodeValue(json: JSONObject): ItemValue? {
            val type = ValueType.fromWire(json.optString("type")) ?: return null
            return ListLogic.decodeValue(type, json.optString("value"), json.optString("unit"))
        }
    }
}
