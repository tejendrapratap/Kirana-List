package com.grocerysync.app.ui

import com.grocerysync.app.text.AppTexts
import com.grocerysync.app.text.withTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppVersionTest {

    @Test
    fun showsTheVersionName() {
        assertEquals("Version 1.2", versionText("1.2"))
        assertEquals("Version 1.1", versionText(" 1.1 "))
    }

    @Test
    fun showsTheVersionNameInTheLanguageShown() {
        assertEquals("वर्ज़न 1.6", withTexts(AppTexts.Hindi) { versionText(" 1.6 ") })
        assertEquals("Version 1.6", versionText("1.6"))
    }

    @Test
    fun noLineWithoutAVersionName() {
        assertNull(versionText(null))
        assertNull(versionText(""))
        assertNull(versionText("  "))
    }
}
