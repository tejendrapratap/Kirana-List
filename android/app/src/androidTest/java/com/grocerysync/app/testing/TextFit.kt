package com.grocerysync.app.testing

import androidx.compose.ui.text.TextLayoutResult

/**
 * Whether the text of [layout] (what a node's GetTextLayoutResult gives) doesn't fit the room it was laid out in:
 * it needed more lines than it may have (cut, or ended with "..."), it is taller than its box, or one of its lines
 * is wider than its box.
 *
 * Not [TextLayoutResult.hasVisualOverflow]: for a Text of a plain String, the result the semantics give is laid out
 * again at the full width the Text was offered, while its size is the width the Text took. So any short text (a
 * dialog title, a button's label) counts as wider than itself, and hasVisualOverflow is true for it.
 */
fun isCutOff(layout: TextLayoutResult): Boolean {
    val paragraph = layout.multiParagraph
    if (paragraph.didExceedMaxLines) return true
    if (layout.size.height < paragraph.height) return true
    val width = layout.size.width + WIDTH_SLACK_PX
    return (0 until layout.lineCount).any { line -> layout.getLineRight(line) - layout.getLineLeft(line) > width }
}

/** The layout's size is whole pixels, rounded up from the line's width. */
private const val WIDTH_SLACK_PX = 1f
