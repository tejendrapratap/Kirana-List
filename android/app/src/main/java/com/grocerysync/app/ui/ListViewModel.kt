package com.grocerysync.app.ui

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grocerysync.app.data.CatalogEntry
import com.grocerysync.app.data.GroceryItem
import com.grocerysync.app.data.EditResult
import com.grocerysync.app.data.GroceryRepository
import com.grocerysync.app.data.ItemKeys
import com.grocerysync.app.data.ItemValue
import com.grocerysync.app.data.OutboxStatus
import com.grocerysync.app.data.SettingsStore
import com.grocerysync.app.data.ShopSection
import com.grocerysync.app.data.ShopSectionSettings
import com.grocerysync.app.data.ShopSectionStore
import com.grocerysync.app.data.SyncSettings
import com.grocerysync.app.data.notNeeded
import com.grocerysync.app.data.stepped
import com.grocerysync.app.sync.ConnectionState
import com.grocerysync.app.sync.LiveSync
import com.grocerysync.app.sync.PushProblem
import com.grocerysync.app.sync.SyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** The two pages of the list screen, in the order of its pager and segmented row. */
enum class ListFilter { TO_BUY, ALL }

@Immutable
data class ListUiState(
    /** False until the local database has been read once (avoids flashing the empty state). */
    val loaded: Boolean = false,
    /** Needed items, by name. */
    val toBuy: List<GroceryItem> = emptyList(),
    /** Needed items first, then the rest; each group by name. */
    val allItems: List<GroceryItem> = emptyList(),
    /** Every item's name as stored, by key: the item dialog says when a typed name is already taken. */
    val namesByKey: Map<String, String> = emptyMap(),
    val outbox: OutboxStatus = OutboxStatus(pending = 0, failed = emptyList()),
    val status: SyncStatus = SyncStatus(ConnectionState.CONNECTING, null, null, null),
    val isConfigured: Boolean = false,
    /** Name this phone writes to "Updated By"; changes by anyone else get highlighted. */
    val myDeviceName: String = "",
    /** True only while a pull-to-refresh sync is running. */
    val isRefreshing: Boolean = false,
    /** This phone's shop sections: whether To buy is grouped, and the sections chosen in the Edit dialog. */
    val shopSections: ShopSectionSettings = ShopSectionSettings(),
    /** [toBuy] by shop section in walking order while [groupBySection]; null while To buy is A to Z. */
    val toBuySections: List<ShopSectionGroup>? = null,
) {
    val title: String
        get() = status.spreadsheetName?.takeIf { it.isNotBlank() } ?: DEFAULT_TITLE

    /**
     * Set while the list is live but changes can't be saved (the sheet answers pushes with a failure)
     * and some are waiting; also while a Retry runs ("Connecting..."), so the warning doesn't vanish
     * before the retry has shown whether it helped. Offline and setup errors have their own status line
     * and banner instead (and clear the problem).
     */
    val pushWarning: PushProblem?
        get() = status.pushProblem?.takeIf {
            isConfigured && status.state in WARNING_STATES && it.shouldWarn && outbox.pending > 0 && !scriptUpdateNeeded
        }

    /**
     * The sheet runs a script older than protocol 3 (no units): the list still updates, but changes wait
     * on this phone until the script is updated. Shown until then, also while offline (the last answer
     * said so); setup errors have their own banner instead.
     */
    val scriptUpdateNeeded: Boolean
        get() = isConfigured && status.scriptOutdated && status.state != ConnectionState.ERROR &&
            status.state != ConnectionState.NOT_CONFIGURED

    fun item(key: String): GroceryItem? = allItems.firstOrNull { it.key == key }

    /** To buy is grouped by shop section (this phone's choice; on unless turned off). */
    val groupBySection: Boolean
        get() = shopSections.groupBySection

    /** [item]'s shop section on this phone ([ShopSectionSettings.sectionOf]). */
    fun sectionOf(item: GroceryItem): ShopSection = shopSections.sectionOf(item)

    companion object {
        const val DEFAULT_TITLE = "Grocery Sync"
        private val WARNING_STATES = setOf(ConnectionState.LIVE, ConnectionState.CONNECTING)
    }
}

/** Which item dialog is open. Kept in [SavedStateHandle] so it survives rotation and process death. */
sealed interface ItemDialog {
    data object Add : ItemDialog
    data class Edit(val key: String) : ItemDialog

