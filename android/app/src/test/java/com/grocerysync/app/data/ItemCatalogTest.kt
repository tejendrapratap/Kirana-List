package com.grocerysync.app.data

import com.grocerysync.app.data.ItemCatalog.entryFor
import com.grocerysync.app.data.ItemCatalog.matchesQuery
import com.grocerysync.app.data.ItemCatalog.nameOnList
import com.grocerysync.app.data.ItemCatalog.sectionFor
import com.grocerysync.app.data.ItemCatalog.similarExisting
import com.grocerysync.app.text.EnglishBulkTexts
import com.grocerysync.app.ui.ItemSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The item dictionary's lookups: whole-name matching, search words, sections, the "Similar" warning and sets. */
class ItemCatalogTest {

    private fun entry(displayName: String): CatalogEntry =
        ItemCatalog.entries.single { it.displayName == displayName }

    private fun assertEntry(expected: String, name: String) {
        assertEquals(name, expected, entryFor(name)?.displayName)
    }

    @Test
    fun hindiAndEnglishNamesFindTheSameEntry() {
        assertEntry("Dahi", "curd")
        assertEntry("Dahi", "Curd")
        assertEntry("Dahi", "दही")
        assertEntry("Dahi", "Yogurt")
        for (onion in listOf("Onion", "pyaaz", "Pyaz", "pyaj", "kanda", "प्याज", "प्याज़", "  PYAAZ ")) {
            assertEntry("Onion", onion)
        }
        assertEntry("Kishmish", "raisins")
        assertEntry("Kishmish", "किशमिश")
        assertEntry("Atta", "Wheat flour")
        assertEntry("Oil", "Tel")
        assertEntry("Oil", "Refined oil")
        assertEntry("Sarson ka tel", "Mustard oil")
        assertEntry("Hari mirch", "Green chilli")
        assertEntry("Toor dal", "Arhar dal")
        assertEntry("Toor dal", "अरहर दाल")
        assertEntry("Chai patti", "Tea")
        // Brand names families use for the thing itself.
        assertEntry("Noodles", "Maggi")
        assertEntry("Toothpaste", "Colgate")
        assertEntry("Dishwash", "Vim")
        assertEntry("Toilet cleaner", "Harpic")
        assertEntry("Detergent", "Surf")
    }

    @Test
    fun namesAreComparedAsSearchFoldsThem() {
        // Case, spaces, accents, apostrophes, the nukta and chandrabindu don't count.
        assertEntry("Dahi", "  DAHI  ")
        assertEntry("Dahi", "Dahí")
        assertEntry("Urad dal", "उड़द दाल")
        assertEntry("Urad dal", "उडद दाल")
        assertEntry("Jeera", "ज़ीरा")
        assertEntry("Jeera", "जीरा")
        assertEntry("Arbi", "घुइयां")
        assertEntry("Arbi", "घुइयाँ")
        // Punctuation separates words.
        assertEntry("Rui batti", "Rui-batti")
        assertEntry("Rui batti", "rui  batti.")
        assertEntry("Kheel", "Khil-batashe")
        assertEntry("Dahi", "*Dahi*")
    }

    @Test
    fun thingsBoughtSeparatelyAreSeparateEntries() {
        // Kheel and batashe: two lines of a Diwali list. "Kheel batashe" on one line is the kheel.
        assertEntry("Kheel", "Khil")
        assertEntry("Kheel", "खील")
        assertEntry("Kheel", "Kheel batashe")
        assertEntry("Batashe", "Batasha")
        assertEntry("Batashe", "बताशे")
        assertNull(similarExisting("Batashe", listOf("Kheel")))
        // Whole pulses (sabut moong for sprouts, urad for dal makhani) are not the split dals...
        for (whole in listOf("Moong", "Mung", "Urad", "Masoor", "Malka masoor", "Sabut moong")) {
            assertNull(whole, entryFor(whole))
            // ...but they sort with them.
            assertEquals(whole, ShopSection.ATTA_RICE_DAL, sectionFor(whole))
        }
        assertNull(similarExisting("Moong", listOf("Moong dal")))
        assertEntry("Moong dal", "Mung dal")
        assertEntry("Masoor dal", "Masur dal")
        assertEntry("Urad dal", "Urd dal")
        // A word inside a longer one decides nothing: peanuts stay nuts.
        assertEquals(ShopSection.DRY_FRUITS_NUTS, sectionFor("Moongphali"))
    }

