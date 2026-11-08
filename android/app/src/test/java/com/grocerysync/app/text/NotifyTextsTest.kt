package com.grocerysync.app.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The change notifications' texts: English as designed, Hindi complete, with its plurals and the shared words. */
class NotifyTextsTest {

    private val english = EnglishNotifyTexts
    private val hindi = HindiNotifyTexts

    @Test
    fun english() {
        assertEquals("Changes to To buy", english.channelName)
        assertEquals("When someone else adds to To buy while the app is closed", english.channelDescription)
        assertEquals("Mummy", english.people(listOf("Mummy")))
        assertEquals("Mummy and Shop", english.people(listOf("Mummy", "Shop")))
        assertEquals("Mummy, Shop and Papa", english.people(listOf("Mummy", "Shop", "Papa")))
        assertEquals("Mummy, Shop and 3 others", english.people(listOf("Mummy", "Shop", "Papa", "Dadi", "Chotu")))
        assertEquals("Mummy added 1 item to To buy", english.addedTitle("Mummy", 1))
        assertEquals("Mummy and Shop added 5 items to To buy", english.addedTitle("Mummy and Shop", 5))
        assertEquals("Mummy changed To buy", english.changedTitle("Mummy"))
        assertEquals("+3 more", english.more(3))
        assertEquals("Get a notification when someone adds to To buy?", english.offerQuestion)
        assertEquals("Turn on", english.turnOn)
        assertEquals("Not now", english.notNow)
    }

    @Test
    fun hindiWithItsPlurals() {
        assertEquals("Mummy ने \"ख़रीदना है\" में 1 चीज़ जोड़ी", hindi.addedTitle("Mummy", 1))
        assertEquals("Mummy ने \"ख़रीदना है\" में 3 चीज़ें जोड़ीं", hindi.addedTitle("Mummy", 3))
        assertEquals("Mummy ने \"ख़रीदना है\" में बदलाव किया", hindi.changedTitle("Mummy"))
        assertEquals("Mummy और Shop", hindi.people(listOf("Mummy", "Shop")))
        assertEquals("Mummy, Shop और Papa", hindi.people(listOf("Mummy", "Shop", "Papa")))
        assertEquals("Mummy, Shop और 2 अन्य", hindi.people(listOf("Mummy", "Shop", "Papa", "Dadi")))
        assertEquals("+3 और", hindi.more(3))
        assertEquals("कोई \"ख़रीदना है\" में कुछ जोड़े, तो सूचना चाहिए?", hindi.offerQuestion)
    }

    @Test
    fun theyUseTheSharedWords() {
        // To buy as the list's tab calls it, and the item counts of CommonTexts.
        assertTrue(english.addedTitle("Mummy", 3).endsWith(EnglishCommonTexts.toBuy))
        assertTrue(english.addedTitle("Mummy", 3).contains(EnglishCommonTexts.itemCount(3)))
        assertTrue(hindi.addedTitle("Mummy", 3).contains("\"${HindiCommonTexts.toBuy}\""))
        assertTrue(hindi.addedTitle("Mummy", 3).contains(HindiCommonTexts.itemCount(3)))
        assertTrue(hindi.offerQuestion.contains("\"${HindiCommonTexts.toBuy}\""))
    }

    @Test
    fun everyFixedTextHasItsOwnHindi() {
        val getters = NotifyTexts::class.java.methods.filter {
            it.parameterCount == 0 && it.returnType == String::class.java
        }
        assertEquals(getters.map { it.name }.toString(), 5, getters.size)
        for (getter in getters) {
            val inHindi = getter.invoke(hindi) as String
            assertNotEquals(getter.name, getter.invoke(english), inHindi)
            assertTrue("${getter.name}: $inHindi", inHindi.any { it in 'ऀ'..'ॿ' })
        }
    }

    @Test
    fun theCurrentTextsAreTheLanguageShown() {
        assertEquals("Turn on", Texts.current.notify.turnOn)
        assertEquals("चालू करें", withTexts(AppTexts.Hindi) { Texts.current.notify.turnOn })
    }
}
