package com.grocerysync.app.ui

import com.grocerysync.app.data.GroceryItem
import com.grocerysync.app.data.InputValidation
import com.grocerysync.app.data.ItemKeys
import com.grocerysync.app.data.ItemValue
import com.grocerysync.app.ui.ItemSearch.addText
import com.grocerysync.app.ui.ItemSearch.cleanInput
import com.grocerysync.app.ui.ItemSearch.count
import com.grocerysync.app.ui.ItemSearch.filter
import com.grocerysync.app.ui.ItemSearch.fold
import com.grocerysync.app.ui.ItemSearch.isActive
import com.grocerysync.app.ui.ItemSearch.matches
import com.grocerysync.app.ui.ItemSearch.suggestedName
import com.grocerysync.app.ui.ItemSearch.words
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Search on the page shown: matching rules, result order, the suggested name and the snackbar after adding. */
class ItemSearchTest {

    private fun item(name: String, needed: Boolean = false) = GroceryItem(
        key = ItemKeys.normalize(name),
        name = name,
        value = ItemValue.BoolValue(needed),
        updatedAt = 1_000L,
        updatedBy = "Home",
        pendingSync = false,
    )

    private val turkish = Locale.forLanguageTag("tr")

    @Test
    fun capitalsAccentsAndApostrophesDontCount() {
        assertTrue(matches("Cr\u00E8me fra\u00EEche", "creme"))
        assertTrue(matches("Cr\u00E8me fra\u00EEche", "FRAICHE"))
        assertTrue(matches("Jalape\u00F1o", "jalapeno"))
        assertTrue(matches("Lay's", "lays"))
        assertTrue(matches("Lay\u2019s chips", "lays"))
        assertTrue(matches("lays", "Lay\u2018s"))
        // A precomposed letter and the same letter typed with a combining accent are the same.
        assertTrue(matches("Caf\u00E9", "cafe\u0301"))
        assertTrue(matches("Cafe\u0301", "caf\u00E9"))
        // Turkish capital I with a dot folds to a plain i, whatever the phone's language.
        assertEquals("i", fold("\u0130"))
        assertTrue(matches("\u0130rmik", "irmik"))
        // Greek and Cyrillic accents too.
        assertTrue(matches("\u039A\u03B1\u03C6\u03AD", "\u03BA\u03B1\u03C6\u03B5"))
        assertTrue(matches("\u0439\u043E\u0433\u0443\u0440\u0442", "\u0438\u043E\u0433\u0443\u0440\u0442"))
    }

    @Test
    fun devanagariNuktaAndChandrabinduDontCountButVowelSignsDo() {
        // Jeera: plain, with the precomposed za (U+095B) and with ja + nukta.
        val jeera = "\u091C\u0940\u0930\u093E"
        val jeeraPrecomposed = "\u095B\u0940\u0930\u093E"
        val jeeraNukta = "\u091C\u093C\u0940\u0930\u093E"
        assertTrue(matches(jeeraPrecomposed, jeera))
        assertTrue(matches(jeeraNukta, jeera))
        assertTrue(matches(jeera, jeeraPrecomposed))
        // Gehun with chandrabindu, searched with anusvara.
        assertTrue(matches("\u0917\u0947\u0939\u0942\u0901", "\u0917\u0947\u0939\u0942\u0902"))
        // Doodh: the vowel sign is part of the word, so "dadh" doesn't find it.
        val doodh = "\u0926\u0942\u0927"
        assertFalse(matches(doodh, "\u0926\u0927"))
        assertTrue(matches(doodh, doodh))
    }

    @Test
    fun everyWordMustAppearInAnyOrder() {
        assertTrue(matches("Basmati rice extra long grain premium", "rice basmati"))
        assertTrue(matches("Rice", "ric"))
        assertTrue(matches("Licorice", "ric"))
        assertFalse(matches("Rice", "rice milk"))
        assertTrue(matches("Oat milk", "  oat    MILK "))
        // Plain text, never a pattern.
        assertTrue(matches("Tea (x2)", "(x2"))
        assertFalse(matches("105 g flour", "1.5"))
        assertTrue(matches("2 * 500 g", "*"))
        assertFalse(matches("Rice", "*"))
        assertTrue(matches("a\\b", "\\"))
        assertFalse(matches("Rice", "\\"))
        assertFalse(matches("Rice", ".*"))
    }

