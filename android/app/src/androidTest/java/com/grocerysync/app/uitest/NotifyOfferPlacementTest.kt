package com.grocerysync.app.uitest

import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grocerysync.app.data.GroceryItem
import com.grocerysync.app.data.ItemKeys
import com.grocerysync.app.data.ItemValue
import com.grocerysync.app.sync.ConnectionState
import com.grocerysync.app.sync.SyncStatus
import com.grocerysync.app.testing.UseTextsRule
import com.grocerysync.app.testing.itemRow
import com.grocerysync.app.text.AppTexts
import com.grocerysync.app.text.EnglishListTexts
import com.grocerysync.app.text.EnglishNotifyTexts
import com.grocerysync.app.ui.ListActions
import com.grocerysync.app.ui.ListContent
import com.grocerysync.app.ui.ListFilter
import com.grocerysync.app.ui.ListUiState
import com.grocerysync.app.ui.SearchState
import com.grocerysync.app.ui.components.NotifyOfferCard
import com.grocerysync.app.ui.theme.GrocerySyncTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where the list screen puts the offer of change notifications ([ListContent]'s notifyOffer, which ListScreen fills
 * with NotifyOffer): first in the banner header, above a banner about the connection and above the rows, in the usual
 * screen and in Simple mode. The card itself is given here, so this runs whatever the emulator's notification
 * permission is (NotifyOfferOnListTest shows the real offer in the app, where the permission decides).
 */
@RunWith(AndroidJUnit4::class)
class NotifyOfferPlacementTest {

    @get:Rule
    val compose = createComposeRule()

    /** The checks below match English texts: a Hindi test that ran before mustn't leave the card in Hindi. */
    @get:Rule
    val english = UseTextsRule(AppTexts.English)

    private val offer = hasText(EnglishNotifyTexts.offerQuestion)
    private val errorBanner = hasText(EnglishListTexts.syncErrorFallback)

    private val dahi = GroceryItem(
        ItemKeys.normalize("Dahi"), "Dahi", ItemValue.BoolValue(true), updatedAt = 0L, updatedBy = "Home", pendingSync = false,
    )

    /** A set-up list with Dahi to buy whose last sync failed, so a banner about the connection shows too. */
    private val state = ListUiState(
        loaded = true,
        toBuy = listOf(dahi),
        allItems = listOf(dahi),
        namesByKey = mapOf(dahi.key to dahi.name),
        status = SyncStatus(ConnectionState.ERROR, System.currentTimeMillis(), null, "Groceries"),
        isConfigured = true,
        myDeviceName = "Shop",
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

    private fun showList(simpleMode: Boolean) {
        compose.setContent {
            GrocerySyncTheme(dynamicColor = false) {
                ListContent(
                    state = state,
                    pagerState = rememberPagerState { ListFilter.entries.size },
                    shownFilter = ListFilter.TO_BUY,
                    onFilterChange = {},
                    search = remember { SearchState() },
                    onCloseSearch = {},
                    snackbarHostState = remember { SnackbarHostState() },
                    actions = noActions,
                    simpleMode = simpleMode,
                    notifyOffer = { NotifyOfferCard(onTurnOn = {}, onNotNow = {}) },
                )
            }
        }
    }

    /** The offer is on screen, above the banner about the connection, which is above Dahi's row. */
    private fun assertTheOfferComesFirst() {
        val card = compose.onNode(offer).assertIsDisplayed().getUnclippedBoundsInRoot()
        val banner = compose.onNode(errorBanner).assertIsDisplayed().getUnclippedBoundsInRoot()
        val row = compose.onNode(itemRow("Dahi")).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("The offer ($card) should be above the banner ($banner)", card.bottom <= banner.top)
        assertTrue("The banner ($banner) should be above the rows ($row)", banner.bottom <= row.top)
    }

    @Test
    fun theOfferIsFirstAboveTheList() {
        showList(simpleMode = false)
        assertTheOfferComesFirst()
    }

    @Test
    fun simpleModeHasItFirstToo() {
        showList(simpleMode = true)
        assertTheOfferComesFirst()
    }
}
