package com.grocerysync.app.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Simple mode's rules for the list screen (SimpleMode.kt): its bigger text, its ⋮ menu, and how long its snackbars
 * stay. With Simple mode off, each is what the screen did before Simple mode.
 */
class SimpleModeRulesTest {

    @Test
    fun theTextIsAQuarterBigger() {
        assertEquals(1.25f, SIMPLE_TEXT_SCALE)
        val style = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp)
        val scaled = style.scaledBy(1.25f)
        assertEquals(20.sp, scaled.fontSize)
        assertEquals(30.sp, scaled.lineHeight)
        assertEquals(0.625.sp, scaled.letterSpacing)
        // Only the sizes change.
        assertEquals(style.copy(fontSize = 20.sp, lineHeight = 30.sp, letterSpacing = 0.625.sp), scaled)
    }

    @Test
    fun sizesInEmAndUnspecifiedOnesStayAsTheyAre() {
        // An em is a share of the font size, which grows already.
        val relative = TextStyle(fontSize = 14.sp, lineHeight = 1.5.em, letterSpacing = 0.1.em)
        assertEquals(TextStyle(fontSize = 17.5.sp, lineHeight = 1.5.em, letterSpacing = 0.1.em), relative.scaledBy(1.25f))

        val unspecified = TextStyle()
        assertEquals(TextUnit.Unspecified, unspecified.scaledBy(1.25f).fontSize)
        assertEquals(unspecified, unspecified.scaledBy(1.25f))
    }

    @Test
    fun everyStyleOfTheThemeGrows() {
        val base = Typography()
        val scaled = base.scaledBy(SIMPLE_TEXT_SCALE)
        val pairs = listOf(
            base.displayLarge to scaled.displayLarge,
            base.displayMedium to scaled.displayMedium,
            base.displaySmall to scaled.displaySmall,
            base.headlineLarge to scaled.headlineLarge,
            base.headlineMedium to scaled.headlineMedium,
            base.headlineSmall to scaled.headlineSmall,
            base.titleLarge to scaled.titleLarge,
            base.titleMedium to scaled.titleMedium,
            base.titleSmall to scaled.titleSmall,
            base.bodyLarge to scaled.bodyLarge,
            base.bodyMedium to scaled.bodyMedium,
            base.bodySmall to scaled.bodySmall,
            base.labelLarge to scaled.labelLarge,
            base.labelMedium to scaled.labelMedium,
            base.labelSmall to scaled.labelSmall,
        )
        for ((before, after) in pairs) {
            assertEquals(before.fontSize.value * 1.25f, after.fontSize.value, 0.001f)
            assertEquals(before.lineHeight.value * 1.25f, after.lineHeight.value, 0.001f)
            assertEquals(before.fontWeight, after.fontWeight)
            assertEquals(before.fontFamily, after.fontFamily)
        }
        // Rows use bodyLarge: Material's 16sp on 24sp lines become 20sp on 30sp.
        assertEquals(20.sp, scaled.bodyLarge.fontSize)
        assertEquals(30.sp, scaled.bodyLarge.lineHeight)
    }

    @Test
    fun theMenuHoldsOnlySettingsInSimpleMode() {
        assertEquals(listOf(ListMenuChoice.SETTINGS), listMenuChoices(simpleMode = true))
        // As before Simple mode, in this order.
        assertEquals(
            listOf(
                ListMenuChoice.ADD_COMMON_ITEMS,
                ListMenuChoice.PASTE_LIST,
                ListMenuChoice.GROUP_BY_SECTION,
                ListMenuChoice.SETTINGS,
            ),
            listMenuChoices(simpleMode = false),
        )
    }

    @Test
    fun inSimpleModeUndoStaysUntilItIsTappedOrClosed() {
        assertEquals(SnackbarLook(SnackbarDuration.Indefinite, withDismissAction = true), SnackbarLook.of(simpleMode = true, hasUndo = true))
        // A message without Undo goes, but later than usual, and can be closed before.
        assertEquals(SnackbarLook(SnackbarDuration.Long, withDismissAction = true), SnackbarLook.of(simpleMode = true, hasUndo = false))
    }

    @Test
    fun withoutSimpleModeEverySnackbarGoesSoonAsBefore() {
        val usual = SnackbarLook(SnackbarDuration.Short, withDismissAction = false)
        assertEquals(usual, SnackbarLook.of(simpleMode = false, hasUndo = true))
        assertEquals(usual, SnackbarLook.of(simpleMode = false, hasUndo = false))
    }

    @Test
    fun simpleRowsAreAtLeast64dpTall() {
        assertEquals(64f, SimpleRowMinHeight.value)
    }
}
