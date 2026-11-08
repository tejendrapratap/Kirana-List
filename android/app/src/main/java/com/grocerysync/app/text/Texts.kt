package com.grocerysync.app.text

/**
 * The Language choice in Settings > This phone. [id] is what
 * [PhoneSettingsStore][com.grocerysync.app.data.PhoneSettingsStore] stores and never changes; for [ENGLISH] and
 * [HINDI] it is also the language's code ("en", "hi").
 */
enum class AppLanguage(val id: String) {
    /** Follows the phone's language (Android 13+: also the app's own language in Android's settings). The default. */
    PHONE("phone"),
    ENGLISH("en"),
    HINDI("hi"),
    ;

    companion object {
        /** The language stored as [id]; null for a missing or unknown id. */
        fun fromId(id: String?): AppLanguage? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Every text the app shows, in one language: one catalogue per area of the app. The compiler checks that each
 * language has every text, and the JVM unit tests (which have no Android resources) read the same texts the screens
 * show.
 *
 * Conventions (every area file follows them; see also [Texts]):
 * - One file per area in this package: `XTexts.kt` holds `interface XTexts`, `object EnglishXTexts : XTexts` and
 *   `object HindiXTexts : XTexts`. A lane edits only the area files it owns; a text another area also needs may be
 *   defined again in its own file (a duplicate across areas is fine). Words several areas share are in [CommonTexts].
 * - Members are `val` for a fixed text (`val undo: String`) and `fun` for a text with parts
 *   (`fun gotIt(name: String): String`, `fun itemCount(n: Int): String`). Member names say what the text is for, in
 *   lowerCamelCase, not what it says in English (`emptyToBuyTitle`, not `nothingToBuyRightNow`).
 * - Plurals are made in code, per language: English "1 item" / "3 items", Hindi "1 चीज़" / "3 चीज़ें".
 * - Item names, units (kg, g, L, ml, pcs, dozen, pack, custom units) and amounts are data: never translated. The
 *   names of the languages in the Language choice are always in their own script ([languageName]).
 * - Hindi: everyday spoken Hindi in Devanagari, the polite आप form, short; the common English loanword where that is
 *   what people say (सेटिंग्स, शेयर, लिस्ट, फ़ोन, शीट); nukta forms kept (फ़ोन, ज़रूरत, सब्ज़ी). The words of the 1.6
 *   spec's glossary are used exactly, so the same thing is called the same everywhere.
 *
 * @param language [AppLanguage.ENGLISH] or [AppLanguage.HINDI] (never PHONE): the language of these texts.
 * @param languageName the language's own name in its own script ("English", "हिन्दी").
 */
class AppTexts(
    val language: AppLanguage,
    val languageName: String,
    val common: CommonTexts,
    val list: ListTexts,
    val item: ItemTexts,
    val bulk: BulkTexts,
    val settings: SettingsTexts,
    val sync: SyncTexts,
    val notify: NotifyTexts,
) {
    companion object {
        val English = AppTexts(
            language = AppLanguage.ENGLISH,
            languageName = "English",
            common = EnglishCommonTexts,
            list = EnglishListTexts,
            item = EnglishItemTexts,
            bulk = EnglishBulkTexts,
            settings = EnglishSettingsTexts,
            sync = EnglishSyncTexts,
            notify = EnglishNotifyTexts,
        )

        val Hindi = AppTexts(
            language = AppLanguage.HINDI,
            languageName = "हिन्दी",
            common = HindiCommonTexts,
            list = HindiListTexts,
            item = HindiItemTexts,
            bulk = HindiBulkTexts,
            settings = HindiSettingsTexts,
            sync = HindiSyncTexts,
            notify = HindiNotifyTexts,
        )
    }
}

/**
 * The texts in the language this phone shows. How code reads them: `Texts.current.list.gotIt(item.name)` (a local
 * `val t = Texts.current` at the top of a composable or function is fine).
 *
 * - Composables read [current] while they compose. A language change saves the choice, sets [current] and recreates
 *   the activity (`Activity.recreate()`), so every screen is composed again in the new language: nothing observes
 *   [current], which is why it is a plain variable and not Compose state.
 * - View models and other state holders keep structured data in long-lived state, never finished sentences: a view
 *   model outlives `recreate()`, and a sentence built before it would stay in the old language. A one-shot event
 *   (a snackbar's message) may carry a finished text, built when the event is sent.
 * - Messages of the sync layer (SyncStatus.message, Protocol.MSG_*, LiveSync's messages, push problems, Test
 *   connection failures, per-change errors) stay English constants there, so its tests and the database stay as
 *   they are; they are translated where they are shown, with [SyncTexts.message].
 * - JVM unit tests run in English (the default here). A test that switches to Hindi restores English afterwards
 *   (the test rule UseTextsRule does both).
 *
 * MainActivity sets [current] in onCreate before setContent, from the saved choice and its own configuration;
 * GroceryApp sets it in onCreate (and when the phone's configuration changes) for work done without a screen.
 */
object Texts {
    /** The texts every screen reads now; English until the app sets it at startup. */
    @Volatile
    var current: AppTexts = AppTexts.English

    /**
     * The texts for the Language choice [language]: [AppLanguage.PHONE] means Hindi when [systemLanguage] (the
     * language code of the phone's, or with Android 13+ the app's own, first locale) is "hi", else English.
     */
    fun of(language: AppLanguage, systemLanguage: String?): AppTexts = when (language) {
        AppLanguage.ENGLISH -> AppTexts.English
        AppLanguage.HINDI -> AppTexts.Hindi
        AppLanguage.PHONE ->
            if (systemLanguage.equals(AppLanguage.HINDI.id, ignoreCase = true)) AppTexts.Hindi else AppTexts.English
    }
}
