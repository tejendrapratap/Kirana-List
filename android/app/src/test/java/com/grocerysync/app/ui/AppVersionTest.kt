package com.grocerysync.app.ui

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
    fun noLineWithoutAVersionName() {
        assertNull(versionText(null))
        assertNull(versionText(""))
        assertNull(versionText("  "))
    }
}
