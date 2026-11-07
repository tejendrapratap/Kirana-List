package com.grocerysync.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogLayoutTest {

    // Width of the widest segmented-button label ("Quantity", labelLarge) on a Pixel 9a at font
    // scale 1.0, 1.15 and 1.3, and an estimate for 1.5.
    private val label100 = 52.6f
    private val label115 = 62.9f
    private val label130 = 70.9f
    private val label150 = 82f

    // Pixel 9a in landscape: a 923 x 411 dp window; the dialog window leaves out the 54dp display cutout.
    private val pixelLandscapeWidth = 869
    private val pixelLandscapeHeight = 411

    @Test
    fun portraitPhoneKeepsTheStackedLayout() {
        assertFalse(DialogLayout.isShortWindow(923))
        assertFalse(DialogLayout.useSideBySide(availableWidthDp = 411, windowHeightDp = 923, segmentLabelWidthDp = label100))
    }

    @Test
    fun landscapePhoneGoesSideBySideUpToFontScale13() {
        assertTrue(DialogLayout.isShortWindow(pixelLandscapeHeight))
        assertEquals(560, DialogLayout.widenedDialogWidthDp(pixelLandscapeWidth))
        assertTrue(DialogLayout.useSideBySide(pixelLandscapeWidth, pixelLandscapeHeight, label100))
        assertTrue(DialogLayout.useSideBySide(pixelLandscapeWidth, pixelLandscapeHeight, label115))
        assertTrue(DialogLayout.useSideBySide(pixelLandscapeWidth, pixelLandscapeHeight, label130))
    }

    @Test
    fun landscapePhoneWithVeryLargeFontsStaysStacked() {
        assertFalse(DialogLayout.useSideBySide(pixelLandscapeWidth, pixelLandscapeHeight, label150))
        assertFalse(DialogLayout.useSideBySide(pixelLandscapeWidth, pixelLandscapeHeight, 110f))
    }

    @Test
    fun segmentedButtonsNeedRoomForTwoLabelsAndTheirIconSpace() {
        // 560dp dialog: the value column also holds two (yes / no) buttons as wide as the "Track as" ones,
        // so labels up to 73dp fit (two columns of 248dp).
        assertTrue(DialogLayout.useSideBySide(pixelLandscapeWidth, pixelLandscapeHeight, 73f))
        assertFalse(DialogLayout.useSideBySide(pixelLandscapeWidth, pixelLandscapeHeight, 73.5f))
    }

    @Test
    fun stepperNeedsItsFullTouchTargetsEvenWithShortLabels() {
        // Changed with units: the stepper is 168dp wide (48 + 72 + 48) and "Track as" takes only the width of
        // its buttons (2 x (51 + 20) = 142dp with 20dp labels), the value column the rest.
        assertEquals(168, DialogLayout.STEPPER_WIDTH_DP)
        // 422dp: dialog 374, content 326, value column 326 - 16 - 142 = 168: just enough.
        assertEquals(DialogLayout.Columns(trackAsWidthDp = 142, valueWidthDp = 168), DialogLayout.sideBySideColumns(422, 320, 20f))
        assertTrue(DialogLayout.useSideBySide(422, 320, 20f))
        assertFalse(DialogLayout.useSideBySide(421, 320, 20f))
    }

    @Test
    fun pixel9aLandscapeFitsStepperAndUnitPickerOnOneLineNextToTrackAs() {
        // 560dp dialog, 512dp content: "Track as" 2 x (51 + 52.6) -> 208dp, the value column 512 - 16 - 208.
        val columns = DialogLayout.sideBySideColumns(pixelLandscapeWidth, pixelLandscapeHeight, label100)!!
        assertEquals(DialogLayout.Columns(trackAsWidthDp = 208, valueWidthDp = 288), columns)
        assertEquals(268, DialogLayout.VALUE_ROW_WIDTH_DP) // 48 + 72 + 48, 8dp gap, 92dp unit picker
        assertTrue(columns.unitBesideStepper)
        // Font scale 1.15 still fits (a 268dp value column); at 1.3 the columns stay, but the unit picker
        // moves under the stepper.
        assertTrue(DialogLayout.sideBySideColumns(pixelLandscapeWidth, pixelLandscapeHeight, label115)!!.unitBesideStepper)
        assertFalse(DialogLayout.sideBySideColumns(pixelLandscapeWidth, pixelLandscapeHeight, label130)!!.unitBesideStepper)
    }

    @Test
    fun portraitDialogsPutTheUnitPickerNextToTheStepperWhenItFits() {
        // Pixel 9a portrait: the platform dialog is 320dp wide, 272dp inside its padding.
        assertNull(DialogLayout.sideBySideColumns(availableWidthDp = 411, windowHeightDp = 923, segmentLabelWidthDp = label100))
        assertTrue(DialogLayout.unitFitsBesideStepper(272))
        assertTrue(DialogLayout.unitFitsBesideStepper(268))
        // Narrower phones (a 280dp dialog): the unit picker goes on the line below.
        assertFalse(DialogLayout.unitFitsBesideStepper(267))
        assertFalse(DialogLayout.unitFitsBesideStepper(232))
    }

    @Test
    fun trackAsColumnIsAsWideAsItsButtons() {
        assertEquals(208, DialogLayout.segmentedRowWidthDp(label100)) // 207.2, rounded up
        assertEquals(248, DialogLayout.segmentedRowWidthDp(73f))
        assertEquals(DialogLayout.Columns(trackAsWidthDp = 244, valueWidthDp = 252), DialogLayout.sideBySideColumns(pixelLandscapeWidth, pixelLandscapeHeight, label130))
    }

    @Test
    fun smallLandscapePhoneNarrowsTheDialogToFitTheScreen() {
        // 640 x 360 dp with a 37dp cutout at the side: a 555dp dialog with 245.5dp columns.
        assertEquals(555, DialogLayout.widenedDialogWidthDp(603))
        assertTrue(DialogLayout.useSideBySide(603, 360, label100))
        // 568 x 320 dp: dialog 520dp wide, columns 228dp; enough at 1.0 and 1.15, not at 1.3.
        assertEquals(520, DialogLayout.widenedDialogWidthDp(568))
        assertTrue(DialogLayout.useSideBySide(568, 320, label100))
        assertTrue(DialogLayout.useSideBySide(568, 320, label115))
        assertFalse(DialogLayout.useSideBySide(568, 320, label130))
    }

    @Test
    fun widenedDialogIsNeverNarrowerThanMaterialsMinimum() {
        assertEquals(280, DialogLayout.widenedDialogWidthDp(300))
        assertEquals(280, DialogLayout.widenedDialogWidthDp(0))
        assertFalse(DialogLayout.useSideBySide(300, 400, label100))
    }

    @Test
    fun tabletsAndUnknownHeightsAreNotShort() {
        assertFalse(DialogLayout.useSideBySide(1280, 800, label100))
        assertFalse(DialogLayout.isShortWindow(0))
        assertFalse(DialogLayout.useSideBySide(pixelLandscapeWidth, 0, label100))
    }

    @Test
    fun shortWindowsUseTighterSpacingAndTallOnesKeepTheRegularSpacing() {
        assertEquals(DialogLayout.BodySpacing(sectionGapDp = 16, labelGapDp = 8, noticeGapDp = 12), DialogLayout.bodySpacing(false))
        assertEquals(DialogLayout.BodySpacing(sectionGapDp = 8, labelGapDp = 4, noticeGapDp = 8), DialogLayout.bodySpacing(true))
    }
}
