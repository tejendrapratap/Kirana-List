package com.grocerysync.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The built-in item dictionary's data: names are unambiguous and valid item names, units suit the - / +
 * stepper, sets are filled, and every item of docs/indian-grocery-list.md is there with its grade.
 */
class CatalogDataTest {

    private val entries = ItemCatalog.entries

    private fun entry(displayName: String): CatalogEntry? = entries.singleOrNull { it.displayName == displayName }

    @Test
    fun aboutAHundredAndFiftyEntries() {
        assertTrue("${entries.size} entries", entries.size in 110..150)
        assertEquals(entries.size, entries.map { ItemKeys.normalize(it.displayName) }.toSet().size)
    }

    @Test
    fun noNameMeansTwoEntries() {
        val owners = HashMap<String, CatalogEntry>()
        for (entry in entries) {
            for (name in entry.names) {
                val owner = owners.putIfAbsent(CatalogText.key(name), entry)
                if (owner != null && owner != entry) {
                    fail("\"$name\" names both ${owner.displayName} and ${entry.displayName}")
                }
            }
        }
        // Nor does one with or without a plural ending ("Pad" must not find one entry and "Pads" another).
        for (entry in entries) {
            for (name in entry.names) {
                for (variant in CatalogText.pluralVariants(CatalogText.key(name))) {
                    val owner = owners[variant] ?: continue
                    assertEquals("\"$name\" as \"$variant\"", entry.displayName, owner.displayName)
                }
            }
        }
    }

    @Test
    fun noEntryRepeatsAName() {
        for (entry in entries) {
            val keys = entry.names.map(CatalogText::key)
            assertEquals("${entry.displayName}: $keys", keys.size, keys.toSet().size)
        }
    }

    @Test
    fun everyNameIsAValidItemName() {
        for (entry in entries) {
            for (name in entry.names) {
                assertNull("\"$name\" (${entry.displayName})", InputValidation.validateName(name))
                // Written tidily: trimmed, single spaces, no punctuation that a key would drop.
                assertEquals(name, ItemKeys.collapseWhitespace(name), name)
                assertTrue("\"$name\" (${entry.displayName})", CatalogText.key(name).isNotEmpty())
            }
            assertTrue(entry.displayName, entry.displayName.first().isUpperCase())
        }
    }

    @Test
    fun everyEntryShowsOneOrTwoOtherNames() {
        for (entry in entries) assertTrue(entry.displayName, entry.otherNames.size in 1..2)
    }

    @Test
    fun defaultUnitsSuitTheStepper() {
        for (entry in entries) {
            when (entry.type) {
                ValueType.BOOLEAN -> assertEquals(entry.displayName, "", entry.unit)
                ValueType.QUANTITY -> {
                    assertTrue("${entry.displayName}: ${entry.unit}", Units.isStandard(entry.unit))
                    assertEquals(entry.displayName, entry.unit, Units.normalize(entry.unit))
                    // A step of 100 g or ml rarely fits a home's amounts: packets and bunches instead.
                    assertTrue("${entry.displayName}: ${entry.unit}", entry.unit != Units.G && entry.unit != Units.ML)
                }
            }
        }
        val examples = mapOf(
            "Atta" to "kg", "Rice" to "kg", "Milk" to "L", "Oil" to "L", "Eggs" to "dozen", "Banana" to "dozen",
            "Hara dhaniya" to "pcs", "Palak" to "pcs", "Methi" to "pcs", "Haldi" to "pack", "Jeera" to "pack",
            "Biscuits" to "pack", "Dahi" to "", "Hari mirch" to "",
        )
        for ((name, unit) in examples) assertEquals(name, unit, entry(name)?.unit)
    }

    @Test
    fun everySetHasItsEntries() {
        for (set in CatalogSet.entries) assertTrue(set.name, ItemCatalog.entriesIn(set).isNotEmpty())
        for (entry in entries) {
            assertTrue(entry.displayName, CatalogSet.ALL in entry.sets)
            assertEquals(entry.displayName, entry.grade != CatalogGrade.REGIONAL, CatalogSet.EVERYDAY in entry.sets)
        }
        assertEquals(entries.filter { it.grade != CatalogGrade.REGIONAL }, ItemCatalog.entriesIn(CatalogSet.EVERYDAY))
    }

    @Test
    fun entriesAreInWalkingOrder() {
        val sections = entries.map { it.section.ordinal }
        assertEquals(sections.sorted(), sections)
        // Every section but Other has items.
        assertEquals(ShopSection.entries - ShopSection.OTHER, entries.map { it.section }.distinct())
    }

