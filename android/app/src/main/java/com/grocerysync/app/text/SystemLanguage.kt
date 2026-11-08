package com.grocerysync.app.text

import android.content.res.Configuration

/**
 * The language code of [configuration]'s first locale ("hi", "en"): what [AppLanguage.PHONE] follows. That is the
 * phone's language, or on Android 13+ the language chosen for this app in Android's settings (res/xml/locales_config
 * offers English and Hindi there). Null when the configuration has no locale.
 */
fun systemLanguage(configuration: Configuration): String? =
    configuration.locales.takeUnless { it.isEmpty }?.get(0)?.language

/**
 * Sets [Texts.current] to the texts of the Language choice [language], PHONE following [configuration]. True when
 * that changed them: a screen composed with the old texts has to be composed again (Settings recreates the activity).
 */
fun Texts.update(language: AppLanguage, configuration: Configuration): Boolean {
    val texts = of(language, systemLanguage(configuration))
    val changed = texts !== current
    current = texts
    return changed
}
