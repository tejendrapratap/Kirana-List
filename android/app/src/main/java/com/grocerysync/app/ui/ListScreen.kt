package com.grocerysync.app.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.grocerysync.app.data.Amount
import com.grocerysync.app.data.GroceryItem
import com.grocerysync.app.data.ItemCatalog
import com.grocerysync.app.data.ItemValue
import com.grocerysync.app.data.OutboxStatus
import com.grocerysync.app.data.ShopSectionSettings
import com.grocerysync.app.data.Units
import com.grocerysync.app.data.ValueType
import com.grocerysync.app.sync.ConnectionState
import com.grocerysync.app.sync.SyncStatus
import com.grocerysync.app.ui.components.AllItemsRow
import com.grocerysync.app.ui.components.EmptyListState
import com.grocerysync.app.ui.components.FailedChangesBanner
import com.grocerysync.app.ui.components.LiveStatusLine
import com.grocerysync.app.ui.components.NoSearchMatches
import com.grocerysync.app.ui.components.PushWarningLine
import com.grocerysync.app.ui.components.ScriptUpdateLine
import com.grocerysync.app.ui.components.SetupBanner
import com.grocerysync.app.ui.components.ShopSectionHeader
import com.grocerysync.app.ui.components.SyncErrorBanner
import com.grocerysync.app.ui.components.ToBuyRow
import com.grocerysync.app.ui.components.rememberTickingTime
import com.grocerysync.app.ui.theme.GrocerySyncTheme
import com.grocerysync.app.ui.theme.selectorColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/** Keeps the last rows clear of the FAB. */
private val FabClearance = 88.dp

/** Row texts ("2 min ago", highlight of recent changes) only need to move every 30 s. */
private const val ROW_CLOCK_PERIOD_MS = 30_000L

/**
 * Search on the page shown. It opens from the app bar's Search icon on either page and closes with the
 * field's X or Back; switching pages keeps it open with its query, which then searches the page now shown.
 * [open] and [query] are saved (rotation, process death, a trip to Settings); focus is not, so the
 * keyboard never comes back by itself.
 */
@Stable
private class SearchState(open: Boolean = false, query: String = "") {
    var open by mutableStateOf(open)
        private set
    var query by mutableStateOf(query)

    /** Increased each time the field should take focus and bring up the keyboard. Not saved. */
    var focusRequests by mutableIntStateOf(0)
        private set

    /** Shows the field (if needed) and focuses it. */
    fun openAndFocus() {
        open = true
        focusRequests++
    }

    /** Clears the query and hides the field. */
    fun close() {
        query = ""
        open = false
    }

    companion object {
        val Saver = listSaver<SearchState, Any>(
            save = { listOf(it.open, it.query) },
            restore = { SearchState(open = it[0] as Boolean, query = it[1] as String) },
        )
    }
}

/** Saves [SearchScroll] with the lists' scroll positions: its query, then the names of the pages at the top. */
private val SearchScrollSaver = listSaver<SearchScroll, String>(
    save = { listOf(it.query) + it.pagesAtTop.map { page -> page.name } },
    restore = { SearchScroll(query = it[0], pagesAtTop = it.drop(1).map(ListFilter::valueOf)) },
)

/**
 * Switches the pager's page from the segmented row (or To buy's "Show n in All items") with the pager's
 * animation. [shownPage] is the page the segmented row highlights: a tapped page at once, otherwise the pager's
 * current page, which only changes once a swipe passes halfway, so the highlight never flickers.
 */
@Stable
private class PageSwitcher(private val pagerState: PagerState, private val scope: CoroutineScope) {
    /** The page tapped last, until the pager has got there or the user takes over by dragging. */
    private var requestedPage by mutableStateOf<Int?>(null)
    private var requests = 0

    val shownPage: Int
        get() = requestedPage ?: pagerState.currentPage

    fun show(page: Int) {
        val request = ++requests
        requestedPage = page
        scope.launch {
            try {
                pagerState.animateScrollToPage(page)
            } finally {
                // A later tap owns the highlight now; a drag (which cancels the animation) gives it back to the pager.
                if (request == requests) requestedPage = null
            }
        }
    }
}

