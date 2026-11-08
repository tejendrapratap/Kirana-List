package com.grocerysync.app.data

import java.text.Normalizer

/**
 * How search compares text: the list screen's search (ItemSearch) and the item dictionary ([ItemCatalog])
 * fold names and queries the same way, so a name that search treats as the same is the same for both.
 */
object SearchText {

    /**
     * Marks that don't count: Latin, Greek and Cyrillic combining accents (after NFD), the Devanagari
     * nukta and the apostrophes ' ‘ ’. Other marks (Devanagari vowel signs, Thai, Arabic) change the
     * word, so they are kept.
     */
    private val IGNORED_MARKS = Regex("[\\u0300-\\u036F\\u093C'\\u2018\\u2019]")

    private const val CHANDRABINDU = '\u0901'
    private const val ANUSVARA = '\u0902'

    /**
     * [text] as search compares it: the item key ([ItemKeys.normalize]: trimmed, whitespace runs as one
     * space, lower case ignoring the device locale), decomposed (NFD) without [IGNORED_MARKS], and
     * chandrabindu written as anusvara. "Crème fraîche" gives "creme fraiche", "Lay's" gives "lays".
     */
    fun fold(text: String): String =
        IGNORED_MARKS.replace(Normalizer.normalize(ItemKeys.normalize(text), Normalizer.Form.NFD), "")
            .replace(CHANDRABINDU, ANUSVARA)
}
