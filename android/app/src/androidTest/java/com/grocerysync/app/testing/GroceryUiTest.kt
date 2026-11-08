package com.grocerysync.app.testing

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.grocerysync.app.MainActivity
import org.junit.After
import org.junit.Before
import org.junit.Rule

/**
 * Base of the UI tests: the real app (MainActivity, its database, sync engine and live polling) against
 * [FakeSheetServer], a fake of the Apps Script web app running inside the test APK on 127.0.0.1.
 *
 * Running them, on an emulator only (Git Bash; it builds, installs on that one emulator and runs them):
 *
 *     tools/e2e/emu.sh            # reuse or boot the emulator
 *     tools/e2e/uitest.sh         # all of them; one class: tools/e2e/uitest.sh --class SearchTest
 *
 * Not ./gradlew connectedDebugAndroidTest: it installs and runs on every device adb knows, a phone included.
 *
 * Each test:
 * 1. [AppState.reset]: a fresh install as far as the app can tell (no connection, empty database, no queued work).
 * 2. Starts a new fake (a new port and URL), which the test then fills: [FakeSheetServer.seed] for items that were
 *    on the sheet before, [FakeSheetServer.upsertAs] / [FakeSheetServer.deleteAs] for the other phone ("Home").
 * 3. [launchConnected] saves the connection through the app's SettingsStore, as Settings would (device name
 *    [ME]), opens MainActivity and waits until the first sync is done and the list is shown. (SettingsTest goes
 *    through the Settings screen instead.)
 *
 * No sleeps: everything that happens in the background (a push, the 5-second poll) is awaited with
 * [waitFor] / [waitForNode], which poll until the condition holds or the timeout passes.
 *
 * The UI clock: Compose runs the screen's effects (snackbar timeouts, the ticking "updated 3s ago") on a test clock
 * that advances while the test waits for the UI. [withUiClockStopped] holds it while only the fake is awaited, so a
 * snackbar's Undo is still there afterwards.
 */
