package com.grocerysync.app.ui

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Layout decisions for the add/edit dialog, kept free of Compose so they can be unit tested.
 *
 * A phone in landscape is only about 400dp tall. Stacked one below the other, the name,
 * "Track as" and value controls don't fit between the dialog's title and its buttons, so the
 * value control ends up below the fold. On such short windows the dialog is widened (up to
 * Material's 560dp maximum), "Track as" and the value sit side by side and the vertical spacing
 * is tighter. "Track as" takes the width of its two buttons; the value column gets the rest, so the
 * amount stepper and the unit picker fit on one line next to it.
 */
internal object DialogLayout {
    /** Windows shorter than this are "compact height" in Material's window size classes. */
    const val SHORT_WINDOW_HEIGHT_DP = 480

    /** Material's minimum and maximum dialog widths. */
    const val MIN_DIALOG_WIDTH_DP = 280
    const val MAX_DIALOG_WIDTH_DP = 560

    /** Space kept between a widened dialog and the edges of the area it can use, per side. */
    const val DIALOG_MARGIN_DP = 24

    /** Gap between the "Track as" column and the value column. */
    const val COLUMN_GAP_DP = 16

    /** AlertDialog's own padding around its content, per side. */
    private const val DIALOG_CONTENT_PADDING_DP = 24

    /** The - and + buttons: 48dp touch targets (40dp visible). */
    const val STEP_BUTTON_DP = 48

    /** The amount field between them: room for "999999" or "0.125" at titleMedium. */
    const val AMOUNT_FIELD_WIDTH_DP = 72

    /** Between the + button and the unit picker. */
    const val UNIT_GAP_DP = 8

    /** The unit picker: room for "dozen", "bottle" (longer custom units scroll inside it). */
    const val UNIT_FIELD_WIDTH_DP = 92

    /** Amount stepper: - field +. Doesn't grow with the font size. */
    const val STEPPER_WIDTH_DP = 2 * STEP_BUTTON_DP + AMOUNT_FIELD_WIDTH_DP

    /** Stepper and unit picker on one line; a narrower value area puts the unit picker on the next line. */
    const val VALUE_ROW_WIDTH_DP = STEPPER_WIDTH_DP + UNIT_GAP_DP + UNIT_FIELD_WIDTH_DP

    /**
     * One segmented button without its label: 12dp padding on each side, plus the 18dp check icon
     * and its 8dp spacing (room for the icon is kept even when the button is not selected), plus
     * 1dp for rounding each of them to whole pixels.
     */
    private const val SEGMENT_CHROME_DP = 51f

    /** Vertical spacing inside the dialog body, in dp. */
    data class BodySpacing(
        /** Between the notice, the name field and the controls below it. */
        val sectionGapDp: Int,
        /** Between a field label ("Track as", "Need to buy?") and its control. */
        val labelGapDp: Int,
        /** Below the "changed on another phone" notice. */
        val noticeGapDp: Int,
    )

    /** The spacing used in portrait and on tall windows. */
    val REGULAR_SPACING = BodySpacing(sectionGapDp = 16, labelGapDp = 8, noticeGapDp = 12)

    /** Tighter spacing for short windows, where every dp decides whether the value control is visible. */
    val COMPACT_SPACING = BodySpacing(sectionGapDp = 8, labelGapDp = 4, noticeGapDp = 8)

    /** True for phones in landscape. 0 means the window has not been measured yet. */
    fun isShortWindow(windowHeightDp: Int): Boolean = windowHeightDp in 1 until SHORT_WINDOW_HEIGHT_DP

    fun bodySpacing(shortWindow: Boolean): BodySpacing = if (shortWindow) COMPACT_SPACING else REGULAR_SPACING

    /**
     * Width of the widened dialog on a short window. [availableWidthDp] is the window width minus
     * the system bars and display cutout at its sides, i.e. the width the dialog window gets.
     * Never below Material's minimum; on a window narrower than that the dialog fills its width.
     */
    fun widenedDialogWidthDp(availableWidthDp: Int): Int =
        min(MAX_DIALOG_WIDTH_DP, availableWidthDp - 2 * DIALOG_MARGIN_DP).coerceAtLeast(MIN_DIALOG_WIDTH_DP)

    /** Widths of the side-by-side layout: "Track as" (its buttons' width) and the value column (the rest). */
    data class Columns(val trackAsWidthDp: Int, val valueWidthDp: Int) {
        /** The unit picker fits next to the amount stepper (else it wraps to the next line). */
        val unitBesideStepper: Boolean get() = unitFitsBesideStepper(valueWidthDp)
    }

    /** Two segmented buttons whose widest label is [segmentLabelWidthDp] wide, with room for their check icons. */
    fun segmentedRowWidthDp(segmentLabelWidthDp: Float): Int = ceil(2 * (SEGMENT_CHROME_DP + segmentLabelWidthDp)).toInt()

    /**
     * The side-by-side columns, or null to keep "Track as" and the value stacked: side by side only on
     * short windows, and only when the widened dialog leaves the value column room for the amount
     * stepper and for the yes/no buttons (as wide as the "Track as" ones). [segmentLabelWidthDp] is the
     * width of the widest segmented-button label, measured at the current font size. Stacked, the
     * dialog scrolls, with an edge hint showing that there is more. (Stacked is never shorter than side
     * by side, so the height of the window doesn't change the choice.)
     */
    fun sideBySideColumns(availableWidthDp: Int, windowHeightDp: Int, segmentLabelWidthDp: Float): Columns? {
        if (!isShortWindow(windowHeightDp)) return null
        val contentWidth = widenedDialogWidthDp(availableWidthDp) - 2 * DIALOG_CONTENT_PADDING_DP
        val trackAsWidth = segmentedRowWidthDp(segmentLabelWidthDp)
        val valueWidth = contentWidth - COLUMN_GAP_DP - trackAsWidth
        return if (valueWidth >= max(STEPPER_WIDTH_DP, trackAsWidth)) Columns(trackAsWidth, valueWidth) else null
    }

    fun useSideBySide(availableWidthDp: Int, windowHeightDp: Int, segmentLabelWidthDp: Float): Boolean =
        sideBySideColumns(availableWidthDp, windowHeightDp, segmentLabelWidthDp) != null

    /** Whether the stepper and the unit picker share a line in a value area [widthDp] wide. */
    fun unitFitsBesideStepper(widthDp: Int): Boolean = widthDp >= VALUE_ROW_WIDTH_DP
}
