package com.grocerysync.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** This phone's shop sections: grouping To buy, sections chosen per item, kept across restarts and renames. */
class ShopSectionStoreTest {

    private val prefs = FakeSharedPreferences()

    private fun item(name: String) = GroceryItem(
        key = ItemKeys.normalize(name),
        name = name,
        value = ItemValue.BoolValue(true),
        updatedAt = 1_000L,
        updatedBy = "Home",
        pendingSync = false,
    )

    @Test
    fun startsGroupedWithNoChoices() {
        val settings = ShopSectionStore(prefs).current()
        assertTrue(settings.groupBySection)
        assertEquals(emptyMap<String, ShopSection>(), settings.chosen)
    }

    @Test
    fun groupingIsKeptAndTheFlowFollows() {
        val store = ShopSectionStore(prefs)
        store.setGroupBySection(false)
        assertFalse(store.settings.value.groupBySection)
        assertFalse(ShopSectionStore(prefs).current().groupBySection)

        store.setGroupBySection(true)
        assertTrue(ShopSectionStore(prefs).current().groupBySection)
    }

    @Test
    fun aChosenSectionIsStoredByIdUnderTheItemKey() {
        val store = ShopSectionStore(prefs)
        store.setSection("gadget", ShopSection.CLEANING_HOUSEHOLD)

        assertEquals("cleaning_household", prefs.values["section:gadget"])
        assertEquals(mapOf("gadget" to ShopSection.CLEANING_HOUSEHOLD), store.settings.value.chosen)
        assertEquals(mapOf("gadget" to ShopSection.CLEANING_HOUSEHOLD), ShopSectionStore(prefs).current().chosen)

        store.setSection("gadget", null)
        assertFalse(prefs.values.containsKey("section:gadget"))
        assertEquals(emptyMap<String, ShopSection>(), ShopSectionStore(prefs).current().chosen)
    }

    @Test
    fun anUnknownIdIsIgnored() {
        // From a newer version, or an entry that isn't a section at all.
        prefs.values["section:gadget"] = "garden"
        prefs.values["section:milk"] = 3
        prefs.values["section:dahi"] = "dairy_eggs_bread"
        assertEquals(mapOf("dahi" to ShopSection.DAIRY_EGGS_BREAD), ShopSectionStore(prefs).current().chosen)
    }

    @Test
    fun anItemKeyThatLooksLikeTheGroupingKeyIsStillAnItem() {
        val store = ShopSectionStore(prefs)
        store.setSection("group_by_section", ShopSection.OTHER)
        store.setGroupBySection(false)
        val reopened = ShopSectionStore(prefs).current()
        assertFalse(reopened.groupBySection)
        assertEquals(mapOf("group_by_section" to ShopSection.OTHER), reopened.chosen)
    }

    @Test
    fun theDictionarysOwnSectionForgetsTheChoice() {
        val store = ShopSectionStore(prefs)
        store.chooseSection("dahi", "Dahi", ShopSection.MEAT_FISH)
        assertEquals(ShopSection.MEAT_FISH, store.current().chosen["dahi"])

        // Back to where the dictionary puts Dahi: nothing to remember, so a better dictionary applies later.
        store.chooseSection("dahi", "Dahi", ShopSection.DAIRY_EGGS_BREAD)
        assertEquals(emptyMap<String, ShopSection>(), store.current().chosen)
        assertFalse(prefs.values.containsKey("section:dahi"))

        // An unknown name is in Other: choosing Other stores nothing either.
        store.chooseSection("gadget", "Gadget", ShopSection.OTHER)
        assertEquals(emptyMap<String, ShopSection>(), store.current().chosen)
    }

    @Test
    fun theChoiceIsComparedWithTheNameBeingSaved() {
        // "Thing" is renamed to "Paneer" in the same dialog: Dairy is Paneer's own section, so nothing is kept.
        val store = ShopSectionStore(prefs)
        store.setSection("thing", ShopSection.PUJA)
        store.chooseSection("thing", "Paneer", ShopSection.DAIRY_EGGS_BREAD)
        assertEquals(emptyMap<String, ShopSection>(), store.current().chosen)
    }

    @Test
    fun aRenameTakesTheChoiceAlong() {
        val store = ShopSectionStore(prefs)
        store.setSection("thing", ShopSection.PUJA)
        store.moveSection("thing", "diya")
        assertEquals(mapOf("diya" to ShopSection.PUJA), store.current().chosen)
        assertEquals(mapOf("diya" to ShopSection.PUJA), ShopSectionStore(prefs).current().chosen)
    }

    @Test
    fun aRenameWithoutAChoiceForgetsAnOldOneUnderTheNewName() {
        // A choice left by a deleted "Dahi" doesn't apply to "Curd" renamed to "Dahi": the item had none.
        val store = ShopSectionStore(prefs)
        store.setSection("dahi", ShopSection.OTHER)
        store.moveSection("curd", "dahi")
        assertEquals(emptyMap<String, ShopSection>(), store.current().chosen)
    }

    @Test
    fun onlyCapitalsChangedKeepsTheChoice() {
        val store = ShopSectionStore(prefs)
        store.setSection("gadget", ShopSection.CLEANING_HOUSEHOLD)
        store.moveSection("gadget", "gadget")
        assertEquals(mapOf("gadget" to ShopSection.CLEANING_HOUSEHOLD), store.current().chosen)
    }

    @Test
    fun sectionOfPrefersThisPhonesChoice() {
        val settings = ShopSectionSettings(chosen = mapOf("milk" to ShopSection.OTHER))
        assertEquals(ShopSection.OTHER, settings.sectionOf(item("Milk")))
        // Anything else as the dictionary says, Other when it doesn't know the name.
        assertEquals(ShopSection.DAIRY_EGGS_BREAD, settings.sectionOf(item("Curd")))
        assertEquals(ShopSection.SABZI_FRUIT, settings.sectionOf(item("Pyaaz")))
        assertEquals(ShopSection.OTHER, settings.sectionOf(item("Gadget")))
    }
}