    @Test
    fun otherNamesFromTheDictionaryFindTheItem() {
        // English, Roman Hindi and Devanagari names of the same thing.
        assertTrue(matches("Dahi", "curd"))
        assertTrue(matches("Onion", "pyaaz"))
        assertTrue(matches("Onion", "प्याज")) // प्याज
        assertTrue(matches("Onion", "प्याज़")) // प्याज़, with the nukta
        assertTrue(matches("Kishmish", "raisins"))
        assertTrue(matches("Kishmish", "raisin"))
        assertTrue(matches("Eggs", "anda"))
        // The other way round: the family's own name is found by the dictionary's.
        assertTrue(matches("Curd", "dahi"))
        assertTrue(matches("Kanda", "onion"))
        assertTrue(matches("Tel", "oil"))
        // Part of a word at its start; capitals, accents and punctuation around it don't count.
        assertTrue(matches("Onion", "PYA"))
        assertTrue(matches("Dahi", "Cúrd"))
        assertTrue(matches("Dahi", "curd,"))
        // Every word, in any order: each may match the name or another name of it.
        assertTrue(matches("Toor dal", "arhar dal"))
        assertTrue(matches("Dahi", "dahi curd"))
        assertFalse(matches("Dahi", "curd milk"))
        // Only an item whose whole name is in the dictionary has other names.
        assertFalse(matches("Basmati rice", "chawal"))
        assertFalse(matches("Amul dahi", "curd"))
        assertFalse(matches("Batteries", "curd"))
    }

    @Test
    fun otherNamesMatchOnlyAtWordStarts() {
        // "dal" never finds Dalchini by another name; the whole other name or its start does.
        assertFalse(matches("Cinnamon", "dal"))
        assertTrue(matches("Cinnamon", "dalchini"))
        assertTrue(matches("Cinnamon", "dalc"))
        // An item called Dalchini still has "dal" in its own name, as before.
        assertTrue(matches("Dalchini", "dal"))
        // Not inside a word of another name: "nion" is only in the name Onion itself.
        assertFalse(matches("Pyaaz", "nion"))
        assertTrue(matches("Onion", "nion"))
        assertFalse(matches("Haldi", "meric"))
        assertTrue(matches("Haldi", "turmeric"))
        // A word without letters or digits matches no other name.
        for (query in listOf("*", "-", "( )", "curd *")) assertFalse(query, matches("Dahi", query))
    }

    @Test
    fun filterAndCountFindOtherNamesToo() {
        val dahi = item("Dahi", needed = true)
        val onion = item("Onion")
        val cinnamon = item("Cinnamon")
        val basmati = item("Basmati rice")
        val items = listOf(onion, dahi, cinnamon, basmati)
        assertEquals(listOf(dahi), filter(items, "curd"))
        assertEquals(listOf(onion), filter(items, "प्याज"))
        assertEquals(emptyList<GroceryItem>(), filter(items, "dal"))
        assertEquals(emptyList<GroceryItem>(), filter(items, "chawal"))
        for (query in listOf("curd", "pya", "dal", "dalc", "rice", "r", "x")) {
            assertEquals(query, filter(items, query).size, count(items, query))
        }
        // Results stay A to Z whichever name matched.
        val kanda = item("kanda")
        val rings = item("Onion rings")
        assertEquals(listOf(kanda, rings), filter(listOf(rings, kanda), "onion"))
    }

    @Test
    fun pagesOfferWhatOtherNamesFind() {
        val dahi = item("Dahi")
        val milk = item("Milk", needed = true)
        val all = listOf(dahi, milk)
        // To buy: Dahi isn't needed, so "curd" offers it under All items instead of adding a Curd.
        assertEquals(
            PageContent.NoMatch(ItemSearch.NO_TO_BUY_MATCH_TEXT, NoMatchAction.ShowInAllItems(1)),
            pageContent(ListFilter.TO_BUY, loaded = true, toBuy = listOf(milk), allItems = all, query = "curd"),
        )
        assertEquals(
            PageContent.Rows(listOf(dahi)),
            pageContent(ListFilter.ALL, loaded = true, toBuy = listOf(milk), allItems = all, query = "curd"),
        )
        // Adding "Curd" while searching "dahi" shows it, so no snackbar says to clear the search.
        assertNull(addedSnackbarText("Curd", needed = true, page = ListFilter.ALL, query = "dahi"))
    }

    @Test
    fun emptyOrMarkOnlyQueriesAreInactive() {
        for (query in listOf("", "   ", "'", "\u2019 \u2018", "\u0301", "\t", "\u00A0\u3000")) {
            assertFalse(query, isActive(query))
            assertTrue(query, matches("Rice", query))
        }
        assertTrue(isActive("r"))
        assertTrue(isActive(" 'r "))
        assertEquals(listOf("oat", "milk"), words("  Oat \t MILK "))
        assertEquals(listOf("lays"), words(" ' Lay's "))
    }

    @Test
    fun inactiveSearchShowsTheListAsItIs() {
        val items = listOf(item("Milk", needed = true), item("Bread"), item("apples"))
        assertSame(items, filter(items, ""))
        assertSame(items, filter(items, " ' "))
    }

    @Test
    fun resultsAreSortedAToZAndKeepTheItems() {
        val milk = item("Milk", needed = true)
        val bread = item("Bread", needed = true)
        val oat = item("oat milk")
        val almond = item("Almond milk")
        // Not "needed first": ticking a result never moves it.
        val result = filter(listOf(milk, bread, oat, almond), "milk")
        assertEquals(listOf(almond, milk, oat), result)
        assertSame(milk, result[1])
        assertEquals(emptyList<GroceryItem>(), filter(listOf(milk, bread), "rice"))
    }

