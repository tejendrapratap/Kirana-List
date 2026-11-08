package com.grocerysync.app.notify

import com.grocerysync.app.data.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The list as last seen, kept on this phone between checks. */
class SeenListStoreTest {

    private val prefs = FakeSharedPreferences()

    @Test
    fun nothingSeenBeforeTheFirstWrite() {
        assertNull(SeenListStore(prefs).read())
    }

    @Test
    fun whatIsWrittenIsReadBackAlsoByANewStore() {
        val seen = mapOf("dahi" to "boolean true", "rice" to "quantity 2 kg", "anda \"desi\"" to "quantity 12 ")
        SeenListStore(prefs).write(SeenState(SHEET, seen))
        assertEquals(SeenState(SHEET, seen), SeenListStore(prefs).read())
        // An empty To buy was seen too: that is not "never seen".
        SeenListStore(prefs).write(SeenState(SHEET, emptyMap()))
        assertEquals(SeenState(SHEET, emptyMap()), SeenListStore(prefs).read())
    }

    @Test
    fun aNotificationUpIsKeptWithTheListSeen() {
        val state = SeenState(
            sheet = SHEET,
            seen = mapOf("rice" to "quantity 2 kg"),
            notice = PostedNotice(
                list = mapOf("rice" to "quantity 2 kg", "dahi" to "boolean true"),
                told = mapOf("dahi" to "boolean true"),
            ),
        )
        SeenListStore(prefs).write(state)
        assertEquals(state, SeenListStore(prefs).read())
    }

    @Test
    fun forgottenIsNeverSeen() {
        SeenListStore(prefs).write(SeenState(SHEET, mapOf("dahi" to "boolean true")))
        SeenListStore(prefs).write(null)
        assertNull(SeenListStore(prefs).read())
    }

    @Test
    fun anUnreadableValueCountsAsNeverSeen() {
        prefs.values["seen"] = "not json"
        assertNull(SeenListStore(prefs).read())
        // The list alone, as an earlier 1.6 build kept it: from which sheet is unknown.
        prefs.values["seen"] = "{\"dahi\":\"boolean true\"}"
        assertNull(SeenListStore(prefs).read())
    }

    private companion object {
        const val SHEET = "https://script.google.com/macros/s/home/exec"
    }
}
