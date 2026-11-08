package com.grocerysync.app.text

/**
 * The list screen: app bar, status line, banners, rows, search, Share, Undo, Ticked today and Simple mode.
 * The words other areas use too (Undo, Retry, the two pages' names, item counts, the shop sections' names, who added
 * or changed an item and when, Share's title) are in [CommonTexts].
 *
 * Item names, amounts and units inside these texts are data: they come in as they are ([name], [amount], [step]).
 * The sync layer's messages inside a text ([pushWarning]'s cause, a banner's error) are already translated with
 * [SyncTexts.message] by the screen.
 */
interface ListTexts {

    // ---- App bar ----------------------------------------------------------------------------------------

    /**
     * The app bar's title, the app's name. Shown only by a build whose name (R.string.app_name) is the English one
     * here: another build (the qa build's "Kirana QA") keeps showing its own, so it stays easy to tell apart.
     */
    val appTitle: String

    /** The app bar's Search icon (TalkBack) and the search field's hint. */
    val searchItems: String

    /** The search field's X (TalkBack). */
    val closeSearch: String

    /** The app bar's Share icon (TalkBack). */
    val shareList: String

    /** The app bar's ⋮ button (TalkBack). */
    val moreOptions: String

    /** The ⋮ menu's choice, and the button of an empty list. */
    val addCommonItems: String

    /** The ⋮ menu's choice. */
    val pasteList: String

    /** The ⋮ menu's tick: To buy grouped by shop section, or A to Z. */
    val groupBySection: String

    // ---- Status line under the app bar ------------------------------------------------------------------

    val notConnected: String

    /** While a sync is starting or running. */
    val connecting: String

    /** Sync stopped and needs the user: the status line, and the title of the banner that says why. */
    val notSyncing: String

    /** Live, before the first sync has finished. */
    val live: String

    /** "Live - updated 3s ago": [ago] is one of the short times ([secondsAgoShort], ...). */
    fun liveUpdated(ago: String): String

    /** "3s ago": the status line's short form, which changes every second. */
    fun secondsAgoShort(n: Int): String

    /** "5 min ago": the status line's short form. */
    fun minutesAgoShort(n: Int): String

    /** "3 h ago": the status line's short form. */
    fun hoursAgoShort(n: Int): String

    /** Offline because the phone can't reach Google: the list shown is the one saved on the phone. */
    val offlineShowingSavedList: String

    /** Offline because the sheet is busy with other changes. */
    val sheetBusyRetrying: String

    /** Offline because the sheet's script failed: [detail] is the script's own words. */
    fun sheetErrorRetrying(detail: String): String

    /** Offline because Google answers, but not properly. */
    val googleNotRespondingRetrying: String

    /** An offline reason ([offlineShowingSavedList], ...) and how old the list shown is ([age]). */
    fun offlineWithAge(reason: String, age: String): String

    /** The age of the list shown while offline, under a minute. */
    val lessThanAMinuteAgo: String

    // ---- Banners ----------------------------------------------------------------------------------------

    /** The banner shown until the phone is connected to a sheet. */
    val setupTitle: String
    val setupMessage: String

    /** The setup banner's button, which opens Settings. */
    val setUp: String

    /** The "Not syncing" banner's text when the sync layer gave no message. */
    val syncErrorFallback: String

    /** The "Not syncing" banner's button. */
    val openSettings: String

    /** The bold start of [pushWarning]. */
    val pushWarningTitle: String

    /**
     * The line shown while changes keep failing to reach the sheet: [pushWarningTitle], then [cause] (the sync
     * layer's reason, translated), then that [waiting] items wait on this phone, and either that they will be sent
     * again by themselves or ([needsFix]: retrying alone won't help) that they wait until the cause is fixed.
     */
    fun pushWarning(cause: String, waiting: Int, needsFix: Boolean): String

    /** The push warning's Retry button (TalkBack). */
    val retrySendingChanges: String

    /**
     * After the "script is out of date" message: what waits on this phone until the script is updated; [waiting]
     * items, or (0) the changes still to be made.
     */
    fun scriptUpdateWaiting(waiting: Int): String

    /** The banner of changes the sheet refused: "1 change couldn't be saved", "3 changes couldn't be saved". */
    fun failedChanges(n: Int): String

