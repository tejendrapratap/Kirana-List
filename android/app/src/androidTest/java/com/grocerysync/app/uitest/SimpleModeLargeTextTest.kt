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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import com.grocerysync.app.data.Units
import com.grocerysync.app.sync.ConnectionState
import com.grocerysync.app.sync.SyncStatus
import com.grocerysync.app.testing.UseTextsRule
import com.grocerysync.app.testing.isCutOff
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Simple mode at 200% text on a 360dp-wide phone: every "Got it" button, the Add item button and the Undo snackbar's
 * buttons are shown whole (nothing cut off by the list or the screen, no label running out of its button) and no
 * button lies over its item's name, in English and in Hindi, whose labels are longer.
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

    /** Each test sets its language when it draws the list; English again afterwards, also after a failure. */
    @get:Rule
    val english = UseTextsRule(AppTexts.English)

    private fun item(name: String, value: ItemValue): GroceryItem =
        GroceryItem(ItemKeys.normalize(name), name, value, updatedAt = 0L, updatedBy = "Home", pendingSync = false, addedAt = 0L)

    /** A long name, a custom unit and a yes / no item: the rows that need the most room. */
    private val items = listOf(
        item("Basmati rice extra long grain", ItemValue.Quantity(Amount.whole(5L), Units.KG)),
        item("Coriander", ItemValue.Quantity.of(2L, "bunches")),
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

    /** The list screen's content in Simple mode, in [texts], 360dp wide, at 200% text. */
    private fun showSimpleModeAtDoubleTextSize(texts: AppTexts) {
        Texts.current = texts
        val state = ListUiState(
            loaded = true,
            toBuy = items,
            allItems = items,
            namesByKey = items.associate { it.key to it.name },
            status = SyncStatus(ConnectionState.LIVE, System.currentTimeMillis(), null, "Groceries"),
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
        assertFalse("$what overflows", isCutOff(layouts.single()))
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
