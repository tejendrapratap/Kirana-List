package com.grocerysync.app.ui

import com.grocerysync.app.data.CatalogEntry
import com.grocerysync.app.data.GroceryItem
import com.grocerysync.app.data.InputValidation
import com.grocerysync.app.data.ItemCatalog
import com.grocerysync.app.data.ItemKeys
import com.grocerysync.app.data.ListLogic
import com.grocerysync.app.data.SearchText
import com.grocerysync.app.text.Texts
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Search on the list screen: which items of the page shown (To buy or All items) match what was typed. It
 * filters the list already on the phone (so it works offline) and looks at item names only.
 *
 * Names and the query are compared after [fold]: capitals, extra spaces, accents and apostrophes don't
 * count. Every word of the query has to appear somewhere in the name, in any order ("rice basmati" finds
 * "Basmati rice"), or match one of the item's other names in the built-in dictionary ([ItemCatalog]) at the
 * start of a word: "curd" finds Dahi, "pyaaz" and "प्याज" find Onion, "raisins" finds Kishmish, but "dal"
 * never finds an item called Cinnamon (Dalchini). Only an item whose whole name is in the dictionary has
 * other names ("Basmati rice" has none). There is no ranking or typo tolerance.
 */
internal object ItemSearch {

    /** Longest query kept: the longest item name, so the suggested name is always a valid one. */
    const val MAX_QUERY_LENGTH = InputValidation.MAX_NAME_LENGTH

    /** All items: nothing on the list matches (in the language shown, like every text here). */
    val NO_MATCH_TEXT: String get() = Texts.current.list.noSearchMatch

    /** To buy: none of the needed items match. Like [NO_MATCH_TEXT] it doesn't repeat the query. */
    val NO_TO_BUY_MATCH_TEXT: String get() = Texts.current.list.noToBuySearchMatch

    /**
     * [text] as search compares it ([SearchText.fold], shared with the item dictionary): the item key
     * (trimmed, whitespace runs as one space, lower case ignoring the device locale) without accents, the
     * Devanagari nukta or apostrophes, and chandrabindu written as anusvara. "Crème fraîche" gives
     * "creme fraiche", "Lay's" gives "lays".
     */
    fun fold(text: String): String = SearchText.fold(text)

    /** The words of [query] after [fold]; empty when there is nothing to search for. */
    fun words(query: String): List<String> = fold(query).split(' ').filter { it.isNotEmpty() }

    /** False for an empty query and for one of only spaces, apostrophes or accents: the list shows everything. */
    fun isActive(query: String): Boolean = words(query).isNotEmpty()

    /**
     * True when every word of [query] is part of [name] (plain text, never a pattern) or matches one of its
     * other names ([Query]); true for an inactive query.
     */
    fun matches(name: String, query: String): Boolean = Query(words(query)).matches(name)

    /**
     * The items to show for [query]: [items] itself (same instance and order) while the search is
     * inactive, else the matching items from A to Z. Results are not "needed first", so ticking Need or
     * stepping an amount never moves a result row away from under the finger.
     */
    fun filter(items: List<GroceryItem>, query: String): List<GroceryItem> {
        val words = words(query)
        if (words.isEmpty()) return items
        val search = Query(words)
        return ListLogic.sortForDisplay(items.filter { search.matches(it.name) })
    }

    /** How many of [items] match [query]: all of them while the search is inactive. */
    fun count(items: List<GroceryItem>, query: String): Int {
        val words = words(query)
        if (words.isEmpty()) return items.size
        val search = Query(words)
        return items.count { search.matches(it.name) }
    }

    /** Most names [entryCache] keeps; it starts over when full. A family's list is a few hundred names. */
    private const val ENTRY_CACHE_MAX = 2_000

    /** In [entryCache] for a name with no dictionary entry. */
    private val NO_ENTRY = Any()

    /**
     * The dictionary entry (or [NO_ENTRY]) of every item name searched so far. [ItemCatalog.entryFor] folds the
     * name on every call: looking each item up again for every letter typed would make search about three
     * times slower than it was before other names.
     */
    private val entryCache = ConcurrentHashMap<String, Any>()