    @Test
    fun mirchAndChilliAloneAreGreenChilli() {
        for (name in listOf("Mirch", "Mirchi", "Chilli", "Chili", "Chillies", "Green chillies")) assertEntry("Hari mirch", name)
        assertEquals(ShopSection.SABZI_FRUIT, sectionFor("Mirch"))
        // The other mirch and chilli names keep their own entries.
        assertEntry("Lal mirch", "Lal mirch")
        assertEntry("Lal mirch", "Red chilli")
        assertEntry("Lal mirch", "Chilli powder")
        assertEntry("Kali mirch", "Kali mirch")
        assertEntry("Capsicum", "Shimla mirch")
    }

    @Test
    fun aSimpleEnglishPluralDoesntCount() {
        assertEntry("Eggs", "Egg")
        assertEntry("Eggs", "eggs")
        assertEntry("Tomato", "Tomatoes")
        assertEntry("Potato", "Potatoes")
        assertEntry("Hari mirch", "Green chillies")
        assertEntry("Dal", "Dals")
        assertEntry("Kishmish", "Raisin")
        assertEntry("Biscuits", "Biscuit")
        assertEntry("Sanitary pads", "Sanitary pad")
        assertEntry("Garbage bags", "Garbage bag")
    }

    @Test
    fun onlyWholeNamesHaveAnEntry() {
        assertNull(entryFor("Basmati rice"))
        assertNull(entryFor("Rice 2 kg"))
        assertNull(entryFor("Hotel"))
        assertNull(entryFor("Dalchinis and more"))
        assertNull(entryFor(""))
        assertNull(entryFor("   "))
        assertNull(entryFor("..."))
        assertNull(entryFor("\u2019"))
    }

    @Test
    fun searchFindsItemsThroughTheirOtherNames() {
        assertTrue(matchesQuery("curd", "Dahi"))
        assertTrue(matchesQuery("pyaaz", "Onion"))
        assertTrue(matchesQuery("प्याज", "Onion"))
        assertTrue(matchesQuery("raisins", "Kishmish"))
        // The family's own name for an item finds it through the catalog too.
        assertTrue(matchesQuery("onion", "Kanda"))
        assertTrue(matchesQuery("kishmish", "Raisins"))
        assertTrue(matchesQuery("dahi", "Curd"))
        // Partial words match at a word start, in Latin and Devanagari.
        assertTrue(matchesQuery("pya", "Onion"))
        assertTrue(matchesQuery("प्या", "Onion"))
        assertTrue(matchesQuery("kish", "Raisins"))
        assertTrue(matchesQuery("dalc", "Dalchini"))
        // Any word order; a word may match the item's name or one of its entry's names.
        assertTrue(matchesQuery("dal toor", "Toor dal"))
        assertTrue(matchesQuery("lal chilli", "Lal mirch"))
        assertTrue(matchesQuery("rice basmati", "Basmati rice"))
        // Plurals.
        assertTrue(matchesQuery("eggs", "Egg"))
        assertTrue(matchesQuery("egg", "Eggs"))
        assertTrue(matchesQuery("tomatoes", "Tomato"))
        assertTrue(matchesQuery("dals", "Moong dal"))
        // An empty query matches everything, like the list's search.
        assertTrue(matchesQuery("", "Rice"))
        assertTrue(matchesQuery("  ", "Rice"))
    }

    @Test
    fun searchMatchesOnlyAtWordStarts() {
        // "dal" is a whole word of catalog names, so it matches only that word: the dals, never Dalchini.
        assertTrue(matchesQuery("dal", "Toor dal"))
        assertTrue(matchesQuery("dal", "Dal"))
        assertFalse(matchesQuery("dal", "Dalchini"))
        assertFalse(matchesQuery("dal", "Cinnamon"))
        assertFalse(matchesQuery("dals", "Dalchini"))
        // "tel" finds the oils, never Hotel.
        assertTrue(matchesQuery("tel", "Oil"))
        assertTrue(matchesQuery("tel", "Sarson ka tel"))
        assertTrue(matchesQuery("tel", "Hair oil"))
        assertFalse(matchesQuery("tel", "Hotel"))
        assertFalse(matchesQuery("tel", "Hotel ka khana"))
        // "makhan" is butter, "makha" both.
        assertFalse(matchesQuery("makhan", "Makhana"))
        assertTrue(matchesQuery("makhan", "Butter"))
        assertTrue(matchesQuery("makha", "Makhana"))
        // Also in Devanagari: "दाल" finds the dals, never दालचीनी.
        assertTrue(matchesQuery("दाल", "Toor dal"))
        assertFalse(matchesQuery("दाल", "Cinnamon"))
        // Not inside a word (the list's own search still finds "ric" in "Licorice"; this is only the catalog).
        assertFalse(matchesQuery("ric", "Licorice"))
        assertFalse(matchesQuery("chawal", "Basmati rice")) // no entry: "Basmati rice" isn't Rice
        assertFalse(matchesQuery("curd", "Rice"))
        assertFalse(matchesQuery("curd dal", "Dahi"))
    }

