package com.grocerysync.app.ui

import com.grocerysync.app.data.Amount
import com.grocerysync.app.data.CatalogEntry
import com.grocerysync.app.data.CatalogSet
import com.grocerysync.app.data.EditResult
import com.grocerysync.app.data.ItemCatalog
import com.grocerysync.app.data.ItemValue
import com.grocerysync.app.data.ShopSection
import com.grocerysync.app.data.Units
import com.grocerysync.app.text.AppTexts
import com.grocerysync.app.text.EnglishBulkTexts
import com.grocerysync.app.text.Texts
import com.grocerysync.app.text.withTexts
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
            StarterList.SETS.map(EnglishBulkTexts::setName),
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
    fun meatFishAndEggsStartUnticked() {
        // Many families never buy them, and what is added goes on the whole family's list.
        assertEquals(setOf("Eggs", "Chicken", "Fish", "Mutton"), StarterList.UNTICKED_AT_FIRST)
        assertEquals(
            (ItemCatalog.entries.filter { it.section == ShopSection.MEAT_FISH } + entry("Eggs")).toSet(),
            ItemCatalog.entries.filter { it.optIn }.toSet(),
        )
        val unticked = StarterList.UNTICKED_AT_FIRST
        val everyday = StarterList.groups(CatalogSet.EVERYDAY, emptyList())
        for (name in listOf("Eggs", "Chicken")) {
            // Shown, and can be ticked.
            assertTrue(name, row(everyday, name).canAdd)
            assertFalse(name, StarterList.isTicked(row(everyday, name), unticked))
        }
        assertTrue(StarterList.isTicked(row(everyday, "Onion"), unticked))
        val toAdd = StarterList.toAdd(everyday, unticked)
        assertEquals(ItemCatalog.entriesIn(CatalogSet.EVERYDAY) - entry("Eggs") - entry("Chicken"), toAdd)
        // So the control offers Select all, which ticks them too, as a tick of the row does.
        assertFalse(StarterList.allTicked(everyday, unticked))
        assertTrue(entry("Chicken") in StarterList.toAdd(everyday, StarterList.toggle(unticked, "Chicken", ticked = true)))
        val all = StarterList.selectAll(everyday, unticked, select = true)
        assertEquals(ItemCatalog.entriesIn(CatalogSet.EVERYDAY), StarterList.toAdd(everyday, all))
        // Under All, fish and mutton too.
        val allSet = StarterList.groups(CatalogSet.ALL, emptyList())
        assertFalse(StarterList.toAdd(allSet, unticked).any { it.section == ShopSection.MEAT_FISH })
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
        assertEquals("1 item stays: changed on another phone", StarterList.keptText(1))
        assertEquals("3 items stay: changed on another phone", StarterList.keptText(3))
        assertEquals(
            "Connect to your sheet in Settings first, so items already on it aren't replaced.",
            StarterList.NOT_CONNECTED_TEXT,
        )
        assertEquals("Wait until your list has loaded from the sheet, then try again.", StarterList.LIST_NOT_LOADED_TEXT)
    }

    @Test
    fun inHindi() = withTexts(AppTexts.Hindi) {
        assertEquals("आम सामान जोड़ें", StarterList.TITLE)
        assertEquals(listOf("रोज़ का", "महीने का किराना", "दिवाली पूजा", "व्रत", "सब"), StarterList.SETS.map(Texts.current.bulk::setName))

        // Item names and units are data; the words around them are Hindi.
        assertEquals("लिस्ट में Curd नाम से है", StarterList.onListText("Curd"))
        assertEquals("लिस्ट में " + "A".repeat(37) + "... नाम से है", StarterList.onListText("A".repeat(60)))
        assertEquals("kg", StarterList.unitText(entry("Atta")))
        assertEquals("हाँ या नहीं", StarterList.unitText(entry("Dahi")))
        val groups = StarterList.groups(CatalogSet.ALL, listOf("Tel"))
        assertEquals("Onion, Pyaaz, प्याज़, kg", StarterList.rowDescription(row(groups, "Onion")))
        assertEquals("Dahi, Curd, दही, हाँ या नहीं", StarterList.rowDescription(row(groups, "Dahi")))
        assertEquals("Oil, लिस्ट में Tel नाम से है", StarterList.rowDescription(row(groups, "Oil")))

        // Counted: चीज़ / चीज़ें, and the verb agrees ("जोड़ दी" for one, "जोड़ दीं" for more).
        assertEquals("चीज़ें जोड़ें", StarterList.addButtonText(0))
        assertEquals("1 चीज़ जोड़ें", StarterList.addButtonText(1))
        assertEquals("12 चीज़ें जोड़ें", StarterList.addButtonText(12))
        assertEquals("\"सारा सामान\" में 1 चीज़ जोड़ दी", StarterList.addedText(1))
        assertEquals("\"सारा सामान\" में 12 चीज़ें जोड़ दीं", StarterList.addedText(12))
        assertEquals("कुछ नहीं जुड़ा: ये चीज़ें पहले से लिस्ट में हैं", StarterList.addedText(0))
        assertEquals("1 चीज़ नहीं हटी: दूसरे फ़ोन पर बदली गई है", StarterList.keptText(1))
        assertEquals("3 चीज़ें नहीं हटीं: दूसरे फ़ोन पर बदली गई हैं", StarterList.keptText(3))
        assertEquals(
            "पहले सेटिंग्स में अपनी शीट से जुड़ें, ताकि उसमें पहले से रखा सामान बदल न जाए।",
            StarterList.NOT_CONNECTED_TEXT,
        )
        assertEquals("शीट से आपकी लिस्ट आने तक रुकें, इसके बाद फिर से कोशिश करें।", StarterList.LIST_NOT_LOADED_TEXT)
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
        val added = StarterList.addEntries(entries, emptyList(), fake::add)

        // What it returns is what was added, for the snackbar's count and Undo.
        assertEquals(fake.added.map { (name, value) -> AddedItem(name, value) }, added)
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
        val added = StarterList.addEntries(ItemCatalog.entries, emptyList(), fake::add)
        assertEquals(ItemCatalog.entries.size, added.size)
        assertTrue(fake.added.none { it.second.isNeeded })
    }

    @Test
    fun skipsWhatReachedTheListMeanwhileAndCountsOnlyWhatWasAdded() = runTest {
        // "Curd" came from the other phone after the dialog opened: no Dahi beside it.
        val fake = FakeAdd(refuse = setOf("Rice"))
        val entries = listOf(entry("Dahi"), entry("Rice"), entry("Atta"))
        val added = StarterList.addEntries(entries, listOf("Curd"), fake::add)

        assertEquals(listOf("Atta"), fake.added.map { it.first })
        // Rice was refused by the repository (an item called "Rice" arrived in between): not counted, nor undone.
        assertEquals(listOf(AddedItem("Atta", ItemValue.Quantity(Amount.ZERO, Units.KG))), added)
    }
}