    @Test
    fun coreAndCommonEntriesHaveADevanagariName() {
        for (entry in entries.filter { it.grade != CatalogGrade.REGIONAL }) {
            assertTrue(entry.displayName, entry.names.any { name -> name.any { it in '\u0900'..'\u097F' } })
        }
    }

    @Test
    fun aliasesAreOtherNamesThenMoreNames() {
        for (entry in entries) {
            assertEquals(entry.otherNames + entry.moreNames, entry.aliases)
            assertEquals(listOf(entry.displayName) + entry.aliases, entry.names)
        }
    }

    @Test
    fun everyItemOfTheGroceryListDocIsThere() {
        val rows = docRows()
        assertEquals(90, rows.size)
        assertEquals(rows.map { it.item }.toSet(), DOC_ITEMS.keys)
        for (row in rows) {
            val expected = DOC_ITEMS.getValue(row.item)
            for (displayName in expected) {
                val entry = entry(displayName) ?: throw AssertionError("${row.item}: no entry $displayName")
                assertEquals("${row.item}: $displayName", row.grade, entry.grade)
            }
            // The doc's English name (or one of its parts) finds the row's entry.
            if (expected.size == 1) {
                val parts = listOf(row.item.substringBefore(" (")) + row.item.substringBefore(" (").split(" / ")
                assertTrue(row.item, parts.any { ItemCatalog.entryFor(it)?.displayName == expected.single() })
            }
        }
    }

    @Test
    fun everyHindiNameOfTheGroceryListDocFindsItsEntry() {
        for (row in docRows()) {
            val expected = DOC_ITEMS.getValue(row.item)
            val main = row.hindi.substringBefore(" (")
            val inBrackets = row.hindi.substringAfter(" (", "").substringBefore(")")
            val parts = main.split(" / ") + inBrackets.split(", ").filter { it.isNotEmpty() }
            for (part in parts) {
                if (part in NOT_ITEM_NAMES) continue
                val found = ItemCatalog.entryFor(part)?.displayName
                assertTrue("${row.item}: \"$part\" gives $found, expected one of $expected", found in expected)
            }
        }
    }

    private data class DocRow(val item: String, val hindi: String, val grade: CatalogGrade)

    /** The item rows of the doc's tables: | Item | Hindi | Unit | Grade | Notes |. */
    private fun docRows(): List<DocRow> = doc().readLines(Charsets.UTF_8).mapNotNull { line ->
        val cells = line.split('|').map(String::trim)
        if (cells.size < 6 || cells[0].isNotEmpty()) return@mapNotNull null
        val grade = when (cells[4]) {
            "core" -> CatalogGrade.CORE
            "common" -> CatalogGrade.COMMON
            "regional" -> CatalogGrade.REGIONAL
            else -> return@mapNotNull null
        }
        DocRow(cells[1], cells[2], grade)
    }

