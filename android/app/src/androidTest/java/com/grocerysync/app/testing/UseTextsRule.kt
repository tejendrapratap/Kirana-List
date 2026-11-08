package com.grocerysync.app.testing

import com.grocerysync.app.text.AppTexts
import com.grocerysync.app.text.Texts
import org.junit.rules.ExternalResource

/**
 * Runs each test of a class with [texts] as [Texts.current], and puts English back afterwards, also when the test
 * fails. For the tests that draw composables with createComposeRule: [Texts.current] is shared by every test of the
 * run, and only MainActivity sets it when it starts, which these tests never do. So a test that ran before in Hindi
 * (ListInHindiTest, say) would otherwise leave this one in Hindi, depending on the order the classes run in.
 *
 *     @get:Rule
 *     val english = UseTextsRule(AppTexts.English)
 *
 * (The JVM unit tests have their own copy of this rule; GroceryUiTest puts English back after each app test.)
 */
class UseTextsRule(private val texts: AppTexts) : ExternalResource() {
    override fun before() {
        Texts.current = texts
    }

    override fun after() {
        Texts.current = AppTexts.English
    }
}