    /** The failed changes banner's buttons: [discard] (with [discardFailedChanges] for TalkBack) and Retry. */
    val discard: String
    val discardFailedChanges: String
    val retryFailedChanges: String

    // ---- Empty pages ------------------------------------------------------------------------------------

    /** To buy with nothing needed. */
    val emptyToBuyTitle: String

    /** To buy's hint while the whole list is empty. */
    val emptyToBuyHintNewList: String

    /** To buy's hint when every item on the list has been ticked off. */
    val emptyToBuyHintAllTicked: String

    /** All items with nothing on the list. */
    val emptyListTitle: String
    val emptyListHint: String

    /** Both pages right after setup, until the family's list has come from the sheet. */
    val listNotLoadedTitle: String
    val listNotLoadedHint: String

    // ---- Search -----------------------------------------------------------------------------------------

    /**
     * All items: nothing on the list matches the search. Like [noToBuySearchMatch] it doesn't repeat the query
     * (which is in the field just above), so as a live region it is announced once, not again with each letter.
     */
    val noSearchMatch: String

    /** To buy: none of the needed items match the search. */
    val noToBuySearchMatch: String

    /** The row offered when nothing on the list matches: Add "Corriander" to the list. */
    fun addToList(name: String): String

    /** The row offered on To buy when [count] items that are not needed match: they are under All items. */
    fun showInAllItems(count: Int): String

    /** The snackbar after adding [name], not needed, while To buy is shown (which only shows needed items). */
    fun addedNotNeeded(name: String): String

    /** The snackbar after adding [name] while the search hides it. */
    fun addedHiddenBySearch(name: String): String

    // ---- Snackbars --------------------------------------------------------------------------------------

    /** After "Got it" ticked [name] off (with Undo). */
    fun gotItSnackbar(name: String): String

    /** After [name] was deleted (with Undo). */
    fun deletedSnackbar(name: String): String

    /**
     * After - / + of [name] (with Undo): "Rice: 2 kg → 3 kg". [before] and [after] are amounts with their unit
     * ("2 kg", or "2" without one): data, so the text is the same in every language.
     */
    fun stepSnackbar(name: String, before: String, after: String): String

    /** After the Need switch of [name] (with Undo): "Dahi: needed" / "Dahi: not needed". */
    fun needSnackbar(name: String, needed: Boolean): String

    /** After the Edit dialog renamed [oldName] to [newName] (with Undo). */
    fun renamedSnackbar(oldName: String, newName: String): String

    /** After the Edit dialog changed the type, amount or unit of [name] (with Undo). */
    fun changedSnackbar(name: String): String

    /** After the Edit dialog changed the type, amount or unit of [oldName] and renamed it [newName] (with Undo). */
    fun changedAndRenamedSnackbar(oldName: String, newName: String): String

    /** A change of [name] (shortened for a quote) the phone couldn't queue: its name isn't a valid one. */
    fun couldNotSave(name: String): String

    /** An item changed meanwhile on another phone (or in the sheet): renamed, removed, or another value. */
    fun changedElsewhere(name: String): String

    /** [changedElsewhere] when the item's name isn't known any more. */
    val itemChangedElsewhere: String

    /** The Need switch, - / + or "Got it" of [name] (cut short) couldn't be saved: its name is too long. */
    fun cantChange(name: String): String

    /** Saving a change failed for a reason the phone can't name. */
    val editFailed: String

    // ---- Ticked today -----------------------------------------------------------------------------------

    /** To buy's row of the items this phone took off To buy today: "Ticked today (3)". Tapping it opens or closes it. */
    fun tickedToday(n: Int): String

    /** What TalkBack reads for that row, a heading: "Ticked today, 3 items". */
    fun tickedTodayDescription(n: Int): String

    /** The state TalkBack reads for that row while it shows its items. */
    val expanded: String

    /** The state TalkBack reads for that row while its items are hidden. */
    val collapsed: String

    /** What tapping that row does while it is closed (TalkBack's "double-tap to ..."). */
    val showTickedItems: String

    /** What tapping that row does while it is open. */
    val hideTickedItems: String

    /** The button of an item under Ticked today that makes it needed again, with the amount it had. */
    val putBack: String

