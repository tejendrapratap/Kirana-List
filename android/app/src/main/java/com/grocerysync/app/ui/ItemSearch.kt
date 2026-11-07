package com.grocerysync.app.ui

import com.grocerysync.app.data.GroceryItem
import com.grocerysync.app.data.InputValidation
import com.grocerysync.app.data.ItemKeys
import com.grocerysync.app.data.ListLogic
import java.text.Normalizer
import java.util.Locale

/**
 * Search on the list screen: which items of the page shown (To buy or All items) match what was typed. It
 * filters the list already on the phone (so it works offline) and looks at item names only.
 *
 * Names and the query are compared after [fold]: capitals, extra spaces, accents and apostrophes don't
 * count. Every word of the query has to appear somewhere in the name, in any order ("rice basmati" finds
 * "Basmati rice"). There is no ranking, typo tolerance or plural handling.
 */
internal object ItemSearch {

    /** Longest query kept: the longest item name, so the suggested name is always a valid one. */
    const val MAX_QUERY_LENGTH = InputValidation.MAX_NAME_LENGTH

    /** All items: nothing on the list matches. */
    const val NO_MATCH_TEXT = "No items match your search"

    /**
     * To buy: none of the needed items match. Like [NO_MATCH_TEXT] it doesn't repeat the query (which is
     * in the field just above), so as a live region it is announced once, not again with each letter typed.
     */
    const val NO_TO_BUY_MATCH_TEXT = "No items to buy match your search"

    /**
     * Marks that don't count: Latin, Greek and Cyrillic combining accents (after NFD), the Devanagari
     * nukta and the apostrophes ' ‘ ’. Other marks (Devanagari vowel signs, Thai, Arabic) change the
     * word, so they are kept.
     */
    private val IGNORED_MARKS = Regex("[\\u0300-\\u036F\\u093C'\\u2018\\u2019]")

    private const val CHANDRABINDU = '\u0901'
    private const val ANUSVARA = '\u0902'

    /**
     * [text] as search compares it: the item key ([ItemKeys.normalize]: trimmed, whitespace runs as one
     * space, lower case ignoring the device locale), decomposed (NFD) without [IGNORED_MARKS], and
     * chandrabindu written as anusvara. "Crème fraîche" gives "creme fraiche", "Lay's" gives "lays".
     */
    fun fold(text: String): String =
        IGNORED_MARKS.replace(Normalizer.normalize(ItemKeys.normalize(text), Normalizer.Form.NFD), "")
            .replace(CHANDRABINDU, ANUSVARA)

    /** The words of [query] after [fold]; empty when there is nothing to search for. */
    fun words(query: String): List<String> = fold(query).split(' ').filter { it.isNotEmpty() }

    /** False for an empty query and for one of only spaces, apostrophes or accents: the list shows everything. */
    fun isActive(query: String): Boolean = words(query).isNotEmpty()

    /** True when every word of [query] is part of [name] (plain text, never a pattern); true for an inactive query. */
    fun matches(name: String, query: String): Boolean = matchesWords(name, words(query))

    private fun matchesWords(name: String, words: List<String>): Boolean {
        if (words.isEmpty()) return true
        val folded = fold(name)
        return words.all { folded.contains(it) }
    }

    /**
     * The items to show for [query]: [items] itself (same instance and order) while the search is
     * inactive, else the matching items from A to Z. Results are not "needed first", so ticking Need or
     * stepping an amount never moves a result row away from under the finger.
     */
    fun filter(items: List<GroceryItem>, query: String): List<GroceryItem> {
        val words = words(query)
        if (words.isEmpty()) return items
        return ListLogic.sortForDisplay(items.filter { matchesWords(it.name, words) })
    }

    /** How many of [items] match [query]: all of them while the search is inactive. */
    fun count(items: List<GroceryItem>, query: String): Int {
        val words = words(query)
        return if (words.isEmpty()) items.size else items.count { matchesWords(it.name, words) }
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
    fun addText(name: String): String = "Add \"$name\" to the list"

    /** The row offered on To buy when [count] items that are not needed match: Show 2 in All items. */
    fun showInAllItemsText(count: Int): String = "Show $count in All items"
}

/**
 * The snackbar after the Add dialog added [name], or null for none. [page] is the page shown and [query]
 * the search ("" when search is closed). On To buy, an item that is not needed doesn't show up, so the
 * snackbar says where it is; on either page, neither does one the search hides.
 */
internal fun addedSnackbarText(name: String, needed: Boolean, page: ListFilter, query: String): String? = when {
    page == ListFilter.TO_BUY && !needed -> "Added $name - it's under All items until you need it"
    !ItemSearch.matches(name, query) -> "Added $name - clear the search to see it"
    else -> null
}
