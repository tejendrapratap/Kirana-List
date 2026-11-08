package com.grocerysync.app.data

import java.math.BigDecimal
import java.math.RoundingMode

/** Why a line of a pasted list can't be used (the preview shows it as skipped). */
enum class LineProblem {
    /** No item or amount could be read ("2 kg" with no name, "Rice 2-3 kg", "Rice 1/0 kg"). */
    UNREADABLE,

    /** The amount is 0 ("Rice 0 kg"): a paste only adds or marks what is needed. */
    ZERO_AMOUNT,

    /** The name is longer than an item name may be ([InputValidation.MAX_NAME_LENGTH]). */
    NAME_TOO_LONG,

    /** It ends with ':' ("Sabzi:", "Kirana list:"), so it heads the lines below it. */
    HEADING,
}

/**
 * One line of a pasted list that holds something (see [PasteParser.parse]).
 *
 * @param text the line without its bullet, numbering and WhatsApp formatting, as the preview shows it.
 * @param name the item it names, spaces tidied up ("" when [problem] is set).
 * @param amount the amount written; null when none was ("Dahi").
 * @param unit the unit written, cleaned ([Units.normalize]: "kgs" is "kg"); null when none was ("Rice 2",
 *   "Rice x2"), which counts pieces.
 * @param problem why the line can't be used; null when it can.
 */
data class PastedLine(
    val text: String,
    val name: String,
    val amount: Amount? = null,
    val unit: String? = null,
    val problem: LineProblem? = null,
)

/**
 * Reads a list typed or pasted as text: a WhatsApp message, a note, or what Share makes (feature 1, "Grocery
 * list (N items)" then one "• Atta - 10 kg" line per item). One item per line. Pure Kotlin, so it is unit-tested
 * on the JVM. The text is untrusted (it may come from another app through Share): it is only ever parsed.
 *
 * Per line it:
 * - drops a copied WhatsApp message's "[8/11/26, 10:15 am] Mummy: ", bullets and check boxes (•, -, *, ☐, ✅,
 *   [ ], [x], emoji), numbering ("1.", "1)", "(1)"), WhatsApp formatting (*bold*, _italic_, ~strike~,
 *   `mono`) and punctuation or emoji at the end;
 * - leaves out blank lines, lines with no letter or digit ("-----"), and Share's own title and
 *   "*Dairy, eggs & bread*" section lines, so a shared list pasted on another phone gives the same items;
 * - reads an amount after the name ("Rice 2 kg", "Rice - 2kg", "Rice: 2 kg", "Rice (2 kg)", "Rice x2",
 *   "Rice 2") or before it ("2 kg rice", "2kg rice", "2 x rice", "½ kg paneer"). Amounts may have decimals
 *   ("1.5", "1,5"), thousands ("1,000"), fractions ("1/2", "½", "1 1/2", "1½") and digits of other scripts
 *   ("२"). Units are cleaned like everywhere else ([Units.normalize]), plus a plural ("dozens") and the Hindi
 *   words किलो, ग्राम, लीटर, दर्जन and पैकेट. A count word after the amount ("x2", "2x") is no unit.
 *
 * Text that doesn't fit these shapes is the item's name as a whole ("Rice 2 kg basmati", "7Up"); a name that
 * starts or ends with a number may be read as an amount ("7 Up" is 7 of "Up"). The preview shows what was read.
 */
object PasteParser {

    /** Most lines with text a paste may have (blank lines don't count). */
    const val MAX_LINES = 200

    /** Most characters a paste may have. */
    const val MAX_CHARS = 10_000

    /** True when [text] is over [MAX_CHARS] characters or [MAX_LINES] lines with text: it isn't read. */
    fun isTooLong(text: String): Boolean = text.length > MAX_CHARS || lineCount(text) > MAX_LINES

    /** Lines of [text] that hold more than spaces. */
    fun lineCount(text: String): Int = text.split(LINE_BREAK).count { it.isNotBlank() }

