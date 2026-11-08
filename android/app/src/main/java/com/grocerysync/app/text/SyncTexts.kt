package com.grocerysync.app.text

/**
 * Messages of the sync layer as people see them. The sync layer makes them in English (Protocol.MSG_* and the
 * messages Protocol composes, LiveSync's, ListLogic.DEFAULT_FAILURE) and keeps them so: in SyncStatus.message, push
 * problems, Test connection failures and the per-change errors stored in the database. Screens translate them where
 * they show them, with [message].
 */
interface SyncTexts {
    /**
     * [raw] in this language: every message the sync layer makes, and the sentences they are made of (so a part of
     * one, such as a push problem without its "Changes are saved on this phone ..." ending, translates too). The
     * script's own words inside a message (an error text, a page title) stay as they are. Anything else, such as an
     * error text the sheet's script sent, comes back unchanged; null stays null.
     */
    fun message(raw: String?): String?
}

object EnglishSyncTexts : SyncTexts {
    override fun message(raw: String?): String? = raw
}

/**
 * The Hindi of every sync message. The table is keyed by the English text itself, not by the Protocol constants:
 * when an English message changes, its Hindi stops matching and SyncTextsTest fails, instead of the old Hindi being
 * shown for the new English. SyncTextsTest also makes each composed message through Protocol and checks it here.
 */
object HindiSyncTexts : SyncTexts {
    private const val SAVED_ON_PHONE = "बदलाव इस फ़ोन पर सेव हैं और अपने-आप सिंक हो जाएँगे।"
    private const val HINT_ACCESS =
        "Apps Script में Deploy > Manage deployments खोलें और 'Who has access' को 'Anyone' पर रखें।"
    private const val NEW_VERSION = "नया वर्ज़न डिप्लॉय करें।"
    private const val SHEET_REFUSED = "शीट ने यह बदलाव नहीं लिया"
    private const val LATEST_SCRIPT = "देख लें कि शीट में सबसे नई Code.gs चल रही है"
    private val tapRetry = "फिर \"${HindiCommonTexts.retry}\" दबाएँ।"