@Composable
fun ListScreen(
    onOpenSettings: () -> Unit,
    viewModel: ListViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val dialog by viewModel.dialog.collectAsStateWithLifecycle()
    val starterListOpen by viewModel.starterListOpen.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    // Saved: the page shown survives rotation, process death and a trip to Settings.
    val pagerState = rememberPagerState(initialPage = ListFilter.TO_BUY.page) { ListFilter.entries.size }
    val pages = remember(pagerState, scope) { PageSwitcher(pagerState, scope) }
    val search = rememberSaveable(saver = SearchState.Saver) { SearchState() }
    var lastAddType by rememberSaveable { mutableStateOf(ValueType.BOOLEAN) }
    // The name the next Add dialog starts with: the search's suggestion, or "" for the + button.
    var addName by rememberSaveable { mutableStateOf("") }

    // Restored in the middle of a page change (rotated during a swipe or a tap's animation): finish it, so
    // the pager never stays drawn between two pages. Switching pages never closes the search.
    LaunchedEffect(pagerState) {
        pageToSettleOnRestore(pagerState.currentPage, pagerState.currentPageOffsetFraction)?.let { page ->
            try {
                pagerState.scrollToPage(page)
            } catch (e: CancellationException) {
                // A drag took over (and settles the pager itself); only a cancelled effect stops here.
                ensureActive()
            }
        }
    }
    // Back with the keyboard up only hides it (the system does that); the next Back closes the search.
    BackHandler(enabled = search.open) {
        focusManager.clearFocus()
        search.close()
    }

    ListEventsEffect(
        events = viewModel.events,
        snackbarHostState = snackbarHostState,
        onUndoGotIt = viewModel::undoGotIt,
        onUndoDelete = viewModel::undoDelete,
        onUndoCommonItems = viewModel::undoAddCommonItems,
        addedText = { name, value ->
            val shown = filterOfPage(pages.shownPage)
            addedSnackbarText(name, value.isNeeded, shown, if (search.open) search.query else "")
        },
    )

    // Everything that opens a dialog or leaves the screen clears focus first, so the keyboard never
    // stays over a dialog or comes back when it closes.
    val actions = remember(viewModel, onOpenSettings, focusManager, search, context, scope, snackbarHostState) {
        ListActions(
            openSettings = {
                focusManager.clearFocus()
                onOpenSettings()
            },
            // On the page shown: the search never switches pages.
            openSearch = search::openAndFocus,
            // Every needed item, whatever the search shows, grouped like To buy: the same sections, this phone's
            // choices from the Edit dialog included, or A to Z while grouping is off.
            share = {
                focusManager.clearFocus()
                val current = viewModel.uiState.value
                shareToBuy(
                    context = context,
                    toBuy = current.toBuy,
                    sectionOf = if (current.groupBySection) current::sectionOf else null,
                ) { message -> scope.showReplacingSnackbar(snackbarHostState, message) }
            },
            refresh = viewModel::refresh,
            retrySync = viewModel::retrySync,
            retryFailed = viewModel::retryFailed,
            discardFailed = viewModel::discardFailed,
            add = { name ->
                focusManager.clearFocus()
                addName = name
                viewModel.openAdd()
            },
            edit = { key ->
                focusManager.clearFocus()
                viewModel.openEdit(key)
            },
            gotIt = viewModel::markGotIt,
            setNeeded = viewModel::setNeeded,
            stepQuantity = viewModel::stepQuantity,
            // ⋮ > Add common items, and the empty list's button.
            openStarterList = {
                focusManager.clearFocus()
                viewModel.openStarterList()
            },
            // ⋮ > Paste a list. A list shared into the app opens the same dialog (MainActivity).
            openPasteList = {
                focusManager.clearFocus()
                viewModel.openPasteList()
            },
            setGroupBySection = viewModel::setGroupBySection,
        )
    }

    ListContent(
        state = state,
        pagerState = pagerState,
        shownFilter = filterOfPage(pages.shownPage),
        // Also To buy's "Show n in All items". An open search stays open and searches the page now shown.
        onFilterChange = { filter ->
            focusManager.clearFocus()
            pages.show(filter.page)
        },
        search = search,
        onCloseSearch = {
            focusManager.clearFocus()
            search.close()
        },
        snackbarHostState = snackbarHostState,
        actions = actions,
    )

    when (val current = dialog) {
        null -> Unit
        ItemDialog.Add -> key("add") {
            ItemEditDialog(
                item = null,
                existingNames = state.namesByKey,
                defaultType = lastAddType,
                onDismiss = viewModel::closeDialog,
                onSave = { name, value, _ ->
                    lastAddType = value.type
                    // Where the new item shows up is said once it is saved (ListEvent.Added).
                    viewModel.saveItem(originalKey = null, openedValue = null, name = name, value = value)
                },
                onDelete = {},
                // Replaces this dialog with the edit dialog of the item already on the list.
                onOpenExisting = viewModel::openEdit,
                initialName = addName,
            )
        }
        is ItemDialog.Edit -> key("edit:${current.key}") {
            EditItemDialog(key = current.key, state = state, viewModel = viewModel)
        }
    }

    if (starterListOpen) {
        StarterListDialog(
            onList = state.allItems,
            onDismiss = viewModel::closeStarterList,
            onAdd = viewModel::addCommonItems,
        )
    }

    // "Paste a list" and lists shared into the app; above an item dialog that may be open.
    PasteListHost(viewModel)
}