    /**
     * The lines of [text] that hold something, in order (see [PasteParser]). Only the first [MAX_LINES] lines
     * with text are read; callers check [isTooLong] first and say so instead.
     */
    fun parse(text: String): List<PastedLine> =
        text.split(LINE_BREAK).asSequence().filter { it.isNotBlank() }.take(MAX_LINES).mapNotNull(::parseLine).toList()

    /** One line; null for a line that holds nothing (blank, decoration, Share's own headings). */
    internal fun parseLine(raw: String): PastedLine? {
        var line = ItemKeys.collapseWhitespace(plainDigits(raw))
        line = WHATSAPP_PREFIX.replaceFirst(line, "")
        // Share writes its section names in WhatsApp bold, on lines of their own: "*Dairy, eggs & bread*".
        val wholeBold = line.length > 2 && line.startsWith('*') && line.endsWith('*')
        line = withoutMarkers(line)
        line = FORMATTING.replace(line, "")
        line = trimEndDecoration(ItemKeys.collapseWhitespace(line))
        if (line.none(Char::isLetterOrDigit)) return null
        if (SHARE_TITLE.matches(line)) return null
        if (wholeBold && SECTION_LABELS.contains(line.lowercase())) return null
        if (line.endsWith(':')) {
            val heading = line.trimEnd(':', ' ')
            return PastedLine(text = heading, name = "", problem = LineProblem.HEADING)
        }
        if (line.length > MAX_LINE_LENGTH) return PastedLine(text = line, name = "", problem = LineProblem.NAME_TOO_LONG)
        return readItem(line)
    }

    /** [line] (cleaned) as an item: the name and the amount and unit before or after it, if any. */
    private fun readItem(line: String): PastedLine {
        val read = trailingAmount(line) ?: leadingAmount(line) ?: Read(line, null, null)
        if (read.unreadable) return PastedLine(text = line, name = "", problem = LineProblem.UNREADABLE)
        val name = cleanName(read.name)
        if (name.none(Char::isLetterOrDigit)) return PastedLine(text = line, name = "", problem = LineProblem.UNREADABLE)
        if (InputValidation.validateName(name) != null) {
            return PastedLine(text = line, name = "", problem = LineProblem.NAME_TOO_LONG)
        }
        val amountText = read.amountText ?: return PastedLine(text = line, name = name)
        val value = numberOf(amountText)
            ?: return PastedLine(text = line, name = "", problem = LineProblem.UNREADABLE)
        if (value > MAX_VALUE) return PastedLine(text = line, name = "", problem = LineProblem.UNREADABLE)
        val amount = Amount.of(value)
        if (!amount.isPositive) return PastedLine(text = line, name = "", problem = LineProblem.ZERO_AMOUNT)
        return PastedLine(text = line, name = name, amount = amount, unit = read.unit)
    }

    /** What a line says before it is checked: [name], [amountText] (null = none) and [unit] (null = none). */
    private class Read(val name: String, val amountText: String?, val unit: String?, val unreadable: Boolean = false)

    /** "Rice (2 kg)", "Rice 2 kg", "Rice - 2kg", "Rice: 2 kg", "Rice x2", "Rice 2"; null for other shapes. */
    private fun trailingAmount(line: String): Read? {
        PAREN_AMOUNT.matchEntire(line)?.let { match ->
            val (name, amount, unitWord) = match.destructured
            if (unitWord.isEmpty() || isUnitWord(unitWord)) return Read(name, amount, unitOf(unitWord))
        }
        val match = TRAILING_AMOUNT.matchEntire(line) ?: return null
        val (name, separator, amount, unitWord) = match.destructured
        if (unitWord.isNotEmpty() && !isUnitWord(unitWord)) return null
        // "Rice 2-3 kg" is a range, which has no single amount to take.
        val range = separator.any { it in DASHES } && name.trimEnd().lastOrNull()?.isDigit() == true
        if (range) return Read(name, null, null, unreadable = true)
        return Read(name, amount, unitOf(unitWord))
    }