    /** That button for TalkBack: "Put back Rice". */
    fun putBackDescription(name: String): String

    // ---- Share ------------------------------------------------------------------------------------------

    /** Share tapped while nothing is needed. */
    val nothingToShare: String

    /** Share tapped on a phone without any app that takes text. */
    val noShareApp: String

    // ---- Rows -------------------------------------------------------------------------------------------

    /** What tapping a row does (TalkBack's "double-tap to ..."). */
    val editItem: String

    /** To buy's checkbox (TalkBack). */
    fun gotItDescription(name: String): String

    /** The "Not synced yet" icon of a row whose change is still on this phone (TalkBack). */
    val notSyncedYet: String

    /** An amount and its unit as TalkBack reads it ([amount]: "2 kg", "1.5 L", "6"). */
    fun amountDescription(amount: String): String

    /** The label in front of All items' switch for a yes / no item. */
    val need: String

    /** The Need switch of [name] (TalkBack). */
    fun needDescription(name: String): String

    /** The − of [name]'s stepper (TalkBack): one [step] ("0.5 kg", "1") less. */
    fun stepDown(step: String, name: String): String

    /** The + of [name]'s stepper (TalkBack): one [step] more. */
    fun stepUp(step: String, name: String): String

    /** What tapping the stepper's amount does (TalkBack's "double-tap to ..."). */
    val editAmount: String

    /**
     * When an item was changed, a week or more ago (or further ahead than the phone's clock): "on 3 Nov", and with
     * [year] (a year other than this one) "on 3 Nov 2025". [month] is 1 to 12.
     */
    fun onDate(day: Int, month: Int, year: Int?): String

    /**
     * The "who - when" line of an All items row when the sheet doesn't say who: [verb] ([CommonTexts.added] or
     * [CommonTexts.updated]) and [time] (a relative time or [onDate]) with [gap] between them: a space, or a line
     * break that gives each a line of its own.
     */
    fun metaWithoutName(verb: String, time: String, gap: Char): String
}

object EnglishListTexts : ListTexts {
    override val appTitle = "Kirana List"
    override val searchItems = "Search items"
    override val closeSearch = "Close search"
    override val shareList = "Share list"
    override val moreOptions = "More options"
    override val addCommonItems = "Add common items"
    override val pasteList = "Paste a list"
    override val groupBySection = "Group by shop section"

    override val notConnected = "Not connected"
    override val connecting = "Connecting..."
    override val notSyncing = "Not syncing"
    override val live = "Live"
    override fun liveUpdated(ago: String) = "Live - updated $ago"
    override fun secondsAgoShort(n: Int) = "${n}s ago"
    override fun minutesAgoShort(n: Int) = "$n min ago"
    override fun hoursAgoShort(n: Int) = "$n h ago"
    override val offlineShowingSavedList = "Offline - showing saved list"
    override val sheetBusyRetrying = "Sheet busy - retrying"
    override fun sheetErrorRetrying(detail: String) = "Sheet error - retrying: $detail"
    override val googleNotRespondingRetrying = "Google isn't responding - retrying"
    override fun offlineWithAge(reason: String, age: String) = "$reason (updated $age)"
    override val lessThanAMinuteAgo = "less than a minute ago"

    override val setupTitle = "Connect your Google Sheet"
    override val setupMessage = "Share one live list between phones. Until then, items are only saved on this phone."
    override val setUp = "Set up"
    override val syncErrorFallback = "Something is wrong with the connection to your sheet."
    override val openSettings = "Open settings"
    override val pushWarningTitle = "Changes aren't saving"

    override fun pushWarning(cause: String, waiting: Int, needsFix: Boolean): String {
        val items = if (waiting == 1) "1 item is waiting on this phone" else "$waiting items are waiting on this phone"
        val next = if (needsFix) "until that is fixed." else "and will be sent again automatically."
        return "$pushWarningTitle: $cause $items $next"
    }

    override val retrySendingChanges = "Retry sending changes"

    override fun scriptUpdateWaiting(waiting: Int) = when (waiting) {
        0 -> "Until then, changes made here wait on this phone."
        1 -> "1 item is waiting on this phone until then."
        else -> "$waiting items are waiting on this phone until then."
    }

