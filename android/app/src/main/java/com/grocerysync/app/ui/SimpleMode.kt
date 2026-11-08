package com.grocerysync.app.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

// Simple mode (Settings > This phone, PhoneSettings.simpleMode): the list screen for someone who finds the usual one
// too busy or too small, often an elder. Only To buy, bigger text and buttons, and an Undo that waits to be tapped.
// The rules that are not drawing are here (unit-tested in SimpleModeRulesTest); ListScreen, ListTopBar and the
// components draw it. With Simple mode off the list screen is exactly as it was before Simple mode existed.

/** How much bigger the list screen's text is in Simple mode, on top of the phone's own font size. */
internal const val SIMPLE_TEXT_SCALE = 1.25f

/** The least height of a row in Simple mode (To buy's rows, Ticked today's): an easy target for a shaky finger. */
internal val SimpleRowMinHeight = 64.dp

/**
 * These text styles [factor] times as big: their font size, line height and letter spacing in sp (as a bigger font
 * size in Android's settings would make them). Sizes in em are relative to the font size already, and unspecified
 * ones stay unspecified.
 */
internal fun Typography.scaledBy(factor: Float): Typography = copy(
    displayLarge = displayLarge.scaledBy(factor),
    displayMedium = displayMedium.scaledBy(factor),
    displaySmall = displaySmall.scaledBy(factor),
    headlineLarge = headlineLarge.scaledBy(factor),
    headlineMedium = headlineMedium.scaledBy(factor),
    headlineSmall = headlineSmall.scaledBy(factor),
    titleLarge = titleLarge.scaledBy(factor),
    titleMedium = titleMedium.scaledBy(factor),
    titleSmall = titleSmall.scaledBy(factor),
    bodyLarge = bodyLarge.scaledBy(factor),
    bodyMedium = bodyMedium.scaledBy(factor),
    bodySmall = bodySmall.scaledBy(factor),
    labelLarge = labelLarge.scaledBy(factor),
    labelMedium = labelMedium.scaledBy(factor),
    labelSmall = labelSmall.scaledBy(factor),
)

/** This style [factor] times as big (see [Typography.scaledBy]). */
internal fun TextStyle.scaledBy(factor: Float): TextStyle = copy(
    fontSize = fontSize.scaledBy(factor),
    lineHeight = lineHeight.scaledBy(factor),
    letterSpacing = letterSpacing.scaledBy(factor),
)

private fun TextUnit.scaledBy(factor: Float): TextUnit = if (isSp) this * factor else this

/** The choices of the app bar's ⋮ menu, in their order. */
internal enum class ListMenuChoice { ADD_COMMON_ITEMS, PASTE_LIST, GROUP_BY_SECTION, SETTINGS }

/**
 * The ⋮ menu's choices. Simple mode keeps only Settings, where Simple mode is turned off again: adding many items at
 * once and changing how To buy is grouped are for the usual screen (grouping keeps the phone's choice meanwhile).
 */
internal fun listMenuChoices(simpleMode: Boolean): List<ListMenuChoice> =
    if (simpleMode) listOf(ListMenuChoice.SETTINGS) else ListMenuChoice.entries

/**
 * How a snackbar of the list screen is shown: for [duration], and with an X that closes it ([withDismissAction]).
 * Normally every snackbar goes after a few seconds. In Simple mode one with Undo ([hasUndo]) stays until it is
 * tapped, closed or replaced by the next message, so there is time to read it and to change one's mind; one without
 * Undo stays for the longer of Material's times. Both have the X then, so nothing waits on screen without a way to
 * close it.
 */
internal data class SnackbarLook(val duration: SnackbarDuration, val withDismissAction: Boolean) {
    companion object {
        fun of(simpleMode: Boolean, hasUndo: Boolean): SnackbarLook = when {
            !simpleMode -> SnackbarLook(SnackbarDuration.Short, withDismissAction = false)
            hasUndo -> SnackbarLook(SnackbarDuration.Indefinite, withDismissAction = true)
            else -> SnackbarLook(SnackbarDuration.Long, withDismissAction = true)
        }
    }
}
