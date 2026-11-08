package com.grocerysync.app.ui

import com.grocerysync.app.data.Amount
import com.grocerysync.app.data.CatalogEntry
import com.grocerysync.app.data.CatalogSet
import com.grocerysync.app.data.EditResult
import com.grocerysync.app.data.ItemCatalog
import com.grocerysync.app.data.ItemValue
import com.grocerysync.app.data.Units
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Add common items": the rows of a set, what is ticked, the texts, and what adding does. */
class StarterListTest {

    private fun entry(displayName: String): CatalogEntry = ItemCatalog.entries.single { it.displayName == displayName }

    private fun rows(groups: List<StarterGroup>): List<StarterRow> = groups.flatMap { it.rows }

    private fun row(groups: List<StarterGroup>, displayName: String): StarterRow =
        rows(groups).single { it.entry.displayName == displayName }

    @Test
    fun theChipsAreTheSetsInTheirOrder() {
        assertEquals(
            listOf("Everyday", "Monthly kirana", "Diwali puja", "Vrat / fasting", "All"),
            StarterList.SETS.map { it.label },
        )
    }

    @Test
    fun everySetShowsItsEntriesBySectionInWalkingOrder() {
        for (set in StarterList.SETS) {
            val groups = StarterList.groups(set, emptyList())
            assertTrue(set.name, groups.isNotEmpty())
            // Every entry of the set once, in the catalog's order (walking order, most common first in a section).
            assertEquals(set.name, ItemCatalog.entriesIn(set), rows(groups).map { it.entry })
            // One group per section, in walking order, none empty, each holding only its section's entries.
            val sections = groups.map { it.section }
            assertEquals(set.name, sections.sortedBy { it.ordinal }, sections)
            assertEquals(set.name, sections.distinct(), sections)
            for (group in groups) {
                assertTrue(group.rows.isNotEmpty())
                assertTrue(group.rows.all { it.entry.section == group.section })
            }
        }
    }

    @Test
    fun rowKeysAreUnique() {
        // The dialog's LazyColumn keys: "section:" + id and "entry:" + row id.
        val groups = StarterList.groups(CatalogSet.ALL, emptyList())
        val ids = rows(groups).map { it.id }
        assertEquals(ids.distinct(), ids)
        assertEquals(ItemCatalog.entries.size, ids.size)
        val sectionIds = groups.map { it.section.id }
        assertEquals(sectionIds.distinct(), sectionIds)
    }

    @Test
    fun onAnEmptyListEverythingStartsTicked() {
        val groups = StarterList.groups(CatalogSet.EVERYDAY, emptyList())
        assertTrue(rows(groups).all { it.canAdd && it.onListAs == null })
        assertTrue(StarterList.hasAddable(groups))
        assertTrue(StarterList.allTicked(groups, emptySet()))
        assertEquals(ItemCatalog.entriesIn(CatalogSet.EVERYDAY), StarterList.toAdd(groups, emptySet()))
    }

    @Test
    fun itemsOnTheListAreUntickedAndSayUnderWhichName() {
        val onList = listOf("Basmati rice", "Curd", "onion", "Tel")
        val groups = StarterList.groups(CatalogSet.ALL, onList)

        // The same name in other capitals, or another name of the same entry.
        assertEquals("onion", row(groups, "Onion").onListAs)
        assertEquals("Curd", row(groups, "Dahi").onListAs)
        assertEquals("Tel", row(groups, "Oil").onListAs)
        // "Basmati rice" names no entry as a whole: Rice can still be added.
        assertNull(row(groups, "Rice").onListAs)

        val onListRows = rows(groups).filterNot { it.canAdd }.map { it.entry.displayName }
        assertEquals(listOf("Onion", "Dahi", "Oil"), onListRows)
        // Never ticked, whatever the selection says, and never added.
        val toAdd = StarterList.toAdd(groups, emptySet()).map { it.displayName }
        assertFalse(toAdd.any { it in onListRows })
        assertFalse(StarterList.isTicked(row(groups, "Dahi"), emptySet()))
        assertEquals(ItemCatalog.entries.size - 3, toAdd.size)
        // Still "all ticked": only rows that can be added count.
        assertTrue(StarterList.allTicked(groups, emptySet()))
    }

