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
import com.grocerysync.app.data.InputValidation
import com.grocerysync.app.data.ItemKeys
import com.grocerysync.app.data.ItemValue
import com.grocerysync.app.data.OutboxStatus
import com.grocerysync.app.data.PasteParser
import com.grocerysync.app.data.PhoneSettings
import com.grocerysync.app.data.PhoneSettingsStore
import com.grocerysync.app.data.SettingsStore
import com.grocerysync.app.data.ShopSection
import com.grocerysync.app.data.ShopSectionSettings
import com.grocerysync.app.data.ShopSectionStore
import com.grocerysync.app.data.SyncSettings
import com.grocerysync.app.data.TickedEntry
import com.grocerysync.app.data.TickedTodayStore
import com.grocerysync.app.data.notNeeded
import com.grocerysync.app.data.shownTickedToday
import com.grocerysync.app.data.stepped
import com.grocerysync.app.sync.ConnectionState
import com.grocerysync.app.sync.LiveSync
import com.grocerysync.app.sync.PushProblem
import com.grocerysync.app.sync.SyncStatus
import com.grocerysync.app.text.Texts
import com.grocerysync.app.ui.components.spokenText
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
    /**
     * "Ticked today" under To buy: the items this phone took off To buy today that are still as it left them
     * ([shownTickedToday]), newest first.
     */
    val tickedToday: List<TickedEntry> = emptyList(),
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
 * event is sent; the others carry data, and the screen makes their text when it shows them ([snackbarText]).
 */
sealed interface ListEvent {
    /**
     * A change of one item's value this phone made, shown with Undo: the item [key] ([name]) had [before] and was set
     * to [after]. Its Undo ([ListViewModel.undoChange]) writes [before] back only while the item still has [after].
     */
    sealed interface ValueChange : ListEvent {
        val key: String
        val name: String
        val before: ItemValue
        val after: ItemValue

        /**
         * The item's entry under Ticked today that this change took away (see [TickedEntry]): this phone had ticked
         * it earlier today, and the change made it needed again or changed it otherwise (Put back: its own entry).
         * The Undo puts it back as it was ([TickedTodayStore.restore]). Null when the item had none.
         */
        val tickedEntry: TickedEntry? get() = null
    }

    /** "Got it" took [name] off To buy. */
    data class GotIt(
        override val key: String,
        override val name: String,
        override val before: ItemValue,
        override val after: ItemValue,
    ) : ValueChange

    /**
     * - / + of a quantity. Steps of one item that follow each other while its snackbar is still the one shown are one
     * change: [before] is the amount before the first of them ([stepEvent]).
     */
    data class Stepped(
        override val key: String,
        override val name: String,
        override val before: ItemValue.Quantity,
        override val after: ItemValue.Quantity,
        override val tickedEntry: TickedEntry? = null,
    ) : ValueChange

    /** The Need switch of a yes / no item set it to [needed]. */
    data class NeedChanged(
        override val key: String,
        override val name: String,
        val needed: Boolean,
        override val tickedEntry: TickedEntry? = null,
    ) : ValueChange {
        override val before: ItemValue get() = ItemValue.BoolValue(!needed)
        override val after: ItemValue get() = ItemValue.BoolValue(needed)
    }

    /**
     * Ticked today's Put back gave the item of [entry] the value it had before this phone ticked it: [after] is that
     * value, [before] the one the tick set. Its Undo ticks the item off again and puts [entry] back under Ticked
     * today, so a Put back tapped by mistake costs nothing.
     */
    data class PutBack(val entry: TickedEntry) : ValueChange {
        override val key: String get() = entry.key
        override val name: String get() = entry.name
        override val before: ItemValue get() = entry.set
        override val after: ItemValue get() = entry.before
        override val tickedEntry: TickedEntry get() = entry
    }

    /**
     * The Edit dialog saved the item [oldName] ([before]) as [newName] ([after]), now under [key]: a new name, a new
     * type, amount or unit, or both. Its Undo ([ListViewModel.undoEdit]) gives both back, only while the item still
     * has [after].
     */
    data class Edited(
        val key: String,
        val oldName: String,
        val newName: String,
        val before: ItemValue,
        val after: ItemValue,
        /** As [ValueChange.tickedEntry]: the Ticked today entry of the item (under its old name) the edit took away. */
        val tickedEntry: TickedEntry? = null,
    ) : ListEvent {
        /** A new name, also only new capitals or spaces. */
        val renamed: Boolean get() = newName != oldName

        /**
         * Its Undo can give the old name back. Not when the edit fixed a name typed into the sheet that can't be sent
         * as a new one ([InputValidation.validateName]: a line break, too long): [GroceryRepository.renameIf] refuses
         * it, so an Undo button would do nothing but say the new name couldn't be saved. Its snackbar has no Undo then.
         */
        val undoable: Boolean get() = !renamed || InputValidation.validateName(oldName) == null
    }

