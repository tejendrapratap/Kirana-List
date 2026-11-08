package com.grocerysync.app.ui.components

import com.grocerysync.app.data.GroceryItem
import com.grocerysync.app.data.ItemValue.BoolValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ItemMetaTextTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0, zone: ZoneId = this.zone): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    /** Saturday 7 Nov 2026, 18:00. */
    private val now = at(2026, 11, 7, 18)

    private fun ago(millis: Long) = relativeTimeText(now - millis, now, zone)

    private val minute = 60_000L
    private val hour = 60 * minute

    // ---- relativeTimeText ----

    @Test
    fun noValidTimeHasNoText() {
        assertNull(relativeTimeText(0L, now, zone))
        assertNull(relativeTimeText(-5L, now, zone))
    }

    @Test
    fun underAMinuteIsJustNow() {
        assertEquals("just now", ago(0L))
        assertEquals("just now", ago(59_999L))
    }

    @Test
    fun minutesInFullWords() {
        assertEquals("1 minute ago", ago(minute))
        assertEquals("1 minute ago", ago(2 * minute - 1))
        assertEquals("2 minutes ago", ago(2 * minute))
        assertEquals("14 minutes ago", ago(14 * minute + 30_000L))
        assertEquals("59 minutes ago", ago(hour - 1))
    }

    @Test
    fun hoursInFullWordsForTheLast24Hours() {
        assertEquals("1 hour ago", ago(hour))
        assertEquals("1 hour ago", ago(2 * hour - 1))
        assertEquals("2 hours ago", ago(2 * hour))
        assertEquals("23 hours ago", ago(24 * hour - 1))
        // Yesterday evening seen this morning is still counted in hours: under 24 hours ago.
        assertEquals("9 hours ago", relativeTimeText(at(2026, 11, 6, 23), at(2026, 11, 7, 8), zone))
    }

    @Test
    fun thenByCalendarDay() {
        assertEquals("yesterday", relativeTimeText(at(2026, 11, 6, 18), now, zone))
        assertEquals("yesterday", relativeTimeText(at(2026, 11, 6, 0, 30), now, zone))
        assertEquals("2 days ago", relativeTimeText(at(2026, 11, 5, 23, 59), now, zone))
        assertEquals("6 days ago", relativeTimeText(at(2026, 11, 1, 9), now, zone))
        // 25 hours ago, but the day before yesterday.
        assertEquals("2 days ago", relativeTimeText(at(2026, 11, 5, 23), at(2026, 11, 7, 0), zone))
    }

    @Test
    fun aWeekOrMoreShowsTheDate() {
        assertEquals("on 31 Oct", relativeTimeText(at(2026, 10, 31, 18), now, zone))
        assertEquals("on 2 Sep", relativeTimeText(at(2026, 9, 2, 7), now, zone))
        assertEquals("on 1 Jan", relativeTimeText(at(2026, 1, 1, 0), now, zone))
        assertEquals("on 3 Nov 2025", relativeTimeText(at(2025, 11, 3, 12), now, zone))
        assertEquals("on 31 Dec 2025", relativeTimeText(at(2025, 12, 31, 23), at(2026, 1, 9, 10), zone))
    }

    @Test
    fun theCalendarDayIsTheLocalOne() {
        // 23:30 on 6 Nov in India is already 7 Nov in Tokyo; 25 hours later it is 8 Nov in both.
        val time = at(2026, 11, 6, 23, 30)
        val later = time + 25 * hour
        assertEquals("2 days ago", relativeTimeText(time, later, zone))
        assertEquals("yesterday", relativeTimeText(time, later, ZoneId.of("Asia/Tokyo")))
    }

    @Test
    fun aDayWithAnExtraHourCountsInHours() {
        // London went back from summer time on 25 Oct 2026: that day had 25 hours.
        val london = ZoneId.of("Europe/London")
        val time = at(2026, 10, 25, 0, 10, london)
        val later = at(2026, 10, 25, 23, 50, london)
        assertEquals(24 * hour + 40 * minute, later - time)
        assertEquals("24 hours ago", relativeTimeText(time, later, london))
    }

    @Test
    fun aLittleInTheFutureIsJustNowFurtherIsTheDate() {
        // The phone's clock can run behind the sheet's.
        assertEquals("just now", relativeTimeText(now + 5 * minute, now, zone))
        assertEquals("just now", relativeTimeText(now + hour, now, zone))
        assertEquals("on 7 Nov", relativeTimeText(now + 2 * hour, now, zone))
        assertEquals("on 1 Jan 2027", relativeTimeText(at(2027, 1, 1, 9), now, zone))
    }

    // ---- ItemMetaText ----

    private fun item(by: String, updatedAt: Long, addedAt: Long = 0L, pending: Boolean = false) =
        GroceryItem("rice", "Rice", BoolValue(true), updatedAt, by, pending, addedAt)

    private fun meta(item: GroceryItem) = ItemMetaText.of(item, now, zone)?.text

    @Test
    fun addedByWhileUnchangedSinceAdded() {
        val updatedAt = now - 2 * hour
        assertEquals("Added by Home - 2 hours ago", meta(item("Home", updatedAt, addedAt = updatedAt)))
        // The sheet stores times to the second.
        assertEquals("Added by Home - 2 hours ago", meta(item("Home", updatedAt, addedAt = updatedAt - 999L)))
        assertEquals("Added by Home - 2 hours ago", meta(item("Home", updatedAt, addedAt = updatedAt + 999L)))
    }

    @Test
    fun updatedByOnceChangedOrWhenAddedAtIsUnknown() {
        val updatedAt = now - 2 * hour
        assertEquals("Updated by Home - 2 hours ago", meta(item("Home", updatedAt, addedAt = updatedAt - 1_000L)))
        assertEquals("Updated by Home - 2 hours ago", meta(item("Home", updatedAt, addedAt = updatedAt + 1_000L)))
        assertEquals("Updated by Home - 2 hours ago", meta(item("Home", updatedAt, addedAt = now - 50 * hour)))
        assertEquals("Updated by Home - 2 hours ago", meta(item("Home", updatedAt, addedAt = 0L)))
    }

    @Test
    fun itemAddedOnThisPhoneNotSyncedYet() {
        // What ListLogic.overlay gives a new local item: Added At = Updated At = the change's time.
        assertEquals("Added by Shop - just now", meta(item("Shop", now, addedAt = now, pending = true)))
    }

    @Test
    fun withoutANameOrATime() {
        val updatedAt = now - 3 * minute
        assertEquals("Added 3 minutes ago", meta(item("", updatedAt, addedAt = updatedAt)))
        assertEquals("Updated 3 minutes ago", meta(item("  ", updatedAt)))
        assertEquals("Updated by Sheet", meta(item("Sheet", 0L)))
        assertEquals("Updated by Sheet", meta(item("Sheet", 0L, addedAt = now)))
        assertNull(meta(item(" ", 0L)))
        assertEquals("Added by Home", ItemMetaText(added = true, "Home", null).text)
        assertEquals("Updated on 3 Nov", ItemMetaText(added = false, null, "on 3 Nov").text)
    }

    @Test
    fun nameIsShownOnOneLineAndVeryLongNamesAreCut() {
        assertEquals("Updated by Home phone - yesterday", meta(item(" Home\n  phone ", now - 25 * hour)))
        val long = "Phone of somebody with a really long name, typed into the sheet"
        val shown = ItemMetaText.of(item(long, now - 25 * hour), now, zone)!!
        assertEquals(long.take(40).trimEnd() + ELLIPSIS, shown.who)
        assertEquals("Updated by ${long.take(40).trimEnd()}$ELLIPSIS - yesterday", shown.text)
    }

    @Test
    fun displayTextKeepsTheTimeInOnePieceAndTheDashWithTheName() {
        val meta = ItemMetaText(added = false, "Home", "2 minutes ago")
        assertEquals("Updated by Home - 2 minutes ago", meta.text)
        assertEquals("Updated by Home\u00A0- 2\u00A0minutes\u00A0ago", meta.displayText())
        // The only ordinary spaces are inside "Updated by Home" and after the dash: a line can only break there.
        assertEquals(listOf("Updated", "by", "Home\u00A0-", "2\u00A0minutes\u00A0ago"), meta.displayText().split(' '))
        assertEquals("Updated by Ho$ELLIPSIS\u00A0- 2\u00A0minutes\u00A0ago", meta.displayText("Ho$ELLIPSIS"))
        assertEquals("Added on\u00A03\u00A0Nov", ItemMetaText(added = true, null, "on 3 Nov").displayText())
        assertEquals("Updated by Home", ItemMetaText(added = false, "Home", null).displayText())
    }

    // ---- fitting helpers ----

    @Test
    fun shortenNameCutsAndAddsAnEllipsis() {
        assertEquals("Home", shortenName("Home", 4))
        assertEquals("Home", shortenName("Home", 40))
        assertEquals("Gra$ELLIPSIS", shortenName("Grandma", 3))
        // No space before the ellipsis.
        assertEquals("Gra$ELLIPSIS", shortenName("Gra ndma", 4))
        // Never half of an emoji.
        val emoji = "ab\uD83D\uDE00cd"
        assertEquals("ab$ELLIPSIS", shortenName(emoji, 3))
        assertEquals("ab\uD83D\uDE00$ELLIPSIS", shortenName(emoji, 4))
        assertEquals(ELLIPSIS, shortenName("Home", 0))
    }

    /** A line laid out by the monospace model: its lines (no-break spaces shown as spaces), and whether a word was cut. */
    private class Laid(val lines: List<String>, val brokeWord: Boolean)

    /**
     * Lays [text] out in a column [width] characters wide like Android's line breaker: it wraps at ordinary spaces
     * only (never at a no-break space), starts a new line at a line break, and cuts a word only when the word is
     * wider than the column.
     */
    private fun layOut(text: String, width: Int): Laid {
        if ('\n' in text) {
            val parts = text.split('\n').map { layOut(it, width) }
            return Laid(parts.flatMap { it.lines }, parts.any { it.brokeWord })
        }
        val lines = mutableListOf<String>()
        var line = ""
        var brokeWord = false
        for (word in text.split(' ')) {
            val joined = if (line.isEmpty()) word else "$line $word"
            if (joined.length <= width) {
                line = joined
                continue
            }
            if (line.isNotEmpty()) lines += line
            var rest = word
            while (rest.length > width) {
                lines += rest.take(width)
                rest = rest.drop(width)
                brokeWord = true
            }
            line = rest
        }
        lines += line
        return Laid(lines.map { it.replace('\u00A0', ' ') }, brokeWord)
    }

    private fun fit(meta: ItemMetaText, width: Int): Laid =
        fitMetaLine(meta, maxLines = 2, layout = { layOut(it, width) }, lineCount = { it.lines.size }, breaksInsideWord = { it.brokeWord })

    private val home = ItemMetaText(added = false, "Home", "59 minutes ago")
    private val grandma = ItemMetaText(added = false, "Grandma's kitchen phone", "59 minutes ago")

    @Test
    fun theTimeStaysInOnePieceWhenThatCostsNoLine() {
        // About 21 characters fit beside a quantity stepper on a 360dp phone.
        assertEquals(listOf("Updated by Home -", "59 minutes ago"), fit(home, 21).lines)
        assertEquals(listOf("Updated by Home - 59 minutes ago"), fit(home, 40).lines)
        assertEquals(listOf("Updated by Grandma's kitchen", "phone - 59 minutes ago"), fit(grandma, 30).lines)
    }

    @Test
    fun otherwiseTheTimeWrapsLikeAnyText() {
        // Kept together it would take three lines: "Updated by" / "Home -" / "59 minutes ago".
        assertEquals(listOf("Updated by Home", "- 59 minutes ago"), fit(home, 16).lines)
        // Kept together the time doesn't fit on a line at all and would be cut ("59 minutes a" / "go").
        val narrow = fit(home, 12)
        assertEquals(listOf("Updated by", "Home - 59", "minutes ago"), narrow.lines)
        assertFalse(narrow.brokeWord)
    }

    @Test
    fun aLongNameIsShortenedJustEnoughForTwoLines() {
        assertEquals(3, layOut(grandma.displayText(), 24).lines.size)
        assertEquals(listOf("Updated by Grandma's", "kitche$ELLIPSIS - 59 minutes ago"), fit(grandma, 24).lines)
        // Two lines would need it shorter than 16 characters: it keeps its third line instead.
        assertEquals(listOf("Updated by Grandma's", "kitchen phone -", "59 minutes ago"), fit(grandma, 21).lines)
    }

    @Test
    fun shortNamesAreNeverCut() {
        // About 14 characters a line (large text beside a wide stepper): two lines can't work with any name.
        assertEquals(listOf("Updated by", "Home -", "59 minutes ago"), fit(home, 14).lines)
        assertEquals(listOf("Updated by", "Shopper -", "23 hours ago"), fit(ItemMetaText(added = false, "Shopper", "23 hours ago"), 14).lines)
        val sixteen = ItemMetaText(added = false, "Tarun's Pixel 8a", "2 minutes ago")
        assertEquals(listOf("Updated by", "Tarun's Pixel", "8a -", "2 minutes ago"), fit(sixteen, 14).lines)
    }

    @Test
    fun whenTwoLinesCantWorkALongNameIsOnlyCutToSaveLines() {
        assertEquals(5, layOut(grandma.displayText(), 14).lines.size)
        assertEquals(listOf("Updated by", "Grandma's", "kitchen pho$ELLIPSIS -", "59 minutes ago"), fit(grandma, 14).lines)
    }

    @Test
    fun theTimeIsNeverCut() {
        val names = listOf("Home", "Shop", "Grandma's kitchen phone", "Phone of somebody with a really long name")
        for (width in 10..60) {
            for (name in names) {
                val laid = fit(ItemMetaText(added = false, name, "59 minutes ago"), width)
                val shown = laid.lines.joinToString(" ")
                assertTrue(shown, shown.startsWith("Updated by ") && shown.endsWith(" - 59 minutes ago"))
                assertFalse(shown, laid.brokeWord)
                if (width >= 26) assertTrue("$width: $shown", laid.lines.size <= 2)
                if (name.length <= MIN_SHORTENED_NAME_LENGTH) assertTrue(shown, shown.startsWith("Updated by $name - "))
            }
        }
    }

    @Test
    fun withoutATimeOrANameThereIsNothingToChoose() {
        assertEquals(listOf("Updated by Home"), fit(ItemMetaText(added = false, "Home", null), 21).lines)
        assertEquals(listOf("Added just now"), fit(ItemMetaText(added = true, null, "just now"), 21).lines)
    }

    // ---- the line count doesn't move with the clock ----

    private fun fitsOnOneLine(width: Int): (String) -> Boolean = { layOut(it, width).lines.size <= 1 }

    /** What the row shows at [width] for the time [time], deciding the break as MetaLine does. */
    private fun rowAt(meta: ItemMetaText, time: String, width: Int): Laid {
        val now = meta.copy(time = time)
        return fitMetaLine(
            now, maxLines = 2, layout = { layOut(it, width) }, lineCount = { it.lines.size }, breaksInsideWord = { it.brokeWord },
            breakBeforeTime = breaksBeforeTime(now, fitsOnOneLine(width)),
        )
    }

    private val times = listOf("just now", "1 minute ago", "2 minutes ago", "59 minutes ago", "1 hour ago", "23 hours ago", "yesterday", "6 days ago", "on 3 Nov")

    @Test
    fun theTimeGetsALineOfItsOwnWhenTheWidestTimeWouldNotFitBesideTheName() {
        // 30 characters beside a Need switch: "Updated by Home - 59 minutes ago" (32) doesn't fit, "- just now" does.
        val updated = ItemMetaText(added = false, "Home", "just now")
        assertTrue(breaksBeforeTime(updated, fitsOnOneLine(30)))
        assertEquals(listOf("Updated by Home -", "just now"), rowAt(updated, "just now", 30).lines)
        assertEquals(listOf("Updated by Home -", "2 minutes ago"), rowAt(updated, "2 minutes ago", 30).lines)
        // Room for the widest: always one line.
        assertFalse(breaksBeforeTime(updated, fitsOnOneLine(32)))
        assertEquals(listOf("Updated by Home - just now"), rowAt(updated, "just now", 32).lines)
        // Without a name or a time.
        assertTrue(breaksBeforeTime(ItemMetaText(added = true, null, "just now"), fitsOnOneLine(16)))
        assertEquals(listOf("Added", "just now"), rowAt(ItemMetaText(added = true, null, "just now"), "just now", 16).lines)
        assertFalse(breaksBeforeTime(ItemMetaText(added = false, "Home", null), fitsOnOneLine(10)))
        // A date in another year wider than the widest ticking time decides for itself.
        assertTrue(breaksBeforeTime(ItemMetaText(added = false, "Home", "on 30 Nov 2025 at noon"), fitsOnOneLine(32)))
    }

    @Test
    fun aRowKeepsItsNumberOfLinesAsTheTimeMovesOn() {
        val names = listOf("Home", "Shop", "Grandma's kitchen phone", "Phone of somebody with a really long name")
        for (width in 14..60) {
            for (added in listOf(true, false)) {
                for (name in names) {
                    val meta = ItemMetaText(added, name, "just now")
                    val counts = times.map { rowAt(meta, it, width).lines.size }.toSet()
                    assertEquals("${meta.text} at $width: $counts", 1, counts.size)
                    for (time in times) {
                        val laid = rowAt(meta, time, width)
                        assertFalse(laid.lines.toString(), laid.brokeWord)
                        assertTrue(laid.lines.toString(), laid.lines.joinToString(" ").endsWith(" - $time"))
                    }
                }
            }
        }
    }

    @Test
    fun longestFittingFindsTheLongestThatFits() {
        assertEquals(7, longestFitting(1, 10) { it <= 7 })
        assertEquals(10, longestFitting(1, 10) { true })
        assertEquals(0, longestFitting(1, 10) { false })
        assertEquals(0, longestFitting(1, 0) { true })
        assertEquals(1, longestFitting(1, 1) { true })
        assertEquals(0, longestFitting(16, 22) { it <= 12 })
        assertEquals(16, longestFitting(16, 22) { it <= 16 })
        var calls = 0
        assertEquals(23, longestFitting(1, 39) { calls++; it <= 23 })
        assertTrue("binary search, not one try per length: $calls", calls <= 6)
    }
}
