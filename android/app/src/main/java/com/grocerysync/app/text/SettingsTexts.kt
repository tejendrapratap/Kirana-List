package com.grocerysync.app.text

/**
 * The Settings screen: its sections and fields, the secret token's help, Test connection, the setup help card, the
 * version line and the This phone section. The menu names of Apps Script (Deploy > Manage deployments, Execute as,
 * Who has access) and the names in Code.gs (SECRET_TOKEN) stay in English in every language: that is what people see
 * there.
 */
interface SettingsTexts {
    /** The arrow at the top that leaves Settings, for TalkBack. */
    val back: String

    /** The section with this person's name. */
    val you: String

    /** The Your name field's label. */
    val yourName: String

    /**
     * Under Your name: where the other phone shows it. Quotes [CommonTexts.addedBy] and [CommonTexts.updatedBy], so
     * it names exactly what the rows of All items say (English leaves the name out, "Added by"; Hindi, where the
     * name comes first, puts "…" in its place).
     */
    val yourNameHelp: String

    /** The section of this phone's own settings, which the other phones don't share. */
    val thisPhone: String

    /** The Language choice's label. */
    val language: String

    /** The Language choice that follows the phone's language (the default); the language it means is shown under it. */
    val phonesLanguage: String

    /** This phone: the Simple mode switch. */
    val simpleMode: String

    /** Under [simpleMode]: what it changes on the list screen. */
    val simpleModeHelp: String

    /** This phone: the switch for notifications about what someone else adds to To buy. */
    val notifyChanges: String

    /** Under [notifyChanges]: how often the app looks (Android runs background work every 15 minutes at most). */
    val notifyChangesHelp: String

    /** Under [notifyChanges] while it is on but Android doesn't let the app show notifications. */
    val notificationsBlocked: String

    /** The button under [notificationsBlocked]: opens the app's notification settings in Android. */
    val allowNotifications: String

    /** The section of the connection to the sheet. */
    val googleSheet: String

    /** The button that reads a setup message from the clipboard. */
    val pasteSetupCode: String

    /** Under [pasteSetupCode]. The app no longer makes setup messages, so it doesn't say who shared one. */
    val pasteSetupHelp: String

    /** The Web app URL field's label. */
    val webAppUrl: String

    /** Under the Web app URL field while it is empty: where to find the URL. */
    val webAppUrlHelp: String

    /** The Secret token field's label. */
    val secretToken: String

    // Under the Secret token field: which token Save and Test connection use (TokenEntry.supportingText), never the
    // token itself.

    /** No token yet (or one is typed and there was none). */
    val tokenNew: String

    /** The saved token, with the field empty. */
    val tokenSaved: String

    /** A pasted setup code's token, with the field empty. */
    val tokenFromSetupCode: String

    /** A token is typed and one is saved. */
    val tokenReplacesSaved: String

    /** A token is typed and a setup code's token is waiting. */
    val tokenReplacesSetupCode: String

    /** The field is empty and the hidden token belongs to another Web app URL than the one in the field. */
    val tokenOtherUrl: String

    /** The button that tries the URL and token typed (not yet saved). */
    val testConnection: String

    /** While Test connection waits for the web app. */
    val testingConnection: String

    /** Test connection worked: "Connected to Groceries" ([spreadsheetName] as the sheet says it). */
    fun connectedTo(spreadsheetName: String): String

    /**
     * Test connection worked but the sheet runs an old script: [problem] (the sync layer's MSG_SCRIPT_OUTDATED in
     * this language, from [SyncTexts.message]), then what that means until the script is updated.
     */
    fun scriptOutdated(problem: String): String

    /** Test connection without a token to send. */
    val enterTokenFirst: String

    /** Snackbar: Paste setup code found no code on the clipboard. */
    val noSetupCode: String

    /** Snackbar: a setup code was read; Save still has to be tapped. */
    val setupCodePasted: String

    /** The title of the card that explains how to set up a sheet. */
    val helpTitle: String

    /** The card's numbered steps, in order (the screen numbers them). */
    val helpSteps: List<String>

