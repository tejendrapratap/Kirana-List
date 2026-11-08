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
        SeenListStore(prefs).write(seen)
        assertEquals(seen, SeenListStore(prefs).read())
        // An empty To buy was seen too: that is not "never seen".
        SeenListStore(prefs).write(emptyMap())
        assertEquals(emptyMap<String, String>(), SeenListStore(prefs).read())
    }

    @Test
    fun anUnreadableValueCountsAsNeverSeen() {
        prefs.values["seen"] = "not json"
        assertNull(SeenListStore(prefs).read())
    }
}