    @Test
    fun aNameWithTheDisplayNamesKeyComesFirst() {
        val groups = StarterList.groups(CatalogSet.ALL, listOf("Curd", "Yogurt", "DAHI"))
        assertEquals("DAHI", row(groups, "Dahi").onListAs)
        // Otherwise the first name of the list that names the entry.
        assertEquals("Curd", row(StarterList.groups(CatalogSet.ALL, listOf("Curd", "Yogurt")), "Dahi").onListAs)
    }

    @Test
    fun namesOnListAgreesWithTheCatalog() {
        val lists = listOf(
            emptyList(),
            listOf("Curd", "Yogurt", "DAHI", "Tel", "Refined oil", "pyaaz", "प्याज", "Eggs", "egg", "Basmati rice"),
            listOf("Maggi", "Colgate", "Surf", "Vim", "Harpic", "raisins", "Kishmish", "Cinnamon", "dal", "Licorice"),
        )
        for (names in lists) {
            val onList = NamesOnList(names)
            for (entry in ItemCatalog.entries) {
                assertEquals("${entry.displayName} in $names", ItemCatalog.nameOnList(entry, names), onList.nameOf(entry))
            }
        }
    }

    @Test
    fun whatIsUntickedStaysUntickedAcrossSets() {
        var unticked = StarterList.toggle(emptySet(), "Onion", ticked = false)
        val everyday = StarterList.groups(CatalogSet.EVERYDAY, emptyList())
        val all = StarterList.groups(CatalogSet.ALL, emptyList())
        assertFalse(StarterList.isTicked(row(everyday, "Onion"), unticked))
        assertFalse(StarterList.isTicked(row(all, "Onion"), unticked))
        assertFalse(StarterList.allTicked(everyday, unticked))
        assertFalse(entry("Onion") in StarterList.toAdd(all, unticked))

        unticked = StarterList.toggle(unticked, "Onion", ticked = true)
        assertTrue(StarterList.isTicked(row(all, "Onion"), unticked))
        assertTrue(unticked.isEmpty())
    }

    @Test
    fun selectAllAndNoneChangeOnlyTheSetShown() {
        val vrat = StarterList.groups(CatalogSet.VRAT, emptyList())
        val diwali = StarterList.groups(CatalogSet.DIWALI_PUJA, emptyList())

        val none = StarterList.selectAll(diwali, emptySet(), select = false)
        assertTrue(StarterList.toAdd(diwali, none).isEmpty())
        assertFalse(StarterList.allTicked(diwali, none))
        // Ghee is in both sets, so it is unticked under Vrat too; Sabudana is only in Vrat and stays ticked.
        assertFalse(StarterList.isTicked(row(vrat, "Ghee"), none))
        assertTrue(StarterList.isTicked(row(vrat, "Sabudana"), none))

        val vratNone = StarterList.selectAll(vrat, none, select = false)
        val vratAll = StarterList.selectAll(vrat, vratNone, select = true)
        assertTrue(StarterList.allTicked(vrat, vratAll))
        // Diwali-only entries stay unticked.
        assertFalse(StarterList.isTicked(row(diwali, "Diya"), vratAll))
        assertEquals(ItemCatalog.entriesIn(CatalogSet.VRAT), StarterList.toAdd(vrat, vratAll))
    }

    @Test
    fun aSetThatIsAllOnTheListHasNothingToAdd() {
        val names = ItemCatalog.entriesIn(CatalogSet.VRAT).map { it.displayName.uppercase() }
        val groups = StarterList.groups(CatalogSet.VRAT, names)
        assertFalse(StarterList.hasAddable(groups))
        assertTrue(StarterList.toAdd(groups, emptySet()).isEmpty())
        assertEquals("Add items", StarterList.addButtonText(0))
    }

