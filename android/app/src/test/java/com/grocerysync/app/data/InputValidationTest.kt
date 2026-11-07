package com.grocerysync.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputValidationTest {

    // ---- validateName ----

    @Test
    fun acceptsNormalNames() {
        assertNull(InputValidation.validateName("Milk"))
        assertNull(InputValidation.validateName("  Oat milk 2 L  "))
        assertNull(InputValidation.validateName("Crème fraîche"))
        assertNull(InputValidation.validateName("Milk\n")) // surrounding whitespace is trimmed
    }

    @Test
    fun rejectsEmptyNames() {
        assertEquals("Enter an item name", InputValidation.validateName(""))
        assertEquals("Enter an item name", InputValidation.validateName("   "))
        assertEquals("Enter an item name", InputValidation.validateName("\n\t"))
    }

    @Test
    fun limitsLength() {
        assertNull(InputValidation.validateName("a".repeat(100)))
        assertNull(InputValidation.validateName("  " + "a".repeat(100) + "  "))
        assertEquals("Keep it under 100 characters", InputValidation.validateName("a".repeat(101)))
    }

    @Test
    fun rejectsLineBreaks() {
        assertEquals("Item name can't contain line breaks", InputValidation.validateName("Milk\nBread"))
        assertEquals("Item name can't contain line breaks", InputValidation.validateName("Milk\r\nBread"))
        assertEquals("Item name can't contain line breaks", InputValidation.validateName("Milk\u2028Bread"))
    }

    // ---- parseAmount (was parseQuantity: whole numbers only; decimals are allowed since units) ----

    private fun amount(text: String) = InputValidation.parseAmount(text)?.format()

    @Test
    fun parsesValidAmounts() {
        assertEquals("2", amount(" 2 "))
        assertEquals("0", amount("0"))
        assertEquals("999999", amount("999999"))
        assertEquals("7", amount("007"))
        assertEquals("0", amount("000"))
        assertEquals("1.5", amount("1.5"))
        assertEquals("1.5", amount("1,5")) // comma as decimal separator
        assertEquals("0.25", amount("0.25"))
        assertEquals("0.125", amount(".125"))
        assertEquals("2", amount("2."))
        assertEquals("2.25", amount("2.250"))
        assertEquals("999998.5", amount("999998.5"))
    }

    @Test
    fun rejectsInvalidAmounts() {
        assertNull(InputValidation.parseAmount("1000000"))
        assertNull(InputValidation.parseAmount("999999.001"))
        assertNull(InputValidation.parseAmount("999999.999"))
        assertNull(InputValidation.parseAmount("-1"))
        assertNull(InputValidation.parseAmount("+1"))
        assertNull(InputValidation.parseAmount("1.2345")) // at most 3 decimals
        assertNull(InputValidation.parseAmount("1,000.5"))
        assertNull(InputValidation.parseAmount("1.5.2"))
        assertNull(InputValidation.parseAmount("abc"))
        assertNull(InputValidation.parseAmount("2 kg"))
        assertNull(InputValidation.parseAmount(""))
        assertNull(InputValidation.parseAmount("   "))
        assertNull(InputValidation.parseAmount("."))
        assertNull(InputValidation.parseAmount("1 2"))
        assertNull(InputValidation.parseAmount("99999999999999999999999"))
        assertNull(InputValidation.parseAmount("1e3"))
    }

    @Test
    fun rejectsNonAsciiDigits() {
        assertNull(InputValidation.parseAmount("٣")) // Arabic-Indic three
        assertNull(InputValidation.parseAmount("２")) // full-width two
        assertNull(InputValidation.parseAmount("१२")) // Devanagari one two
    }

    @Test
    fun cleansTypedUnits() {
        assertEquals("kg", InputValidation.cleanUnit("  Kgs "))
        assertEquals("bunch", InputValidation.cleanUnit("bunch"))
        assertEquals("", InputValidation.cleanUnit("  "))
    }

    // ---- validateWebAppUrl ----

    @Test
    fun acceptsAppsScriptExecUrls() {
        assertEquals(UrlCheck.Ok, InputValidation.validateWebAppUrl("https://script.google.com/macros/s/AKfycbx_123-abc/exec"))
        assertEquals(UrlCheck.Ok, InputValidation.validateWebAppUrl("  https://script.google.com/macros/s/AKfycbx/exec  "))
        assertEquals(
            UrlCheck.Ok,
            InputValidation.validateWebAppUrl("https://script.google.com/a/macros/example.com/s/AKfycbx/exec"),
        )
    }

    @Test
    fun warnsAboutDevUrls() {
        assertEquals(
            UrlCheck.Warning(InputValidation.URL_DEV_WARNING),
            InputValidation.validateWebAppUrl("https://script.google.com/macros/s/AKfycbx/dev"),
        )
        assertEquals(
            "This is a test (/dev) URL. Use the Web app URL ending in /exec from Deploy > Manage deployments.",
            InputValidation.URL_DEV_WARNING,
        )
    }

    @Test
    fun warnsAboutPlainHttp() {
        assertEquals(
            UrlCheck.Warning("Not secure - only use http:// for local testing."),
            InputValidation.validateWebAppUrl("http://10.0.2.2:8080/macros/s/test/exec"),
        )
    }

    @Test
    fun warnsAboutOtherHttpsUrls() {
        val expected = UrlCheck.Warning("This doesn't look like an Apps Script web app URL (it should end in /exec).")
        assertEquals(expected, InputValidation.validateWebAppUrl("https://example.com/exec"))
        assertEquals(expected, InputValidation.validateWebAppUrl("https://script.google.com/macros/s/AKfycbx/edit"))
        assertEquals(expected, InputValidation.validateWebAppUrl("https://docs.google.com/spreadsheets/d/abc/edit"))
        assertEquals(expected, InputValidation.validateWebAppUrl("https://script.google.com/home/projects/abc"))
    }

    @Test
    fun rejectsUnusableUrls() {
        assertTrue(InputValidation.validateWebAppUrl("") is UrlCheck.Invalid)
        assertTrue(InputValidation.validateWebAppUrl("   ") is UrlCheck.Invalid)
        assertTrue(InputValidation.validateWebAppUrl("not a url") is UrlCheck.Invalid)
        assertTrue(InputValidation.validateWebAppUrl("script.google.com/macros/s/x/exec") is UrlCheck.Invalid)
        assertTrue(InputValidation.validateWebAppUrl("https://") is UrlCheck.Invalid)
        assertEquals(
            UrlCheck.Invalid(InputValidation.URL_BAD_SCHEME),
            InputValidation.validateWebAppUrl("ftp://script.google.com/macros/s/x/exec"),
        )
    }
}
