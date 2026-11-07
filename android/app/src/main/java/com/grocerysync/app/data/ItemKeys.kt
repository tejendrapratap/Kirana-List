package com.grocerysync.app.data

import java.util.Locale

/**
 * Item identity rules shared with Code.gs: `name.trim().replace(/\s+/g, ' ').toLowerCase()`.
 *
 * JavaScript's `trim()` and `\s` cover more characters than Java's regex `\s`
 * (no-break space, ideographic space, BOM, ...), so whitespace is matched explicitly here
 * to produce exactly the same key as the server.
 */
object ItemKeys {

    fun normalize(name: String): String = collapseWhitespace(name).lowercase(Locale.ROOT)

    /**
     * Trims like JavaScript's `trim()` and turns every whitespace run into one space, keeping case:
     * Code.gs applies the same rule to item keys and to the device name it stores in "Updated By".
     */
    fun collapseWhitespace(text: String): String {
        val trimmed = clean(text)
        val sb = StringBuilder(trimmed.length)
        var inWhitespace = false
        for (c in trimmed) {
            if (isJsWhitespace(c)) {
                if (!inWhitespace) sb.append(' ')
                inWhitespace = true
            } else {
                sb.append(c)
                inWhitespace = false
            }
        }
        return sb.toString()
    }

    /**
     * [name] on one line: when it contains a line break (possible for names typed into the sheet),
     * every whitespace run becomes one space. The key stays the same; other names are returned as they are.
     */
    fun singleLine(name: String): String =
        if (name.any { it == '\n' || it == '\r' || it == '\u2028' || it == '\u2029' }) collapseWhitespace(name) else name

    /** Display name as the server stores it: trimmed the way JavaScript's `trim()` does, inner spacing kept. */
    fun clean(name: String): String {
        var start = 0
        var end = name.length
        while (start < end && isJsWhitespace(name[start])) start++
        while (end > start && isJsWhitespace(name[end - 1])) end--
        return name.substring(start, end)
    }

    /** ECMAScript WhiteSpace + LineTerminator. */
    internal fun isJsWhitespace(c: Char): Boolean = when (c) {
        '\t', '\n', '\u000B', '\u000C', '\r', '\uFEFF', '\u2028', '\u2029' -> true
        else -> Character.getType(c) == Character.SPACE_SEPARATOR.toInt()
    }
}
