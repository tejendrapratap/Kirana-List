package com.grocerysync.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How this phone shows To buy ([ShopSectionStore]): grouped by shop section or A to Z, and the sections chosen
 * for items in their Edit dialog, by item key ([ItemKeys.normalize]).
 */
data class ShopSectionSettings(
    /** To buy is grouped by shop section, in walking order (the default), instead of A to Z. */
    val groupBySection: Boolean = true,
    /** The section chosen for an item on this phone, by item key; items not in here go where the dictionary says. */
    val chosen: Map<String, ShopSection> = emptyMap(),
) {
    /** The section of [item] on this phone: the one chosen for it, else the dictionary's ([ItemCatalog.sectionFor]). */
    fun sectionOf(item: GroceryItem): ShopSection = chosen[item.key] ?: ItemCatalog.sectionFor(item.name)
}

/**
 * [ShopSectionSettings] in private SharedPreferences. They belong to this phone only: nothing here is synced or
 * written to the sheet, so each person can sort their own To buy the way they walk through their shops.
 *
 * A chosen section is stored as its [ShopSection.id] (which never changes) under [SECTION_PREFIX] + the item key.
 * An id this version doesn't know is ignored, and the item goes where the dictionary says.
 */
class ShopSectionStore internal constructor(private val prefs: SharedPreferences) {

    constructor(context: Context) :
        this(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    private val state = MutableStateFlow(load())

    val settings: StateFlow<ShopSectionSettings> = state.asStateFlow()

    fun current(): ShopSectionSettings = state.value

    fun setGroupBySection(group: Boolean) {
        synchronized(this) {
            prefs.edit { putBoolean(KEY_GROUP_BY_SECTION, group) }
            state.value = state.value.copy(groupBySection = group)
        }
    }

    /**
     * The section picked for the item [key], which is called (or being renamed to) [name]. The dictionary's own
     * section for [name] forgets the choice instead, so the item follows the dictionary again.
     */
    fun chooseSection(key: String, name: String, section: ShopSection) {
        setSection(key, section.takeUnless { it == ItemCatalog.sectionFor(name) })
    }

    /** Puts the item [key] into [section] on this phone; null forgets the choice, so the dictionary decides again. */
    fun setSection(key: String, section: ShopSection?) {
        synchronized(this) {
            if (state.value.chosen[key] == section) return
            prefs.edit {
                if (section == null) remove(SECTION_PREFIX + key) else putString(SECTION_PREFIX + key, section.id)
            }
            state.value = state.value.copy(chosen = state.value.chosen.with(key, section))
        }
    }

    /**
     * The item [fromKey] was renamed to [toKey] on this phone: its choice goes with it, and so does having none
     * (a choice left under [toKey] by an item deleted earlier no longer counts). Nothing changes when the keys
     * are the same (only capitals changed).
     */
    fun moveSection(fromKey: String, toKey: String) {
        if (fromKey == toKey) return
        synchronized(this) {
            val section = state.value.chosen[fromKey]
            setSection(toKey, section)
            setSection(fromKey, null)
        }
    }

    private fun load(): ShopSectionSettings {
        val chosen = HashMap<String, ShopSection>()
        for ((name, value) in prefs.all) {
            if (!name.startsWith(SECTION_PREFIX)) continue
            val section = ShopSection.fromId(value as? String) ?: continue
            chosen[name.removePrefix(SECTION_PREFIX)] = section
        }
        return ShopSectionSettings(
            groupBySection = prefs.getBoolean(KEY_GROUP_BY_SECTION, true),
            chosen = chosen,
        )
    }

    private companion object {
        const val PREFS_NAME = "grocery_sync_shop_sections"
        const val KEY_GROUP_BY_SECTION = "group_by_section"
        const val SECTION_PREFIX = "section:"

        fun Map<String, ShopSection>.with(key: String, section: ShopSection?): Map<String, ShopSection> =
            if (section == null) this - key else this + (key to section)
    }
}
