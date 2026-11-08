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
import com.grocerysync.app.data.PasteParser
import com.grocerysync.app.data.PhoneSettingsStore
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
import com.grocerysync.app.text.Texts
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
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

    /**
     * The family's list has come from the sheet on this phone (once is enough: it is kept in the database). Until
     * then the list is empty or out of date, so changes of many items at once ("Add common items", a paste) wait:
     * every item would look new, and its change could replace the family's item of that name on the sheet.
     */
    val hasSheetList: Boolean
        get() = isConfigured && status.lastSuccessAt != null

    /** To buy is grouped by shop section (this phone's choice; on unless turned off). */
    val groupBySection: Boolean
        get() = shopSections.groupBySection

    /** [item]'s shop section on this phone ([ShopSectionSettings.sectionOf]). */
    fun sectionOf(item: GroceryItem): ShopSection = shopSections.sectionOf(item)

    companion object {
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

/**
 * One-shot messages for the snackbar. A [Message] carries its finished text, made in the language shown when the
 * event is sent; the others carry data, and the screen makes their text when it shows them.
 */
sealed interface ListEvent {
    data class GotIt(val name: String, val previous: ItemValue) : ListEvent
    data class Deleted(val name: String, val previous: ItemValue) : ListEvent
    data class Message(val text: String) : ListEvent

    /** The add dialog's item was saved; the screen says where it is if the page shown hides it. */
    data class Added(val name: String, val value: ItemValue) : ListEvent

    /** "Add common items" added [items] (at least one); the snackbar says how many and offers Undo. */
    data class CommonItemsAdded(val items: List<AddedItem>) : ListEvent
}

class ListViewModel(
    private val repository: GroceryRepository,
    private val liveSync: LiveSync,
    private val settingsStore: SettingsStore,
    private val shopSectionStore: ShopSectionStore,
    /** This phone's own preferences (never synced): the list lane reads Simple mode from it. */
    private val phoneSettingsStore: PhoneSettingsStore,
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
     * Opens "Add common items". Not before the family's list has come from the sheet ([bulkChangeProblem]): until
     * then the items would go out as new and could set the family's items of the same name to "not needed".
     */
    fun openStarterList() {
        bulkChangeProblem()?.let { problem ->
            viewModelScope.launch { _events.send(ListEvent.Message(problem)) }
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
     * names is skipped ([StarterList.addEntries]). The snackbar says how many were added, with Undo
     * ([undoAddCommonItems]): a set can be a hundred items, and they are on the family's list at once.
     */
    fun addCommonItems(entries: List<CatalogEntry>) {
        // A second tap on "Add" before the dialog has gone would only say "Nothing added".
        if (!starterListOpen.value) return
        closeStarterList()
        edit {
            // Checked again: the dialog may have come back with the app after it was closed (process death).
            bulkChangeProblem()?.let { problem ->
                _events.send(ListEvent.Message(problem))
                return@edit
            }
            val names = repository.items.first().map { it.name }
            val added = StarterList.addEntries(entries, names, repository::add)
            _events.send(if (added.isEmpty()) ListEvent.Message(StarterList.addedText(0)) else ListEvent.CommonItemsAdded(added))
        }
    }

    /**
     * Undo of "Add common items": deletes the [items] it added, each only while it is as it was added
     * ([GroceryRepository.deleteIf]): one marked needed or changed on the other phone meanwhile stays, and a
     * snackbar says how many did. One gone already (deleted there) needs nothing.
     */
    fun undoAddCommonItems(items: List<AddedItem>) = edit {
        var kept = 0
        for (item in items) {
            if (repository.deleteIf(ItemKeys.normalize(item.name), item.value) == EditResult.ValueChanged) kept++
        }
        if (kept > 0) _events.send(ListEvent.Message(StarterList.keptText(kept)))
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
        EditResult.NotOnList, EditResult.ValueChanged -> ListEvent.Message(changedElsewhereText(goneName))
        EditResult.InvalidName -> couldNotSave(name)
    }

    private fun couldNotSave(name: String) = ListEvent.Message(Texts.current.list.couldNotSave(quotedItemName(name)))

    /** The open edit dialog's item was renamed or removed on another phone (or in the sheet). */
    fun onEditedItemRemoved(name: String?) {
        closeDialog()
        val text = if (name != null) changedElsewhereText(name) else Texts.current.list.itemChangedElsewhere
        viewModelScope.launch { _events.send(ListEvent.Message(text)) }
    }

    private fun changedElsewhereText(name: String) = Texts.current.list.changedElsewhere(quotedItemName(name))

    private fun cantChange(item: GroceryItem) =
        ListEvent.Message(Texts.current.list.cantChange(ItemKeys.singleLine(item.name).take(40)))

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
                    _events.send(ListEvent.Message(Texts.current.list.editFailed))
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

    // region Paste a list (PastePlan.kt, PasteListDialog.kt)

    /**
     * The "Paste a list" dialog; null while it is closed. In the preview, each line is planned against the list as
     * it is now ([planPaste]), so the rows follow changes from the other phone, and it can't be applied before the
     * family's list has come from the sheet ([PasteListState.waitingForList]). Kept in [SavedStateHandle] like the
     * item dialog. The list comes from [uiState] (once loaded), so this reads nothing more from the database.
     */
    val pasteList: StateFlow<PasteListState?> = combine(
        savedStateHandle.getStateFlow<String?>(KEY_PASTE, null),
        uiState.filter { it.loaded }.map { it.allItems to it.hasSheetList }.distinctUntilChanged(),
    ) { saved, (items, hasSheetList) ->
        PasteDraft.decode(saved)?.let { PasteListState.of(it, items, waitingForList = !hasSheetList) }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * "Paste a list": the dialog with an empty text field. Not before the family's list has come from the sheet
     * ([bulkChangeProblem]), like "Add common items": without it every line would be a new item, and could replace
     * the family's item of the same name (its type and amount) once it is sent.
     */
    fun openPasteList() {
        bulkChangeProblem()?.let { problem ->
            viewModelScope.launch { _events.send(ListEvent.Message(problem)) }
            return
        }
        setPasteDraft(PasteDraft(text = "", previewing = false))
    }

    /**
     * A share into the app (Share > Kirana List): the preview of its [text] at once. It comes from another app, so
     * it is only parsed; nothing changes until the user confirms, and that waits for the family's list
     * ([PasteListState.waitingForList]: a share may start the app right after setup, before its first sync). Before
     * the app is set up, for a share without a text it can read ([text] null) or a text that is too long, a
     * snackbar says so instead.
     */
    fun openSharedText(text: String?) {
        val problem = when {
            !settingsStore.current().isConfigured -> SHARE_NOT_SET_UP_TEXT
            text == null -> SHARE_UNREADABLE_TEXT
            PasteParser.isTooLong(text) -> PASTE_TOO_LONG_TEXT
            else -> {
                setPasteDraft(PasteDraft(text = text, previewing = true))
                return
            }
        }
        viewModelScope.launch { _events.send(ListEvent.Message(problem)) }
    }

    /** The dialog's Next: the preview of [text]. A text that is too long stays (the dialog says so). */
    fun previewPaste(text: String) {
        if (!PasteParser.isTooLong(text)) setPasteDraft(PasteDraft(text = text, previewing = true))
    }

    /** From the preview back to the text, to change it. */
    fun editPasteText() {
        pasteDraft()?.let { setPasteDraft(it.copy(previewing = false, choices = emptyMap())) }
    }

    /** Ticks or unticks the preview's row for line [index]. */
    fun setPasteRowSelected(index: Int, selected: Boolean) {
        pasteDraft()?.let { setPasteDraft(it.copy(choices = it.choices + (index to selected))) }
    }

    fun closePasteList() = setPasteDraft(null)

    /**
     * The preview's confirm button: applies [actions] (the ticked rows) as normal edits, one by one, so duplicate
     * names, the outbox and sync work as for a single item. An update goes ahead only while the item still has the
     * value the preview showed ([pasteEdit]), checked again as each change is queued
     * ([GroceryRepository.updateIf]): the first change starts a sync, whose list may land halfway through a long
     * paste. A snackbar says what was done.
     */
    fun applyPaste(actions: List<PasteAction>) {
        // A second tap before the dialog has gone would apply the same rows again, and say they were all skipped.
        if (savedStateHandle.get<String>(KEY_PASTE) == null) return
        // The button waits for the family's list (PasteListState.canApply); the dialog stays open until then.
        if (bulkChangeProblem() != null) return
        closePasteList()
        edit {
            val byKey = repository.items.first().associateBy { it.key }
            var added = 0
            var updated = 0
            var leftOut = 0
            for (action in actions) {
                when (val step = pasteEdit(action, action.itemKey?.let(byKey::get))) {
                    is PasteEdit.Add -> if (repository.add(step.name, step.value) == EditResult.Saved) added++ else leftOut++
                    is PasteEdit.Update ->
                        if (repository.updateIf(step.key, step.expected, step.value) == EditResult.Saved) updated++ else leftOut++
                    PasteEdit.Unchanged -> Unit
                    PasteEdit.ChangedMeanwhile -> leftOut++
                }
            }
            _events.send(ListEvent.Message(pasteResultText(added, updated, leftOut)))
        }
    }

    /**
     * Why a change of many items at once ("Add common items", a paste) can't go ahead now: the phone isn't set up,
     * or the family's list hasn't come from the sheet yet ([ListUiState.hasSheetList]); null when it can. Read from
     * [LiveSync] itself, which knows the last sync time (kept in the database) before [uiState] has caught up.
     */
    private fun bulkChangeProblem(): String? = when {
        !settingsStore.current().isConfigured -> StarterList.NOT_CONNECTED_TEXT
        liveSync.status.value.lastSuccessAt == null -> StarterList.LIST_NOT_LOADED_TEXT
        else -> null
    }

    private fun pasteDraft(): PasteDraft? = PasteDraft.decode(savedStateHandle.get<String>(KEY_PASTE))

    private fun setPasteDraft(draft: PasteDraft?) {
        // set (not remove) so the StateFlow from getStateFlow() emits the change.
        savedStateHandle.set(KEY_PASTE, draft?.encode())
    }

    // endregion

    private companion object {
        const val TAG = "ListViewModel"
        const val KEY_DIALOG = "item_dialog"
        const val KEY_STARTER_LIST = "starter_list"
        const val KEY_PASTE = "paste_list"
        val REFRESH_TIMEOUT = 10.seconds
        val MIN_REFRESH_INDICATOR = 600.milliseconds
    }
}