    @Test
    fun anItemNeverDropsOutWhileItsOtherNameIsTyped() {
        // Every start of the other name finds the item, also where it is a name of something else ("rai" is
        // mustard seeds, "sem" beans, "ghi" ghee, "tur" toor dal, "pan" paan).
        val typed = listOf(
            "Kishmish" to "raisins", "Suji" to "semolina", "Sewai" to "semiya", "Sewai" to "sevai",
            "Lauki" to "ghiya", "Lauki" to "doodhi", "Amchur" to "aamchur", "Haldi" to "turmeric",
            "Cottage cheese" to "paneer", "Bath soap" to "sabun", "Rai" to "sarson", "Moongphali" to "peanuts",
            "Kalava" to "kalawa", "Tori" to "toori", "Dalchini" to "cinnamon",
        )
        for ((item, otherName) in typed) {
            for (end in 2..otherName.length) {
                val query = otherName.take(end)
                assertTrue("\"$query\" for $item", matchesQuery(query, item))
            }
        }
        // The only exceptions: "dal" and "makhan" are items of their own, so they don't find Dalchini or Makhana.
        assertFalse(matchesQuery("dal", "Cinnamon"))
        assertFalse(matchesQuery("makhan", "Fox nuts"))
        assertTrue(matchesQuery("dalc", "Cinnamon"))
        assertTrue(matchesQuery("makhana", "Fox nuts"))
    }

    @Test
    fun anEntryMatchesSingleSearchWords() {
        val onion = entry("Onion")
        assertTrue(onion.matchesWord("pya"))
        assertTrue(onion.matchesWord("प्याज"))
        assertTrue(onion.matchesWord("ONION"))
        assertFalse(onion.matchesWord("nion"))
        assertFalse(entry("Dalchini").matchesWord("dal"))
        assertTrue(entry("Dalchini").matchesWord("cinna"))
        // A word with no letters or digits is part of no name (the list's search keeps "*" as a word).
        for (word in listOf("", " ", "*", "-", ".*", "\\", "’")) assertFalse(word, onion.matchesWord(word))
    }

    @Test
    fun sectionsComeFromTheEntry() {
        assertEquals(ShopSection.SABZI_FRUIT, sectionFor("Onion"))
        assertEquals(ShopSection.SABZI_FRUIT, sectionFor("pyaaz"))
        assertEquals(ShopSection.DAIRY_EGGS_BREAD, sectionFor("Curd"))
        assertEquals(ShopSection.ATTA_RICE_DAL, sectionFor("Atta"))
        assertEquals(ShopSection.OIL_GHEE, sectionFor("Oil"))
        assertEquals(ShopSection.MASALA, sectionFor("Jeera"))
        assertEquals(ShopSection.TEA_SUGAR_SALT, sectionFor("Chai patti"))
        assertEquals(ShopSection.PUJA, sectionFor("Agarbatti"))
        assertEquals(ShopSection.OTHER, sectionFor("Batteries"))
        assertEquals(ShopSection.OTHER, sectionFor(""))
    }

    @Test
    fun aLongerNameTakesTheSectionOfAnItemInIt() {
        assertEquals(ShopSection.ATTA_RICE_DAL, sectionFor("Basmati rice"))
        assertEquals(ShopSection.DAIRY_EGGS_BREAD, sectionFor("Amul butter"))
        assertEquals(ShopSection.DAIRY_EGGS_BREAD, sectionFor("Toned milk"))
        assertEquals(ShopSection.OIL_GHEE, sectionFor("Fortune oil"))
        assertEquals(ShopSection.TEA_SUGAR_SALT, sectionFor("Tata salt"))
        assertEquals(ShopSection.ATTA_RICE_DAL, sectionFor("Aashirvaad atta 10 kg"))
        // The longest run wins, and the later one on a tie (English puts the thing last).
        assertEquals(ShopSection.MASALA, sectionFor("Garam masala powder"))
        assertEquals(ShopSection.SNACKS_PACKAGED, sectionFor("Aloo bhujia"))
        // A few words say where a name belongs without being an item.
        assertEquals(ShopSection.MASALA, sectionFor("Chole masala"))
        assertEquals(ShopSection.MASALA, sectionFor("Pav bhaji masala"))
        assertEquals(ShopSection.ATTA_RICE_DAL, sectionFor("Rice flour"))
        // Generic words don't decide alone.
        assertEquals(ShopSection.MASALA, sectionFor("Haldi powder"))
        assertEquals(ShopSection.OTHER, sectionFor("Ice cream"))
        assertEquals(ShopSection.PERSONAL_CARE, sectionFor("Powder"))
    }