/**
 * The edit dialog for [key]. If the item disappears while the dialog is open (renamed or deleted on
 * the other phone), the dialog stays with the last known item and says so, keeping what the user typed.
 * The item is looked up in the whole list, whatever the search shows.
 */
@Composable
private fun EditItemDialog(key: String, state: ListUiState, viewModel: ListViewModel) {
    val live = state.item(key)
    // A plain holder (not state): it is only read in the same composition that updates it.
    val lastKnown = remember { arrayOfNulls<GroceryItem>(1) }
    if (live != null) lastKnown[0] = live
    val item = live ?: lastKnown[0]

    when {
        item != null -> ItemEditDialog(
            item = item,
            existingNames = state.namesByKey,
            defaultType = item.value.type,
            onDismiss = viewModel::closeDialog,
            onSave = { name, value, baseValue ->
                viewModel.saveItem(originalKey = key, openedValue = baseValue, name = name, value = value)
            },
            onDelete = { viewModel.deleteItem(key) },
            removedElsewhere = live == null,
            // This phone's choice for the item, else the dictionary's section for the name being typed.
            shopSection = { name -> state.shopSections.chosen[key] ?: ItemCatalog.sectionFor(name) },
            onShopSectionChange = { name, section -> viewModel.setShopSection(key, name, section) },
        )
        // Gone before this screen saw it (e.g. after the app was restarted with the dialog open).
        state.loaded -> LaunchedEffect(key) { viewModel.onEditedItemRemoved(null) }
    }
}

/** Callbacks of the list screen, grouped so the stateless content stays previewable. */
class ListActions(
    val openSettings: () -> Unit,
    /** The app bar's Search icon: shows the search field, focused, on the page shown (no page switch). */
    val openSearch: () -> Unit,
    /** The app bar's Share icon: the To buy list as a message, through Android's share sheet ([shareToBuy]). */
    val share: () -> Unit,
    val refresh: () -> Unit,
    val retrySync: () -> Unit,
    val retryFailed: () -> Unit,
    val discardFailed: () -> Unit,
    /** Opens the Add dialog with [initialName] in its name field ("" for an empty one). */
    val add: (initialName: String) -> Unit,
    val edit: (key: String) -> Unit,
    val gotIt: (key: String) -> Unit,
    val setNeeded: (key: String, needed: Boolean) -> Unit,
    val stepQuantity: (key: String, up: Boolean) -> Unit,
    /** ⋮ > Add common items, and the empty list's button: opens [StarterListDialog]. */
    val openStarterList: () -> Unit,
    /** ⋮ > Paste a list: adds the items of a pasted message. */
    val openPasteList: () -> Unit,
    /** ⋮ > Group by shop section: turns grouping To buy by shop section on or off (this phone only). */
    val setGroupBySection: (Boolean) -> Unit,
)

