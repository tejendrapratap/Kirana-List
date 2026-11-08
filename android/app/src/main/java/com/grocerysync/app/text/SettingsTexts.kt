package com.grocerysync.app.text

/**
 * The Settings screen: its sections and fields, the secret token's help, the setup help card, the version line and
 * the This phone section. Filled by the settings lane; the This phone section's Language choice came first.
 */
interface SettingsTexts {
    /** The section of this phone's own settings, which the other phones don't share. */
    val thisPhone: String

    /** The Language choice's label. */
    val language: String

    /** The Language choice that follows the phone's language (the default); the language it means is shown under it. */
    val phonesLanguage: String
}

object EnglishSettingsTexts : SettingsTexts {
    override val thisPhone = "This phone"
    override val language = "Language"
    override val phonesLanguage = "Phone's language"
}

object HindiSettingsTexts : SettingsTexts {
    override val thisPhone = "यह फ़ोन"
    override val language = "भाषा"
    override val phonesLanguage = "फ़ोन की भाषा"
}
