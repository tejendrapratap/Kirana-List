package com.grocerysync.app.ui

import com.grocerysync.app.data.Amount
import com.grocerysync.app.data.FailedChange
import com.grocerysync.app.data.GroceryItem
import com.grocerysync.app.data.ItemValue
import com.grocerysync.app.data.ListLogic
import com.grocerysync.app.data.ShopSection
import com.grocerysync.app.data.Units
import com.grocerysync.app.sync.Protocol
import com.grocerysync.app.sync.PushProblem
import com.grocerysync.app.text.AppTexts
import com.grocerysync.app.text.HindiListTexts
import com.grocerysync.app.text.Texts
import com.grocerysync.app.text.UseTextsRule
import com.grocerysync.app.ui.components.ItemMetaText
import com.grocerysync.app.ui.components.PUSH_WARNING_TITLE
import com.grocerysync.app.ui.components.SCRIPT_UPDATE_TITLE
import com.grocerysync.app.ui.components.WIDEST_TICKING_TIME
import com.grocerysync.app.ui.components.appBarTitle
import com.grocerysync.app.ui.components.failedChangesText
import com.grocerysync.app.ui.components.liveStatusText
import com.grocerysync.app.ui.components.offlineStatusText
import com.grocerysync.app.ui.components.pushWarningText
import com.grocerysync.app.ui.components.relativeTimeText
import com.grocerysync.app.ui.components.scriptUpdateText
import com.grocerysync.app.ui.components.syncErrorText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The list screen in Hindi: the texts made of parts (counts, names, times, the sync layer's messages) and the plurals.
 * Item names, amounts and units stay as they are.
 */
class ListTextsHindiTest {

    @get:Rule
    val hindi = UseTextsRule(AppTexts.Hindi)

    private val zone = ZoneId.of("Asia/Kolkata")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    /** Saturday 7 Nov 2026, 18:00. */
    private val now = at(2026, 11, 7, 18)
    private val minute = 60_000L
    private val hour = 60 * minute

    private fun item(name: String, value: ItemValue, by: String = "Home", updatedAt: Long = now, addedAt: Long = 0L) =
        GroceryItem(name.lowercase(), name, value, updatedAt, by, pendingSync = false, addedAt = addedAt)

    private fun yesNo(name: String, needed: Boolean = true) = item(name, ItemValue.BoolValue(needed))

    @Test
    fun theAppBarTitleIsInHindiUnlessTheBuildHasANameOfItsOwn() {
        assertEquals("किराना लिस्ट", appBarTitle("Kirana List"))
        // The qa build stays recognisable.
        assertEquals("Kirana QA", appBarTitle("Kirana QA"))
    }

    @Test
    fun relativeTimesAndDates() {
        assertEquals("अभी", relativeTimeText(now - 30_000L, now, zone))
        assertEquals("1 मिनट पहले", relativeTimeText(now - minute, now, zone))
        assertEquals("14 मिनट पहले", relativeTimeText(now - 14 * minute - 30_000L, now, zone))
        assertEquals("1 घंटे पहले", relativeTimeText(now - hour, now, zone))
        assertEquals("23 घंटे पहले", relativeTimeText(now - 24 * hour + 1, now, zone))
        assertEquals("कल", relativeTimeText(at(2026, 11, 6, 18), now, zone))
        assertEquals("2 दिन पहले", relativeTimeText(at(2026, 11, 5, 23, 59), now, zone))
        assertEquals("31 अक्टूबर को", relativeTimeText(at(2026, 10, 31, 18), now, zone))
        assertEquals("1 फ़रवरी को", relativeTimeText(at(2026, 2, 1, 9), now, zone))
        assertEquals("3 नवंबर 2025 को", relativeTimeText(at(2025, 11, 3, 12), now, zone))
        assertEquals("59 मिनट पहले", WIDEST_TICKING_TIME)
    }

    private fun meta(item: GroceryItem) = ItemMetaText.of(item, now, zone)?.text

    @Test
    fun whoAndWhenLines() {
        val updatedAt = now - 2 * hour
        val yes = ItemValue.BoolValue(true)
        assertEquals("Home ने जोड़ा - 2 घंटे पहले", meta(item("Rice", yes, updatedAt = updatedAt, addedAt = updatedAt)))
        assertEquals("Home ने बदला - 2 घंटे पहले", meta(item("Rice", yes, updatedAt = updatedAt)))
        // Without a name the time comes first.
        assertEquals("2 घंटे पहले जोड़ा गया", meta(item("Rice", yes, by = "", updatedAt = updatedAt, addedAt = updatedAt)))
        assertEquals("2 घंटे पहले बदला गया", meta(item("Rice", yes, by = " ", updatedAt = updatedAt)))
        assertEquals("Sheet ने बदला", meta(item("Rice", yes, by = "Sheet", updatedAt = 0L)))
    }

    @Test
    fun whoAndWhenLinesKeepTheTimeInOnePiece() {
        val meta = ItemMetaText(added = false, "Home", "2 मिनट पहले")
        assertEquals("Home ने बदला - 2 मिनट पहले", meta.displayText())
        assertEquals("Home ने बदला -\n2 मिनट पहले", meta.displayText(keepTimeTogether = false, breakBeforeTime = true))
        assertEquals("अभी\nजोड़ा गया", ItemMetaText(added = true, null, "अभी").displayText(breakBeforeTime = true))
    }

    @Test
    fun statusLine() {
        assertEquals("लाइव", liveStatusText(null, now))
        assertEquals("लाइव - 3 सेकंड पहले अपडेट हुई", liveStatusText(now - 3_000L, now))
        assertEquals("लाइव - 5 मिनट पहले अपडेट हुई", liveStatusText(now - 5 * minute, now))
        assertEquals("लाइव - 2 घंटे पहले अपडेट हुई", liveStatusText(now - 2 * hour, now))

        assertEquals("ऑफ़लाइन - सेव की हुई लिस्ट दिख रही है", offlineStatusText(Protocol.MSG_OFFLINE, null, now))
        assertEquals(
            "ऑफ़लाइन - सेव की हुई लिस्ट दिख रही है (अभी अपडेट हुई)",
            offlineStatusText(null, now - 20_000L, now),
        )
        assertEquals(
            "शीट व्यस्त है - फिर से कोशिश हो रही है (5 मिनट पहले अपडेट हुई)",
            offlineStatusText(Protocol.MSG_BUSY, now - 5 * minute, now),
        )
        assertEquals(
            "Google जवाब नहीं दे रहा - फिर से कोशिश हो रही है (3 घंटे पहले अपडेट हुई)",
            offlineStatusText(Protocol.MSG_GOOGLE_TROUBLE, now - 3 * hour, now),
        )
        assertEquals(
            "ऑफ़लाइन - सेव की हुई लिस्ट दिख रही है (4 दिन पहले अपडेट हुई)",
            offlineStatusText(Protocol.MSG_OFFLINE, now - 96 * hour, now),
        )
        // The script's own words stay as they are.
        val internal = Protocol.mapServerError("internal: Choose Boolean or Integer.").message
        assertEquals("शीट में गड़बड़ी - फिर से कोशिश हो रही है: Choose Boolean or Integer.", offlineStatusText(internal, null, now))
    }

    private fun problem(message: String, needsFix: Boolean = false) =
        PushProblem(message, 1, mayBeMomentary = false, needsFix = needsFix)

    @Test
    fun pushWarningTranslatesTheReasonAndCountsTheItems() {
        assertEquals("बदलाव सेव नहीं हो रहे", PUSH_WARNING_TITLE)
        assertEquals(
            "बदलाव सेव नहीं हो रहे: शीट अभी दूसरे बदलावों में व्यस्त है। " +
                "1 चीज़ इस फ़ोन पर रुकी है और अपने-आप फिर से भेजी जाएगी।",
            pushWarningText(problem(Protocol.MSG_BUSY), 1),
        )
        assertEquals(
            "बदलाव सेव नहीं हो रहे: अभी Google शीट ठीक से जवाब नहीं दे रही है। " +
                "2 चीज़ें इस फ़ोन पर रुकी हैं और अपने-आप फिर से भेजी जाएँगी।",
            pushWarningText(problem(Protocol.MSG_GOOGLE_TROUBLE), 2),
        )
        val badRequest = Protocol.mapServerError("bad_request: unknown action \"apply\"").message
        val reason = Texts.current.sync.message(badRequest)
        assertNotEquals(badRequest, reason)
        assertEquals(
            "बदलाव सेव नहीं हो रहे: $reason इसके ठीक होने तक 1 चीज़ इस फ़ोन पर रुकी रहेगी।",
            pushWarningText(problem(badRequest, needsFix = true), 1),
        )
        assertEquals(
            "बदलाव सेव नहीं हो रहे: $reason इसके ठीक होने तक 3 चीज़ें इस फ़ोन पर रुकी रहेंगी।",
            pushWarningText(problem(badRequest, needsFix = true), 3),
        )
    }

    @Test
    fun scriptUpdateLineStartsWithItsBoldSentence() {
        assertEquals("शीट की स्क्रिप्ट पुरानी है।", SCRIPT_UPDATE_TITLE)
        assertEquals(
            "शीट की स्क्रिप्ट पुरानी है। यूनिट इस्तेमाल करने के लिए Apps Script में सबसे नई Code.gs पेस्ट करें और " +
                "नया वर्ज़न डिप्लॉय करें। तब तक 1 चीज़ इस फ़ोन पर रुकी रहेगी।",
            scriptUpdateText(1),
        )
        assertTrue(scriptUpdateText(2).startsWith(SCRIPT_UPDATE_TITLE))
        assertTrue(scriptUpdateText(2).endsWith(" तब तक 2 चीज़ें इस फ़ोन पर रुकी रहेंगी।"))
        assertTrue(scriptUpdateText(0).endsWith(" तब तक यहाँ किए गए बदलाव इस फ़ोन पर रुके रहेंगे।"))
    }

    @Test
    fun bannersTranslateTheSyncLayersMessages() {
        assertEquals("सीक्रेट टोकन स्क्रिप्ट के SECRET_TOKEN से मेल नहीं खाता।", syncErrorText(Protocol.MSG_UNAUTHORIZED))
        assertEquals("आपकी शीट से जुड़ने में कुछ गड़बड़ है।", syncErrorText(null))
        // An error text of the sheet's own comes as it is.
        assertEquals("Exceeded maximum execution time", syncErrorText("Exceeded maximum execution time"))

        assertEquals(
            "1 बदलाव सेव नहीं हो सका: Milk: शीट ने यह बदलाव नहीं माना",
            failedChangesText(listOf(FailedChange("1", "Milk", ListLogic.DEFAULT_FAILURE))),
        )
        assertEquals(
            "3 बदलाव सेव नहीं हो सके: name too long",
            failedChangesText(List(3) { FailedChange("$it", "", "name too long") }),
        )
    }

    @Test
    fun theBannersButtonsAreNamedForTalkBackWithTheirOwnLabels() {
        // TalkBack and voice control go by the button's name, and the failed change hints say to tap the label
        // shown, so each name holds that label word for word; in English as in Hindi.
        for (texts in listOf(AppTexts.English, AppTexts.Hindi)) {
            val list = texts.list
            assertTrue(list.retrySendingChanges, list.retrySendingChanges.contains(texts.common.retry))
            assertTrue(list.retryFailedChanges, list.retryFailedChanges.contains(texts.common.retry))
            assertTrue(list.discardFailedChanges, list.discardFailedChanges.contains(list.discard))
        }
        assertEquals("सेव न हुए बदलाव भेजने की फिर से कोशिश करें", HindiListTexts.retryFailedChanges)
    }

    @Test
    fun shareUsesHindiForTheTitleAndTheSectionsOnly() {
        val atta = item("Atta", ItemValue.Quantity(Amount.whole(10L), Units.KG))
        assertEquals("किराने की लिस्ट (1 चीज़):\n\n*दूध, अंडे और ब्रेड*\n• Dahi", buildShareText(listOf(yesNo("Dahi")), ::catalogSectionOf))
        assertEquals("किराने की लिस्ट (2 चीज़ें):\n\n• Atta - 10 kg\n• Dahi", buildShareText(listOf(yesNo("Dahi"), atta), sectionOf = null))
        assertEquals("किराने की लिस्ट (0 चीज़ें):", buildShareText(listOf(yesNo("Dahi", needed = false)), sectionOf = null))
        assertEquals("अभी कुछ ख़रीदना नहीं है", NOTHING_TO_SHARE_TEXT)
    }

    @Test
    fun sectionHeadersSayTheNameAndHowManyItems() {
        assertEquals("मसाले, 1 चीज़", sectionHeaderDescription(ShopSection.MASALA, 1))
        assertEquals("दूध, अंडे और ब्रेड, 3 चीज़ें", sectionHeaderDescription(ShopSection.DAIRY_EGGS_BREAD, 3))
    }

    @Test
    fun search() {
        assertEquals("\"Corriander\" लिस्ट में जोड़ें", ItemSearch.addText("Corriander"))
        assertEquals("\"सारा सामान\" में 1 चीज़ देखें", ItemSearch.showInAllItemsText(1))
        assertEquals("\"सारा सामान\" में 3 चीज़ें देखें", ItemSearch.showInAllItemsText(3))
        assertEquals(
            PageContent.NoMatch("ख़रीदने के सामान में खोज से कुछ नहीं मिला", NoMatchAction.ShowInAllItems(1)),
            pageContent(ListFilter.TO_BUY, true, emptyList(), listOf(yesNo("Coffee", needed = false)), "coffee"),
        )
        assertEquals("खोज से कोई सामान नहीं मिला", ItemSearch.NO_MATCH_TEXT)
        assertEquals(
            "Rice जोड़ा - जब तक ज़रूरत न हो, यह \"सारा सामान\" में रहेगा",
            addedSnackbarText("Rice", needed = false, page = ListFilter.TO_BUY, query = ""),
        )
        assertEquals("Milk जोड़ा - देखने के लिए खोज हटाएँ", addedSnackbarText("Milk", needed = true, page = ListFilter.ALL, query = "rice"))
    }

    @Test
    fun snackbarsAndRowsKeepTheItemsNames() {
        val t = HindiListTexts
        assertEquals("Rice ले लिया", t.gotItSnackbar("Rice"))
        assertEquals("Rice हटा दिया", t.deletedSnackbar("Rice"))
        assertEquals("\"Rice\" दूसरे फ़ोन पर बदला गया", t.changedElsewhere("Rice"))
        assertEquals("ले लिया: Dahi", t.gotItDescription("Dahi"))
        assertEquals("Dahi चाहिए", t.needDescription("Dahi"))
        assertEquals("Rice 0.5 kg कम करें", t.stepDown("0.5 kg", "Rice"))
        assertEquals("Eggs 1 बढ़ाएँ", t.stepUp("1", "Eggs"))
        assertEquals("मात्रा 2 kg", t.amountDescription("2 kg"))
    }

    @Test
    fun tickedTodayCountsTheItemsAndPutBackNamesOne() {
        val t = HindiListTexts
        assertEquals("आज लिया (3)", t.tickedToday(3))
        assertEquals("आज लिया, 1 चीज़", t.tickedTodayDescription(1))
        assertEquals("आज लिया, 3 चीज़ें", t.tickedTodayDescription(3))
        assertEquals("वापस लाएँ", t.putBack)
        assertEquals("Rice वापस लाएँ", t.putBackDescription("Rice"))
        assertEquals("Rice वापस \"ख़रीदना है\" में", t.putBackSnackbar("Rice"))
        assertEquals("Rice: 2 kg, पहले जैसा", t.stepsBackSnackbar("Rice", "2 kg"))
    }

    @Test
    fun simpleModeUsesTheGlossarysWordsAndKeepsTheName() {
        val t = HindiListTexts
        assertEquals("ले लिया", t.gotIt)
        assertEquals("जो चाहिए, उसे \"सामान जोड़ें\" दबाकर जोड़ें।", t.emptyToBuyHintNewListSimple)
        assertEquals("सब ले लिया गया है। कुछ और चाहिए तो \"सामान जोड़ें\" दबाएँ।", t.emptyToBuyHintAllTickedSimple)
        assertEquals(
            "Dahi जोड़ा - यह अभी नहीं चाहिए, इसलिए \"ख़रीदना है\" में नहीं है",
            addedSnackbarText("Dahi", needed = false, page = ListFilter.TO_BUY, query = "", simpleMode = true),
        )
    }
}