    /**
     * "2 kg rice", "2kg rice", "2 x rice", "2 rice", "2 kg of rice"; null for other shapes. The amount must stand on
     * its own or be followed by a unit: "7Up" is a name.
     */
    private fun leadingAmount(line: String): Read? {
        val match = LEADING_AMOUNT.matchEntire(line) ?: return null
        val (amount, rest) = match.destructured
        if (rest.isBlank()) return Read("", amount, null, unreadable = true)
        val spaced = rest.first() == ' '
        val words = rest.trim()
        val first = words.substringBefore(' ')
        val afterFirst = words.substringAfter(' ', "")
        val (unit, name) = when {
            first.equals(COUNT_X, ignoreCase = true) -> null to afterFirst
            isLeadingUnit(first) -> unitOf(first) to afterFirst
            spaced -> null to words
            else -> return null
        }
        val withoutOf = name.removePrefixIgnoringCase("of ").removePrefixIgnoringCase("$COUNT_X ")
        if (withoutOf.isBlank()) return Read("", amount, null, unreadable = true)
        return Read(withoutOf, amount, unit)
    }

    private fun String.removePrefixIgnoringCase(prefix: String): String =
        if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this

    /** The name without separators or quotes around it, spaces tidied up. */
    private fun cleanName(name: String): String =
        ItemKeys.collapseWhitespace(name).trim { it in NAME_EDGE || it.isWhitespace() }

    /** A word that may be a unit after an amount: letters (a dot for "pkt."), at most [Units.MAX_LENGTH] long. */
    private fun isUnitWord(word: String): Boolean = word.trimEnd('.').length in 1..Units.MAX_LENGTH

    /** A unit before the name: a standard unit's spelling ([Units]) or a common count word (bunch, bottle, ...). */
    private fun isLeadingUnit(word: String): Boolean {
        if (!UNIT_WORD.matches(word)) return false
        val unit = unitOf(word) ?: return false
        return Units.isStandard(unit) || word.trimEnd('.').lowercase() in COUNT_WORDS
    }

    /**
     * The unit [word] means, cleaned ([Units.normalize]); null for none ("" or "x", a count). A plural of a
     * standard unit is that unit ("dozens"), and the Hindi words of [HINDI_UNITS] are their standard units.
     * Any other word is a unit of the user's own, as typed (bunch, bottle).
     */
    private fun unitOf(word: String): String? {
        val clean = word.trimEnd('.')
        if (clean.isEmpty() || clean.equals(COUNT_X, ignoreCase = true)) return null
        HINDI_UNITS[clean]?.let { return it }
        val unit = Units.normalize(clean)
        if (Units.isStandard(unit)) return unit
        if (clean.length > 3 && clean.endsWith('s', ignoreCase = true)) {
            val single = Units.normalize(clean.dropLast(1))
            if (Units.isStandard(single)) return single
        }
        return unit
    }

    /**
     * The value of an amount as written: "2", "1.5", "1,5" (decimal comma), "1,000" and "1,00,000" (thousands),
     * "1/2", "1 1/2". Null when it can't be read ("1.2.3", "1/0").
     */
    private fun numberOf(text: String): BigDecimal? {
        MIXED_NUMBER.matchEntire(text)?.let { match ->
            val (whole, numerator, denominator) = match.destructured
            return fraction(numerator, denominator)?.add(BigDecimal(whole))
        }
        FRACTION.matchEntire(text)?.let { match ->
            val (numerator, denominator) = match.destructured
            return fraction(numerator, denominator)
        }
        if (THOUSANDS.matches(text)) return BigDecimal(text.replace(",", ""))
        if (text.count { it == '.' || it == ',' } > 1) return null
        val decimal = text.replace(',', '.')
        return BigDecimal(if (decimal.startsWith('.')) "0$decimal" else decimal)
    }

    private fun fraction(numerator: String, denominator: String): BigDecimal? {
        val below = BigDecimal(denominator)
        if (below.signum() == 0) return null
        return BigDecimal(numerator).divide(below, FRACTION_SCALE, RoundingMode.HALF_UP)
    }

