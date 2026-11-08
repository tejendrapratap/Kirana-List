package com.grocerysync.app.notify

import com.grocerysync.app.data.ItemKeys
import com.grocerysync.app.data.ItemValue
import com.grocerysync.app.data.displayText
import com.grocerysync.app.text.AppTexts

/** What the change notification says: its [title] and its [text] (the one line under it, longer when expanded). */
data class ChangeNotice(val title: String, val text: String) {
    companion object {
        /** Item names the text lists before "+3 more": about what fits on two lines of an expanded notification. */
        const val MAX_NAMES = 8

        /**
         * The notification for [changes] (never empty), in [texts]' language:
         * - title: who made them and what they did: "Mummy added 3 items to To buy" (at least one item is new on
         *   To buy: the title counts only those and names only who added them, so an amount changed beside them
         *   is listed in the text but never counted as added), "Mummy and Shop added 5 items to To buy", or, when
         *   only amounts changed, "Mummy changed To buy";
         * - text: every item with its amount, comma separated, in list order: "Dahi, Oil 2 L, Batti"; after
         *   [MAX_NAMES] of them "+3 more".
         * People are named as the sheet has them (spaces tidied), each once, in the order of their first change.
         */
        fun of(changes: List<ToBuyChange>, texts: AppTexts): ChangeNotice {
            require(changes.isNotEmpty()) { "Nothing changed" }
            val added = changes.filter { it.isNew }
            val title = if (added.isNotEmpty()) {
                texts.notify.addedTitle(people(added, texts), added.size)
            } else {
                texts.notify.changedTitle(people(changes, texts))
            }
            val names = changes.take(MAX_NAMES).joinToString(", ") { itemText(it) }
            val more = changes.size - MAX_NAMES
            return ChangeNotice(title, if (more > 0) "$names ${texts.notify.more(more)}" else names)
        }

        /** Who made [changes], each once, for the title ([com.grocerysync.app.text.NotifyTexts.people]). */
        private fun people(changes: List<ToBuyChange>, texts: AppTexts): String =
            texts.notify.people(
                changes.map { ItemKeys.collapseWhitespace(it.item.updatedBy) }.distinctBy { it.lowercase() },
            )

        /** "Dahi" (yes / no), "Oil 2 L", "Eggs × 12": name and amount as the list shows them (data, not translated). */
        private fun itemText(change: ToBuyChange): String = when (val value = change.item.value) {
            is ItemValue.BoolValue -> change.item.name
            is ItemValue.Quantity -> "${change.item.name} ${value.displayText()}"
        }
    }
}
