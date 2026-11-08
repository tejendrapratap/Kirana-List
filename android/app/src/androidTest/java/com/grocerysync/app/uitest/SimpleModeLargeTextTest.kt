package com.grocerysync.app.uitest

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grocerysync.app.data.Amount
import com.grocerysync.app.data.GroceryItem
import com.grocerysync.app.data.ItemKeys
import com.grocerysync.app.data.ItemValue
import com.grocerysync.app.data.OutboxStatus
import com.grocerysync.app.data.Units
import com.grocerysync.app.sync.ConnectionState
import com.grocerysync.app.sync.Protocol
import com.grocerysync.app.sync.PushProblem
import com.grocerysync.app.sync.SyncStatus
import com.grocerysync.app.testing.itemRow
import com.grocerysync.app.text.AppTexts
import com.grocerysync.app.text.Texts
import com.grocerysync.app.ui.ListActions
import com.grocerysync.app.ui.ListContent
import com.grocerysync.app.ui.ListFilter
import com.grocerysync.app.ui.ListUiState
import com.grocerysync.app.ui.SearchState
import com.grocerysync.app.ui.components.spokenText
import com.grocerysync.app.ui.theme.GrocerySyncTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Simple mode at 200% text on a 360dp-wide phone: every "Got it" button, the Add item button and the Undo snackbar's
 * buttons are shown whole (nothing cut off by the list or the screen, no label running out of its button) and no
 * button lies over its item's name, in English and in Hindi, whose labels are longer. An amount that wraps onto
 * several lines keeps its text inside its badge's corners. A warning above the list (changes not sent, the script out
 * of date) leaves the list room for its rows and can be read to its end, Retry included.
 *
 * The font size is set through the screen's Density (fontScale 2, applied linearly: stricter than Android 14's own
 * 200%, which grows big text less), so the emulator's settings stay as they are. Only the list screen's content is
 * shown, with a list made here: no app, sheet or database is needed for how it is laid out.
 */