    companion object {
        private const val ADD = "add"
        private const val EDIT_PREFIX = "edit:"

        fun encode(dialog: ItemDialog?): String? = when (dialog) {
            null -> null
            Add -> ADD
            is Edit -> EDIT_PREFIX + dialog.key
        }

        fun decode(value: String?): ItemDialog? = when {
            value == ADD -> Add
            value != null && value.startsWith(EDIT_PREFIX) -> Edit(value.removePrefix(EDIT_PREFIX))
            else -> null
        }
    }
}

/** One-shot messages for the snackbar. */
sealed interface ListEvent {
    data class GotIt(val name: String, val previous: ItemValue) : ListEvent
    data class Deleted(val name: String, val previous: ItemValue) : ListEvent
    data class Message(val text: String) : ListEvent

    /** The add dialog's item was saved; the screen says where it is if the page shown hides it. */
    data class Added(val name: String, val value: ItemValue) : ListEvent
}

class ListViewModel(
    private val repository: GroceryRepository,
    private val liveSync: LiveSync,
    private val settingsStore: SettingsStore,
    private val shopSectionStore: ShopSectionStore,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val manualRefresh = MutableStateFlow(false)
    private var refreshJob: Job? = null

    // Serialises local edits so read-modify-write actions (stepper taps, "Got it") always see the previous edit.
    private val editMutex = Mutex()

    private val _events = Channel<ListEvent>(Channel.BUFFERED)
    val events: Flow<ListEvent> = _events.receiveAsFlow()

    val uiState: StateFlow<ListUiState> = combine(
        repository.items,
        repository.outboxStatus,
        liveSync.status,
        settingsStore.settings,
        combine(manualRefresh, shopSectionStore.settings, ::Pair),
    ) { items, outbox, status, settings, (refreshing, shopSections) ->
        buildState(items, outbox, status, settings, refreshing, shopSections)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ListUiState(
                status = liveSync.status.value,
                isConfigured = settingsStore.current().isConfigured,
                shopSections = shopSectionStore.current(),
            ),
        )

    val dialog: StateFlow<ItemDialog?> = savedStateHandle.getStateFlow<String?>(KEY_DIALOG, null)
        .map { ItemDialog.decode(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ItemDialog.decode(savedStateHandle.get<String>(KEY_DIALOG)))

    private fun buildState(
        items: List<GroceryItem>,
        outbox: OutboxStatus,
        status: SyncStatus,
        settings: SyncSettings,
        refreshing: Boolean,
        shopSections: ShopSectionSettings,
    ): ListUiState {
        // The repository already sorts by name, and partition keeps that order inside each group.
        val (needed, rest) = items.partition { it.value.isNeeded }
        return ListUiState(
            loaded = true,
            toBuy = needed,
            allItems = needed + rest,
            namesByKey = items.associateTo(HashMap(items.size)) { it.key to it.name },
            outbox = outbox,
            status = status,
            isConfigured = settings.isConfigured,
            myDeviceName = settingsStore.effectiveDeviceName(),
            isRefreshing = refreshing,
            shopSections = shopSections,
            toBuySections = if (shopSections.groupBySection) groupBySection(needed, shopSections::sectionOf) else null,
        )
    }

    // region Dialog

    fun openAdd() {
        savedStateHandle.set(KEY_DIALOG, ItemDialog.encode(ItemDialog.Add))
    }

    fun openEdit(key: String) {
        savedStateHandle.set(KEY_DIALOG, ItemDialog.encode(ItemDialog.Edit(key)))
    }

    fun closeDialog() {
        // set (not remove) so the StateFlow from getStateFlow() emits the change.
        savedStateHandle.set<String?>(KEY_DIALOG, null)
    }

    // endregion

    // region "Add common items" (StarterListDialog; its rules are in StarterList)

    /** Whether "Add common items" is open. Kept in [SavedStateHandle] like [dialog]. */
    val starterListOpen: StateFlow<Boolean> = savedStateHandle.getStateFlow(KEY_STARTER_LIST, false)

    /**
     * Opens "Add common items". Not before the phone is connected to the sheet: until it has the family's list, the
     * items would go out as new and could set the family's items of the same name to "not needed".
     */
    fun openStarterList() {
        if (!settingsStore.current().isConfigured) {
            viewModelScope.launch { _events.send(ListEvent.Message(StarterList.NOT_CONNECTED_TEXT)) }
            return
        }
        savedStateHandle[KEY_STARTER_LIST] = true
    }

    fun closeStarterList() {
        savedStateHandle[KEY_STARTER_LIST] = false
    }

    /**
     * "Add N items": adds [entries] to All items, not needed, through the repository's normal add, so the duplicate
     * rules, the outbox and sync are those of any new item. An entry that reached the list meanwhile under any of its
     * names is skipped ([StarterList.addEntries]). The snackbar says how many were added.
     */
    fun addCommonItems(entries: List<CatalogEntry>) {
        // A second tap on "Add" before the dialog has gone would only say "Nothing added".
        if (!starterListOpen.value) return
        closeStarterList()
        edit {
            val names = repository.items.first().map { it.name }
            val added = StarterList.addEntries(entries, names, repository::add)
            _events.send(ListEvent.Message(StarterList.addedText(added)))
        }
    }

    // endregion

    // region Item edits

    /**
     * "Got it" in the To buy view: marks the item as not needed (a quantity becomes 0 and keeps its unit)
     * and offers Undo with the exact previous value.
     */
    fun markGotIt(key: String) = edit {
        val item = currentItem(key) ?: return@edit
        if (!item.value.isNeeded) return@edit
        if (repository.upsert(item.name, item.value.notNeeded())) {
            _events.send(ListEvent.GotIt(item.name, item.value))
        } else {
            _events.send(cantChange(item))
        }
    }

    fun setNeeded(key: String, needed: Boolean) = edit {
        val item = currentItem(key) ?: return@edit
        if (item.value is ItemValue.BoolValue && item.value.value != needed) {
            if (!repository.upsert(item.name, ItemValue.BoolValue(needed))) _events.send(cantChange(item))
        }
    }

    /** - / + on a quantity: one step of its unit ([com.grocerysync.app.data.Units.step]), never below 0. */
    fun stepQuantity(key: String, up: Boolean) = edit {
        val item = currentItem(key) ?: return@edit
        val current = item.value as? ItemValue.Quantity ?: return@edit
        val next = current.stepped(up)
        if (next != current && !repository.upsert(item.name, next)) _events.send(cantChange(item))
    }

    /**
     * Saves the item dialog. [originalKey] is null when adding; [openedValue] is the item's value when
     * the dialog opened, so a change made on the other phone meanwhile is only overwritten when the
     * user actually changed the value (see [planItemSave]).
     *
     * An add or rename to a name another item already has is refused here too (nothing is queued, a
     * snackbar says why), not only by the dialog: that item may have arrived from the other phone
     * between the dialog's last check and the tap on Save.
     */
    fun saveItem(originalKey: String?, openedValue: ItemValue?, name: String, value: ItemValue) {
        closeDialog()
        edit {
            val items = repository.items.first()
            val current = originalKey?.let { key -> items.firstOrNull { it.key == key } }
            when (val plan = planItemSave(current, openedValue, name, value, onList = items)) {
                ItemSavePlan.Nothing -> Unit
                // A new item (also one removed elsewhere while its dialog was open): checked again as it is
                // queued, so an item that arrived meanwhile is never overwritten.
                is ItemSavePlan.Upsert -> if (current == null) {
                    val result = repository.add(plan.name, plan.value)
                    if (result == EditResult.Saved && originalKey != null) keepShopSection(originalKey, plan.name)
                    if (result == EditResult.Saved && originalKey == null) {
                        _events.send(ListEvent.Added(plan.name, plan.value))
                    } else {
                        editRefused(result, plan.name)?.let { _events.send(it) }
                    }
                } else if (!repository.upsert(plan.name, plan.value)) {
                    _events.send(couldNotSave(plan.name))
                }
                is ItemSavePlan.Rename -> {
                    val result = repository.rename(plan.key, plan.newName, plan.value)
                    if (result == EditResult.Saved) keepShopSection(plan.key, plan.newName)
                    // "Not on the list" is about the item being renamed (its old name).
                    editRefused(result, plan.newName, goneName = current?.name ?: plan.newName)?.let { _events.send(it) }
                }
                is ItemSavePlan.AlreadyOnList -> _events.send(ListEvent.Message(alreadyOnListText(plan.existingName)))
            }
        }
    }

    /**
     * The snackbar for an edit of [name] the repository refused ([EditResult]); null when it was saved.
     * [goneName]: the name of the item that is no longer on the list (the old name for a rename).
     */
    private fun editRefused(result: EditResult, name: String, goneName: String = name): ListEvent.Message? = when (result) {
        EditResult.Saved -> null
        is EditResult.AlreadyOnList -> ListEvent.Message(alreadyOnListText(result.existingName))
        EditResult.NotOnList -> ListEvent.Message(changedElsewhereText(goneName))
        EditResult.InvalidName -> couldNotSave(name)
    }

    private fun couldNotSave(name: String) =
        ListEvent.Message("Couldn't save \"${quotedItemName(name)}\". Check the name and try again.")

    /** The open edit dialog's item was renamed or removed on another phone (or in the sheet). */
    fun onEditedItemRemoved(name: String?) {
        closeDialog()
        val text = if (name != null) changedElsewhereText(name) else "That item was changed on another phone"
        viewModelScope.launch { _events.send(ListEvent.Message(text)) }
    }

    private fun changedElsewhereText(name: String) = "\"${quotedItemName(name)}\" was changed on another phone"

    private fun cantChange(item: GroceryItem) = ListEvent.Message(
        "Couldn't change \"${ItemKeys.singleLine(item.name).take(40)}\". Open it and shorten its name.",
    )

    fun deleteItem(key: String) {
        closeDialog()
        edit {
            val item = currentItem(key) ?: return@edit
            repository.delete(key)
            _events.send(ListEvent.Deleted(item.name, item.value))
        }
    }

    /**
     * Undo of "Got it": writes the previous value back, if the item is still on the list. When it was renamed or
     * removed on the other phone meanwhile, nothing is written (it would come back as a second item under its
     * old name) and a snackbar says so.
     */
    fun undoGotIt(name: String, value: ItemValue) = edit {
        editRefused(repository.update(name, value), name)?.let { _events.send(it) }
    }

    /**
     * Undo of a delete: adds the item back with its previous value, unless an item with its name is on the list
     * again meanwhile (added or renamed to it on the other phone): that one is never overwritten, and a snackbar
     * says the name is already on the list.
     */
    fun undoDelete(name: String, value: ItemValue) = edit {
        editRefused(repository.add(name, value), name)?.let { _events.send(it) }
    }

    fun retryFailed() = edit { repository.retryFailed() }

    fun discardFailed() = edit { repository.discardFailed() }

    private suspend fun currentItem(key: String): GroceryItem? =
        repository.items.first().firstOrNull { it.key == key }

    private fun edit(block: suspend () -> Unit) {
        viewModelScope.launch {
            editMutex.withLock {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Local edit failed", e)
                    _events.send(ListEvent.Message("Couldn't save that change. Please try again."))
                }
            }
        }
    }

    // endregion

    // region Shop sections (this phone only, never synced)

    /** To buy grouped by shop section (true) or A to Z. */
    fun setGroupBySection(group: Boolean) {
        shopSectionStore.setGroupBySection(group)
    }

    /**
     * The Edit dialog's "Shop section" for the item [key], which is being saved as [name] (a new name too: the
     * choice follows the rename, see [keepShopSection]). See [ShopSectionStore.chooseSection].
     */
    fun setShopSection(key: String, name: String, section: ShopSection) {
        shopSectionStore.chooseSection(key, name, section)
    }

    /** The item [fromKey] was saved as [name] from its Edit dialog: this phone's section for it goes along. */
    private fun keepShopSection(fromKey: String, name: String) {
        shopSectionStore.moveSection(fromKey, ItemKeys.normalize(name))
    }

    // endregion

    // region Sync

    fun retrySync() {
        liveSync.syncNow()
    }

    /**
     * Pull-to-refresh: the indicator stays up until the sync started for it has finished (whatever
     * the outcome, even when the status ends up exactly as before), or a timeout passes.
     */
    fun refresh() {
        if (refreshJob?.isActive == true) return
        val sync = liveSync.syncNow()
        if (!settingsStore.current().isConfigured) return

        manualRefresh.value = true
        refreshJob = viewModelScope.launch {
            val started = TimeSource.Monotonic.markNow()
            try {
                withTimeoutOrNull(REFRESH_TIMEOUT) { sync.join() }
                val remaining = MIN_REFRESH_INDICATOR - started.elapsedNow()
                if (remaining.isPositive()) delay(remaining)
            } finally {
                manualRefresh.value = false
            }
        }
    }

    // endregion

    private companion object {
        const val TAG = "ListViewModel"
        const val KEY_DIALOG = "item_dialog"
        const val KEY_STARTER_LIST = "starter_list"
        val REFRESH_TIMEOUT = 10.seconds
        val MIN_REFRESH_INDICATOR = 600.milliseconds
    }
}
