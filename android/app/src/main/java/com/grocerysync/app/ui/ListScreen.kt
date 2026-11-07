package com.grocerysync.app.ui

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
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
import com.grocerysync.app.data.ItemValue
import com.grocerysync.app.data.OutboxStatus
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
import com.grocerysync.app.ui.components.SyncErrorBanner
import com.grocerysync.app.ui.components.ToBuyRow
import com.grocerysync.app.ui.components.rememberTickingTime
import com.grocerysync.app.ui.theme.GrocerySyncTheme
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
 * Search on All items. It opens from the app bar's Search icon and closes with the field's X, Back or
 * on leaving All items. [open] and [query] are saved (rotation, process death, a trip to Settings);
 * focus is not, so the keyboard never comes back by itself.
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

/**
 * Switches the pager's page from the segmented row (or the Search icon) with the pager's animation.
 * [shownPage] is the page the segmented row highlights: a tapped page at once, otherwise the pager's
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
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    // Saved: the page shown survives rotation, process death and a trip to Settings.
    val pagerState = rememberPagerState(initialPage = ListFilter.TO_BUY.page) { ListFilter.entries.size }
    val pages = remember(pagerState, scope) { PageSwitcher(pagerState, scope) }
    val search = rememberSaveable(saver = SearchState.Saver) { SearchState() }
    var lastAddType by rememberSaveable { mutableStateOf(ValueType.BOOLEAN) }
    // The name the next Add dialog starts with: the search's suggestion, or "" for the + button.
    var addName by rememberSaveable { mutableStateOf("") }

    // Leaving All items closes the search: at once for a tap on To buy, and for a swipe once the pager
    // has come to rest on To buy (a swipe the user takes back keeps the search).
    LaunchedEffect(pagerState, search, focusManager) {
        // Restored in the middle of a page change (rotated during a swipe or a tap's animation): finish it
        // first, so the pager never stays between two pages and a search being opened stays open.
        pageToSettleOnRestore(pagerState.currentPage, pagerState.currentPageOffsetFraction, search.open)?.let { page ->
            try {
                pagerState.scrollToPage(page)
            } catch (e: CancellationException) {
                // A drag took over (and settles the pager itself); only a cancelled effect stops here.
                ensureActive()
            }
        }
        snapshotFlow { pagerState.isScrollInProgress || pagerState.currentPage == ListFilter.ALL.page }
            .collect { onAllItemsOrMoving ->
                if (!onAllItemsOrMoving && search.open) {
                    focusManager.clearFocus()
                    search.close()
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
        addedText = { name, value ->
            val shown = filterOfPage(pages.shownPage)
            addedSnackbarText(name, value.isNeeded, shown, if (search.open) search.query else "")
        },
    )

    // Everything that opens a dialog or leaves the screen clears focus first, so the keyboard never
    // stays over a dialog or comes back when it closes.
    val actions = remember(viewModel, onOpenSettings, focusManager, pages, search) {
        ListActions(
            openSettings = {
                focusManager.clearFocus()
                onOpenSettings()
            },
            openSearch = {
                search.openAndFocus()
                if (pages.shownPage != ListFilter.ALL.page) pages.show(ListFilter.ALL.page)
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
        )
    }

    ListContent(
        state = state,
        pagerState = pagerState,
        shownFilter = filterOfPage(pages.shownPage),
        onFilterChange = { filter ->
            focusManager.clearFocus()
            if (filter != ListFilter.ALL) search.close()
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
        )
        // Gone before this screen saw it (e.g. after the app was restarted with the dialog open).
        state.loaded -> LaunchedEffect(key) { viewModel.onEditedItemRemoved(null) }
    }
}

/** Callbacks of the list screen, grouped so the stateless content stays previewable. */
class ListActions(
    val openSettings: () -> Unit,
    /** The app bar's Search icon: shows All items with the search field focused. */
    val openSearch: () -> Unit,
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
)

