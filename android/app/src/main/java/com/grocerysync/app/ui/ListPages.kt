package com.grocerysync.app.ui

// Pure rules of the list screen's pager (unit-tested in ListPagesTest).

/** The pager shows one page per [ListFilter], in its order: 0 = To buy, 1 = All items. */
internal val ListFilter.page: Int get() = ordinal

internal fun filterOfPage(page: Int): ListFilter = ListFilter.entries[page]

/**
 * The page to snap the pager to when the list screen starts with a saved state, or null to leave it where it
 * is. The pager saves its offset with its page, and nothing settles it again after a restore: a rotation (or
 * process death) in the middle of a swipe, or of the animation after a tap on To buy / All items or the Search
 * icon, would leave it drawn part-way between the two pages until the next touch.
 *
 * An open search belongs to All items. Restored open while the pager is not at rest on All items, it was
 * being opened from To buy (the Search icon switches to All items with an animation) or the user had started
 * to swipe away and not finished: the pager goes to All items and the search stays open, rather than being
 * closed as if the user had left All items. Otherwise an unfinished page change goes back to the page the
 * pager counts as current (the one more than half in view).
 */
internal fun pageToSettleOnRestore(currentPage: Int, offsetFraction: Float, searchOpen: Boolean): Int? = when {
    searchOpen && (currentPage != ListFilter.ALL.page || offsetFraction != 0f) -> ListFilter.ALL.page
    offsetFraction != 0f -> currentPage
    else -> null
}
