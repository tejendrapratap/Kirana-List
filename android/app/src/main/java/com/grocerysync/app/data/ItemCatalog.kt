package com.grocerysync.app.data

/**
 * Where an item is usually found, in the order a shopper walks the sabzi stalls and the kirana (To buy can be
 * grouped in this order). [id] is what a phone stores for a section and never changes; [label] is shown.
 */
enum class ShopSection(val id: String, val label: String) {
    SABZI_FRUIT("sabzi_fruit", "Sabzi & fruit"),
    DAIRY_EGGS_BREAD("dairy_eggs_bread", "Dairy, eggs & bread"),
    MEAT_FISH("meat_fish", "Meat & fish"),
    ATTA_RICE_DAL("atta_rice_dal", "Atta, rice & dal"),
    OIL_GHEE("oil_ghee", "Oil & ghee"),
    MASALA("masala", "Masala"),
    TEA_SUGAR_SALT("tea_sugar_salt", "Tea, sugar & salt"),
    DRY_FRUITS_NUTS("dry_fruits_nuts", "Dry fruits & nuts"),
    SNACKS_PACKAGED("snacks_packaged", "Snacks & packaged food"),
    CLEANING_HOUSEHOLD("cleaning_household", "Cleaning & household"),
    PERSONAL_CARE("personal_care", "Personal care"),
    PUJA("puja", "Puja"),
    OTHER("other", "Other");

    companion object {
        /** The section stored as [id]; null for a missing or unknown id. */
        fun fromId(id: String?): ShopSection? = entries.firstOrNull { it.id == id }
    }
}

/** The ready-made sets offered by "Add common items". */
enum class CatalogSet(val label: String) {
    /** Every core and common entry. */
    EVERYDAY("Everyday"),

    /** Dry staples and household goods bought once a month. */
    MONTHLY_KIRANA("Monthly kirana"),

    /** What Lakshmi puja on Diwali uses up: diya, rui batti, ghee, kapoor, agarbatti, roli, ... */
    DIWALI_PUJA("Diwali puja"),

    /** What fasting days (vrat, Navratri) use: sabudana, kuttu atta, sendha namak, makhana, ... */
    VRAT("Vrat / fasting"),

    /** Every entry. */
    ALL("All"),
}

/**
 * How widely an item is used, from docs/indian-grocery-list.md: core (about 80% or more of homes), common, or
 * regional (some regions, a minority of homes, or occasional). Entries the doc doesn't list are regional.
 */
enum class CatalogGrade { CORE, COMMON, REGIONAL }

/**
 * One item of the built-in dictionary ([ItemCatalog]).
 *
 * @param displayName what a family usually writes on a list ("Atta", "Onion", "Dahi"), and the name it is
 *   added with.
 * @param otherNames the other common names, shown in small text next to it ("Pyaaz", "प्याज़"); they match too.
 * @param moreNames further names that match (spellings, English names, generic brand names); not shown.
 * @param type and [unit] the value to add it with: a quantity of [unit] (a standard unit, never g or ml, so
 *   that - / + steps make sense), or yes/no (unit "") for items people just tick.
 * @param sets every set it belongs to, [CatalogSet.ALL] and (for core and common entries)
 *   [CatalogSet.EVERYDAY] included.
 */
data class CatalogEntry(
    val displayName: String,
    val otherNames: List<String>,
    val moreNames: List<String>,
    val section: ShopSection,
    val type: ValueType,
    val unit: String,
    val grade: CatalogGrade,
    val sets: Set<CatalogSet>,
) {
    /** Every other name that means this entry: [otherNames], then [moreNames]. */
    val aliases: List<String> get() = otherNames + moreNames

    /** [displayName], then [aliases]. */
    val names: List<String> get() = listOf(displayName) + aliases

    /** The words of all its names as search compares them ([CatalogText.words]). */
    internal val words: List<String> =
        (listOf(displayName) + otherNames + moreNames).flatMap(CatalogText::words).distinct()

    /** The value to add it with while it isn't needed: yes/no off, or 0 of [unit]. */
    fun notNeededValue(): ItemValue = when (type) {
        ValueType.BOOLEAN -> ItemValue.BoolValue(false)
        ValueType.QUANTITY -> ItemValue.Quantity(Amount.ZERO, unit)
    }

    /**
     * True when the search word [queryWord] matches one of its names at a word start ("pya" and "pyaaz" find
     * the onion entry), by the rules of [ItemCatalog.matchesQuery]. False for a word with no letters or digits
     * ("*", "-"), which is part of no name.
     */
    fun matchesWord(queryWord: String): Boolean {
        val queryWords = CatalogText.words(queryWord)
        return queryWords.isNotEmpty() && ItemCatalog.matchesAllWords(queryWords, words)
    }
}

