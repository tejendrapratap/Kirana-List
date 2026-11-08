package com.grocerysync.app.text

import com.grocerysync.app.data.ShopSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Which texts a Language choice shows, and the words every area shares. */
class TextsTest {

    @Test
    fun phonesLanguageFollowsThePhone() {
        assertSame(AppTexts.Hindi, Texts.of(AppLanguage.PHONE, "hi"))
        assertSame(AppTexts.Hindi, Texts.of(AppLanguage.PHONE, "HI"))
        assertSame(AppTexts.English, Texts.of(AppLanguage.PHONE, "en"))
        // Any other language, and none, shows English.
        assertSame(AppTexts.English, Texts.of(AppLanguage.PHONE, "mr"))
        assertSame(AppTexts.English, Texts.of(AppLanguage.PHONE, ""))
        assertSame(AppTexts.English, Texts.of(AppLanguage.PHONE, null))
    }

    @Test
    fun aChosenLanguageIsShownWhateverThePhoneSays() {
        for (system in listOf("hi", "en", null)) {
            assertSame(AppTexts.English, Texts.of(AppLanguage.ENGLISH, system))
            assertSame(AppTexts.Hindi, Texts.of(AppLanguage.HINDI, system))
        }
    }

    @Test
    fun unitTestsRunInEnglish() {
        assertSame(AppTexts.English, Texts.current)
    }

    @Test
    fun theRuleSwitchesAndRestores() {
        val rule = UseTextsRule(AppTexts.Hindi)
        val statement = object : Statement() {
            override fun evaluate() {
                assertSame(AppTexts.Hindi, Texts.current)
                throw IllegalStateException("the test failed")
            }
        }
        runCatching { rule.apply(statement, Description.EMPTY).evaluate() }
        assertSame(AppTexts.English, Texts.current)

        assertEquals("1 चीज़", withTexts(AppTexts.Hindi) { Texts.current.common.itemCount(1) })
        assertSame(AppTexts.English, Texts.current)
    }

    @Test
    fun languagesAreStoredByTheirIds() {
        assertEquals(listOf("phone", "en", "hi"), AppLanguage.entries.map { it.id })
        for (language in AppLanguage.entries) assertSame(language, AppLanguage.fromId(language.id))
        assertNull(AppLanguage.fromId("mr"))
        assertNull(AppLanguage.fromId(null))
    }

    @Test
    fun eachLanguageIsNamedInItsOwnScript() {
        assertEquals(AppLanguage.ENGLISH, AppTexts.English.language)
        assertEquals("English", AppTexts.English.languageName)
        assertEquals(AppLanguage.HINDI, AppTexts.Hindi.language)
        assertEquals("हिन्दी", AppTexts.Hindi.languageName)
    }

    @Test
    fun itemCountsArePluralPerLanguage() {
        assertEquals("1 item", EnglishCommonTexts.itemCount(1))
        assertEquals("3 items", EnglishCommonTexts.itemCount(3))
        assertEquals("0 items", EnglishCommonTexts.itemCount(0))
        assertEquals("1 चीज़", HindiCommonTexts.itemCount(1))
        assertEquals("3 चीज़ें", HindiCommonTexts.itemCount(3))
        assertEquals("0 चीज़ें", HindiCommonTexts.itemCount(0))
    }

    @Test
    fun shopSectionsHaveTheirNames() {
        // English shows the stable English names; Hindi the glossary's.
        for (section in ShopSection.entries) assertEquals(section.label, EnglishCommonTexts.sectionName(section))
        assertEquals("सब्ज़ी और फल", HindiCommonTexts.sectionName(ShopSection.SABZI_FRUIT))
        assertEquals("दूध, अंडे और ब्रेड", HindiCommonTexts.sectionName(ShopSection.DAIRY_EGGS_BREAD))
        assertEquals("मसाले", HindiCommonTexts.sectionName(ShopSection.MASALA))
        assertEquals("बाकी", HindiCommonTexts.sectionName(ShopSection.OTHER))
        val hindi = ShopSection.entries.map(HindiCommonTexts::sectionName)
        assertEquals("every section has its own Hindi name", hindi.size, hindi.toSet().size)
        for (section in ShopSection.entries) {
            assertNotEquals(section.name, section.label, HindiCommonTexts.sectionName(section))
        }
    }

    @Test
    fun hindiWordsFollowTheGlossary() {
        val hindi = HindiCommonTexts
        assertEquals("पहले जैसा करें", hindi.undo)
        assertEquals("खरीदना है", hindi.toBuy)
        assertEquals("सारा सामान", hindi.allItems)
        assertEquals("फिर से कोशिश करें", hindi.retry)
        assertEquals("यह फ़ोन", HindiSettingsTexts.thisPhone)
        assertEquals("फ़ोन की भाषा", HindiSettingsTexts.phonesLanguage)
    }
}