    /**
     * [name] ([previous]) was deleted. Its Undo ([ListViewModel.undoDelete]) adds it back with the Added At it had
     * ([addedAt], 0 = unknown, from [GroceryRepository.delete]), and with [tickedEntry] (as [ValueChange.tickedEntry]).
     */
    data class Deleted(
        val name: String,
        val previous: ItemValue,
        val addedAt: Long,
        val tickedEntry: TickedEntry? = null,
    ) : ListEvent
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
    /** This phone's own preferences (never synced): Simple mode ([simpleMode]) and the notifications offer. */
    private val phoneSettingsStore: PhoneSettingsStore,
    private val savedStateHandle: SavedStateHandle,
    /** "Ticked today" (this phone only). Without one given, kept in memory only, as for tests and previews. */
    private val tickedTodayStore: TickedTodayStore = TickedTodayStore(prefs = null),
) : ViewModel() {

    private val manualRefresh = MutableStateFlow(false)
    private var refreshJob: Job? = null

    // Serialises local edits so read-modify-write actions (stepper taps, "Got it") always see the previous edit.
    private val editMutex = Mutex()

    /**
     * The - / + snackbar on screen now, until the screen says it has gone ([snackbarClosed]): the next step of the
     * same item joins it ([stepEvent]). Main thread only (the edits run there between their database calls).
     */
    private var shownStep: ListEvent.Stepped? = null

    /**
     * The Undo snackbar shown to stay in Simple mode ([SnackbarLook]: until it is tapped, closed or replaced by the
     * next message), until the screen says it has gone ([snackbarClosed]). The screen's snackbar goes when the screen
     * stops (turned off, rotated, Settings opened), so it shows this one again when it comes back
     * ([lastingUndoShown]). Kept as data, so its words are made again in the language shown then. Main thread only,
     * like [shownStep]; lost with the process, as the rest of the screen's state that isn't saved.
     */
    var lastingUndo: ListEvent? = null
        private set

    private val _events = Channel<ListEvent>(Channel.BUFFERED)
    val events: Flow<ListEvent> = _events.receiveAsFlow()

    val uiState: StateFlow<ListUiState> = combine(
        repository.items,
        repository.outboxStatus,
        liveSync.status,
        settingsStore.settings,
        // Ticked today's changes only say when to read it again (see buildState).
        combine(manualRefresh, shopSectionStore.settings, tickedTodayStore.changes, ::Triple),
    ) { items, outbox, status, settings, (refreshing, shopSections, _) ->
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

    /**
     * Simple mode (Settings > This phone): the list screen shows only To buy, with bigger text and buttons. Followed
     * all the time (not only while the screen collects it, unlike [uiState]), so the screen shows the choice made
     * in Settings from its first frame back, without a frame of the other look.
     */
    val simpleMode: StateFlow<Boolean> = phoneSettingsStore.settings
        .map { it.simpleMode }
        .stateIn(viewModelScope, SharingStarted.Eagerly, phoneSettingsStore.current().simpleMode)

    /**
     * This phone's own preferences, for the offer of change notifications above the list
     * ([com.grocerysync.app.ui.components.NotifyOffer]): whether notifications are on and the offer was answered.
     */
    val phoneSettings: StateFlow<PhoneSettings> = phoneSettingsStore.settings

    /** The notifications offer was answered ("Turn on" or "Not now"): it never shows again on this phone. */
    fun onNotifyOfferAnswered() = phoneSettingsStore.setNotifyOfferDone(true)

    /** The offer's "Turn on" asks Android for the permission: Settings no longer offers to ask it. */
    fun onNotifyPermissionAsked() = phoneSettingsStore.setNotifyPermissionAsked(true)

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
        val me = settingsStore.effectiveDeviceName()
        return ListUiState(
            loaded = true,
            toBuy = needed,
            allItems = needed + rest,
            namesByKey = items.associateTo(HashMap(items.size)) { it.key to it.name },
            outbox = outbox,
            status = status,
            isConfigured = settings.isConfigured,
            myDeviceName = me,
            isRefreshing = refreshing,
            shopSections = shopSections,
            toBuySections = if (shopSections.groupBySection) groupBySection(needed, shopSections::sectionOf) else null,
            // Read here, so entries from before midnight go as soon as the list is shown again.
            tickedToday = shownTickedToday(tickedTodayStore.today(), items, me),
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
     * "Got it" in the To buy view: marks the item as not needed (a quantity becomes 0 and keeps its unit), puts it
     * under Ticked today and offers Undo with the exact previous value ([undoChange]).
     */
    fun markGotIt(key: String) = edit {
        val item = currentItem(key) ?: return@edit
        if (!item.value.isNeeded) return@edit
        val set = item.value.notNeeded()
        if (repository.upsert(item.name, set)) {
            sendChange(ListEvent.GotIt(key, item.name, before = item.value, after = set))
        } else {
            _events.send(cantChange(item))
        }
    }

    /**
     * The Need switch of a yes / no item, with Undo ([undoChange]); turned off, the item goes under Ticked today, and
     * turned on, it leaves it until that is undone.
     */
    fun setNeeded(key: String, needed: Boolean) = edit {
        val item = currentItem(key) ?: return@edit
        if (item.value !is ItemValue.BoolValue || item.value.value == needed) return@edit
        val ticked = tickedEntryOf(item)
        if (repository.upsert(item.name, ItemValue.BoolValue(needed))) {
            sendChange(ListEvent.NeedChanged(key, item.name, needed, ticked))
        } else {
            _events.send(cantChange(item))
        }
    }

    /**
     * - / + on a quantity: one step of its unit ([com.grocerysync.app.data.Units.step]), never below 0, with Undo
     * ([undoChange]). Steps of the same item while its snackbar is shown are one change ([stepEvent]); one that ends
     * at 0 puts the item under Ticked today with the amount from before the steps.
     *
     * Steps that came back to where they started (+ then - by mistake) have nothing to undo: the snackbar says the
     * amount is as before, without Undo, and the next step is a change of its own. A ticked item that a + had taken
     * off Ticked today is back under it then, as the item is just as the tick left it.
     */
    fun stepQuantity(key: String, up: Boolean) = edit {
        val item = currentItem(key) ?: return@edit
        val current = item.value as? ItemValue.Quantity ?: return@edit
        val next = current.stepped(up)
        if (next == current) return@edit
        val ticked = tickedEntryOf(item)
        if (repository.upsert(item.name, next)) {
            val event = stepEvent(shownStep, key, item.name, current, next, ticked)
            if (event.before == event.after) {
                shownStep = null
                event.tickedEntry?.let(tickedTodayStore::restore)
                _events.send(ListEvent.Message(Texts.current.list.stepsBackSnackbar(item.name, next.spokenText())))
            } else {
                shownStep = event
                sendChange(event)
            }
        } else {
            _events.send(cantChange(item))
        }
    }

    /**
     * Sends the Undo snackbar of a [change] that was saved. One that took a needed item off To buy ([ticksOff]) puts
     * it under Ticked today, with the value it had before and the name this phone ticked it with.
     */
    private suspend fun sendChange(change: ListEvent.ValueChange) {
        if (change.ticksOff) {
            tickedTodayStore.record(change.key, change.name, change.before, change.after, settingsStore.effectiveDeviceName())
        }
        _events.send(change)
    }

    /**
     * The snackbar of [event] has gone: timed out, swiped away, closed, its action tapped, or replaced by the next
     * one. A - / + after that is a change of its own, not part of [event]'s, and its Undo isn't shown again
     * ([lastingUndo]).
     */
    fun snackbarClosed(event: ListEvent) {
        if (event === shownStep) shownStep = null
        if (event === lastingUndo) lastingUndo = null
    }

    /**
     * The screen shows the Undo snackbar of [event] to stay (Simple mode): it is the one to show again
     * ([lastingUndo]).
     */
    fun lastingUndoShown(event: ListEvent) {
        lastingUndo = event
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
            val ticked = current?.let(::tickedEntryOf)
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
                } else if (repository.upsert(plan.name, plan.value)) {
                    // A new value and / or new capitals or spaces: the same item.
                    sendEdited(ListEvent.Edited(current.key, current.name, plan.name, current.value, plan.value, ticked))
                } else {
                    _events.send(couldNotSave(plan.name))
                }
                is ItemSavePlan.Rename -> {
                    val result = repository.rename(plan.key, plan.newName, plan.value)
                    if (result == EditResult.Saved) {
                        keepShopSection(plan.key, plan.newName)
                        if (current != null) {
                            val newName = ItemKeys.clean(plan.newName)
                            val after = plan.value ?: current.value
                            sendEdited(ListEvent.Edited(ItemKeys.normalize(newName), current.name, newName, current.value, after, ticked))
                        }
                    }
                    // "Not on the list" is about the item being renamed (its old name).
                    editRefused(result, plan.newName, goneName = current?.name ?: plan.newName)?.let { _events.send(it) }
                }
                is ItemSavePlan.AlreadyOnList -> _events.send(ListEvent.Message(alreadyOnListText(plan.existingName)))
            }
        }
    }

    /**
     * The snackbar of an Edit dialog save: with Undo, unless the Undo couldn't give the old name back
     * ([ListEvent.Edited.undoable]); then the same words without it.
     */
    private suspend fun sendEdited(edited: ListEvent.Edited) {
        _events.send(if (edited.undoable) edited else ListEvent.Message(edited.snackbarText()))
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

    /**
     * Deletes the item, with Undo ([undoDelete]). When nothing was deleted there is nothing to undo: the item went
     * meanwhile (removed or renamed on the other phone; an Undo would add back an item they removed), and the
     * snackbar says it was changed there, or its name can't be sent.
     */
    fun deleteItem(key: String) {
        closeDialog()
        edit {
            val item = currentItem(key) ?: return@edit
            val ticked = tickedEntryOf(item)
            val deletion = repository.delete(key)
            _events.send(
                when (deletion.result) {
                    EditResult.Saved -> ListEvent.Deleted(item.name, item.value, deletion.addedAt, ticked)
                    EditResult.InvalidName -> cantChange(item)
                    else -> ListEvent.Message(changedElsewhereText(item.name))
                },
            )
        }
    }

    /**
     * Undo of a change of one item's value ("Got it", - / +, the Need switch, Put back): writes the value from before
     * back, but only while the item still has the value the change set ([GroceryRepository.updateIf], checked as it is
     * queued). When the other phone changed, renamed or removed it meanwhile, nothing is written (a renamed one would
     * come back as a second item under its old name) and a snackbar says so. One that has the value from before
     * already (this phone gave it back meanwhile) needs nothing. The Undo of a tick also takes the item off Ticked
     * today; the Undo of a change that took it off Ticked today puts it back ([ListEvent.ValueChange.tickedEntry]).
     */
    fun undoChange(change: ListEvent.ValueChange) = edit {
        val result = repository.updateIf(change.key, change.after, change.before)
        when {
            change.ticksOff -> tickedTodayStore.remove(change.key)
            result == EditResult.Saved -> change.tickedEntry?.let(tickedTodayStore::restore)
        }
        editRefused(result, change.name)?.let { _events.send(it) }
    }

    /**
     * Undo of an Edit dialog save: the old name and the old value come back, but only while the item still has the
     * value the edit set: [GroceryRepository.updateIf], or for a new name [GroceryRepository.renameIf], which also
     * never overwrites an item that has taken the old name meanwhile ("already on the list"). This phone's shop
     * section for it goes back to the old name too, and so does its entry under Ticked today, if the edit took one.
     */
    fun undoEdit(change: ListEvent.Edited) = edit {
        val result = if (change.renamed) {
            repository.renameIf(change.key, expected = change.after, newName = change.oldName, value = change.before)
        } else {
            repository.updateIf(change.key, expected = change.after, value = change.before)
        }
        if (result == EditResult.Saved) {
            keepShopSection(change.key, change.oldName)
            change.tickedEntry?.let(tickedTodayStore::restore)
        }
        editRefused(result, change.newName)?.let { _events.send(it) }
    }

    /**
     * Ticked today's Put back: the item gets back the value it had before this phone ticked it, with Undo
     * ([ListEvent.PutBack], whose snackbar also replaces the tick's own Undo). Only while it is still as the tick left
     * it ([TickedEntry.isStillTicked], checked again here: the list on screen may not have caught up with a change
     * from the other phone yet) and still has the value the tick set ([GroceryRepository.updateIf]); otherwise nothing
     * is written and a snackbar says it was changed on another phone. Either way it leaves Ticked today. A second tap
     * before the row has gone (or a tap on an entry undone or pruned meanwhile) does nothing.
     */
    fun putBack(entry: TickedEntry) = edit {
        if (!tickedTodayStore.holds(entry)) return@edit
        val result = if (entry.isStillTicked(currentItem(entry.key), settingsStore.effectiveDeviceName())) {
            repository.updateIf(entry.key, expected = entry.set, value = entry.before)
        } else {
            EditResult.ValueChanged
        }
        tickedTodayStore.remove(entry.key)
        _events.send(editRefused(result, entry.name) ?: ListEvent.PutBack(entry))
    }

    /**
     * Simple mode's add dialog, for a name of an item that is on the list but not needed ([ItemEditDialog]'s
     * onNeedExisting): closes the dialog and makes that item needed again ([neededAgainChange]), with Undo
     * ([undoChange], which also puts back its entry under Ticked today, if this took one away). Written only while
     * the item still has the value read here ([GroceryRepository.updateIf]): one the other phone changed meanwhile is
     * left as it is and a snackbar says so; one needed already is left alone.
     */
    fun needAgain(key: String) {
        closeDialog()
        edit {
            val item = currentItem(key)
            if (item == null) {
                _events.send(ListEvent.Message(Texts.current.list.itemChangedElsewhere))
                return@edit
            }
            if (item.value.isNeeded) return@edit
            val change = neededAgainChange(item, tickedTodayStore.entryOf(key), settingsStore.effectiveDeviceName())
            val result = repository.updateIf(key, expected = change.before, value = change.after)
            if (result == EditResult.Saved) {
                sendChange(change)
            } else {
                editRefused(result, item.name)?.let { _events.send(it) }
            }
        }
    }

    /**
     * Undo of a delete: adds the item back with its previous value and its Added At, unless an item with its name is
     * on the list again meanwhile (added or renamed to it on the other phone): that one is never overwritten, and a
     * snackbar says the name is already on the list. A ticked item goes back under Ticked today.
     */
    fun undoDelete(change: ListEvent.Deleted) = edit {
        val result = repository.addBack(change.name, change.previous, change.addedAt)
        if (result == EditResult.Saved) change.tickedEntry?.let(tickedTodayStore::restore)
        editRefused(result, change.name)?.let { _events.send(it) }
    }

    fun retryFailed() = edit { repository.retryFailed() }

    fun discardFailed() = edit { repository.discardFailed() }

    private suspend fun currentItem(key: String): GroceryItem? =
        repository.items.first().firstOrNull { it.key == key }

    /**
     * Runs a local edit after the ones before it ([editMutex]), then prunes Ticked today ([pruneTickedToday]). A
     * failure is logged, and the snackbar says the change couldn't be saved.
     */
    private fun edit(block: suspend () -> Unit) {
        viewModelScope.launch {
            editMutex.withLock {
                try {
                    block()
                    pruneTickedToday()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Local edit failed", e)
                    _events.send(ListEvent.Message(Texts.current.list.editFailed))
                }
            }
        }
    }

    /**
     * The entry of [item] under Ticked today while it is shown there ([TickedEntry.isStillTicked]). Read before a
     * change that may take it away ([pruneTickedToday]: + or the Need switch making it needed again, an edit, a
     * delete), so that change's Undo can put it back ([ListEvent.ValueChange.tickedEntry]). One that is only hidden
     * (the other phone ticked the item off again itself) isn't this phone's tick, and an Undo mustn't make it one.
     */
    private fun tickedEntryOf(item: GroceryItem): TickedEntry? =
        tickedTodayStore.entryOf(item.key)?.takeIf { it.isStillTicked(item, settingsStore.effectiveDeviceName()) }

    /**
     * Takes the entries whose item is needed again, renamed, gone or changed last on the other phone off Ticked today
     * ([TickedEntry.isStillTicked]), after each change this phone makes. The list is read again here, not taken from
     * [uiState]: that may not have caught up with the change just made yet, and a tick recorded a moment ago would
     * look needed again.
     */
    private suspend fun pruneTickedToday() {
        if (tickedTodayStore.isEmpty) return
        val byKey = repository.items.first().associateBy { it.key }
        val me = settingsStore.effectiveDeviceName()
        tickedTodayStore.retain { it.isStillTicked(byKey[it.key], me) }
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
