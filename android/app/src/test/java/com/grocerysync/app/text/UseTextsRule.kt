package com.grocerysync.app.text

import org.junit.rules.ExternalResource

/**
 * Runs each test of a class with [texts] as [Texts.current], and puts English back afterwards, also when the test
 * fails: JVM unit tests run in English, and the next test (of any class) must not find Hindi.
 *
 *     @get:Rule
 *     val hindi = UseTextsRule(AppTexts.Hindi)
 *
 * For one check in another language inside an English test, use [withTexts].
 */
class UseTextsRule(private val texts: AppTexts) : ExternalResource() {
    override fun before() {
        Texts.current = texts
    }

    override fun after() {
        Texts.current = AppTexts.English
    }
}

/** Runs [block] with [texts] as [Texts.current], then puts English back (also when [block] throws). */
fun <T> withTexts(texts: AppTexts, block: () -> T): T {
    Texts.current = texts
    try {
        return block()
    } finally {
        Texts.current = AppTexts.English
    }
}
