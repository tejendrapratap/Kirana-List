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
         *   To buy; the count is every item the text lists), "Mummy and Shop added 5 items to To buy", or, when only
         *   amounts changed, "Mummy changed To buy";
         * - text: the items with their amounts, comma separated, in list order: "Dahi, Oil 2 L, Batti"; after
         *   [MAX_NAMES] of them "+3 more".
         * People are named as the sheet has them (spaces tidied), each once, in the order of their first change.
         */
        fun of(changes: List<ToBuyChange>, texts: AppTexts): ChangeNotice {
            require(changes.isNotEmpty()) { "Nothing changed" }
            val people = changes
                .map { ItemKeys.collapseWhitespace(it.item.updatedBy) }
                .distinctBy { it.lowercase() }
            val who = texts.notify.people(people)
            val title = if (changes.any { it.isNew }) {
                texts.notify.addedTitle(who, changes.size)
            } else {
                texts.notify.changedTitle(who)
            }
            val names = changes.take(MAX_NAMES).joinToString(", ") { itemText(it) }
            val more = changes.size - MAX_NAMES
            return ChangeNotice(title, if (more > 0) "$names ${texts.notify.more(more)}" else names)
        }

        /** "Dahi" (yes / no), "Oil 2 L", "Eggs × 12": name and amount as the list shows them (data, not translated). */
        private fun itemText(change: ToBuyChange): String = when (val value = change.item.value) {
            is ItemValue.BoolValue -> change.item.name
            is ItemValue.Quantity -> "${change.item.name} ${value.displayText()}"
        }
    }
}