    @Test
    fun similarFindsAnExistingNameForTheSameEntry() {
        assertEquals("Dahi", similarExisting("Curd", listOf("Rice", "Dahi")))
        assertEquals("Curd", similarExisting("Dahi", listOf("Curd")))
        assertEquals("Onion", similarExisting("प्याज", listOf("Milk", "Onion")))
        assertEquals("Rice", similarExisting("Chawal", listOf("Rice")))
        // A plural or an accent is similar, not the same name.
        assertEquals("Eggs", similarExisting("Egg", listOf("Eggs")))
        assertEquals("Dahi", similarExisting("Dahí", listOf("Dahi")))
        // The first one in the list's order.
        assertEquals("Curd", similarExisting("Yogurt", listOf("Curd", "Dahi")))
    }

    @Test
    fun similarIgnoresTheSameNameAndOtherEntries() {
        // The exact duplicate (same item key) is checked elsewhere.
        assertNull(similarExisting("dahi", listOf("Dahi")))
        assertNull(similarExisting(" Dahi ", listOf("dahi")))
        assertEquals("Curd", similarExisting("dahi", listOf("Dahi", "Curd")))
        // Different entries: mustard oil is not the cooking oil, Basmati rice has no entry.
        assertNull(similarExisting("Oil", listOf("Sarson ka tel", "Hair oil")))
        assertNull(similarExisting("Basmati rice", listOf("Rice")))
        assertNull(similarExisting("Rice", listOf("Basmati rice")))
        assertNull(similarExisting("Dal", listOf("Toor dal", "Dalchini")))
        assertNull(similarExisting("Curd", emptyList()))
        assertNull(similarExisting("", listOf("Dahi")))
    }

    @Test
    fun nameOnListPrefersTheDisplayName() {
        assertEquals("Oil", nameOnList(entry("Oil"), listOf("Rice", "Oil")))
        assertEquals("Refined oil", nameOnList(entry("Oil"), listOf("Rice", "Refined oil")))
        assertEquals("dahi", nameOnList(entry("Dahi"), listOf("Curd", "dahi")))
        assertEquals("Curd", nameOnList(entry("Dahi"), listOf("Milk", "Curd")))
        assertNull(nameOnList(entry("Dahi"), listOf("Milk", "Paneer")))
        assertNull(nameOnList(entry("Oil"), listOf("Sarson ka tel")))
    }

    @Test
    fun entriesAreAddedNotNeededWithTheirUnit() {
        assertEquals(ItemValue.Quantity(Amount.ZERO, "kg"), entry("Atta").notNeededValue())
        assertEquals(ItemValue.Quantity(Amount.ZERO, "L"), entry("Milk").notNeededValue())
        assertEquals(ItemValue.Quantity(Amount.ZERO, "dozen"), entry("Eggs").notNeededValue())
        assertEquals(ItemValue.Quantity(Amount.ZERO, "pcs"), entry("Hara dhaniya").notNeededValue())
        assertEquals(ItemValue.Quantity(Amount.ZERO, "pack"), entry("Haldi").notNeededValue())
        assertEquals(ItemValue.BoolValue(false), entry("Dahi").notNeededValue())
        for (entry in ItemCatalog.entries) assertFalse(entry.displayName, entry.notNeededValue().isNeeded)
    }

