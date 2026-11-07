package com.grocerysync.app.ui

import com.grocerysync.app.data.GroceryItem
import java.util.Locale

// Pure rules of the list screen's pages: the pager, and what each page shows (unit-tested in ListPagesTest).

/** The pager shows one page per [ListFilter], in its order: 0 = To buy, 1 = All items. */
internal val ListFilter.page: Int get() = ordinal

internal fun filterOfPage(page: Int): ListFilter = ListFilter.entries[page]

/**
 * The page to snap the pager to when the list screen starts with a saved state, or null to leave it where it
 * is. The pager saves its offset with its page, and nothing settles it again after a restore: a rotation (or
 * process death) in the middle of a swipe, or of the animation after a tap on To buy / All items (or on
 * "Show n in All items"), would leave it drawn part-way between the two pages until the next touch.
 *
 * An unfinished page change goes back to the page the pager counts as current (the one more than half in
 * view). An open search doesn't matter: it searches whichever page that is.
 */
internal fun pageToSettleOnRestore(currentPage: Int, offsetFraction: Float): Int? =
    if (offsetFraction != 0f) currentPage else null

/** What a page of the list shows under its banners ([pageContent]). */
internal sealed interface PageContent {
    /** Nothing: the list hasn't been read yet, so no empty or "No items match" text flashes while the app starts. */
    data object NotLoaded : PageContent

    /** The page's own empty state ("Nothing to buy right now", "Your list is empty"): no search and no items. */
    data object Empty : PageContent

    /** One row per item, in this order. */
    data class Rows(val items: List<GroceryItem>) : PageContent

    /** The search matches nothing on this page: [message], then one row offering [action]. */
    data class NoMatch(val message: String, val action: NoMatchAction) : PageContent
}

/** The row under the no-match line of a page ([PageContent.NoMatch]). */
internal sealed interface NoMatchAction {
    /** The row's text. */
    val text: String

    /**
     * To buy only: [count] items that are not needed right now match. The row switches to All items, where
     * the same search shows them.
     */
    data class ShowInAllItems(val count: Int) : NoMatchAction {
        override val text: String get() = ItemSearch.showInAllItemsText(count)
    }

    /** Nothing on the list matches: the row opens the Add dialog with [name] in its name field. */
    data class Add(val name: String) : NoMatchAction {
        override val text: String get() = ItemSearch.addText(name)
    }
}

/**
 * What [page] shows for [query] (the open search's text; "" while the search is closed). [loaded], [toBuy] and
 * [allItems] are those of [ListUiState].
 *
 * While the search is inactive ([ItemSearch.isActive]) a page shows its list as it is, or its empty state. While
 * it is active, a page shows its matching items from A to Z ([ItemSearch.filter]): To buy only the needed ones,
 * All items every one. When none match, the page says so and offers one way on:
 * - To buy, when items that are not needed match: show them under All items ([NoMatchAction.ShowInAllItems]).
 * - Otherwise nothing on the list matches: add the query as a new item ([NoMatchAction.Add], named with
 *   [ItemSearch.suggestedName] in [locale]).
 */
internal fun pageContent(
    page: ListFilter,
    loaded: Boolean,
    toBuy: List<GroceryItem>,
    allItems: List<GroceryItem>,
    query: String,
    locale: Locale = Locale.getDefault(),
): PageContent {
    if (!loaded) return PageContent.NotLoaded
    val items = when (page) {
        ListFilter.TO_BUY -> toBuy
        ListFilter.ALL -> allItems
    }
    if (!ItemSearch.isActive(query)) return if (items.isEmpty()) PageContent.Empty else PageContent.Rows(items)
    val shown = ItemSearch.filter(items, query)
    if (shown.isNotEmpty()) return PageContent.Rows(shown)
    // None of the needed items match; any match under All items is an item that isn't needed right now.
    val notNeeded = if (page == ListFilter.TO_BUY) ItemSearch.count(allItems, query) else 0
    return PageContent.NoMatch(
        message = if (page == ListFilter.TO_BUY) ItemSearch.NO_TO_BUY_MATCH_TEXT else ItemSearch.NO_MATCH_TEXT,
        action = if (notNeeded > 0) {
            NoMatchAction.ShowInAllItems(notNeeded)
        } else {
            NoMatchAction.Add(ItemSearch.suggestedName(query, locale))
        },
    )
}