    private val translator = MessageTranslator(
        sentences = mapOf(
            // Endings of other messages (Protocol.SAVED_ON_PHONE, Protocol's access hint).
            "Changes are saved on this phone and will sync automatically." to SAVED_ON_PHONE,
            "In Apps Script use Deploy > Manage deployments and set 'Who has access' to 'Anyone'." to HINT_ACCESS,

            // MSG_OFFLINE, MSG_GOOGLE_TROUBLE, MSG_BUSY: each of these, then SAVED_ON_PHONE.
            "Can't reach Google right now." to "अभी Google से जुड़ नहीं पा रहे हैं।",
            "Google Sheets isn't responding properly right now." to "अभी Google शीट ठीक से जवाब नहीं दे रही है।",
            "The sheet is busy with other changes." to "शीट अभी दूसरे बदलावों में व्यस्त है।",

            // MSG_HTML, MSG_ACCESS_DENIED: each of these, then the access hint.
            "Google returned a web page instead of data." to "Google ने डेटा की जगह एक वेब पेज भेजा।",
            "Google refused access to the web app." to "Google ने वेब ऐप खोलने नहीं दिया।",

            // MSG_SCRIPT_OUTDATED: two sentences; the list screen shows the first one in bold.
            "The sheet's script is out of date." to "शीट की स्क्रिप्ट पुरानी है।",
            "To use units, paste the latest Code.gs into Apps Script and deploy a new version." to
                "यूनिट इस्तेमाल करने के लिए Apps Script में सबसे नई Code.gs पेस्ट करें और $NEW_VERSION",

            // MSG_NO_DOGET
            "The web app doesn't run the Kirana List script (Code.gs). " +
                "Paste Code.gs into Apps Script and deploy a new version." to
                "यह वेब ऐप किराना लिस्ट की स्क्रिप्ट (Code.gs) नहीं चलाता। " +
                "Apps Script में Code.gs पेस्ट करें और $NEW_VERSION",
            // MSG_NOT_FOUND
            "Web app not found (404). Check the URL, or deploy the script as a Web app." to
                "वेब ऐप नहीं मिला (404)। URL जाँचें, या स्क्रिप्ट को Web app की तरह डिप्लॉय करें।",
            // MSG_DEPLOYMENT_GONE
            "Google can't open this web app (\"Sorry, unable to open the file at this time\"): " +
                "the deployment doesn't exist any more. In Apps Script open Deploy > Manage deployments, " +
                "copy the Web app URL (ending in /exec) again and paste it in Settings." to
                "Google यह वेब ऐप नहीं खोल पा रहा (\"Sorry, unable to open the file at this time\"): " +
                "यह डिप्लॉयमेंट अब नहीं है। Apps Script में Deploy > Manage deployments खोलें, " +
                "Web app URL (जो /exec पर खत्म होता है) फिर से कॉपी करें और सेटिंग्स में पेस्ट करें।",
            // MSG_UNAUTHORIZED
            "The secret token doesn't match SECRET_TOKEN in the script." to
                "सीक्रेट टोकन स्क्रिप्ट के SECRET_TOKEN से मेल नहीं खाता।",
            // Protocol.MSG_NOT_CONFIGURED
            "The script still has SECRET_TOKEN = 'CHANGE_ME'. " +
                "Set your own token in Code.gs and deploy a new version." to
                "स्क्रिप्ट में अभी भी SECRET_TOKEN = 'CHANGE_ME' है। Code.gs में अपना टोकन डालें और $NEW_VERSION",
            // MSG_INVALID_URL
            "The web app URL isn't valid. Check it in Settings." to
                "वेब ऐप का URL सही नहीं है। इसे सेटिंग्स में जाँचें।",
            // MSG_NOT_OUR_SCRIPT
            "This web app isn't the Kirana List script (Code.gs). " +
                "Paste the latest Code.gs into Apps Script and deploy a new version." to
                "यह वेब ऐप किराना लिस्ट की स्क्रिप्ट (Code.gs) नहीं है। " +
                "Apps Script में सबसे नई Code.gs पेस्ट करें और $NEW_VERSION",

            // A change the sheet refused (Protocol.changeErrorText): the reason, then what to do.
            "The sheet refused this change" to SHEET_REFUSED,
            "The sheet refused this change." to "$SHEET_REFUSED।",
            "Remove or relax that rule in the sheet (Data > Data validation), then tap Retry." to
                "शीट में वह नियम हटाएँ या ढीला करें (Data > Data validation), $tapRetry",
            "Remove that protection in the sheet (Data > Protect sheets and ranges), then tap Retry." to
                "शीट में वह सुरक्षा हटाएँ (Data > Protect sheets and ranges), $tapRetry",
            // ListLogic.DEFAULT_FAILURE: a change the sheet refused without saying why.
            "The sheet rejected this change" to SHEET_REFUSED,

            // LiveSync, while no web app URL or token is saved.
            "Add the web app URL and secret token in Settings." to "सेटिंग्स में वेब ऐप का URL और सीक्रेट टोकन डालें।",
        ),
        patterns = listOf(
            // Protocol.SCRIPT_ERROR_PREFIX and SCRIPT_FAILED_PREFIX, then the script's own words.
            MessagePattern("""The sheet's script reported an error: (.*)""") {
                "शीट की स्क्रिप्ट ने गड़बड़ी बताई: ${it[1]}"
            },
            MessagePattern("""The sheet's script failed: (.*)""") { "शीट की स्क्रिप्ट नहीं चल पाई: ${it[1]}" },
            // Protocol.changeErrorText: the sheet's reason, then one of the two hints above.
            MessagePattern("""The sheet refused this change: (.*) (Remove .*)""") { parts ->
                sentence(parts[2])?.let { hint -> "$SHEET_REFUSED: ${parts[1]} $hint" }
            },
            MessagePattern("""Google answered with HTTP (\d+)\. Check the web app URL in Settings\.""") {
                "Google ने HTTP ${it[1]} से जवाब दिया। सेटिंग्स में वेब ऐप का URL जाँचें।"
            },
            // A web page with a title, then the access hint.
            MessagePattern("""Google returned a web page \("(.*)"\) instead of data\. (In Apps Script .*)""") { parts ->
                sentence(parts[2])?.let { hint -> "Google ने डेटा की जगह एक वेब पेज (\"${parts[1]}\") भेजा। $hint" }
            },
            // Protocol.mapServerError: "bad_request", with or without a detail.
            MessagePattern(
                """The script rejected the request(?: \((.*)\))?\. """ +
                    """Make sure the sheet runs the latest Code\.gs and deploy a new version\.""",
            ) {
                val detail = if (it[1].isEmpty()) "" else " (${it[1]})"
                "स्क्रिप्ट ने ऐप का अनुरोध नहीं माना$detail। $LATEST_SCRIPT, और $NEW_VERSION"
            },
            // Protocol.mapServerError: any other error code, with or without the script's text.
            MessagePattern("""The script reported an error(?:: (.*))?\. Make sure the sheet runs the latest Code\.gs\.""") {
                val detail = if (it[1].isEmpty()) "" else ": ${it[1]}"
                "स्क्रिप्ट ने गड़बड़ी बताई$detail। $LATEST_SCRIPT।"
            },
        ),
    )

    override fun message(raw: String?): String? = raw?.let(translator::translate)

    /** The Hindi of one known sentence (a hint inside a pattern's message); null for any other text. */
    private fun sentence(english: String): String? = translator.translatedOrNull(english)
}

/**
 * A composed message: [regex] matches all of it (a dot also matches a line break), and [translate] makes it in
 * another language from the groups (index 1 = the first group; an optional group that didn't match is ""), or
 * returns null when a part is not known after all.
 */
internal class MessagePattern(regex: String, val translate: (List<String>) -> String?) {
    val regex = Regex(regex, RegexOption.DOT_MATCHES_ALL)
}

/**
 * Translates messages from [sentences] (English to the other language, whole messages and the sentences they are
 * made of) and [patterns] (messages with the script's own words inside). A message is tried whole, then against the
 * patterns, then as a known sentence followed by a space and more that translates too ("Can't reach Google right
 * now." + " " + "Changes are saved on this phone ..."). All or nothing: a message with any unknown part comes back
 * unchanged rather than half translated.
 */
internal class MessageTranslator(
    private val sentences: Map<String, String>,
    private val patterns: List<MessagePattern>,
) {
    fun translate(raw: String): String = translatedOrNull(raw) ?: raw

    fun translatedOrNull(text: String): String? {
        sentences[text]?.let { return it }
        for (pattern in patterns) {
            val match = pattern.regex.matchEntire(text) ?: continue
            pattern.translate(match.groupValues)?.let { return it }
        }
        for ((english, translated) in sentences) {
            if (text.length > english.length + 1 && text[english.length] == ' ' && text.startsWith(english)) {
                translatedOrNull(text.substring(english.length + 1))?.let { return "$translated $it" }
            }
        }
        return null
    }
}