    /** [ItemCatalog.entryFor] of [name], looked up once per name. */
    private fun entryOf(name: String): CatalogEntry? {
        entryCache[name]?.let { return it as? CatalogEntry }
        if (entryCache.size >= ENTRY_CACHE_MAX) entryCache.clear()
        val entry = ItemCatalog.entryFor(name)
        entryCache[name] = entry ?: NO_ENTRY
        return entry
    }

    /**
     * The [words] of a query, ready to test many names. A word matches a name that contains it, or the name's
     * dictionary entry ([ItemCatalog.entryFor]) when one of the entry's names has a word starting with it
     * ([CatalogEntry.matchesWord]: "pya" and "प्याज" find the onion entry; "dal" finds the dals, not Dalchini). The
     * entries a word finds are worked out once per query, so testing a name costs at most one dictionary lookup.
     */
    private class Query(private val words: List<String>) {
        /** For each word, the entries it finds, once needed; most words find none. */
        private val entriesByWord = arrayOfNulls<Set<CatalogEntry>>(words.size)

        fun matches(name: String): Boolean {
            if (words.isEmpty()) return true
            val folded = fold(name)
            var entry: CatalogEntry? = null
            var entryLookedUp = false
            for (i in words.indices) {
                if (folded.contains(words[i])) continue
                val entries = entriesOf(i)
                if (entries.isEmpty()) return false
                if (!entryLookedUp) {
                    entry = entryOf(name)
                    entryLookedUp = true
                }
                if (entry == null || entry !in entries) return false
            }
            return true
        }

        private fun entriesOf(i: Int): Set<CatalogEntry> =
            entriesByWord[i] ?: ItemCatalog.entries.filterTo(HashSet()) { it.matchesWord(words[i]) }
                .also { entriesByWord[i] = it }
    }

    /**
     * The name the Add dialog starts with when nothing matches [query]: spaces tidied up and a capital
     * first letter in the phone's language ("  oat   milk " gives "Oat milk", Turkish "irmik" gives
     * "İrmik"). A capital that is longer than its small letter ("ß" gives "Ss") is skipped when it would
     * make the name too long.
     */
    fun suggestedName(query: String, locale: Locale = Locale.getDefault()): String {
        val collapsed = ItemKeys.collapseWhitespace(query)
        val titled = collapsed.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
        return if (titled.length <= InputValidation.MAX_NAME_LENGTH) titled else collapsed
    }

    /**
     * What the search field keeps of [text]: line breaks (from a paste) as spaces, and at most
     * [MAX_QUERY_LENGTH] characters, never cutting an emoji in half. So [suggestedName] of the result is
     * a valid item name whenever the search [isActive].
     */
    fun cleanInput(text: String): String {
        val oneLine = if (text.any(::isLineBreak)) String(CharArray(text.length) { i -> if (isLineBreak(text[i])) ' ' else text[i] }) else text
        if (oneLine.length <= MAX_QUERY_LENGTH) return oneLine
        val end = if (oneLine[MAX_QUERY_LENGTH - 1].isHighSurrogate()) MAX_QUERY_LENGTH - 1 else MAX_QUERY_LENGTH
        return oneLine.substring(0, end)
    }

    private fun isLineBreak(c: Char): Boolean = c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029'

    /** The row offered when nothing matches: Add "Corriander" to the list. */
    fun addText(name: String): String = Texts.current.list.addToList(name)

    /** The row offered on To buy when [count] items that are not needed match: Show 2 in All items. */
    fun showInAllItemsText(count: Int): String = Texts.current.list.showInAllItems(count)
}

/**
 * The snackbar after the Add dialog added [name], or null for none. [page] is the page shown and [query]
 * the search ("" when search is closed). On To buy, an item that is not needed doesn't show up, so the
 * snackbar says where it is; on either page, neither does one the search hides.
 */
internal fun addedSnackbarText(name: String, needed: Boolean, page: ListFilter, query: String): String? = when {
    page == ListFilter.TO_BUY && !needed -> Texts.current.list.addedNotNeeded(name)
    !ItemSearch.matches(name, query) -> Texts.current.list.addedHiddenBySearch(name)
    else -> null
}