    @Test
    fun countUsesTheSameRules() {
        val items = listOf(item("Milk", needed = true), item("Oat milk"), item("Basmati rice"), item("Bread"))
        assertEquals(2, count(items, "MILK"))
        assertEquals(1, count(items, "rice basmati"))
        assertEquals(0, count(items, "rice milk"))
        assertEquals(0, count(emptyList(), "rice"))
        // Inactive: every item.
        assertEquals(4, count(items, ""))
        assertEquals(4, count(items, " ' "))
        for (query in listOf("", "milk", "i", "rice", "x")) assertEquals(query, filter(items, query).size, count(items, query))
    }

    @Test
    fun suggestedNameIsTidiedUpWithACapital() {
        assertEquals("Oat milk", suggestedName("  oat   milk ", Locale.ENGLISH))
        assertEquals("\u0130rmik", suggestedName("irmik", turkish))
        assertEquals("Irmik", suggestedName("irmik", Locale.ENGLISH))
        assertEquals("", suggestedName("", Locale.ENGLISH))
        assertEquals("McVitie's", suggestedName("McVitie's", Locale.ENGLISH))
        assertEquals("123 eggs", suggestedName("123 eggs", Locale.ENGLISH))
        assertEquals("Corriander", suggestedName("corriander", Locale.ENGLISH))
    }

    @Test
    fun cleanInputKeepsOneLineAndTheNameLimit() {
        assertEquals("oat milk", cleanInput("oat\nmilk"))
        assertEquals("a b c d", cleanInput("a\rb\u2028c\u2029d"))
        assertEquals("x".repeat(ItemSearch.MAX_QUERY_LENGTH), cleanInput("x".repeat(150)))
        // An emoji that doesn't fit is left out whole, not cut in half.
        assertEquals("x".repeat(99), cleanInput("x".repeat(99) + "\uD83C\uDF4E"))
        assertEquals("x".repeat(98) + "\uD83C\uDF4E", cleanInput("x".repeat(98) + "\uD83C\uDF4E" + "y"))
        val short = "rice"
        assertSame(short, cleanInput(short))
    }

    @Test
    fun theSuggestedNameIsAlwaysAValidItemName() {
        val samples = listOf(
            "rice",
            "  oat \n milk ",
            "x".repeat(250),
            "\n\n a",
            "a" + "\u00A0".repeat(200) + "b",
            "x".repeat(99) + "\uD83C\uDF4E",
            // Capitals longer than their small letter at the limit: sharp s, n with apostrophe, fi ligature.
            "\u00DF".repeat(120),
            "\u0149" + "x".repeat(120),
            "\uFB01".repeat(100),
        )
        for (raw in samples) {
            val query = cleanInput(raw)
            assertTrue(raw, isActive(query))
            for (locale in listOf(Locale.ENGLISH, turkish)) {
                assertNull(raw, InputValidation.validateName(suggestedName(query, locale)))
            }
        }
    }

    @Test
    fun noMatchTexts() {
        assertEquals("Add \"Corriander\" to the list", addText("Corriander"))
        assertEquals("No items match your search", ItemSearch.NO_MATCH_TEXT)
        assertEquals("No items to buy match your search", ItemSearch.NO_TO_BUY_MATCH_TEXT)
        assertEquals("Show 3 in All items", ItemSearch.showInAllItemsText(3))
    }

    @Test
    fun theSnackbarAfterAddingSaysWhereTheItemIs() {
        // To buy only shows needed items.
        assertEquals(
            "Added Rice - it's under All items until you need it",
            addedSnackbarText("Rice", needed = false, page = ListFilter.TO_BUY, query = ""),
        )
        assertNull(addedSnackbarText("Rice", needed = true, page = ListFilter.TO_BUY, query = ""))
        // All items shows it unless the search hides it.
        assertNull(addedSnackbarText("Rice", needed = false, page = ListFilter.ALL, query = ""))
        assertNull(addedSnackbarText("Basmati rice", needed = false, page = ListFilter.ALL, query = "rice"))
        assertNull(addedSnackbarText("Milk", needed = false, page = ListFilter.ALL, query = " ' "))
        assertEquals(
            "Added Milk - clear the search to see it",
            addedSnackbarText("Milk", needed = true, page = ListFilter.ALL, query = "rice"),
        )
        // To buy searches too: a needed item the search hides says so; one it shows needs no snackbar.
        assertEquals(
            "Added Milk - clear the search to see it",
            addedSnackbarText("Milk", needed = true, page = ListFilter.TO_BUY, query = "rice"),
        )
        assertNull(addedSnackbarText("Rice", needed = true, page = ListFilter.TO_BUY, query = "rice"))
        // Not needed: it's under All items, whatever the search.
        assertEquals(
            "Added Rice - it's under All items until you need it",
            addedSnackbarText("Rice", needed = false, page = ListFilter.TO_BUY, query = "rice"),
        )
        assertEquals(
            "Added Milk - it's under All items until you need it",
            addedSnackbarText("Milk", needed = false, page = ListFilter.TO_BUY, query = "rice"),
        )
    }
}