    @Test
    fun rowTexts() {
        assertEquals("Pyaaz · प्याज़", StarterList.otherNamesText(entry("Onion")))
        assertEquals("Curd · दही", StarterList.otherNamesText(entry("Dahi")))
        assertEquals("On the list as Curd", StarterList.onListText("Curd"))
        // A long name from the sheet is shortened, on one line.
        assertEquals("On the list as " + "A".repeat(37) + "...", StarterList.onListText("A".repeat(60)))

        // What it is added as: a unit the - / + steps suit, or yes / no.
        assertEquals("kg", StarterList.unitText(entry("Atta")))
        assertEquals("L", StarterList.unitText(entry("Milk")))
        assertEquals("dozen", StarterList.unitText(entry("Eggs")))
        assertEquals("pcs", StarterList.unitText(entry("Hara dhaniya")))
        assertEquals("pack", StarterList.unitText(entry("Haldi")))
        assertEquals("Yes / no", StarterList.unitText(entry("Dahi")))
    }

    @Test
    fun talkBackReadsNamesWithCommas() {
        val groups = StarterList.groups(CatalogSet.ALL, listOf("Tel"))
        assertEquals("Onion, Pyaaz, प्याज़, kg", StarterList.rowDescription(row(groups, "Onion")))
        assertEquals("Dahi, Curd, दही, yes or no", StarterList.rowDescription(row(groups, "Dahi")))
        assertEquals("Oil, on the list as Tel", StarterList.rowDescription(row(groups, "Oil")))
    }

    @Test
    fun buttonAndSnackbarTexts() {
        assertEquals("Add 1 item", StarterList.addButtonText(1))
        assertEquals("Add 12 items", StarterList.addButtonText(12))
        assertEquals("Added 1 item to All items", StarterList.addedText(1))
        assertEquals("Added 12 items to All items", StarterList.addedText(12))
        assertEquals("Nothing added: those items are on the list already", StarterList.addedText(0))
        assertEquals(
            "Connect to your sheet in Settings first, so items already on it aren't replaced.",
            StarterList.NOT_CONNECTED_TEXT,
        )
        assertEquals("Wait until your list has loaded from the sheet, then try again.", StarterList.LIST_NOT_LOADED_TEXT)
    }

    /** Records what the dialog adds; answers [refuse]d names with AlreadyOnList, as the repository would. */
    private class FakeAdd(private val refuse: Set<String> = emptySet()) {
        val added = mutableListOf<Pair<String, ItemValue>>()

        suspend fun add(name: String, value: ItemValue): EditResult {
            if (name in refuse) return EditResult.AlreadyOnList(name)
            added += name to value
            return EditResult.Saved
        }
    }

    @Test
    fun addsEachEntryByItsDisplayNameAsNotNeeded() = runTest {
        val fake = FakeAdd()
        val entries = listOf(entry("Atta"), entry("Dahi"), entry("Milk"), entry("Eggs"))
        val count = StarterList.addEntries(entries, emptyList(), fake::add)

        assertEquals(4, count)
        assertEquals(
            listOf(
                "Atta" to ItemValue.Quantity(Amount.ZERO, Units.KG),
                "Dahi" to ItemValue.BoolValue(false),
                "Milk" to ItemValue.Quantity(Amount.ZERO, Units.L),
                "Eggs" to ItemValue.Quantity(Amount.ZERO, Units.DOZEN),
            ),
            fake.added,
        )
        // Not needed: they go to All items, not To buy.
        assertTrue(fake.added.none { it.second.isNeeded })
    }

    @Test
    fun everyEntryIsAddedNotNeeded() = runTest {
        val fake = FakeAdd()
        val count = StarterList.addEntries(ItemCatalog.entries, emptyList(), fake::add)
        assertEquals(ItemCatalog.entries.size, count)
        assertTrue(fake.added.none { it.second.isNeeded })
    }

    @Test
    fun skipsWhatReachedTheListMeanwhileAndCountsOnlyWhatWasAdded() = runTest {
        // "Curd" came from the other phone after the dialog opened: no Dahi beside it.
        val fake = FakeAdd(refuse = setOf("Rice"))
        val entries = listOf(entry("Dahi"), entry("Rice"), entry("Atta"))
        val count = StarterList.addEntries(entries, listOf("Curd"), fake::add)

        assertEquals(listOf("Atta"), fake.added.map { it.first })
        // Rice was refused by the repository (an item called "Rice" arrived in between): not counted.
        assertEquals(1, count)
    }
}