    override fun failedChanges(n: Int) = if (n == 1) "1 change couldn't be saved" else "$n changes couldn't be saved"
    override val discard = "Discard"
    override val discardFailedChanges = "Discard failed changes"
    override val retryFailedChanges = "Retry failed changes"

    override val emptyToBuyTitle = "Nothing to buy right now"
    override val emptyToBuyHintNewList = "Tap + to add the things you usually buy. Mark them as needed when you run out."
    override val emptyToBuyHintAllTicked = "Everything is ticked off. Open All items to mark what you need."
    override val emptyListTitle = "Your list is empty"
    override val emptyListHint = "Tap + to add your first item."
    override val listNotLoadedTitle = "Your list hasn't loaded yet"
    override val listNotLoadedHint = "It shows here once it has come from the sheet."

    override val noSearchMatch = "No items match your search"
    override val noToBuySearchMatch = "No items to buy match your search"
    override fun addToList(name: String) = "Add \"$name\" to the list"
    override fun showInAllItems(count: Int) = "Show $count in All items"
    override fun addedNotNeeded(name: String) = "Added $name - it's under All items until you need it"
    override fun addedHiddenBySearch(name: String) = "Added $name - clear the search to see it"

    override fun gotItSnackbar(name: String) = "Got $name"
    override fun deletedSnackbar(name: String) = "Deleted $name"
    override fun stepSnackbar(name: String, before: String, after: String) = "$name: $before → $after"
    override fun needSnackbar(name: String, needed: Boolean) =
        "$name: ${if (needed) EnglishCommonTexts.needed else EnglishCommonTexts.notNeeded}"
    override fun renamedSnackbar(oldName: String, newName: String) = "Renamed $oldName to $newName"
    override fun changedSnackbar(name: String) = "Changed $name"
    override fun changedAndRenamedSnackbar(oldName: String, newName: String) = "Changed $oldName, now called $newName"
    override fun couldNotSave(name: String) = "Couldn't save \"$name\". Check the name and try again."
    override fun changedElsewhere(name: String) = "\"$name\" was changed on another phone"
    override val itemChangedElsewhere = "That item was changed on another phone"
    override fun cantChange(name: String) = "Couldn't change \"$name\". Open it and shorten its name."
    override val editFailed = "Couldn't save that change. Please try again."

    override fun tickedToday(n: Int) = "Ticked today ($n)"
    override fun tickedTodayDescription(n: Int) = "Ticked today, ${EnglishCommonTexts.itemCount(n)}"
    override val expanded = "Expanded"
    override val collapsed = "Collapsed"
    override val showTickedItems = "Show items"
    override val hideTickedItems = "Hide items"
    override val putBack = "Put back"
    override fun putBackDescription(name: String) = "Put back $name"

    override val nothingToShare = "Nothing to buy right now"
    override val noShareApp = "No app on this phone can share the list"

    override val editItem = "Edit item"
    override fun gotItDescription(name: String) = "Got it: $name"
    override val notSyncedYet = "Not synced yet"
    override fun amountDescription(amount: String) = "Quantity $amount"
    override val need = "Need"
    override fun needDescription(name: String) = "Need $name"
    override fun stepDown(step: String, name: String) = "$step less $name"
    override fun stepUp(step: String, name: String) = "$step more $name"
    override val editAmount = "Edit amount"

    private val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    override fun onDate(day: Int, month: Int, year: Int?) = "on $day ${months[month - 1]}" + (year?.let { " $it" } ?: "")
    override fun metaWithoutName(verb: String, time: String, gap: Char) = "$verb$gap$time"
}

object HindiListTexts : ListTexts {
    override val appTitle = "किराना लिस्ट"
    override val searchItems = "सामान खोजें"
    override val closeSearch = "खोज बंद करें"
    override val shareList = "लिस्ट शेयर करें"
    override val moreOptions = "और विकल्प"
    override val addCommonItems = "आम सामान जोड़ें"
    override val pasteList = "लिस्ट पेस्ट करें"
    override val groupBySection = "सेक्शन के हिसाब से दिखाएँ"

    override val notConnected = "शीट से नहीं जुड़ा"
    override val connecting = "जुड़ रहा है..."
    override val notSyncing = "सिंक नहीं हो रहा"
    override val live = "लाइव"

