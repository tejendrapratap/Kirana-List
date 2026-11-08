package com.grocerysync.app.ui

import androidx.compose.runtime.Immutable
import com.grocerysync.app.data.GroceryItem
import com.grocerysync.app.data.ShopSection
import java.util.EnumMap

// To buy by shop section (unit-tested in ShopSectionsTest): which section each needed item is in comes from
// ShopSectionSettings.sectionOf (this phone's choice, else the item dictionary); here the items are grouped.

/** One shop section of To buy: [section] and its needed [items], in To buy's order (A to Z). Never empty. */
@Immutable
data class ShopSectionGroup(val section: ShopSection, val items: List<GroceryItem>)

/**
 * [items] grouped by [sectionOf]: the sections in walking order ([ShopSection]'s order, [ShopSection.OTHER]
 * last), each holding its items in the order of [items]. Sections without items are left out.
 */
internal fun groupBySection(items: List<GroceryItem>, sectionOf: (GroceryItem) -> ShopSection): List<ShopSectionGroup> {
    if (items.isEmpty()) return emptyList()
    // An EnumMap iterates in the enum's order.
    val bySection = EnumMap<ShopSection, MutableList<GroceryItem>>(ShopSection::class.java)
    for (item in items) bySection.getOrPut(sectionOf(item)) { ArrayList() }.add(item)
    return bySection.map { (section, sectionItems) -> ShopSectionGroup(section, sectionItems) }
}

/** What TalkBack reads for a section header: "Masala, 3 items", "Puja, 1 item". */
internal fun sectionHeaderDescription(section: ShopSection, count: Int): String =
    "${section.label}, $count ${if (count == 1) "item" else "items"}"