/**
 * The screen: status lines, the To buy / All items row, the search field while search is open (shared by
 * both pages: it searches the page shown), and a pager with one list per page (swipe or tap the row to
 * switch). Each page keeps its own scroll position, banners and pull to refresh. The app bar menu's
 * "Group by shop section" tick ([ListTopBar]) shows [ListUiState.groupBySection].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListContent(
    state: ListUiState,
    pagerState: PagerState,
    shownFilter: ListFilter,
    onFilterChange: (ListFilter) -> Unit,
    search: SearchState,
    onCloseSearch: () -> Unit,
    snackbarHostState: SnackbarHostState,
    actions: ListActions,
) {
    val focusManager = LocalFocusManager.current
    // Hoisted (not inside the pages), so a page keeps its scroll position while the other one is shown.
    val toBuyListState = rememberLazyListState()
    val allListState = rememberLazyListState()
    fun listStateOf(filter: ListFilter) = if (filter == ListFilter.TO_BUY) toBuyListState else allListState
    // Which lists already went to the top for the search's query; saved like their scroll positions.
    val searchScroll = rememberSaveable(saver = SearchScrollSaver) { SearchScroll() }
    // Dragging either list or swiping between the pages hides the keyboard.
    LaunchedEffect(toBuyListState, allListState, pagerState, focusManager) {
        merge(
            toBuyListState.interactionSource.interactions,
            allListState.interactionSource.interactions,
            pagerState.interactionSource.interactions,
        ).collect { if (it is DragInteraction.Start) focusManager.clearFocus() }
    }
    val query = if (search.open) search.query else ""
    val rowClock = rememberTickingTime(ROW_CLOCK_PERIOD_MS)
    val now = { rowClock.value }

    Scaffold(
        topBar = { ListTopBar(groupBySection = state.groupBySection, actions = actions) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            // Always a blank Add dialog, also during a search. Marigold, the app's accent for adding.
            FloatingActionButton(
                onClick = { actions.add("") },
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add item")
            }
        },
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        Column(
            Modifier
                .fillMaxSize()
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    start = innerPadding.calculateStartPadding(layoutDirection),
                    end = innerPadding.calculateEndPadding(layoutDirection),
                ),
        ) {
            // Below the app bar (not in its fixed-height title slot) so large text never clips it. About as much
            // space above it as below (the selector's 8dp plus its touch target's margin), off the green bar's edge.
            LiveStatusLine(
                status = state.status,
                isConfigured = state.isConfigured,
                modifier = Modifier.padding(horizontal = 16.dp).padding(top = 10.dp),
            )
            // Outside the list, so they are in view however far the list is scrolled.
            if (state.scriptUpdateNeeded) {
                ScriptUpdateLine(
                    pendingItems = state.outbox.pending,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(top = 6.dp),
                )
            }
            state.pushWarning?.let { problem ->
                PushWarningLine(
                    problem = problem,
                    pendingItems = state.outbox.pending,
                    // A manual sync pushes at once, skipping the push backoff.
                    onRetry = actions.retrySync,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(top = 6.dp),
                )
            }
            FilterRow(
                filter = shownFilter,
                toBuyCount = state.toBuy.size,
                allCount = state.allItems.size,
                onFilterChange = onFilterChange,
            )
            // Outside the pager: it never scrolls away, and a swipe on it never changes the page.
            AnimatedVisibility(visible = search.open) {
                SearchField(
                    query = search.query,
                    onQueryChange = { text ->
                        val clean = ItemSearch.cleanInput(text)
                        // New results start at the top: here (with them) on the page typed on; on the other page
                        // only once it is shown (see the pager), so it keeps its place if the search ends first.
                        // Closing or emptying the search keeps the place.
                        if (searchScroll.takeScrollToTop(shownFilter, clean)) {
                            listStateOf(shownFilter).requestScrollToItem(0)
                        }
                        search.query = clean
                    },
                    onSearch = { focusManager.clearFocus() },
                    onClose = onCloseSearch,
                    focusRequests = search.focusRequests,
                    // The gap below is the list's header item (see bannerHeader).
                    modifier = Modifier
                        .padding(start = 16.dp, top = 8.dp, end = 16.dp)
                        .fillMaxWidth(),
                )
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) { page ->
                val filter = filterOfPage(page)
                val listState = listStateOf(filter)
                // Shown (tapped, swiped into view, "Show n in All items") with a query it hasn't shown yet: its
                // results start at the top too. Only visible pages are composed, and a side effect runs before the
                // list is measured, so the results never show at the old place. The page shown also passes ""
                // here once the search is closed, so a search typed again later starts over.
                SideEffect {
                    if (searchScroll.takeScrollToTop(filter, query)) listState.requestScrollToItem(0)
                }
                val content = remember(filter, state.loaded, state.toBuy, state.allItems, query, state.toBuySections) {
                    pageContent(filter, state.loaded, state.toBuy, state.allItems, query, toBuySections = state.toBuySections)
                }
                PullToRefreshBox(
                    isRefreshing = state.isRefreshing,
                    onRefresh = actions.refresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        // The list scrolls behind the navigation bar; its last rows stay above the FAB.
                        contentPadding = PaddingValues(bottom = innerPadding.calculateBottomPadding() + FabClearance),
                    ) {
                        bannerHeader(state, actions)
                        pageItems(filter, content, state, now, actions, onShowAllItems = { onFilterChange(ListFilter.ALL) })
                    }
                }
            }
        }
    }
}

/**
 * The search field: a Material text field styled as a search pill (no experimental SearchBar, whose field
 * fixes the keyboard options). [focusRequests] > 0 focuses it and brings up the keyboard, again each time
 * it grows (the Search icon tapped while the field is open). The X closes the search ([onClose]); the
 * keyboard's Search key only hides the keyboard ([onSearch]), as results are already live.
 */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClose: () -> Unit,
    focusRequests: Int,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(focusRequests) {
        if (focusRequests == 0) return@LaunchedEffect
        // A field that has just appeared may only be attached in the next frame.
        if (!focusRequester.requestFocus()) {
            withFrameNanos { }
            focusRequester.requestFocus()
        }
        // Already focused with the keyboard hidden (Back): focusing again doesn't show it.
        keyboard?.show()
    }
    val container = MaterialTheme.colorScheme.surfaceContainerHigh
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.focusRequester(focusRequester),
        singleLine = true,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = container,
            unfocusedContainerColor = container,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        // No label or contentDescription: the placeholder is the hint, and TalkBack reads the typed text.
        placeholder = { Text("Search items", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        // One control: closing clears the query too (a search is typed again in two taps).
        trailingIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close search")
            }
        },
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterRow(
    filter: ListFilter,
    toBuyCount: Int,
    allCount: Int,
    onFilterChange: (ListFilter) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            // The gap below is the list's header item (see bannerHeader).
            .padding(start = 16.dp, top = 8.dp, end = 16.dp),
    ) {
        SegmentedButton(
            selected = filter == ListFilter.TO_BUY,
            onClick = { onFilterChange(ListFilter.TO_BUY) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            colors = selectorColors(),
        ) { Text("To buy ($toBuyCount)", maxLines = 1) }
        SegmentedButton(
            selected = filter == ListFilter.ALL,
            onClick = { onFilterChange(ListFilter.ALL) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            colors = selectorColors(),
        ) { Text("All items ($allCount)", maxLines = 1) }
    }
}

// Item keys are prefixed so they can never collide with the header's key.
private fun itemKey(item: GroceryItem): String = "item:" + item.key

/**
 * The list's first item, always present and never empty (its top padding is the gap under the filter
 * row), holding the banners. A banner that appears while the list is at the top grows this item in
 * place and pushes the rows down. As an item of its own, inserted in front of the rows, it would land
 * above the visible part: a lazy list keeps its first visible row where it is. Never filtered by search.
 */
private fun LazyListScope.bannerHeader(state: ListUiState, actions: ListActions) {
    item(key = "header", contentType = "header") {
        Column(
            Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(top = 8.dp),
        ) {
            if (!state.isConfigured) {
                SetupBanner(onSetUp = actions.openSettings)
            } else if (state.status.state == ConnectionState.ERROR) {
                SyncErrorBanner(
                    message = state.status.message,
                    onOpenSettings = actions.openSettings,
                    onRetry = actions.retrySync,
                )
            }
            if (state.outbox.failed.isNotEmpty()) {
                FailedChangesBanner(
                    failed = state.outbox.failed,
                    onRetry = actions.retryFailed,
                    onDiscard = actions.discardFailed,
                )
            }
        }
    }
}

/**
 * The rows of the page showing [filter], as [content] says ([pageContent]): its items, its empty state, or
 * (search active, nothing matches on this page) the no-match line with one row: [onShowAllItems] for To buy's
 * "Show n in All items", or the Add dialog with the query as the name.
 */
private fun LazyListScope.pageItems(
    filter: ListFilter,
    content: PageContent,
    state: ListUiState,
    now: () -> Long,
    actions: ListActions,
    onShowAllItems: () -> Unit,
) {
    when (content) {
        PageContent.NotLoaded -> Unit
        PageContent.Empty -> emptyPage(filter, state, actions)
        is PageContent.Rows -> when (filter) {
            ListFilter.TO_BUY -> toBuyRows(content.items, state, now, actions)
            ListFilter.ALL -> allItemsRows(content.items, state, now, actions)
        }
        is PageContent.Sections -> toBuySectionRows(content.groups, state, now, actions)
        is PageContent.NoMatch -> noSearchMatches(content, actions, onShowAllItems)
    }
}

/**
 * A page's empty state. While the whole list is empty, with a button to "Add common items" once the family's list
 * has come from the sheet ([ListUiState.hasSheetList]). Before setup the setup banner shows the way; after it, until
 * the first sync, the page says the list hasn't loaded yet (the status line or a banner says why it takes long).
 */
private fun LazyListScope.emptyPage(filter: ListFilter, state: ListUiState, actions: ListActions) {
    val listEmpty = state.allItems.isEmpty()
    val notLoaded = listEmpty && state.isConfigured && !state.hasSheetList
    val starterList = if (listEmpty && state.hasSheetList) StarterList.TITLE else null
    when (filter) {
        ListFilter.TO_BUY -> item(key = "empty:to-buy", contentType = "empty") {
            EmptyListState(
                title = if (notLoaded) LIST_NOT_LOADED_TITLE else "Nothing to buy right now",
                hint = when {
                    notLoaded -> LIST_NOT_LOADED_HINT
                    listEmpty -> "Tap + to add the things you usually buy. Mark them as needed when you run out."
                    else -> "Everything is ticked off. Open All items to mark what you need."
                },
                modifier = Modifier.animateItem(),
                actionText = starterList,
                onAction = actions.openStarterList,
            )
        }
        ListFilter.ALL -> item(key = "empty:all", contentType = "empty") {
            EmptyListState(
                title = if (notLoaded) LIST_NOT_LOADED_TITLE else "Your list is empty",
                hint = if (notLoaded) LIST_NOT_LOADED_HINT else "Tap + to add your first item.",
                modifier = Modifier.animateItem(),
                actionText = starterList,
                onAction = actions.openStarterList,
            )
        }
    }
}

/** The empty pages while the family's list hasn't come from the sheet yet (right after setup). */
private const val LIST_NOT_LOADED_TITLE = "Your list hasn't loaded yet"
private const val LIST_NOT_LOADED_HINT = "It shows here once it has come from the sheet."

/** To buy's rows: every needed item, or (search active) the needed items that match, A to Z; or a section's. */
private fun LazyListScope.toBuyRows(items: List<GroceryItem>, state: ListUiState, now: () -> Long, actions: ListActions) {
    items(items, key = ::itemKey, contentType = { "to-buy" }) { item ->
        ToBuyRow(
            item = item,
            myDeviceName = state.myDeviceName,
            now = now,
            onGotIt = { actions.gotIt(item.key) },
            onClick = { actions.edit(item.key) },
            modifier = Modifier.animateItem(),
        )
    }
}

/**
 * To buy by shop section: per section a sticky header (its name and count; a heading for TalkBack), then its rows
 * as [toBuyRows] draws them. Header keys can't collide with item keys ("item:..."), and each item is in one section.
 */
private fun LazyListScope.toBuySectionRows(groups: List<ShopSectionGroup>, state: ListUiState, now: () -> Long, actions: ListActions) {
    for (group in groups) {
        stickyHeader(key = "section:" + group.section.id, contentType = "section") {
            ShopSectionHeader(section = group.section, count = group.items.size, modifier = Modifier.animateItem())
        }
        toBuyRows(group.items, state, now, actions)
    }
}

/** All items' rows: every item, needed first, or (search active) the items that match, A to Z. */
private fun LazyListScope.allItemsRows(items: List<GroceryItem>, state: ListUiState, now: () -> Long, actions: ListActions) {
    items(items, key = ::itemKey, contentType = { "all" }) { item ->
        AllItemsRow(
            item = item,
            myDeviceName = state.myDeviceName,
            now = now,
            onNeededChange = { needed -> actions.setNeeded(item.key, needed) },
            onQuantityStep = { up -> actions.stepQuantity(item.key, up) },
            onClick = { actions.edit(item.key) },
            modifier = Modifier.animateItem(),
        )
    }
}

/** The no-match line and its row (see [NoMatchAction]). */
private fun LazyListScope.noSearchMatches(content: PageContent.NoMatch, actions: ListActions, onShowAllItems: () -> Unit) {
    // One key for every query, so it isn't inserted again with each letter typed; one call, so a row that turns
    // from "Show n in All items" into "Add ..." (or back) changes in place and the line isn't announced again.
    item(key = "empty:search", contentType = "empty") {
        val action = content.action
        NoSearchMatches(
            message = content.message,
            actionText = action.text,
            actionIcon = when (action) {
                is NoMatchAction.ShowInAllItems -> Icons.AutoMirrored.Filled.ArrowForward
                is NoMatchAction.Add -> Icons.Default.Add
            },
            onAction = {
                when (action) {
                    is NoMatchAction.ShowInAllItems -> onShowAllItems()
                    is NoMatchAction.Add -> actions.add(action.name)
                }
            },
            modifier = Modifier.animateItem(),
        )
    }
}

/**
 * Shows view-model events as snackbars while the screen is visible; a new one replaces the current one.
 * [addedText] says where an item just added shows up when the page shown hides it (null: no snackbar).
 */
@Composable
private fun ListEventsEffect(
    events: Flow<ListEvent>,
    snackbarHostState: SnackbarHostState,
    onUndoGotIt: (name: String, value: ItemValue) -> Unit,
    onUndoDelete: (name: String, value: ItemValue) -> Unit,
    onUndoCommonItems: (List<AddedItem>) -> Unit,
    addedText: (name: String, value: ItemValue) -> String?,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnUndoGotIt by rememberUpdatedState(onUndoGotIt)
    val currentOnUndoDelete by rememberUpdatedState(onUndoDelete)
    val currentOnUndoCommonItems by rememberUpdatedState(onUndoCommonItems)
    val currentAddedText by rememberUpdatedState(addedText)
    LaunchedEffect(events, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            events.collect { event ->
                when (event) {
                    is ListEvent.GotIt -> showReplacingSnackbar(snackbarHostState, "Got ${event.name}", "Undo") {
                        currentOnUndoGotIt(event.name, event.previous)
                    }
                    is ListEvent.Deleted -> showReplacingSnackbar(snackbarHostState, "Deleted ${event.name}", "Undo") {
                        currentOnUndoDelete(event.name, event.previous)
                    }
                    is ListEvent.Message -> showReplacingSnackbar(snackbarHostState, event.text)
                    is ListEvent.CommonItemsAdded ->
                        showReplacingSnackbar(snackbarHostState, StarterList.addedText(event.items.size), "Undo") {
                            currentOnUndoCommonItems(event.items)
                        }
                    is ListEvent.Added -> currentAddedText(event.name, event.value)?.let {
                        showReplacingSnackbar(snackbarHostState, it)
                    }
                }
            }
        }
    }
}

