package com.grocerysync.app.ui.components

import com.grocerysync.app.data.ItemKeys
import com.grocerysync.app.data.ItemValue.BoolValue
import com.grocerysync.app.data.TickedEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ticked today's section of To buy: its list keys, and whether it is open when Simple mode is turned on or off. */
class TickedTodaySectionTest {

    private fun ticked(name: String) = TickedEntry(ItemKeys.normalize(name), name, BoolValue(true), BoolValue(false), 0L)

    @Test
    fun noItemNameGivesARowTheHeadersKey() {
        // Two items with one key would crash the list: an item called "Header" was one.
        for (name in listOf("Header", " HEADER ", "header", "item:header", "ticked:header", ":header")) {
            assertNotEquals(name, TICKED_TODAY_HEADER_KEY, tickedTodayRowKey(ticked(name)))
        }
    }

    @Test
    fun rowsOfDifferentItemsHaveDifferentKeys() {
        val keys = listOf("Rice", "Dal", "Header", "header rice").map { tickedTodayRowKey(ticked(it)) }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun itStartsClosedButOpenInSimpleMode() {
        assertFalse(TickedTodayOpen.startIn(simpleMode = false).expandedIn(simpleMode = false))
        assertTrue(TickedTodayOpen.startIn(simpleMode = true).expandedIn(simpleMode = true))
    }

    @Test
    fun turningSimpleModeOnOrOffStartsItOverTheNewModesWay() {
        // Left closed in the usual mode (as it starts), then Simple mode turned on in Settings: open.
        assertTrue(TickedTodayOpen.startIn(simpleMode = false).expandedIn(simpleMode = true))
        assertTrue(TickedTodayOpen(simpleMode = false, expanded = true).expandedIn(simpleMode = true))
        // Left open in Simple mode (as it starts), then Simple mode turned off: closed.
        assertFalse(TickedTodayOpen.startIn(simpleMode = true).expandedIn(simpleMode = false))
        assertFalse(TickedTodayOpen(simpleMode = true, expanded = false).expandedIn(simpleMode = false))
    }

    @Test
    fun withinOneModeItStaysAsThePersonLeftIt() {
        assertTrue(TickedTodayOpen(simpleMode = false, expanded = true).expandedIn(simpleMode = false))
        assertFalse(TickedTodayOpen(simpleMode = true, expanded = false).expandedIn(simpleMode = true))
    }
}
