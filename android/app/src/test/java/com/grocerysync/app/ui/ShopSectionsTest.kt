package com.grocerysync.app.ui

import com.grocerysync.app.data.Amount
import com.grocerysync.app.data.GroceryItem
import com.grocerysync.app.data.ItemKeys
import com.grocerysync.app.data.ItemValue
import com.grocerysync.app.data.ShopSection
import com.grocerysync.app.data.ShopSectionSettings
import com.grocerysync.app.data.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * To buy by shop section: grouping in walking order ([groupBySection]), what To buy shows with and without a search
 * ([pageContent]), the headers' TalkBack text, and [ListUiState]'s view of this phone's choices.
 */
class ShopSectionsTest {

    private fun item(name: String, value: ItemValue = ItemValue.BoolValue(true)) = GroceryItem(
        key = ItemKeys.normalize(name),
        name = name,
        value = value,
        updatedAt = 1_000L,
        updatedBy = "Home",
        pendingSync = false,
    )

    // To buy as ListUiState holds it: by name.
    private val agarbatti = item("Agarbatti")
    private val atta = item("Atta", ItemValue.Quantity(Amount.whole(10L), Units.KG))
    private val curd = item("Curd")
    private val gadget = item("Gadget")
    private val haldi = item("Haldi powder")
    private val milk = item("Milk", ItemValue.Quantity(Amount.whole(2L), Units.L))
    private val onion = item("Onion", ItemValue.Quantity(Amount.whole(1L), Units.KG))
    private val pyaaz = item("pyaaz")
    private val toBuy = listOf(agarbatti, atta, curd, gadget, haldi, milk, onion, pyaaz)

    private val dictionary = ShopSectionSettings()

    private fun group(section: ShopSection, vararg items: GroceryItem) = ShopSectionGroup(section, items.toList())

    @Test
    fun sectionsAreInWalkingOrderWithOtherLast() {
        assertEquals(
            listOf(
                group(ShopSection.SABZI_FRUIT, onion, pyaaz),
                group(ShopSection.DAIRY_EGGS_BREAD, curd, milk),
                group(ShopSection.ATTA_RICE_DAL, atta),
                group(ShopSection.MASALA, haldi),
                group(ShopSection.PUJA, agarbatti),
                group(ShopSection.OTHER, gadget),
            ),
            groupBySection(toBuy, dictionary::sectionOf),
        )
    }

    @Test
    fun itemsKeepTheirOrderInsideASection() {
        // Whatever order To buy has: grouping never sorts the items again.
        val reversed = toBuy.reversed()
        val dairy = groupBySection(reversed, dictionary::sectionOf).single { it.section == ShopSection.DAIRY_EGGS_BREAD }
        assertEquals(listOf(milk, curd), dairy.items)
    }

    @Test
    fun emptySectionsAreLeftOut() {
        assertEquals(emptyList<ShopSectionGroup>(), groupBySection(emptyList(), dictionary::sectionOf))
        assertEquals(listOf(group(ShopSection.OTHER, gadget)), groupBySection(listOf(gadget), dictionary::sectionOf))
    }

    @Test
    fun thisPhonesChoiceMovesAnItem() {
        val settings = ShopSectionSettings(chosen = mapOf("gadget" to ShopSection.CLEANING_HOUSEHOLD, "milk" to ShopSection.OTHER))
        val groups = groupBySection(toBuy, settings::sectionOf)
        assertEquals(listOf(curd), groups.single { it.section == ShopSection.DAIRY_EGGS_BREAD }.items)
        assertEquals(listOf(gadget), groups.single { it.section == ShopSection.CLEANING_HOUSEHOLD }.items)
        assertEquals(listOf(milk), groups.single { it.section == ShopSection.OTHER }.items)
        // Every item is in exactly one section, so the list's keys stay unique.
        assertEquals(toBuy.toSet(), groups.flatMap { it.items }.toSet())
        assertEquals(toBuy.size, groups.sumOf { it.items.size })
    }

    @Test
    fun headersSayHowManyItems() {
        assertEquals("Masala, 1 item", sectionHeaderDescription(ShopSection.MASALA, 1))
        assertEquals("Dairy, eggs & bread, 3 items", sectionHeaderDescription(ShopSection.DAIRY_EGGS_BREAD, 3))
    }

    // ---- What To buy shows ------------------------------------------------------------------------------

    private val sections = groupBySection(toBuy, dictionary::sectionOf)
    private val allItems = toBuy + item("Rice", ItemValue.BoolValue(false))

    private fun content(page: ListFilter, query: String, toBuyItems: List<GroceryItem> = toBuy, grouped: List<ShopSectionGroup>? = sections) =
        pageContent(page, loaded = true, toBuy = toBuyItems, allItems = allItems, query = query, locale = Locale.ENGLISH, toBuySections = grouped)

    @Test
    fun toBuyShowsItsSectionsWithoutASearch() {
        assertEquals(PageContent.Sections(sections), content(ListFilter.TO_BUY, ""))
        // Only spaces is no search either.
        assertEquals(PageContent.Sections(sections), content(ListFilter.TO_BUY, "  "))
    }

    @Test
    fun aSearchShowsFlatResults() {
        // The search's results A to Z with no headers, as before 1.4, whatever search matches.
        for (query in listOf("a", "milk", "haldi pow")) {
            assertEquals(query, PageContent.Rows(ItemSearch.filter(toBuy, query)), content(ListFilter.TO_BUY, query))
        }
        // "a" finds items of several sections; they are not in walking order.
        val results = (content(ListFilter.TO_BUY, "a") as PageContent.Rows).items
        assertTrue(results.map(dictionary::sectionOf).toSet().size > 1)
        assertEquals(results.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }), results)
        // The no-match row is unchanged too.
        assertEquals(
            PageContent.NoMatch(ItemSearch.NO_TO_BUY_MATCH_TEXT, NoMatchAction.ShowInAllItems(1)),
            content(ListFilter.TO_BUY, "rice"),
        )
    }

    @Test
    fun toBuyIsAToZWhenGroupingIsOff() {
        assertEquals(PageContent.Rows(toBuy), content(ListFilter.TO_BUY, "", grouped = null))
    }

    @Test
    fun nothingToBuyIsStillTheEmptyState() {
        assertEquals(PageContent.Empty, content(ListFilter.TO_BUY, "", toBuyItems = emptyList(), grouped = emptyList()))
    }

    @Test
    fun allItemsStaysAToZ() {
        assertEquals(PageContent.Rows(allItems), content(ListFilter.ALL, ""))
    }

    @Test
    fun nothingShowsBeforeTheListIsRead() {
        assertEquals(
            PageContent.NotLoaded,
            pageContent(ListFilter.TO_BUY, false, emptyList(), emptyList(), "", Locale.ENGLISH, toBuySections = emptyList()),
        )
    }

    // ---- ListUiState ------------------------------------------------------------------------------------

    @Test
    fun theStateShowsThisPhonesChoices() {
        val state = ListUiState(shopSections = ShopSectionSettings(groupBySection = false, chosen = mapOf("curd" to ShopSection.OTHER)))
        assertFalse(state.groupBySection)
        assertEquals(ShopSection.OTHER, state.sectionOf(curd))
        assertEquals(ShopSection.DAIRY_EGGS_BREAD, state.sectionOf(milk))
        // Grouped unless turned off.
        assertTrue(ListUiState().groupBySection)
    }
}