/** Dismisses the visible snackbar (so quick ticks don't queue up) and shows [message] without blocking the caller. */
private fun CoroutineScope.showReplacingSnackbar(
    hostState: SnackbarHostState,
    message: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    hostState.currentSnackbarData?.dismiss()
    launch {
        val result = hostState.showSnackbar(
            message = message,
            actionLabel = actionLabel,
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) onAction()
    }
}

/** [added]: unchanged since it was added, so the row says "Added by" (else "Updated by"). */
private fun previewItem(
    key: String,
    name: String,
    value: ItemValue,
    agoMillis: Long,
    by: String,
    pending: Boolean,
    added: Boolean = false,
): GroceryItem {
    val updatedAt = System.currentTimeMillis() - agoMillis
    return GroceryItem(
        key = key,
        name = name,
        value = value,
        updatedAt = updatedAt,
        updatedBy = by,
        pendingSync = pending,
        addedAt = if (added) updatedAt else updatedAt - 86_400_000L,
    )
}

private val PreviewItems = listOf(
    previewItem("bread", "Bread", ItemValue.BoolValue(true), 40_000L, "Home", pending = false, added = true),
    previewItem("milk", "Milk", ItemValue.Quantity(Amount(1_500L), Units.L), 600_000L, "Shop", pending = true, added = true),
    previewItem("onion", "Onion", ItemValue.Quantity(Amount.whole(1L), Units.KG), 300_000L, "Home", pending = false),
    previewItem("rice", "Rice", ItemValue.Quantity(Amount.whole(2L), Units.KG), 900_000L, "Home", pending = false),
    previewItem("basmati rice", "Basmati rice", ItemValue.BoolValue(false), 3_600_000L, "Home", pending = false),
    previewItem("coffee", "Coffee", ItemValue.BoolValue(false), 7_200_000L, "Sheet", pending = false),
)