    // "अपडेट हुई": the list (लिस्ट, feminine) was updated.
    override fun liveUpdated(ago: String) = "लाइव - $ago अपडेट हुई"
    override fun secondsAgoShort(n: Int) = "$n सेकंड पहले"
    override fun minutesAgoShort(n: Int) = "$n मिनट पहले"
    override fun hoursAgoShort(n: Int) = "$n घंटे पहले"
    override val offlineShowingSavedList = "ऑफ़लाइन - सेव की हुई लिस्ट दिख रही है"
    override val sheetBusyRetrying = "शीट व्यस्त है - फिर से कोशिश हो रही है"
    override fun sheetErrorRetrying(detail: String) = "शीट में गड़बड़ी - फिर से कोशिश हो रही है: $detail"
    override val googleNotRespondingRetrying = "Google जवाब नहीं दे रहा - फिर से कोशिश हो रही है"
    override fun offlineWithAge(reason: String, age: String) = "$reason ($age अपडेट हुई)"

    // Reads "(अभी अपडेट हुई)" after the reason: updated just now.
    override val lessThanAMinuteAgo = "अभी"

    override val setupTitle = "अपनी Google शीट जोड़ें"
    override val setupMessage = "एक ही लाइव लिस्ट सभी फ़ोन पर रखें। तब तक सामान सिर्फ़ इस फ़ोन पर सेव होगा।"
    override val setUp = "सेट अप करें"
    override val syncErrorFallback = "आपकी शीट से जुड़ने में कुछ गड़बड़ है।"
    override val openSettings = "सेटिंग्स खोलें"
    override val pushWarningTitle = "बदलाव सेव नहीं हो रहे"

    override fun pushWarning(cause: String, waiting: Int, needsFix: Boolean): String {
        val items = HindiCommonTexts.itemCount(waiting)
        val one = waiting == 1
        val next = when {
            needsFix && one -> "इसके ठीक होने तक $items इस फ़ोन पर रुकी रहेगी।"
            needsFix -> "इसके ठीक होने तक $items इस फ़ोन पर रुकी रहेंगी।"
            one -> "$items इस फ़ोन पर रुकी है और अपने-आप फिर से भेजी जाएगी।"
            else -> "$items इस फ़ोन पर रुकी हैं और अपने-आप फिर से भेजी जाएँगी।"
        }
        return "$pushWarningTitle: $cause $next"
    }

    override val retrySendingChanges = "बदलाव फिर से भेजें"

    override fun scriptUpdateWaiting(waiting: Int) = when (waiting) {
        0 -> "तब तक यहाँ किए गए बदलाव इस फ़ोन पर रुके रहेंगे।"
        1 -> "तब तक 1 चीज़ इस फ़ोन पर रुकी रहेगी।"
        else -> "तब तक ${HindiCommonTexts.itemCount(waiting)} इस फ़ोन पर रुकी रहेंगी।"
    }

    // बदलाव is the same for one and many; the verb shows the number.
    override fun failedChanges(n: Int) = if (n == 1) "1 बदलाव सेव नहीं हो सका" else "$n बदलाव सेव नहीं हो सके"
    override val discard = "छोड़ दें"
    override val discardFailedChanges = "सेव न हुए बदलाव छोड़ दें"
    override val retryFailedChanges = "सेव न हुए बदलाव फिर से भेजें"

    override val emptyToBuyTitle = "अभी कुछ ख़रीदना नहीं है"
    override val emptyToBuyHintNewList =
        "आप जो सामान अक्सर ख़रीदते हैं, उसे + दबाकर जोड़ें। जब कुछ ख़त्म हो, उसे \"${HindiCommonTexts.needed}\" कर दें।"
    override val emptyToBuyHintAllTicked = "सब ले लिया गया है। जो चाहिए, उसे \"${HindiCommonTexts.allItems}\" में चुनें।"
    override val emptyListTitle = "आपकी लिस्ट ख़ाली है"
    override val emptyListHint = "पहला सामान जोड़ने के लिए + दबाएँ।"
    override val listNotLoadedTitle = "आपकी लिस्ट अभी नहीं आई है"
    override val listNotLoadedHint = "शीट से आते ही यहाँ दिखेगी।"