    /** TalkBack's action on a collapsed card ("double-tap to expand"). */
    val expand: String

    /** TalkBack's action on an expanded card. */
    val collapse: String

    /** TalkBack's state of an expanded card. */
    val expanded: String

    /** TalkBack's state of a collapsed card. */
    val collapsed: String

    /** The line at the bottom of Settings: "Version 1.6" ([name]: the installed versionName). */
    fun version(name: String): String
}

object EnglishSettingsTexts : SettingsTexts {
    override val back = "Back"
    override val you = "You"
    override val yourName = "Your name"

    // As since 1.2: "Added by" and "Updated by" without a name.
    override val yourNameHelp = "Shown to the other person as \"${EnglishCommonTexts.addedBy("").trim()}\" or " +
        "\"${EnglishCommonTexts.updatedBy("").trim()}\"."
    override val thisPhone = "This phone"
    override val language = "Language"
    override val phonesLanguage = "Phone's language"
    override val simpleMode = "Simple mode"
    override val simpleModeHelp = "Bigger text and buttons. Shows only ${EnglishCommonTexts.toBuy}, and " +
        "${EnglishCommonTexts.undo} stays on screen."
    override val notifyChanges = "Notify me when someone adds to ${EnglishCommonTexts.toBuy}"
    override val notifyChangesHelp = "Checks about every 15 minutes while the app is closed."
    override val notificationsBlocked = "Notifications are blocked for this app."
    override val allowNotifications = "Allow in Android settings"
    override val googleSheet = "Google Sheet"
    override val pasteSetupCode = "Paste setup code"
    override val pasteSetupHelp = "Have a setup message? Copy it, then tap $pasteSetupCode."
    override val webAppUrl = "Web app URL"
    override val webAppUrlHelp = "From Deploy > Manage deployments in Apps Script (ends in /exec)."
    override val secretToken = "Secret token"
    override val tokenNew = "Type or paste the SECRET_TOKEN from Code.gs. The app never shows it."
    override val tokenSaved = "Saved and hidden. Type or paste a new token only to replace it."
    override val tokenFromSetupCode = "Token from the setup code, hidden. Tap ${EnglishCommonTexts.save} to use it."
    override val tokenReplacesSaved = "Replaces the saved token when you tap ${EnglishCommonTexts.save}."
    override val tokenReplacesSetupCode = "Used instead of the token from the setup code."
    override val tokenOtherUrl = "New web app URL: type or paste its token. A hidden token is only sent to its own URL."
    override val testConnection = "Test connection"
    override val testingConnection = "Testing connection..."
    override fun connectedTo(spreadsheetName: String) = "Connected to $spreadsheetName"
    override fun scriptOutdated(problem: String) = "$problem Until then the list syncs, but changes wait on this phone."
    override val enterTokenFirst = "Enter the secret token first."
    override val noSetupCode = "No setup code found on the clipboard"
    override val setupCodePasted = "Setup code pasted - tap ${EnglishCommonTexts.save}"
    override val helpTitle = "How to connect a Google Sheet"
    override val helpSteps = listOf(
        "Open your sheet > Extensions > Apps Script, paste Code.gs.",
        "Set SECRET_TOKEN to your own secret.",
        "Deploy > New deployment > Web app; Execute as: Me; Who has access: Anyone.",
        "Copy the Web app URL (ends in /exec) here.",
    )
    override val expand = "Expand"
    override val collapse = "Collapse"
    override val expanded = "Expanded"
    override val collapsed = "Collapsed"
    override fun version(name: String) = "Version $name"
}

object HindiSettingsTexts : SettingsTexts {
    /** A button named inside a sentence is quoted: "सेव करें" दबाएँ. */
    private fun quoted(button: String) = "\"$button\""