private fun previewState(items: List<GroceryItem>) = ListUiState(
    loaded = true,
    toBuy = items.filter { it.value.isNeeded },
    toBuySections = groupBySection(items.filter { it.value.isNeeded }, ShopSectionSettings()::sectionOf),
    allItems = items.filter { it.value.isNeeded } + items.filterNot { it.value.isNeeded },
    namesByKey = items.associate { it.key to it.name },
    outbox = OutboxStatus(pending = 1, failed = emptyList()),
    status = SyncStatus(ConnectionState.LIVE, System.currentTimeMillis() - 2_000L, null, "Groceries"),
    isConfigured = true,
    myDeviceName = "Shop",
)

private val PreviewActions = ListActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}, {}, {})

@Composable
private fun ListContentPreviewOf(filter: ListFilter, search: SearchState) {
    GrocerySyncTheme(dynamicColor = false) {
        ListContent(
            state = previewState(PreviewItems),
            pagerState = rememberPagerState(initialPage = filter.page) { ListFilter.entries.size },
            shownFilter = filter,
            onFilterChange = {},
            search = search,
            onCloseSearch = {},
            snackbarHostState = remember { SnackbarHostState() },
            actions = PreviewActions,
        )
    }
}

@Preview(showBackground = true, heightDp = 640)
@Composable
private fun ListContentPreview() {
    ListContentPreviewOf(ListFilter.ALL, SearchState())
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun ListSearchPreview() {
    ListContentPreviewOf(ListFilter.ALL, SearchState(open = true, query = "rice"))
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun ListSearchNoMatchPreview() {
    ListContentPreviewOf(ListFilter.ALL, SearchState(open = true, query = "corriander"))
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun ToBuySearchPreview() {
    ListContentPreviewOf(ListFilter.TO_BUY, SearchState(open = true, query = "rice"))
}

/** Coffee is on the list but not needed: To buy offers "Show 1 in All items". */
@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun ToBuySearchInAllItemsPreview() {
    ListContentPreviewOf(ListFilter.TO_BUY, SearchState(open = true, query = "coffee"))
}

/** To buy by shop section (the default) on a 360dp phone with large text, in the dark theme. */
@Preview(showBackground = true, widthDp = 360, heightDp = 640, fontScale = 1.3f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ToBuySectionsPreview() {
    ListContentPreviewOf(ListFilter.TO_BUY, SearchState())
}