abstract class GroceryUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    protected lateinit var fake: FakeSheetServer
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun resetAppAndStartFake() {
        AppState.reset()
        fake = FakeSheetServer().start()
    }

    @After
    fun closeAppAndFake() {
        scenario?.close()
        scenario = null
        fake.close()
    }

    /**
     * Connects the app to [fake] as device [ME] (through SettingsStore, as Settings > Save does), opens it (with
     * [intent], e.g. a list shared into the app, when given) and waits until it has synced and shows every item of
     * the fake. Seed the fake first.
     */
    protected fun launchConnected(intent: Intent? = null) {
        val before = System.currentTimeMillis()
        AppState.connect(fake.url, fake.token, ME)
        launchApp(intent)
        waitFor("the first sync with the fake", SYNC_TIMEOUT_MS) { AppState.syncedSince(before) }
        waitForNode(tab("All items", fake.items().size))
    }

    /**
     * Opens the app as it is now (connected or not): from the launcher, or with [intent] (an explicit intent for
     * MainActivity, such as [sharedTextIntent]).
     */
    protected fun launchApp(intent: Intent? = null) {
        scenario = if (intent == null) {
            ActivityScenario.launch(MainActivity::class.java)
        } else {
            ActivityScenario.launch<MainActivity>(intent)
        }
    }

    /** What another app's Share > Grocery Sync sends: ACTION_SEND with [text] as text/plain, for MainActivity. */
    protected fun sharedTextIntent(text: String): Intent =
        Intent(Intent.ACTION_SEND)
            .setClass(InstrumentationRegistry.getInstrumentation().targetContext, MainActivity::class.java)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)

    // ---- Waiting ----------------------------------------------------------------------------------------

    /**
     * Waits until [condition] holds (checked every few ms), else fails naming [what] and listing what the screen
     * shows at that moment ([screenTexts]).
     */
    protected fun waitFor(what: String, timeoutMs: Long = SYNC_TIMEOUT_MS, condition: () -> Boolean) {
        try {
            compose.waitUntil(what, timeoutMs, condition)
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("Waited $timeoutMs ms for $what. The screen shows: ${screenTexts()}", e)
        }
    }

    /** Waits until a node matching [matcher] is on screen (in any window, dialogs included) and returns it. */
    protected fun waitForNode(matcher: SemanticsMatcher, timeoutMs: Long = UI_TIMEOUT_MS): SemanticsNodeInteraction {
        waitFor("a node that ${matcher.description}", timeoutMs) { count(matcher) > 0 }
        return compose.onNode(matcher)
    }

    /** Waits until no node matches [matcher]. */
    protected fun waitForNoNode(matcher: SemanticsMatcher, timeoutMs: Long = UI_TIMEOUT_MS) {
        waitFor("no node that ${matcher.description}", timeoutMs) { count(matcher) == 0 }
    }

    /** How many nodes match [matcher] right now. */
    protected fun count(matcher: SemanticsMatcher): Int = nodes(matcher).size

    /** Fails when a node matches [matcher] right now, naming the texts of the nodes that do. */
    protected fun assertNoNode(matcher: SemanticsMatcher) {
        val found = nodes(matcher)
        if (found.isNotEmpty()) {
            throw AssertionError("Expected no node that ${matcher.description}, found ${found.size}: ${found.joinToString { describe(it) }}")
        }
    }

    /** Every text and content description on screen (all windows), for failure messages. Never typed text. */
    protected fun screenTexts(): String =
        nodes(SemanticsMatcher("any node") { true }).map(::describe).filter { it.isNotBlank() }.joinToString(" | ")

    private fun nodes(matcher: SemanticsMatcher): List<SemanticsNode> =
        compose.onAllNodes(matcher).fetchSemanticsNodes(atLeastOneRootRequired = false)

    /**
     * A node's own texts, content descriptions and state, as a failure message shows them. A text field is only
     * named as one: what was typed into it (a token, say) is left out.
     */
    private fun describe(node: SemanticsNode): String {
        val config = node.config
        return (
            config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                listOfNotNull(
                    config.getOrNull(SemanticsProperties.StateDescription)?.let { "state: $it" },
                    if (config.contains(SemanticsProperties.EditableText)) "[text field]" else null,
                )
            ).joinToString(" / ")
    }

    /**
     * Runs [block] (waiting for the fake, not the UI) with the UI's test clock stopped: a snackbar shown before
     * stays up, so its Undo can still be tapped afterwards. The app's own work (database, sync) runs on its real
     * dispatchers and goes on meanwhile.
     */
    protected fun <T> withUiClockStopped(block: () -> T): T {
        compose.mainClock.autoAdvance = false
        try {
            return block()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    // ---- Common nodes ------------------------------------------------------------------------------------

    protected fun node(matcher: SemanticsMatcher): SemanticsNodeInteraction = compose.onNode(matcher)

    protected fun addButton() = compose.onNodeWithContentDescription("Add item")

    protected fun searchButton() = compose.onNodeWithContentDescription("Search items")

    protected fun shareButton() = compose.onNodeWithContentDescription("Share list")

    /** The app bar's ⋮ button, which opens the menu (Add common items, ..., Settings). */
    protected fun moreOptionsButton() = compose.onNodeWithContentDescription("More options")

    /** Opens the app bar's ⋮ menu and taps [label] in it ("Settings", "Paste a list", ...). */
    protected fun chooseFromMenu(label: String) {
        moreOptionsButton().performClick()
        waitForNode(button(label)).performClick()
    }

    /** Opens Settings from the app bar's ⋮ menu. */
    protected fun openSettings() = chooseFromMenu("Settings")

    /** Taps the All items tab and waits until it is shown. */
    protected fun showAllItems() {
        node(tab("All items")).performClick()
        waitForNode(tab("All items") and isSelected())
    }

    companion object {
        /** This phone's name in Settings: what the app writes to "Updated By". */
        const val ME = "Shop"

        /** Something on screen reacting to a tap (includes animations). */
        const val UI_TIMEOUT_MS = 5_000L

        /** A change reaching the fake (about half a second) or the fake's change reaching the app (polled every 5 s). */
        const val SYNC_TIMEOUT_MS = 15_000L

        /** Waiting changes going out after an outage: the app retries after 5, 10, then 20 s. */
        const val RECOVERY_TIMEOUT_MS = 40_000L
    }
}