@RunWith(AndroidJUnit4::class)
class SimpleModeLargeTextTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var snackbars: SnackbarHostState
    private lateinit var scope: CoroutineScope

    @After
    fun backToEnglish() {
        Texts.current = AppTexts.English
    }

    private fun item(name: String, value: ItemValue): GroceryItem =
        GroceryItem(ItemKeys.normalize(name), name, value, updatedAt = 0L, updatedBy = "Home", pendingSync = false, addedAt = 0L)

    /**
     * A long name, custom units and a yes / no item: the rows that need the most room. The longest custom unit wraps
     * its badge onto three lines, with a wide line at the bottom.
     */
    private val items = listOf(
        item("Basmati rice extra long grain", ItemValue.Quantity(Amount.whole(5L), Units.KG)),
        item("Coriander", ItemValue.Quantity.of(2L, "bunches")),
        item("Atta", ItemValue.Quantity.of(2L, "packets (500 g)")),
        item("Dahi", ItemValue.BoolValue(true)),
    )

    private val noActions = ListActions(
        openSettings = {},
        openSearch = {},
        share = {},
        refresh = {},
        retrySync = {},
        retryFailed = {},
        discardFailed = {},
        add = {},
        edit = {},
        gotIt = {},
        setNeeded = { _, _ -> },
        stepQuantity = { _, _ -> },
        putBack = {},
        openStarterList = {},
        openPasteList = {},
        setGroupBySection = {},
    )

    /** Live, with no problem: the status line only. */
    private val live: SyncStatus get() = SyncStatus(ConnectionState.LIVE, System.currentTimeMillis(), null, "Groceries")

    /**
     * The list screen's content in Simple mode, in [texts], 360dp wide, at 200% text. [status] and [outbox] decide what
     * shows above the list.
     */
    private fun showSimpleModeAtDoubleTextSize(
        texts: AppTexts,
        status: SyncStatus = live,
        outbox: OutboxStatus = OutboxStatus(pending = 0, failed = emptyList()),
    ) {
        Texts.current = texts
        val state = ListUiState(
            loaded = true,
            toBuy = items,
            allItems = items,
            namesByKey = items.associate { it.key to it.name },
            outbox = outbox,
            status = status,
            isConfigured = true,
            myDeviceName = "Shop",
        )
        compose.setContent {
            snackbars = remember { SnackbarHostState() }
            scope = rememberCoroutineScope()
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                GrocerySyncTheme(dynamicColor = false) {
                    Box(
                        Modifier
                            .width(PHONE_WIDTH)
                            .fillMaxHeight()
                            .testTag(PHONE),
                    ) {
                        ListContent(
                            state = state,
                            pagerState = rememberPagerState { ListFilter.entries.size },
                            shownFilter = ListFilter.TO_BUY,
                            onFilterChange = {},
                            search = remember { SearchState() },
                            onCloseSearch = {},
                            snackbarHostState = snackbars,
                            actions = noActions,
                            simpleMode = true,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun inEnglishEverythingShowsWhole() = checkEverythingShowsWhole(AppTexts.English)

    @Test
    fun inHindiEverythingShowsWhole() = checkEverythingShowsWhole(AppTexts.Hindi)

    private fun checkEverythingShowsWhole(texts: AppTexts) {
        showSimpleModeAtDoubleTextSize(texts)
        val list = compose.onNode(hasScrollToNodeAction())

        for (item in items) {
            val gotIt = hasContentDescription(texts.list.gotItDescription(item.name)) and hasClickAction()
            list.performScrollToNode(gotIt)
            val button = compose.onNode(gotIt)
            assertWhole(button, "Got it of ${item.name}")
            assertNoOverflow(
                compose.onNode(hasText(texts.list.gotIt) and hasAnyAncestor(gotIt), useUnmergedTree = true),
                "the label of Got it of ${item.name}",
            )
            // The name, and the amount after or under it, end before the button starts.
            val buttonStart = button.getUnclippedBoundsInRoot().left
            val name = compose.onNode(hasText(item.name), useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue("${item.name} runs under its Got it", name.right <= buttonStart)
            (item.value as? ItemValue.Quantity)?.let { quantity ->
                val amount = compose.onNode(
                    hasContentDescription(texts.list.amountDescription(quantity.spokenText())),
                    useUnmergedTree = true,
                )
                assertWhole(amount, "the amount of ${item.name}")
                assertBadgeHoldsItsText(amount, "the amount of ${item.name}")
                assertTrue("the amount of ${item.name} runs under its Got it", amount.getUnclippedBoundsInRoot().right <= buttonStart)
            }
            val row = compose.onNode(itemRow(item.name)).getUnclippedBoundsInRoot()
            assertTrue("the row of ${item.name} is ${row.height} tall", row.height >= 64.dp)
        }

        assertWhole(compose.onNode(hasContentDescription(texts.common.addItem) and hasClickAction()), "Add item")

        // An Undo snackbar with a long name, above the Add item button.
        val message = texts.list.gotItSnackbar("Basmati rice extra long grain")
        compose.runOnIdle {
            scope.launch {
                snackbars.showSnackbar(
                    message = message,
                    actionLabel = texts.common.undo,
                    withDismissAction = true,
                    duration = SnackbarDuration.Indefinite,
                )
            }
        }
        compose.waitUntil(5_000L) {
            compose.onAllNodes(hasText(message)).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        assertNoOverflow(compose.onNode(hasText(message), useUnmergedTree = true), "the snackbar's message")
        assertWhole(compose.onNode(hasText(texts.common.undo) and hasClickAction()), "Undo")
        assertWhole(compose.onNode(hasContentDescription(texts.common.close) and hasClickAction()), "the snackbar's X")
    }

    @Test
    fun inHindiAPushWarningLeavesRoomForTheList() = checkWarningsLeaveRoomForTheList(AppTexts.Hindi, pushFailing)

    @Test
    fun inEnglishAPushWarningLeavesRoomForTheList() = checkWarningsLeaveRoomForTheList(AppTexts.English, pushFailing)

    @Test
    fun inHindiTheScriptUpdateLineLeavesRoomForTheList() =
        checkWarningsLeaveRoomForTheList(AppTexts.Hindi, live.copy(scriptOutdated = true))

    /** Live, but Google Sheets failed the last two pushes: the warning with Retry shows (changes are waiting). */
    private val pushFailing: SyncStatus
        get() = live.copy(pushProblem = PushProblem(Protocol.MSG_GOOGLE_TROUBLE, failures = 2, mayBeMomentary = true))

    /**
     * With [status] (a warning) and changes waiting, the first row of To buy shows whole without scrolling: the lines
     * above the list take only part of the screen. Past that they scroll, so all of the warning, and its Retry, can be
     * read.
     */
    private fun checkWarningsLeaveRoomForTheList(texts: AppTexts, status: SyncStatus) {
        showSimpleModeAtDoubleTextSize(texts, status, OutboxStatus(pending = 3, failed = emptyList()))

        val first = items.first()
        val firstGotIt = hasContentDescription(texts.list.gotItDescription(first.name)) and hasClickAction()
        assertWhole(compose.onNode(firstGotIt), "Got it of ${first.name}")
        if (status.pushProblem != null) {
            val retryButton = hasContentDescription(texts.list.retrySendingChanges) and hasClickAction()
            compose.onNode(retryButton).performScrollTo()
            assertWhole(compose.onNode(retryButton), "Retry")
            assertNoOverflow(
                compose.onNode(hasText(texts.common.retry) and hasAnyAncestor(retryButton), useUnmergedTree = true),
                "the label of Retry",
            )
        }
    }

    /**
     * The badge [node] is painted where its text can be: at the corners of its text area (12dp in from its sides, 16dp
     * from its top and bottom), where a one-line badge is round enough not to cut its text. A pill as tall as a badge
     * that wraps leaves those corners outside its round ends, cutting off the ends of its widest lines.
     */
    private fun assertBadgeHoldsItsText(node: SemanticsNodeInteraction, what: String) {
        val image = node.captureToImage().toPixelMap()
        val inX = with(compose.density) { 12.dp.roundToPx() } + 1
        val inY = with(compose.density) { 16.dp.roundToPx() }
        // A corner of the badge's box is outside its rounded shape: the colour under the badge.
        val under = image[0, 0]
        val right = image.width - 1 - inX
        val bottom = image.height - 1 - inY
        for ((x, y) in listOf(inX to inY, right to inY, inX to bottom, right to bottom)) {
            assertNotEquals("$what is cut off at ($x, $y) of ${image.width} x ${image.height} px", under, image[x, y])
        }
    }

    /** [node] is on screen, inside the phone's width and height, and nothing of it is cut off. */
    private fun assertWhole(node: SemanticsNodeInteraction, what: String) {
        node.assertIsDisplayed()
        val shown = node.getBoundsInRoot()
        val whole = node.getUnclippedBoundsInRoot()
        assertSame(whole, shown, "$what is cut off")
        val phone = compose.onNodeWithTag(PHONE).getUnclippedBoundsInRoot()
        assertTrue(
            "$what ($whole) is outside the phone ($phone)",
            whole.left >= phone.left - SLACK && whole.right <= phone.right + SLACK &&
                whole.top >= phone.top - SLACK && whole.bottom <= phone.bottom + SLACK,
        )
    }

    private fun assertSame(expected: DpRect, actual: DpRect, message: String) {
        val edges = listOf(
            expected.left to actual.left,
            expected.top to actual.top,
            expected.right to actual.right,
            expected.bottom to actual.bottom,
        )
        for ((a, b) in edges) assertEquals("$message: $actual of $expected", a.value, b.value, SLACK.value)
    }

    /** The text of [node] fits in the room it was given: no line runs past it, and none is cut off below. */
    private fun assertNoOverflow(node: SemanticsNodeInteraction, what: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        val getLayout = node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action
        assertTrue("$what has no text layout", getLayout?.invoke(layouts) == true)
        assertFalse("$what overflows", layouts.single().hasVisualOverflow)
    }

    private companion object {
        /** The narrow phone the content is shown on (the emulator's screen is at least as wide). */
        val PHONE_WIDTH = 360.dp

        /** A test tag of this test's own box, not of the app: the app has none. */
        const val PHONE = "phone"

        /** Rounding of px to dp. */
        val SLACK = 0.5.dp
    }
}
