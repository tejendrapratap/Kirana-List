package com.grocerysync.app.ui.components

import com.grocerysync.app.data.GroceryItem
import com.grocerysync.app.data.ItemKeys
import com.grocerysync.app.text.Texts
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// Pure text for the "who - when" line of All items rows (unit-tested in ItemMetaTextTest), in the language shown.

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS

/** A time this far ahead of the phone's clock still reads "just now" (the phone's clock runs behind the sheet's). */
private const val FUTURE_TOLERANCE_MS = HOUR_MS

/** No-break space: keeps "2 minutes ago" on one line, and the " -" with the name before it. */
private const val NBSP = '\u00A0'

/** Ends a shortened name. */
internal const val ELLIPSIS = "\u2026"

/**
 * When [time] was, in full words, relative to [now] (both epoch millis): "just now" (under a minute),
 * "1 minute ago", "N minutes ago", "1 hour ago", "N hours ago" (under 24 hours), then by calendar day in
 * [zone]: "yesterday", "N days ago" (2 to 6), and after that the date, "on 3 Nov" ("on 3 Nov 2025" in another
 * year). Up to an hour in the future (the phone's clock behind the sheet's) is "just now"; further ahead the
 * date. Null when the sheet has no valid time ([time] <= 0). In the language shown: the words are CommonTexts'
 * relative times and ListTexts.onDate.
 *
 * Doesn't use android.text.format.DateUtils, whose wording differs between Android versions.
 */