    /** Found from the module directory (Gradle's working directory for unit tests) or any parent of it. */
    private fun doc(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, DOC)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("$DOC not found above ${File("").absolutePath}")
    }

    private companion object {
        const val DOC = "docs/indian-grocery-list.md"

        /** Each row of the doc (its Item cell) and the entries made from it: one, or several where it was split. */
        val DOC_ITEMS: Map<String, List<String>> = mapOf(
            "Rice" to listOf("Rice"),
            "Wheat flour" to listOf("Atta"),
            "Semolina" to listOf("Suji"),
            "Gram flour" to listOf("Besan"),
            "Bread" to listOf("Bread"),
            "Flattened rice" to listOf("Poha"),
            "Refined wheat flour" to listOf("Maida"),
            "Millet flour (jowar / bajra / ragi)" to listOf("Jowar atta", "Bajra atta", "Ragi atta"),
            "Dal (your household's usual dal)" to listOf("Dal"),
            "Split pigeon pea" to listOf("Toor dal"),
            "Split green gram" to listOf("Moong dal"),
            "Split chickpea" to listOf("Chana dal"),
            "Red lentil" to listOf("Masoor dal"),
            "Black gram" to listOf("Urad dal"),
            "Chickpeas (white or black)" to listOf("Kabuli chana", "Kala chana"),
            "Kidney beans" to listOf("Rajma"),
            "Cooking oil (your usual: refined / mustard / groundnut / coconut)" to listOf("Oil"),
            "Ghee" to listOf("Ghee"),
            "Mustard oil" to listOf("Sarson ka tel"),
            "Salt" to listOf("Salt"),
            "Sugar" to listOf("Sugar"),
            "Jaggery" to listOf("Gud"),
            "Tea leaves / dust" to listOf("Chai patti"),
            "Coffee powder" to listOf("Coffee"),
            "Turmeric" to listOf("Haldi"),
            "Red chilli (powder or whole)" to listOf("Lal mirch"),
            "Cumin" to listOf("Jeera"),
            "Coriander powder / seeds" to listOf("Dhaniya powder", "Sabut dhaniya"),
            "Mustard seeds" to listOf("Rai"),
            "Garam masala" to listOf("Garam masala"),
            "Asafoetida" to listOf("Hing"),
            "Green cardamom" to listOf("Elaichi"),
            "Whole spices (black pepper, cloves, cinnamon, bay leaf)" to
                listOf("Kali mirch", "Laung", "Dalchini", "Tej patta"),
            "Tamarind" to listOf("Imli"),
            "Sambar / curry powder" to listOf("Sambar masala"),
            "Milk" to listOf("Milk"),
            "Curd" to listOf("Dahi"),
            "Cottage cheese" to listOf("Paneer"),
            "Onion" to listOf("Onion"),
            "Potato" to listOf("Potato"),
            "Green chilli" to listOf("Hari mirch"),
            "Garlic" to listOf("Garlic"),
            "Tomato" to listOf("Tomato"),
            "Ginger" to listOf("Ginger"),
            "Coriander leaves" to listOf("Hara dhaniya"),
            "Leafy greens (spinach, methi, saag)" to listOf("Palak", "Methi"),
            "Brinjal" to listOf("Baingan"),
            "Lemon" to listOf("Lemon"),
            "Okra" to listOf("Bhindi"),
            "Cauliflower" to listOf("Cauliflower"),
            "Cabbage" to listOf("Cabbage"),
            "Gourd / pumpkin" to listOf("Lauki", "Tori", "Kaddu"),
            "Beans" to listOf("Beans"),
            "Carrot" to listOf("Carrot"),
            "Green peas" to listOf("Matar"),
            "Curry leaves" to listOf("Kadi patta"),
            "Coconut" to listOf("Nariyal"),
            "Banana" to listOf("Banana"),
            "Mango" to listOf("Mango"),
            "Groundnuts" to listOf("Moongphali"),
            "Apple" to listOf("Apple"),
            "Raisins" to listOf("Kishmish"),
            "Cashews" to listOf("Kaju"),
            "Eggs" to listOf("Eggs"),
            "Chicken" to listOf("Chicken"),
            "Fish" to listOf("Fish"),
            "Mutton" to listOf("Mutton"),
            "Biscuits" to listOf("Biscuits"),
            "Namkeen / bhujia" to listOf("Namkeen"),
            "Pickle" to listOf("Achaar"),
            "Instant noodles" to listOf("Noodles"),
            "Detergent (powder or bar)" to listOf("Detergent"),
            "Matchbox" to listOf("Machis"),
            "Incense sticks" to listOf("Agarbatti"),
            "Dishwash bar / liquid" to listOf("Dishwash"),
            "Scrub pad / steel wool" to listOf("Scrubber"),
            "Floor cleaner" to listOf("Floor cleaner"),
            "Mosquito repellent (coil / liquid)" to listOf("Mosquito repellent"),
            "Camphor" to listOf("Kapoor"),
            "Cotton wicks" to listOf("Rui batti"),
            "Toilet cleaner" to listOf("Toilet cleaner"),
            "Garbage bags" to listOf("Garbage bags"),
            "Bath soap" to listOf("Bath soap"),
            "Toothpaste" to listOf("Toothpaste"),
            "Toothbrush" to listOf("Toothbrush"),
            "Hair oil" to listOf("Hair oil"),
            "Shampoo" to listOf("Shampoo"),
            "Talcum powder / face cream" to listOf("Talcum powder", "Face cream"),
            "Shaving blades / razor" to listOf("Blade"),
            "Sanitary pads" to listOf("Sanitary pads"),
        )

        /** Parts of the doc's Hindi cells that are not item names (case matters: "Powder / Cream" are names). */
        val NOT_ITEM_NAMES = setOf(
            "Khada masala", // the four whole spices together; each is an entry now
            "Saag", // mustard greens, an entry of its own (Sarson ka saag), not palak or methi
            "powder", // the end of "Kapde dhone ka sabun / powder"
            "liquid", // the end of "Bartan dhone ka sabun / liquid" and "Machhar coil / liquid"
        )
    }
}