    /**
     * [text] with every decimal digit of another script ("२") as an ASCII digit, vulgar fractions as "1/2" (after
     * a digit, with a space: "1½" is "1 1/2"), the fraction slash as '/' and the multiplication sign as 'x'.
     */
    private fun plainDigits(text: String): String {
        val out = StringBuilder(text.length + 8)
        for (c in text) {
            val fraction = VULGAR_FRACTIONS[c]
            when {
                fraction != null -> {
                    if (out.isNotEmpty() && out.last().isDigit()) out.append(' ')
                    out.append(fraction)
                }
                c == '⁄' || c == '∕' -> out.append('/')
                c == '×' -> out.append('x')
                c.isDigit() && c !in '0'..'9' -> out.append(Character.digit(c, 10).coerceAtLeast(0))
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    /** Drops bullets, check boxes and numbering at the start, however they are combined ("1. ☐ Rice", "- [ ] Rice"). */
    private fun withoutMarkers(line: String): String {
        var text = line
        repeat(MAX_MARKERS) {
            val before = text
            text = CHECK_BOX.replaceFirst(text, "")
            text = text.substring(firstKeptIndex(text))
            text = NUMBERING.replaceFirst(text, "")
            if (text == before) return text
        }
        return text
    }

    /**
     * Index of the first character a line keeps at its start: a letter, a digit, a fraction ("½" became "1/2"
     * already), '(' for "(1)" and '.' for ".5 kg". Bullets, emoji, dashes, quotes and spaces before it go.
     */
    private fun firstKeptIndex(text: String): Int {
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            if (Character.isLetterOrDigit(codePoint) || codePoint == '('.code || codePoint == '.'.code) return i
            i += Character.charCount(codePoint)
        }
        return text.length
    }

    /**
     * [line] without punctuation, emoji and spaces at its end ("Rice 2 kg." "Dahi ✅"); a closing bracket ("Rice
     * (2 kg)"), '%' ("Milk 3%") and ':' (a heading, see [LineProblem.HEADING]) stay.
     */
    private fun trimEndDecoration(line: String): String {
        var end = line.length
        while (end > 0) {
            val codePoint = Character.codePointBefore(line, end)
            val keep = Character.isLetterOrDigit(codePoint) ||
                codePoint == ')'.code || codePoint == '%'.code || codePoint == ':'.code ||
                Character.getType(codePoint) == Character.NON_SPACING_MARK.toInt() ||
                Character.getType(codePoint) == Character.COMBINING_SPACING_MARK.toInt()
            if (keep) break
            end -= Character.charCount(codePoint)
        }
        return line.substring(0, end)
    }

    // ---- Patterns ---------------------------------------------------------------------------------

    private val LINE_BREAK = Regex("\r\n|[\n\r  \u0085]")

    /** Lines longer than this can't name one item: no amount is looked for. */
    private const val MAX_LINE_LENGTH = InputValidation.MAX_NAME_LENGTH + 40

    /** How many layers of bullets, boxes and numbering one line may start with. */
    private const val MAX_MARKERS = 4

    /** Decimals of a fraction before it is rounded to an [Amount]. */
    private const val FRACTION_SCALE = 6

    private val MAX_VALUE = BigDecimal.valueOf(InputValidation.MAX_QUANTITY)

    /** "x2", "2x", "2 x rice": a count, not a unit. */
    private const val COUNT_X = "x"

    private const val DASHES = "-–—"

    /** Taken off both ends of a name: what separates it from an amount, dots and quotes. */
    private const val NAME_EDGE = "-–—:=,;.\"'“”‘’"

    /** "[8/11/26, 10:15 am] Mummy: " (a copied message) or "8/11/26, 10:15 - Mummy: " (an exported chat). */
    private val WHATSAPP_PREFIX = Regex(
        """^\[?\d{1,2}[/.-]\d{1,2}[/.-]\d{2,4},?\s+\d{1,2}[:.]\d{2}(?:[:.]\d{2})?(?:\s*[ap]\.?\s?m\.?)?\]?\s*(?:-\s*)?[^:]{1,50}?:\s+""",
        RegexOption.IGNORE_CASE,
    )

    /** "[ ]", "[x]", "[✓]" at the start. */
    private val CHECK_BOX = Regex("""^\[\s*[xX✓✔✗]?\s*]\s*""")

    /** "1. ", "1.Rice", "1) ", "(1) " at the start; not "1.5 kg". */
    private val NUMBERING = Regex("""^(?:\d{1,3}\.(?:\s+|(?=\p{L}))|\d{1,3}\)\s*|\(\d{1,3}\)\s*)""")

    /** WhatsApp's formatting marks, wherever they are. */
    private val FORMATTING = Regex("[*_~`]")

    /** The first line of a shared list: "Grocery list (12 items)". */
    private val SHARE_TITLE = Regex("""grocery list\s*\(\s*\d+\s+items?\s*\)""", RegexOption.IGNORE_CASE)

    /** Share's section lines are these labels in bold. */
    private val SECTION_LABELS: Set<String> = ShopSection.entries.map { it.label.lowercase() }.toSet()

    /** "2", "1.5", "1,5", ".5", "1,000", "1,00,000". */
    private const val NUMBER = """\d+(?:[.,]\d+)*|[.,]\d+"""

    /** A mixed number ("1 1/2"), a fraction ("1/2") or a [NUMBER]. */
    private const val AMOUNT = """\d+\s\d+\s?/\s?\d+|\d+\s?/\s?\d+|$NUMBER"""

    /** A word of letters (and marks, for Hindi), possibly with dots ("pkt."). */
    private const val UNIT = """[\p{L}\p{M}][\p{L}\p{M}.]*"""

    private val UNIT_WORD = Regex(UNIT)

    private val PAREN_AMOUNT =
        Regex("""^(.+?)\s?\(\s?(?:x\s?)?($AMOUNT)\s?($UNIT)?\s?\)$""", RegexOption.IGNORE_CASE)

    private val TRAILING_AMOUNT =
        Regex("""^(.+?)(\s?[-–—:=]\s?|\s)(?:x\s?)?($AMOUNT)\s?($UNIT)?$""", RegexOption.IGNORE_CASE)

    private val LEADING_AMOUNT = Regex("""^(?:x\s?)?($AMOUNT)(.*)$""", RegexOption.IGNORE_CASE)

    private val MIXED_NUMBER = Regex("""(\d+)\s(\d+)\s?/\s?(\d+)""")
    private val FRACTION = Regex("""(\d+)\s?/\s?(\d+)""")

    /** "1,000", "12,500", "1,00,000" (Indian grouping), "1,000,000"; not "1,5" or "0,500". */
    private val THOUSANDS = Regex("""[1-9]\d{0,2}(?:,\d{2,3})*,\d{3}""")

    private val VULGAR_FRACTIONS = mapOf(
        '½' to "1/2", '⅓' to "1/3", '⅔' to "2/3", '¼' to "1/4", '¾' to "3/4",
        '⅕' to "1/5", '⅛' to "1/8", '⅜' to "3/8", '⅝' to "5/8", '⅞' to "7/8",
    )

    /** Hindi unit words and their standard units (Roman spellings such as "kilo" are in [Units] already). */
    private val HINDI_UNITS = mapOf(
        "किलो" to Units.KG, "किग्रा" to Units.KG, "ग्राम" to Units.G,
        "लीटर" to Units.L, "लिटर" to Units.L, "दर्जन" to Units.DOZEN, "पैकेट" to Units.PACK,
    )

    /** Units of the user's own that may also stand before a name ("1 bunch dhaniya", "2 bottles oil"). */
    private val COUNT_WORDS = setOf(
        "bunch", "bunches", "bottle", "bottles", "box", "boxes", "jar", "jars", "tin", "tins",
        "bag", "bags", "pouch", "pouches", "roll", "rolls",
    )
}