internal fun relativeTimeText(time: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String? {
    if (time <= 0L) return null
    val words = Texts.current.common
    val elapsed = now - time
    return when {
        elapsed < -FUTURE_TOLERANCE_MS -> dateText(time, now, zone)
        elapsed < MINUTE_MS -> words.justNow
        elapsed < HOUR_MS -> words.minutesAgo((elapsed / MINUTE_MS).toInt())
        elapsed < DAY_MS -> words.hoursAgo((elapsed / HOUR_MS).toInt())
        else -> {
            val days = ChronoUnit.DAYS.between(localDate(time, zone), localDate(now, zone))
            when {
                // 24 hours or more within one calendar day: a day with an extra hour (clocks going back).
                days < 1 -> words.hoursAgo((elapsed / HOUR_MS).toInt())
                days == 1L -> words.yesterday
                days < 7 -> words.daysAgo(days.toInt())
                else -> dateText(time, now, zone)
            }
        }
    }
}

private fun localDate(time: Long, zone: ZoneId) = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()

private fun dateText(time: Long, now: Long, zone: ZoneId): String {
    val date = localDate(time, zone)
    val otherYear = date.year.takeIf { it != localDate(now, zone).year }
    return Texts.current.list.onDate(date.dayOfMonth, date.monthValue, otherYear)
}

/**
 * The "who - when" line of an All items row: "Added by Home - 2 hours ago" while nobody has changed the item
 * since it was added ([GroceryItem.isUnchangedSinceAdded]), else "Updated by Home - 2 hours ago" (also when
 * the sheet doesn't know when it was added). Without a name "Added 2 hours ago" / "Updated 2 hours ago";
 * without a valid time "Added by Home" / "Updated by Home"; null without either. The words are those of the
 * language shown (CommonTexts.addedBy, ...; Hindi puts the time first when there is no name).
 *
 * [added]: "Added" rather than "Updated". [who] is the name as shown (on one line); [time] the relative time
 * ([relativeTimeText]).
 */
internal data class ItemMetaText(val added: Boolean, val who: String?, val time: String?) {

    /** The whole line with ordinary spaces and the full name: for TalkBack and tests. */
    val text: String get() = displayText(who, keepTimeTogether = false)

    /**
     * The line to lay out, with [name] (default: the full one) in place of [who]. [keepTimeTogether]: the time
     * can't be split across lines and the " -" stays with the name (no-break spaces), so a line can only break
     * before the time or inside "Updated by <name>"; otherwise ordinary spaces wrap anywhere between words.
     * [breakBeforeTime]: the time starts a new line (see [breaksBeforeTime]).
     */
    fun displayText(name: String? = who, keepTimeTogether: Boolean = true, breakBeforeTime: Boolean = false): String {
        val texts = Texts.current
        val space = if (keepTimeTogether) NBSP else ' '
        val shownTime = if (keepTimeTogether) time?.replace(' ', NBSP) else time
        val beforeTime = if (breakBeforeTime) '\n' else ' '
        if (name == null) {
            val verb = if (added) texts.common.added else texts.common.updated
            return shownTime?.let { texts.list.metaWithoutName(verb, it, beforeTime) } ?: verb
        }
        val by = if (added) texts.common.addedBy(name) else texts.common.updatedBy(name)
        return if (shownTime != null) "$by$space-$beforeTime$shownTime" else by
    }

    companion object {
        /** Longer names (only possible when typed into the sheet's Updated By cell) are always shortened. */
        const val MAX_NAME_LENGTH = 40

        /** Null when the row has neither a name nor a valid time. */
        fun of(item: GroceryItem, now: Long, zone: ZoneId = ZoneId.systemDefault()): ItemMetaText? {
            val who = ItemKeys.collapseWhitespace(item.updatedBy).takeIf { it.isNotEmpty() }
                ?.let { shortenName(it, MAX_NAME_LENGTH) }
            val time = relativeTimeText(item.updatedAt, now, zone)
            if (who == null && time == null) return null
            return ItemMetaText(item.isUnchangedSinceAdded, who, time)
        }
    }
}

/**
 * [name] cut to at most [maxLength] characters followed by [ELLIPSIS] (not counted), or unchanged when it is
 * that short. Never splits a surrogate pair; spaces before the ellipsis are dropped.
 */
internal fun shortenName(name: String, maxLength: Int): String {
    if (name.length <= maxLength) return name
    var end = maxLength.coerceAtLeast(0)
    if (end > 0 && Character.isHighSurrogate(name[end - 1])) end--
    return name.substring(0, end).trimEnd() + ELLIPSIS
}

/**
 * The widest time a row's line moves through as the clock goes on: "just now", "1 minute ago" ... "59 minutes
 * ago", then "1 hour ago" ... "23 hours ago", "yesterday", "N days ago" and a date are all narrower. In the
 * language shown (Hindi "59 मिनट पहले" is wider than "23 घंटे पहले" too).
 */
internal val WIDEST_TICKING_TIME: String get() = Texts.current.common.minutesAgo(59)

/**
 * Whether the "who - when" line [meta] always puts its time on a line of its own ("Updated by Home -" / "just
 * now"), so that the row's height (and where ListItem puts the Need switch) doesn't change by itself as the
 * time text moves on ("just now" fits beside the name, "2 minutes ago" might not). True when the line doesn't
 * fit on one line with the widest time it can show ([WIDEST_TICKING_TIME], or its own time when that is wider,
 * e.g. a date in another year). [fitsOnOneLine] lays a text out in the row's width in the widest style the
 * row can have (the highlight of a recent change, in a heavier weight, ends after two minutes).
 */
internal fun breaksBeforeTime(meta: ItemMetaText, fitsOnOneLine: (String) -> Boolean): Boolean {
    val time = meta.time ?: return false
    return listOf(WIDEST_TICKING_TIME, time).distinct().any { !fitsOnOneLine(meta.copy(time = it).displayText()) }
}

/**
 * Lays out the "who - when" line [meta] for one width and returns the layout to draw. [layout] lays a text out
 * in that width (wrapping between words, and inside a word only when the word is wider than a line);
 * [lineCount] and [breaksInsideWord] read a layout. [breakBeforeTime]: the time starts a line of its own (see
 * [breaksBeforeTime]).
 *
 * Wording: the time kept together ([ItemMetaText.displayText]) reads best ("Updated by Home -" / "59 minutes
 * ago"). It is used unless it takes more lines than ordinary wrapping, or the time doesn't fit on a line and a
 * word gets cut ("59 minutes ag" / "o"), as with large text beside a wide stepper. The name: see [fittedName],
 * with [maxLines] lines.
 */
internal fun <T> fitMetaLine(
    meta: ItemMetaText,
    maxLines: Int,
    layout: (String) -> T,
    lineCount: (T) -> Int,
    breaksInsideWord: (T) -> Boolean,
    breakBeforeTime: Boolean = false,
): T {
    fun best(name: String?): T {
        val together = layout(meta.displayText(name, keepTimeTogether = true, breakBeforeTime = breakBeforeTime))
        if (meta.time == null) return together
        val loose = layout(meta.displayText(name, keepTimeTogether = false, breakBeforeTime = breakBeforeTime))
        return if (!breaksInsideWord(together) && lineCount(together) <= lineCount(loose)) together else loose
    }
    val name = meta.who?.let { who -> fittedName(who, maxLines) { lineCount(best(it)) } }
    return best(name)
}

/**
 * Names up to this long are never shortened to save a line ("Home", "Shopper", "Mum's phone"): another line
 * reads better than half a name. Longer names keep at least this many characters.
 */
internal const val MIN_SHORTENED_NAME_LENGTH = 16

/**
 * The name to show in the "who - when" line: [name] itself when the line fits in [maxLines] ([linesWith] counts
 * the lines it takes with a given name), or when the name is short ([MIN_SHORTENED_NAME_LENGTH]). A longer name
 * is shortened just enough to fit [maxLines], keeping at least [MIN_SHORTENED_NAME_LENGTH] characters; when that
 * can't fit (large text on a narrow screen), just enough to fit one more line, and so on. The full name stays
 * when shortening it saves no line.
 */
internal fun fittedName(name: String, maxLines: Int, linesWith: (String) -> Int): String {
    if (name.length <= MIN_SHORTENED_NAME_LENGTH) return name
    val fullLines = linesWith(name)
    for (lines in maxLines until fullLines) {
        val keep = longestFitting(MIN_SHORTENED_NAME_LENGTH, name.length - 1) { length ->
            linesWith(shortenName(name, length)) <= lines
        }
        if (keep > 0) return shortenName(name, keep)
    }
    return name
}

/**
 * The largest n in [min]..[max] (min >= 1) for which [fits] is true, assuming that once it is false for some n
 * it stays false for every larger one (a longer name rarely needs fewer lines); 0 when it fits for none. Binary
 * search: about log2(max - min) calls, and the n it returns always fits.
 */
internal fun longestFitting(min: Int, max: Int, fits: (Int) -> Boolean): Int {
    var low = min
    var high = max
    var best = 0
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (fits(mid)) {
            best = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return best
}