    override val back = "वापस जाएँ"
    override val you = "आप"
    override val yourName = "आपका नाम"
    override val yourNameHelp = "दूसरे फ़ोन पर ऐसे दिखेगा: \"${HindiCommonTexts.addedBy("…")}\" या " +
        "\"${HindiCommonTexts.updatedBy("…")}\"।"
    override val thisPhone = "यह फ़ोन"
    override val language = "भाषा"
    override val phonesLanguage = "फ़ोन की भाषा"
    override val simpleMode = "आसान मोड"
    override val simpleModeHelp = "बड़े अक्षर और बड़े बटन। सिर्फ़ ${quoted(HindiCommonTexts.toBuy)} दिखेगा, और " +
        "${quoted(HindiCommonTexts.undo)} स्क्रीन पर बना रहेगा।"
    override val notifyChanges = "कोई ${quoted(HindiCommonTexts.toBuy)} में कुछ जोड़े, तो मुझे सूचना दें"
    override val notifyChangesHelp = "ऐप बंद होने पर लगभग हर 15 मिनट में लिस्ट जाँचता है।"
    override val notificationsBlocked = "इस ऐप की सूचनाएँ बंद हैं।"
    override val allowNotifications = "Android सेटिंग्स में चालू करें"
    override val googleSheet = "Google शीट"
    override val pasteSetupCode = "सेटअप कोड पेस्ट करें"
    override val pasteSetupHelp = "सेटअप मैसेज है? उसे कॉपी करें, फिर ${quoted(pasteSetupCode)} दबाएँ।"
    override val webAppUrl = "वेब ऐप का URL"
    override val webAppUrlHelp = "Apps Script में Deploy > Manage deployments से लें (यह /exec पर ख़त्म होता है)।"
    override val secretToken = "सीक्रेट टोकन"
    override val tokenNew = "Code.gs का SECRET_TOKEN लिखें या पेस्ट करें। ऐप इसे कभी नहीं दिखाता।"
    override val tokenSaved = "सेव है और छिपा हुआ है। इसे बदलना हो तभी नया टोकन लिखें या पेस्ट करें।"
    override val tokenFromSetupCode =
        "सेटअप कोड का टोकन, छिपा हुआ। इसे इस्तेमाल करने के लिए ${quoted(HindiCommonTexts.save)} दबाएँ।"
    override val tokenReplacesSaved = "${quoted(HindiCommonTexts.save)} दबाने पर यह सेव किए हुए टोकन की जगह लेगा।"
    override val tokenReplacesSetupCode = "सेटअप कोड के टोकन की जगह यह इस्तेमाल होगा।"
    override val tokenOtherUrl =
        "नया वेब ऐप URL: इसका टोकन लिखें या पेस्ट करें। छिपा हुआ टोकन सिर्फ़ अपने URL पर ही भेजा जाता है।"
    override val testConnection = "कनेक्शन जाँचें"
    override val testingConnection = "कनेक्शन जाँच रहे हैं..."
    override fun connectedTo(spreadsheetName: String) = "$spreadsheetName से जुड़ गया"
    override fun scriptOutdated(problem: String) =
        "$problem तब तक लिस्ट सिंक होती रहेगी, पर बदलाव इस फ़ोन पर रुके रहेंगे।"
    override val enterTokenFirst = "पहले सीक्रेट टोकन डालें।"
    override val noSetupCode = "क्लिपबोर्ड पर कोई सेटअप कोड नहीं मिला"
    override val setupCodePasted = "सेटअप कोड पेस्ट हो गया - ${quoted(HindiCommonTexts.save)} दबाएँ"
    override val helpTitle = "Google शीट कैसे जोड़ें"
    override val helpSteps = listOf(
        "अपनी शीट में Extensions > Apps Script खोलें और Code.gs पेस्ट करें।",
        "SECRET_TOKEN में अपना सीक्रेट टोकन डालें।",
        "Deploy > New deployment > Web app चुनें; Execute as: Me; Who has access: Anyone।",
        "Web app URL (जो /exec पर ख़त्म होता है) कॉपी करके यहाँ पेस्ट करें।",
    )
    override val expand = "खोलें"
    override val collapse = "बंद करें"
    override val expanded = "खुला है"
    override val collapsed = "बंद है"
    override fun version(name: String) = "वर्ज़न $name"
}