/**
 * The screen: status lines, the To buy / All items row, the search field while search is open, and a
 * pager with one list per page (swipe or tap the row to switch). Each page keeps its own scroll
 * position, banners and pull to refresh.
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
    // Dragging the All items list or swiping between the pages hides the keyboard.
    LaunchedEffect(allListState, pagerState, focusManager) {
        merge(allListState.interactionSource.interactions, pagerState.interactionSource.interactions)
            .collect { if (it is DragInteraction.Start) focusManager.clearFocus() }
    }
    val query = if (search.open) search.query else ""
    val shownAll = remember(state.allItems, query) { ItemSearch.filter(state.allItems, query) }
    val rowClock = rememberTickingTime(ROW_CLOCK_PERIOD_MS)
    val now = { rowClock.value }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    IconButton(onClick = actions.openSearch) {
                        Icon(Icons.Default.Search, contentDescription = "Search items")
                    }
                    IconButton(onClick = actions.openSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            // Always a blank Add dialog, also during a search.
            FloatingActionButton(onClick = { actions.add("") }) {
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
            // Below the app bar (not in its fixed-height title slot) so large text never clips it.
            LiveStatusLine(
                status = state.status,
                isConfigured = state.isConfigured,
                modifier = Modifier.padding(horizontal = 16.dp).padding(top = 4.dp),
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
                        // New results start at the top; closing or emptying the search keeps the place.
                        if (clean != search.query && ItemSearch.isActive(clean)) allListState.requestScrollToItem(0)
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
                PullToRefreshBox(
                    isRefreshing = state.isRefreshing,
                    onRefresh = actions.refresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = if (filter == ListFilter.TO_BUY) toBuyListState else allListState,
                        // The list scrolls behind the navigation bar; its last rows stay above the FAB.
                        contentPadding = PaddingValues(bottom = innerPadding.calculateBottomPadding() + FabClearance),
                    ) {
                        bannerHeader(state, actions)
                        when (filter) {
                            ListFilter.TO_BUY -> toBuyItems(state, now, actions)
                            ListFilter.ALL -> allItems(state, shownAll, query, now, actions)
                        }
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
        ) { Text("To buy ($toBuyCount)", maxLines = 1) }
        SegmentedButton(
            selected = filter == ListFilter.ALL,
            onClick = { onFilterChange(ListFilter.ALL) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
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

private fun LazyListScope.toBuyItems(state: ListUiState, now: () -> Long, actions: ListActions) {
    if (state.loaded && state.toBuy.isEmpty()) {
        item(key = "empty:to-buy", contentType = "empty") {
            EmptyListState(
                title = "Nothing to buy right now",
                hint = if (state.allItems.isEmpty()) {
                    "Tap + to add the things you usually buy. Mark them as needed when you run out."
                } else {
                    "Everything is ticked off. Open All items to mark what you need."
                },
                modifier = Modifier.animateItem(),
            )
        }
        return
    }
    items(state.toBuy, key = ::itemKey, contentType = { "to-buy" }) { item ->
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
 * The All items rows: [shown] is the list filtered by [query] ([ItemSearch.filter]; every item, needed
 * first, while the search is inactive). When the search matches nothing, one item says so and offers to
 * add the query as a new item. Nothing shows before the list was first read, so "No items match" never
 * flashes while the app starts.
 */
private fun LazyListScope.allItems(
    state: ListUiState,
    shown: List<GroceryItem>,
    query: String,
    now: () -> Long,
    actions: ListActions,
) {
    if (!state.loaded) return
    val searching = ItemSearch.isActive(query)
    if (!searching && state.allItems.isEmpty()) {
        item(key = "empty:all", contentType = "empty") {
            EmptyListState(
                title = "Your list is empty",
                hint = "Tap + to add your first item.",
                modifier = Modifier.animateItem(),
            )
        }
        return
    }
    if (searching && shown.isEmpty()) {
        // One key for every query, so it isn't inserted again with each letter typed.
        item(key = "empty:search", contentType = "empty") {
            val name = ItemSearch.suggestedName(query)
            NoSearchMatches(
                message = ItemSearch.NO_MATCH_TEXT,
                addText = ItemSearch.addText(name),
                onAdd = { actions.add(name) },
                modifier = Modifier.animateItem(),
            )
        }
        return
    }
    items(shown, key = ::itemKey, contentType = { "all" }) { item ->
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
    addedText: (name: String, value: ItemValue) -> String?,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnUndoGotIt by rememberUpdatedState(onUndoGotIt)
    val currentOnUndoDelete by rememberUpdatedState(onUndoDelete)
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
    previewItem("rice", "Rice", ItemValue.Quantity(Amount.whole(2L), Units.KG), 900_000L, "Home", pending = false),
    previewItem("basmati rice", "Basmati rice", ItemValue.BoolValue(false), 3_600_000L, "Home", pending = false),
    previewItem("coffee", "Coffee", ItemValue.BoolValue(false), 7_200_000L, "Sheet", pending = false),
)

private fun previewState(items: List<GroceryItem>) = ListUiState(
    loaded = true,
    toBuy = items.filter { it.value.isNeeded },
    allItems = items.filter { it.value.isNeeded } + items.filterNot { it.value.isNeeded },
    namesByKey = items.associate { it.key to it.name },
    outbox = OutboxStatus(pending = 1, failed = emptyList()),
    status = SyncStatus(ConnectionState.LIVE, System.currentTimeMillis() - 2_000L, null, "Groceries"),
    isConfigured = true,
    myDeviceName = "Shop",
)

private val PreviewActions = ListActions({}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, { _, _ -> })

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