/**
 * The built-in dictionary of common Indian grocery items (from docs/indian-grocery-list.md plus the items of
 * the ready-made sets): names in English, Hindi (Roman and Devanagari) and common spellings, a shop section and
 * a usual unit. It ships in the app, works offline and is the same on every phone; nothing of it is synced.
 *
 * Names are compared as search compares them ([SearchText.fold]: case, accents, the Devanagari nukta and
 * chandrabindu and extra spaces don't count), and punctuation separates words ("Rui-batti" is "rui batti").
 * A simple English plural on the last word doesn't count either ("Egg" is "Eggs", "Tomatoes" is "Tomato").
 */
object ItemCatalog {

    val entries: List<CatalogEntry> = BuiltInCatalog.entries

    /**
     * A query word this long or longer that is a whole word of some name ("dal", "tel") matches only that
     * whole word: "dal" never finds Dalchini. Shorter or partial words match any word starting with them.
     */
    internal const val MIN_WHOLE_WORD_LENGTH = 3

    /** Every name's key ([CatalogText.key]); the tests check that no key belongs to two entries. */
    private val byKey: Map<String, CatalogEntry> = buildMap {
        for (entry in ItemCatalog.entries) for (name in entry.names) putIfAbsent(CatalogText.key(name), entry)
    }

    /** Every word of every name, at least [MIN_WHOLE_WORD_LENGTH] characters long. */
    private val vocabulary: Set<String> =
        entries.flatMapTo(HashSet()) { entry -> entry.words.filter { it.length >= MIN_WHOLE_WORD_LENGTH } }

    /**
     * Words that never decide a section on their own inside a longer name: "Haldi powder" is a masala, not
     * talcum powder, "Ginger garlic paste" isn't toothpaste, "Ice cream" isn't face cream.
     */
    private val GENERIC_WORDS = setOf("powder", "cream", "paste", "brush")

    /** Words that are no item but say where a longer name belongs ("Chole masala", "Rice flour"). */
    private val SECTION_WORDS = mapOf(
        "masala" to ShopSection.MASALA,
        "flour" to ShopSection.ATTA_RICE_DAL,
        "sabzi" to ShopSection.SABZI_FRUIT,
        "fruit" to ShopSection.SABZI_FRUIT,
        "cleaner" to ShopSection.CLEANING_HOUSEHOLD,
    )

    /**
     * The entry [itemName] names as a whole, matched against every entry's display name and aliases ("Curd",
     * "dahi" and "दही" give Dahi); null when it names none ("Basmati rice" is not Rice).
     */
    fun entryFor(itemName: String): CatalogEntry? = lookup(CatalogText.key(itemName))

    /**
     * True when every word of [query] matches, at the start of a word, the item's own name or a name of the
     * item's entry ([entryFor]): "curd" finds Dahi, "pyaaz" and "प्याज" find Onion, "kish" finds Raisins. A
     * word that is a whole word of some catalog name ("dal", "tel"; see [MIN_WHOLE_WORD_LENGTH]) must match
     * that whole word (or its plural): "dal" finds Toor dal but not Dalchini, "tel" never finds Hotel. True for
     * an empty query.
     */
    fun matchesQuery(query: String, itemName: String): Boolean {
        val queryWords = CatalogText.words(query)
        if (queryWords.isEmpty()) return true
        val nameWords = CatalogText.words(itemName)
        val entryWords = entryFor(itemName)?.words.orEmpty()
        return queryWords.all { matchesWord(it, nameWords) || matchesWord(it, entryWords) }
    }

    /**
     * The shop section of [itemName]: its entry's section ([entryFor]). A name with no entry takes the section
     * of the longest run of its words that names an entry, the later run on a tie ("Basmati rice" and "Amul
     * butter" find theirs, "Haldi powder" is a masala, "Chole masala" too). [ShopSection.OTHER] when nothing
     * matches.
     */
    fun sectionFor(itemName: String): ShopSection {
        val words = CatalogText.words(itemName)
        for (length in words.size downTo 1) {
            for (start in words.size - length downTo 0) {
                if (length == 1 && words.size > 1 && words[start] in GENERIC_WORDS) continue
                val key = words.subList(start, start + length).joinToString(" ")
                val section = lookup(key)?.section
                    ?: CatalogText.singularForms(key).firstNotNullOfOrNull { SECTION_WORDS[it] }
                if (section != null) return section
            }
        }
        return ShopSection.OTHER
    }

    /**
     * For the "Similar" warning: the first of [existingNames] that names the same entry as [newName] but has a
     * different item key ([ItemKeys.normalize]), else null. "Curd" with "Dahi" on the list gives "Dahi";
     * "dahi" with "Dahi" gives null (that is the exact duplicate, which is checked separately).
     */
    fun similarExisting(newName: String, existingNames: Collection<String>): String? {
        val entry = entryFor(newName) ?: return null
        val newKey = ItemKeys.normalize(newName)
        return existingNames.firstOrNull { ItemKeys.normalize(it) != newKey && entryFor(it) == entry }
    }