    @Test
    fun setsHoldTheirItems() {
        fun names(set: CatalogSet) = ItemCatalog.entriesIn(set).map { it.displayName }.toSet()

        val diwali = names(CatalogSet.DIWALI_PUJA)
        for (name in listOf(
            "Diya", "Rui batti", "Ghee", "Kapoor", "Agarbatti", "Roli", "Kalava", "Supari", "Paan patta", "Nariyal",
            "Genda phool mala", "Kheel", "Batashe", "Mithai", "Rangoli colours", "Candles", "Machis",
        )) assertTrue(name, name in diwali)
        assertFalse("Onion" in diwali)

        val vrat = names(CatalogSet.VRAT)
        for (name in listOf(
            "Sabudana", "Kuttu atta", "Singhara atta", "Samak chawal", "Sendha namak", "Makhana", "Moongphali",
            "Potato", "Shakarkandi",
        )) assertTrue(name, name in vrat)
        assertFalse("Onion" in vrat)
        assertFalse("Salt" in vrat) // sendha namak instead
        assertFalse("Rice" in vrat)

        val monthly = names(CatalogSet.MONTHLY_KIRANA)
        for (name in listOf("Atta", "Rice", "Dal", "Oil", "Salt", "Sugar", "Chai patti", "Detergent", "Bath soap")) {
            assertTrue(name, name in monthly)
        }
        for (fresh in listOf("Onion", "Milk", "Dahi", "Eggs", "Bread", "Chicken")) assertFalse(fresh, fresh in monthly)

        val everyday = names(CatalogSet.EVERYDAY)
        assertTrue("Atta" in everyday)
        assertTrue("Milk" in everyday)
        assertFalse("Diya" in everyday) // regional
        assertEquals(ItemCatalog.entries, ItemCatalog.entriesIn(CatalogSet.ALL))
    }

    @Test
    fun setsKeepWalkingOrder() {
        for (set in CatalogSet.entries) {
            val sections = ItemCatalog.entriesIn(set).map { it.section.ordinal }
            assertEquals(set.name, sections.sorted(), sections)
        }
    }

    @Test
    fun sectionsAndSetsHaveTheirLabelsInOrder() {
        assertEquals(
            listOf(
                "Sabzi & fruit", "Dairy, eggs & bread", "Meat & fish", "Atta, rice & dal", "Oil & ghee", "Masala",
                "Tea, sugar & salt", "Dry fruits & nuts", "Snacks & packaged food", "Cleaning & household",
                "Personal care", "Puja", "Other",
            ),
            ShopSection.entries.map { it.label },
        )
        assertEquals(
            listOf("Everyday", "Monthly kirana", "Diwali puja", "Vrat / fasting", "All"),
            CatalogSet.entries.map(EnglishBulkTexts::setName),
        )
    }

    @Test
    fun sectionIdsRoundTrip() {
        assertEquals(ShopSection.entries.size, ShopSection.entries.map { it.id }.toSet().size)
        for (section in ShopSection.entries) assertSame(section, ShopSection.fromId(section.id))
        assertNull(ShopSection.fromId(null))
        assertNull(ShopSection.fromId(""))
        assertNull(ShopSection.fromId("SABZI_FRUIT"))
    }

    @Test
    fun keysFoldLikeTheListSearch() {
        // Whatever the list's search treats as the same text, the catalog does too.
        val samples = listOf(
            "Crème fraîche", "Lay's chips", "Lay\u2019s", "  Oat \t MILK ", "İrmik", "Jalapeño",
            "प्याज़", "ज़ीरा", "गेहूँ", "उड़द दाल", "Dahi\u00A0vada", "Rice",
        )
        for (sample in samples) {
            assertEquals(sample, ItemSearch.words(sample).joinToString(" "), CatalogText.key(sample))
        }
        assertEquals("rui batti", CatalogText.key(" Rui-batti! "))
        assertEquals("atta 10 kg", CatalogText.key("Atta (10 kg)"))
        assertEquals("", CatalogText.key(" - * ' "))
        // An invisible joiner never splits a word; an emoji separates words.
        assertEquals("क्षेत्र", CatalogText.key("क्\u200Dषेत्र"))
        assertEquals(1, CatalogText.words("क्\u200Dषेत्र").size)
        assertEquals(listOf("dahi", "milk"), CatalogText.words("Dahi\uD83E\uDD5BMilk"))
    }

    @Test
    fun pluralRulesStayConservative() {
        assertEquals(listOf("eggs", "egg"), CatalogText.singularForms("eggs"))
        assertTrue("tomato" in CatalogText.singularForms("tomatoes"))
        assertTrue("cherry" in CatalogText.singularForms("cherries"))
        assertTrue("green chilli" in CatalogText.singularForms("green chillies"))
        assertEquals(listOf("gas"), CatalogText.singularForms("gas"))
        assertEquals(listOf("glass"), CatalogText.singularForms("glass"))
        assertEquals(listOf("dal"), CatalogText.singularForms("dal"))
        assertTrue(CatalogText.sameWord("dal", "dals"))
        assertFalse(CatalogText.sameWord("dal", "dalchini"))
        assertTrue("eggs" in CatalogText.pluralVariants("egg"))
        assertTrue(CatalogText.pluralVariants("दाल").isEmpty())
        assertNull(entryFor("Cherry tomatoes"))
    }
}
