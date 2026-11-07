package com.grocerysync.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class ItemKeysTest {

    @Test
    fun trimsAndLowercases() {
        assertEquals("milk", ItemKeys.normalize("  Milk  "))
        assertEquals("milk", ItemKeys.normalize("MILK"))
    }

    @Test
    fun collapsesInternalWhitespace() {
        assertEquals("oat milk", ItemKeys.normalize("Oat   Milk"))
        assertEquals("oat milk", ItemKeys.normalize("Oat\t\tMilk\n"))
        assertEquals("oat milk 2 l", ItemKeys.normalize(" oat \n milk  2   L "))
    }

    @Test
    fun treatsUnicodeSpacesLikeJavaScript() {
        // No-break space, ideographic space, narrow no-break space and BOM are whitespace in JS.
        assertEquals("oat milk", ItemKeys.normalize("\u00A0Oat\u00A0\u00A0Milk\u3000"))
        assertEquals("oat milk", ItemKeys.normalize("\uFEFFOat\u202FMilk"))
        assertEquals("oat milk", ItemKeys.normalize("Oat\u2003Milk\u2028"))
    }

    @Test
    fun lowercasesUnicodeLetters() {
        assertEquals("äpfel", ItemKeys.normalize("ÄPFEL"))
        assertEquals("crème brûlée", ItemKeys.normalize("Crème  Brûlée"))
        assertEquals("γάλα", ItemKeys.normalize("ΓΆΛΑ"))
        assertEquals("молоко", ItemKeys.normalize("Молоко"))
        assertEquals("寿司", ItemKeys.normalize(" 寿司 "))
    }

    @Test
    fun ignoresDefaultLocale() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("milk", ItemKeys.normalize("MILK"))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun differentSpellingsShareAKey() {
        assertEquals(ItemKeys.normalize("Oat Milk"), ItemKeys.normalize("  oat   MILK "))
    }

    @Test
    fun emptyAndBlank() {
        assertEquals("", ItemKeys.normalize(""))
        assertEquals("", ItemKeys.normalize(" \t\n "))
    }

    @Test
    fun collapseWhitespaceKeepsCaseLikeCodeGsDeviceNames() {
        // Code.gs stores "Updated By" as device.replace(/\s+/g, ' ').trim().
        assertEquals("Mum's Phone", ItemKeys.collapseWhitespace("  Mum's \t Phone\n"))
        assertEquals("Pixel 9a", ItemKeys.collapseWhitespace("Pixel\u00A0\u00A09a"))
        assertEquals("", ItemKeys.collapseWhitespace(" \u3000 "))
    }

    @Test
    fun cleanTrimsButKeepsInnerSpacing() {
        assertEquals("Oat  Milk", ItemKeys.clean("\u00A0 Oat  Milk \n"))
        assertEquals("Milk", ItemKeys.clean("Milk"))
        assertEquals("", ItemKeys.clean("   "))
    }

    @Test
    fun singleLineOnlyChangesNamesWithLineBreaks() {
        assertEquals("Milk 2%", ItemKeys.singleLine("Milk\n2%"))
        assertEquals("Milk 2% low fat", ItemKeys.singleLine("Milk\r\n2%  low fat"))
        assertEquals("Milk 2%", ItemKeys.singleLine("Milk\u20282%"))
        assertEquals("Oat  Milk", ItemKeys.singleLine("Oat  Milk"))
        assertEquals(ItemKeys.normalize("Milk\n\n2%"), ItemKeys.normalize(ItemKeys.singleLine("Milk\n\n2%")))
    }
}
