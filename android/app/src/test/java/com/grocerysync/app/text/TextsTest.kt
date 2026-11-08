package com.grocerysync.app.text

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshots.Snapshot
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
    fun whoAndWhenInEnglishAreTheWordsAllItemsShows() {
        val english = EnglishCommonTexts
        assertEquals("Added by Home", english.addedBy("Home"))
        assertEquals("Updated by Mum's phone", english.updatedBy("Mum's phone"))
        assertEquals("Added", english.added)
        assertEquals("Updated", english.updated)
        assertEquals("just now", english.justNow)
        assertEquals("1 minute ago", english.minutesAgo(1))
        assertEquals("59 minutes ago", english.minutesAgo(59))
        assertEquals("1 hour ago", english.hoursAgo(1))
        assertEquals("2 hours ago", english.hoursAgo(2))
        assertEquals("yesterday", english.yesterday)
        assertEquals("1 day ago", english.daysAgo(1))
        assertEquals("3 days ago", english.daysAgo(3))
    }

    @Test
    fun whoAndWhenInHindi() {
        withTexts(AppTexts.Hindi) {
            val common = Texts.current.common
            assertEquals("Home ने जोड़ा", common.addedBy("Home"))
            assertEquals("Mummy ने बदला", common.updatedBy("Mummy"))
            assertEquals("जोड़ा गया", common.added)
            assertEquals("बदला गया", common.updated)
            assertEquals("अभी", common.justNow)
            // पहले (ago) takes the same form for one and many.
            assertEquals("1 मिनट पहले", common.minutesAgo(1))
            assertEquals("5 मिनट पहले", common.minutesAgo(5))
            assertEquals("1 घंटे पहले", common.hoursAgo(1))
            assertEquals("2 घंटे पहले", common.hoursAgo(2))
            assertEquals("कल", common.yesterday)
            assertEquals("1 दिन पहले", common.daysAgo(1))
            assertEquals("3 दिन पहले", common.daysAgo(3))
        }
        assertSame(AppTexts.English, Texts.current)
    }

    @Test
    fun shareTitlesCountTheItemsPerLanguage() {
        // English as Share writes it today.
        assertEquals("Grocery list (1 item)", EnglishCommonTexts.shareTitle(1))
        assertEquals("Grocery list (3 items)", EnglishCommonTexts.shareTitle(3))
        assertEquals("Grocery list (0 items)", EnglishCommonTexts.shareTitle(0))
        assertEquals("किराने की लिस्ट (1 चीज़)", HindiCommonTexts.shareTitle(1))
        assertEquals("किराने की लिस्ट (3 चीज़ें)", HindiCommonTexts.shareTitle(3))
        assertEquals("किराने की लिस्ट (3 चीज़ें)", withTexts(AppTexts.Hindi) { Texts.current.common.shareTitle(3) })
    }

    @Test
    fun textsWorkedOutFromTheCurrentOnesFollowALanguageChange() {
        // As a derivedStateOf in a view model, which outlives the activity's recreate(): worked out once, then read
        // again after the language changed, without any of its other inputs changing.
        val undo = derivedStateOf { Texts.current.common.undo }
        assertEquals("Undo", undo.value)
        nextFrame()
        withTexts(AppTexts.Hindi) {
            assertEquals("पहले जैसा करें", undo.value)
            nextFrame()
        }
        assertEquals("Undo", undo.value)
    }

    /**
     * What passes on the phone between a screen reading its texts and someone tapping another language: at least a
     * frame, and Compose takes a snapshot for each one. (Read and changed again within one global snapshot, without
     * that, a state may be overwritten in place, which a derivedStateOf doesn't notice.)
     */
    private fun nextFrame() = Snapshot.takeSnapshot().dispose()

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