    override val noSearchMatch = "खोज से कोई सामान नहीं मिला"
    override val noToBuySearchMatch = "ख़रीदने के सामान में खोज से कुछ नहीं मिला"
    override fun addToList(name: String) = "\"$name\" लिस्ट में जोड़ें"
    override fun showInAllItems(count: Int) = "\"${HindiCommonTexts.allItems}\" में ${HindiCommonTexts.itemCount(count)} देखें"
    override fun addedNotNeeded(name: String) = "$name जोड़ा - जब तक ज़रूरत न हो, यह \"${HindiCommonTexts.allItems}\" में रहेगा"
    override fun addedHiddenBySearch(name: String) = "$name जोड़ा - देखने के लिए खोज हटाएँ"

    override fun gotItSnackbar(name: String) = "$name ले लिया"
    override fun deletedSnackbar(name: String) = "$name हटा दिया"

    // The amounts are data, so it reads the same as in English.
    override fun stepSnackbar(name: String, before: String, after: String) = "$name: $before → $after"
    override fun needSnackbar(name: String, needed: Boolean) =
        "$name: ${if (needed) HindiCommonTexts.needed else HindiCommonTexts.notNeeded}"

    // "Curd का नाम बदलकर Dahi कर दिया": changed Curd's name to Dahi.
    override fun renamedSnackbar(oldName: String, newName: String) = "$oldName का नाम बदलकर $newName कर दिया"
    override fun changedSnackbar(name: String) = "$name बदल दिया"

    // "Curd बदल दिया, अब इसका नाम Dahi है": changed Curd, its name is now Dahi.
    override fun changedAndRenamedSnackbar(oldName: String, newName: String) = "$oldName बदल दिया, अब इसका नाम $newName है"
    override fun couldNotSave(name: String) = "\"$name\" सेव नहीं हो सका। नाम जाँचें और फिर से कोशिश करें।"
    override fun changedElsewhere(name: String) = "\"$name\" किसी दूसरे फ़ोन पर बदला गया"
    override val itemChangedElsewhere = "वह सामान किसी दूसरे फ़ोन पर बदला गया"
    override fun cantChange(name: String) = "\"$name\" नहीं बदला जा सका। इसे खोलकर इसका नाम छोटा करें।"
    override val editFailed = "यह बदलाव सेव नहीं हो सका। कृपया फिर से कोशिश करें।"

    override fun tickedToday(n: Int) = "आज लिया ($n)"
    override fun tickedTodayDescription(n: Int) = "आज लिया, ${HindiCommonTexts.itemCount(n)}"
    override val expanded = "खुला है"
    override val collapsed = "बंद है"
    override val showTickedItems = "सामान दिखाएँ"
    override val hideTickedItems = "सामान छिपाएँ"
    override val putBack = "वापस लाएँ"
    override fun putBackDescription(name: String) = "$name वापस लाएँ"

    override val nothingToShare = "अभी कुछ ख़रीदना नहीं है"
    override val noShareApp = "इस फ़ोन पर लिस्ट शेयर करने के लिए कोई ऐप नहीं है"

    override val editItem = "सामान बदलें"
    override fun gotItDescription(name: String) = "ले लिया: $name"
    override val notSyncedYet = "अभी सिंक नहीं हुआ"
    override fun amountDescription(amount: String) = "मात्रा $amount"
    override val need = "चाहिए"
    override fun needDescription(name: String) = "$name चाहिए"
    override fun stepDown(step: String, name: String) = "$name $step कम करें"
    override fun stepUp(step: String, name: String) = "$name $step बढ़ाएँ"
    override val editAmount = "मात्रा बदलें"

    private val months = listOf(
        "जनवरी", "फ़रवरी", "मार्च", "अप्रैल", "मई", "जून", "जुलाई", "अगस्त", "सितंबर", "अक्टूबर", "नवंबर", "दिसंबर",
    )

    // "3 नवंबर को": the date, then को (on).
    override fun onDate(day: Int, month: Int, year: Int?) = "$day ${months[month - 1]}" + (year?.let { " $it" } ?: "") + " को"

    // Time first reads naturally without a name: "2 घंटे पहले जोड़ा गया" (added 2 hours ago).
    override fun metaWithoutName(verb: String, time: String, gap: Char) = "$time$gap$verb"
}
