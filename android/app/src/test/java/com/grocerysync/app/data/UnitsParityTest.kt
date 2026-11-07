package com.grocerysync.app.data

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app cleans units exactly like Code.gs: both test suites check the same table,
 * apps-script/test/fixtures/unit-normalization.json (aliases in any case, whitespace, the 16-character
 * cut that never splits an emoji, custom units kept as typed).
 */
class UnitsParityTest {

    @Test
    fun normalizesEveryCaseOfTheSharedTable() {
        val cases = JSONArray(fixture().readText(Charsets.UTF_8))
        assertTrue(cases.length() > 50)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONArray(i)
            val input = if (case.isNull(0)) null else case.getString(0)
            assertEquals("case $i: $input", case.getString(1), Units.normalize(input))
        }
    }

    /** Found from the module directory (Gradle's working directory for unit tests) or any parent of it. */
    private fun fixture(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, FIXTURE)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("$FIXTURE not found above ${File("").absolutePath}")
    }

    private companion object {
        const val FIXTURE = "apps-script/test/fixtures/unit-normalization.json"
    }
}
