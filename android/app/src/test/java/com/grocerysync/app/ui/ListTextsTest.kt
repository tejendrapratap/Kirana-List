package com.grocerysync.app.ui

import com.grocerysync.app.data.FailedChange
import com.grocerysync.app.sync.Protocol
import com.grocerysync.app.text.EnglishListTexts
import com.grocerysync.app.ui.components.appBarTitle
import com.grocerysync.app.ui.components.failedChangesText
import com.grocerysync.app.ui.components.liveStatusText
import com.grocerysync.app.ui.components.syncErrorText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The list screen's English texts that came from the screens into ListTexts (1.6) and that no other test checks
 * word for word: they read as before. The Hindi ones are in ListTextsHindiTest.
 */
class ListTextsTest {

    private val t = EnglishListTexts

    @Test
    fun theAppBarShowsTheBuildsName() {
        assertEquals("Kirana List", appBarTitle("Kirana List"))
        assertEquals("Kirana QA", appBarTitle("Kirana QA"))
    }

    @Test
    fun liveStatusInMinutesAndHours() {
        val now = 1_000_000_000L
        assertEquals("Live", liveStatusText(null, now))
        assertEquals("Live - updated 59s ago", liveStatusText(now - 59_999L, now))
        assertEquals("Live - updated 5 min ago", liveStatusText(now - 5 * 60_000L, now))
        assertEquals("Live - updated 2 h ago", liveStatusText(now - 2 * 3_600_000L, now))
    }

    @Test
    fun snackbars() {
        assertEquals("Got Rice", t.gotItSnackbar("Rice"))
        assertEquals("Deleted Rice", t.deletedSnackbar("Rice"))
        assertEquals("Couldn't save \"Rice\". Check the name and try again.", t.couldNotSave("Rice"))
        assertEquals("\"Rice\" was changed on another phone", t.changedElsewhere("Rice"))
        assertEquals("That item was changed on another phone", t.itemChangedElsewhere)
        assertEquals("Couldn't change \"Rice\". Open it and shorten its name.", t.cantChange("Rice"))
        assertEquals("Couldn't save that change. Please try again.", t.editFailed)
        assertEquals("No app on this phone can share the list", NO_SHARE_APP_TEXT)
    }

    @Test
    fun banners() {
        assertEquals("Something is wrong with the connection to your sheet.", syncErrorText(null))
        assertEquals("Something is wrong with the connection to your sheet.", syncErrorText(" "))
        assertEquals(Protocol.MSG_UNAUTHORIZED, syncErrorText(Protocol.MSG_UNAUTHORIZED))

        assertNull(failedChangesText(emptyList()))
        assertEquals(
            "1 change couldn't be saved: Milk: name too long",
            failedChangesText(listOf(FailedChange("1", "Milk", "name too long"))),
        )
        assertEquals(
            "2 changes couldn't be saved: name too long",
            failedChangesText(listOf(FailedChange("1", " ", "name too long"), FailedChange("2", "Rice", "other"))),
        )
    }

    @Test
    fun rowsForTalkBack() {
        assertEquals("Got it: Dahi", t.gotItDescription("Dahi"))
        assertEquals("Need Dahi", t.needDescription("Dahi"))
        assertEquals("0.5 kg less Rice", t.stepDown("0.5 kg", "Rice"))
        assertEquals("0.5 kg more Rice", t.stepUp("0.5 kg", "Rice"))
        assertEquals("Quantity 2 kg", t.amountDescription("2 kg"))
    }

    @Test
    fun tickedToday() {
        assertEquals("Ticked today (3)", t.tickedToday(3))
        assertEquals("Ticked today, 1 item", t.tickedTodayDescription(1))
        assertEquals("Ticked today, 3 items", t.tickedTodayDescription(3))
        assertEquals("Put back", t.putBack)
        assertEquals("Put back Rice", t.putBackDescription("Rice"))
    }

    @Test
    fun simpleMode() {
        assertEquals("Got it", t.gotIt)
        assertEquals("Tap Add item to add what you need.", t.emptyToBuyHintNewListSimple)
        assertEquals("Everything is ticked off. Tap Add item when you need something.", t.emptyToBuyHintAllTickedSimple)
        assertEquals("Added Dahi - it isn't needed now, so it isn't on To buy", t.addedNotNeededSimple("Dahi"))
    }
}