    /** The entries of [set], in walking order (by section). */
    fun entriesIn(set: CatalogSet): List<CatalogEntry> = entries.filter { set in it.sets }

    /**
     * The name under which [entry] is already on the list, from [existingNames]: one with the display name's
     * item key first, else the first that names the entry ("Oil" for cooking oil, "Curd" for Dahi); null
     * when none does.
     */
    fun nameOnList(entry: CatalogEntry, existingNames: Collection<String>): String? {
        val key = ItemKeys.normalize(entry.displayName)
        return existingNames.firstOrNull { ItemKeys.normalize(it) == key }
            ?: existingNames.firstOrNull { entryFor(it) == entry }
    }

    /** True when every one of [queryWords] (catalog words) matches one of [words]. */
    internal fun matchesAllWords(queryWords: List<String>, words: List<String>): Boolean =
        queryWords.all { matchesWord(it, words) }

    private fun matchesWord(queryWord: String, words: List<String>): Boolean {
        if (words.isEmpty()) return false
        return if (isWholeWord(queryWord)) {
            words.any { CatalogText.sameWord(queryWord, it) }
        } else {
            words.any { it.startsWith(queryWord) }
        }
    }

    private fun isWholeWord(word: String): Boolean =
        word.length >= MIN_WHOLE_WORD_LENGTH && CatalogText.singularForms(word).any { it in vocabulary }

    /** The entry of [key] ([CatalogText.key]), also with or without a plural ending on its last word. */
    private fun lookup(key: String): CatalogEntry? {
        if (key.isEmpty()) return null
        return byKey[key] ?: CatalogText.pluralVariants(key).firstNotNullOfOrNull { byKey[it] }
    }
}

/** How the dictionary compares names: search's folding ([SearchText.fold]) plus word and plural rules. */
internal object CatalogText {

    /**
     * [text] folded like search ([SearchText.fold]), with every run of characters that are not letters, digits
     * or marks (punctuation, symbols, emoji, spaces) as one space between words: "Rui-batti" and " rui  batti."
     * both give "rui batti". Invisible joiners (ZWJ, ZWNJ, soft hyphen) are dropped, so they never split a word.
     * Two names that search folds the same always get the same key.
     */
    fun key(text: String): String {
        val folded = SearchText.fold(text)
        val out = StringBuilder(folded.length)
        var gap = false
        var i = 0
        while (i < folded.length) {
            val codePoint = folded.codePointAt(i)
            i += Character.charCount(codePoint)
            when {
                isWordPart(codePoint) -> {
                    if (gap && out.isNotEmpty()) out.append(' ')
                    out.appendCodePoint(codePoint)
                    gap = false
                }
                Character.getType(codePoint) == Character.FORMAT.toInt() -> Unit
                else -> gap = true
            }
        }
        return out.toString()
    }

    /** The words of [key] of [text]; empty when there are none. */
    fun words(text: String): List<String> = key(text).split(' ').filter { it.isNotEmpty() }

    /**
     * [word] first, then what it may be without a simple English plural ending: "eggs" also gives "egg",
     * "tomatoes" also "tomato" (and "tomatoe"), "cherries" also "cherry". Only words longer than three letters
     * ending in a single s are changed ("gas" and "glass" stay as they are). With several words, the last one.
     */
    fun singularForms(word: String): List<String> {
        val last = word.substringAfterLast(' ')
        if (last.length <= 3 || !last.endsWith('s') || last.endsWith("ss")) return listOf(word)
        val forms = mutableListOf(word, word.dropLast(1))
        if (last.length > 4 && last.endsWith("es")) forms += word.dropLast(2)
        if (last.length > 4 && last.endsWith("ies")) forms += word.dropLast(3) + "y"
        return forms
    }

    /**
     * Other ways [key] may be written with or without a plural ending on its last word, for looking a whole
     * name up: "egg" gives "eggs" and "egges", "eggs" gives "egg", "cherry" also gives "cherries". Empty for a
     * key not ending in a Latin letter.
     */
    fun pluralVariants(key: String): List<String> {
        if (key.isEmpty() || key.last() !in 'a'..'z') return emptyList()
        val variants = singularForms(key).drop(1).toMutableList()
        variants += key + "s"
        variants += key + "es"
        if (key.endsWith('y') && key.substringAfterLast(' ').length > 2) variants += key.dropLast(1) + "ies"
        return variants
    }

    /** True when the words [a] and [b] are the same, or the same but for a plural ending ("dal", "dals"). */
    fun sameWord(a: String, b: String): Boolean {
        if (a == b) return true
        val formsOfB = singularForms(b)
        return singularForms(a).any { it in formsOfB }
    }

    private fun isWordPart(codePoint: Int): Boolean = Character.isLetterOrDigit(codePoint) ||
        when (Character.getType(codePoint)) {
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt() -> true
            else -> false
        }
}
