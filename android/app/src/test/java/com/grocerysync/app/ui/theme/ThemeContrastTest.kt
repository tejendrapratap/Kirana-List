package com.grocerysync.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.grocerysync.app.data.ShopSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * WCAG 2 contrast of the brand schemes, light and dark: 4.5:1 for text and icons on the colour they sit on, 3:1
 * for outlines and other non-text parts (and the cart icon in the app bar, which is decoration).
 */
class ThemeContrastTest {

    private class Scheme(val name: String, val colors: ColorScheme, val brand: ExtendedColors)

    private val schemes = listOf(
        Scheme("light", LightColorScheme, LightExtendedColors),
        Scheme("dark", DarkColorScheme, DarkExtendedColors),
    )

    /** Each role and the text / icon colour drawn on it. */
    private fun ColorScheme.onPairs(): List<Triple<String, Color, Color>> = listOf(
        Triple("onPrimary on primary", onPrimary, primary),
        Triple("onPrimaryContainer on primaryContainer", onPrimaryContainer, primaryContainer),
        Triple("onSecondary on secondary", onSecondary, secondary),
        Triple("onSecondaryContainer on secondaryContainer", onSecondaryContainer, secondaryContainer),
        Triple("onTertiary on tertiary", onTertiary, tertiary),
        Triple("onTertiaryContainer on tertiaryContainer", onTertiaryContainer, tertiaryContainer),
        Triple("onError on error", onError, error),
        Triple("onErrorContainer on errorContainer", onErrorContainer, errorContainer),
        Triple("onBackground on background", onBackground, background),
        Triple("onSurface on surface", onSurface, surface),
        Triple("onSurfaceVariant on surfaceVariant", onSurfaceVariant, surfaceVariant),
        Triple("inverseOnSurface on inverseSurface", inverseOnSurface, inverseSurface),
        // A snackbar's action (Undo).
        Triple("inversePrimary on inverseSurface", inversePrimary, inverseSurface),
        Triple("onPrimaryFixed on primaryFixed", onPrimaryFixed, primaryFixed),
        Triple("onPrimaryFixed on primaryFixedDim", onPrimaryFixed, primaryFixedDim),
        Triple("onPrimaryFixedVariant on primaryFixed", onPrimaryFixedVariant, primaryFixed),
        Triple("onSecondaryFixed on secondaryFixed", onSecondaryFixed, secondaryFixed),
        Triple("onSecondaryFixed on secondaryFixedDim (quantity chip)", onSecondaryFixed, secondaryFixedDim),
        Triple("onSecondaryFixedVariant on secondaryFixed", onSecondaryFixedVariant, secondaryFixed),
        Triple("onTertiaryFixed on tertiaryFixed", onTertiaryFixed, tertiaryFixed),
        Triple("onTertiaryFixed on tertiaryFixedDim", onTertiaryFixed, tertiaryFixedDim),
        Triple("onTertiaryFixedVariant on tertiaryFixed", onTertiaryFixedVariant, tertiaryFixed),
    )

    /** The surfaces text is drawn on: screens, menus, dialogs, the search field, cards. */
    private fun ColorScheme.surfaces(): List<Pair<String, Color>> = listOf(
        "surface" to surface,
        "background" to background,
        "surfaceDim" to surfaceDim,
        "surfaceBright" to surfaceBright,
        "surfaceContainerLowest" to surfaceContainerLowest,
        "surfaceContainerLow" to surfaceContainerLow,
        "surfaceContainer" to surfaceContainer,
        "surfaceContainerHigh" to surfaceContainerHigh,
        "surfaceContainerHighest" to surfaceContainerHighest,
    )

    @Test
    fun everyOnColourReadsOnItsRole() {
        assertAll { scheme ->
            scheme.colors.onPairs().map { (what, fg, bg) -> Check(what, fg, bg, TEXT) }
        }
    }

    /**
     * onSurface and onSurfaceVariant on every surface; so are primary (section titles, "Updated by", links, text
     * buttons), tertiary (notices) and error (error texts).
     */
    @Test
    fun textReadsOnEverySurface() {
        assertAll { scheme ->
            val c = scheme.colors
            val texts = listOf(
                "onSurface" to c.onSurface,
                "onSurfaceVariant" to c.onSurfaceVariant,
                "primary" to c.primary,
                "tertiary" to c.tertiary,
                "error" to c.error,
            )
            c.surfaces().flatMap { (surfaceName, surface) ->
                texts.map { (textName, text) -> Check("$textName on $surfaceName", text, surface, TEXT) }
            }
        }
    }

    /** Outlines (text fields, segmented buttons, the switch border) are UI components: 3:1. */
    @Test
    fun outlinesStandOutFromEverySurface() {
        assertAll { scheme ->
            scheme.colors.surfaces().map { (name, surface) -> Check("outline on $name", scheme.colors.outline, surface, UI) }
        }
    }

    @Test
    fun appBarAndStatusColours() {
        assertAll { scheme ->
            val brand = scheme.brand
            listOf(
                Check("onAppBar on appBar", brand.onAppBar, brand.appBar, TEXT),
                Check("appBarIcon on appBar", brand.appBarIcon, brand.appBar, UI),
                Check("live dot on surface", brand.live, scheme.colors.surface, UI),
                Check("error dot on surface", scheme.colors.error, scheme.colors.surface, UI),
            )
        }
    }

    /** A recent change by the other phone: the row is tinted with primaryContainer at 45 % (ItemRows). */
    @Test
    fun highlightedRowsStayReadable() {
        assertAll { scheme ->
            val c = scheme.colors
            val row = c.primaryContainer.copy(alpha = 0.45f).compositeOver(c.surface)
            listOf(
                Check("onSurface on a highlighted row", c.onSurface, row, TEXT),
                Check("onSurfaceVariant on a highlighted row", c.onSurfaceVariant, row, TEXT),
                Check("primary (Updated by) on a highlighted row", c.primary, row, TEXT),
            )
        }
    }

    /** Buttons on the banners: Set up (filled, primary) on the turmeric card, text buttons on the error cards. */
    @Test
    fun buttonsOnBanners() {
        assertAll { scheme ->
            val c = scheme.colors
            listOf(
                Check("Set up button (primary) on secondaryContainer", c.primary, c.secondaryContainer, UI),
                Check("text button (primary) on errorContainer", c.primary, c.errorContainer, TEXT),
            )
        }
    }

    /**
     * To buy's shop-section headers (1.5): each section's accent mark stands out (3:1) from its header, which is the
     * surface tinted with the same accent, and the name (onSurface) and count (onSurfaceVariant) read on it (4.5:1).
     */
    @Test
    fun sectionHeadersReadInEverySection() {
        assertAll { scheme ->
            val c = scheme.colors
            val accents = scheme.brand.sectionAccents
            ShopSection.entries.flatMap { section ->
                val header = accents.headerColor(section, c.surface)
                listOf(
                    Check("${section.label} mark on its header", accents[section], header, UI),
                    Check("${section.label} name (onSurface) on its header", c.onSurface, header, TEXT),
                    Check("${section.label} count (onSurfaceVariant) on its header", c.onSurfaceVariant, header, TEXT),
                )
            }
        }
    }

    /**
     * The accent marks elsewhere: Add common items' section headers (surface), the Edit dialog's Shop section picker
     * (surfaceContainerHigh) and its menu (surfaceContainer). Non-text: 3:1.
     */
    @Test
    fun sectionMarksStandOutWhereverTheyShow() {
        assertAll { scheme ->
            val c = scheme.colors
            val surfaces = listOf(
                "surface" to c.surface,
                "surfaceContainer (menu)" to c.surfaceContainer,
                "surfaceContainerHigh (dialog)" to c.surfaceContainerHigh,
            )
            ShopSection.entries.flatMap { section ->
                surfaces.map { (name, surface) ->
                    Check("${section.label} mark on $name", scheme.brand.sectionAccents[section], surface, UI)
                }
            }
        }
    }

    /** Every section has its own accent, in both themes; a header's tint is light, never a block of colour. */
    @Test
    fun sectionAccentsAreDistinctAndOnlyTintTheHeaders() {
        for (scheme in schemes) {
            val accents = scheme.brand.sectionAccents
            val colours = ShopSection.entries.map { accents[it] }
            assertEquals("${scheme.name}: one accent per section", ShopSection.entries.size, colours.toSet().size)
            assertTrue("${scheme.name}: header tint ${accents.headerTint}", accents.headerTint in 0.04f..0.16f)
        }
    }

    /**
     * The full-screen dialogs (Add common items, Paste a list) and the empty list: the filled Add / Apply button
     * (primary) on the bottom bar (surfaceContainer, surface), the selected set chip and the empty list's tonal
     * button (onPrimaryContainer on primaryContainer, checked with every on-colour), the menus' tick (primary).
     */
    @Test
    fun buttonsAndTicksOnTheDialogs() {
        assertAll { scheme ->
            val c = scheme.colors
            listOf(
                Check("Add button (primary) on the bottom bar (surfaceContainer)", c.primary, c.surfaceContainer, UI),
                Check("Apply button (primary) on surface", c.primary, c.surface, UI),
                Check("the menu's tick (primary) on surfaceContainer", c.primary, c.surfaceContainer, UI),
                Check("the Similar warning's outline (tertiary) on the dialog", c.tertiary, c.surfaceContainerHigh, UI),
            )
        }
    }

    @Test
    fun contrastRatioMatchesWcag() {
        assertEquals(21.0, contrast(Color.Black, Color.White), 0.01)
        assertEquals(1.0, contrast(Color(0xFF376A26), Color(0xFF376A26)), 0.0001)
        // #777777 on white is the classic "just below 4.5:1" grey.
        assertEquals(4.48, contrast(Color(0xFF777777), Color.White), 0.01)
        assertEquals(contrast(Color.White, Color(0xFF13190F)), contrast(Color(0xFF13190F), Color.White), 0.0001)
    }

    private class Check(val what: String, val foreground: Color, val background: Color, val minimum: Double)

    /** Runs [checks] for both schemes and fails once, listing every pair below its minimum. */
    private fun assertAll(checks: (Scheme) -> List<Check>) {
        val failures = schemes.flatMap { scheme ->
            checks(scheme).mapNotNull { check ->
                val ratio = contrast(check.foreground, check.background)
                if (ratio + 1e-9 >= check.minimum) {
                    null
                } else {
                    "${scheme.name}: ${check.what} is ${"%.2f".format(ratio)}:1, needs ${check.minimum}:1"
                }
            }
        }
        assertTrue(failures.joinToString("\n", prefix = "Contrast too low:\n"), failures.isEmpty())
    }

    private companion object {
        /** Normal text and icons. */
        const val TEXT = 4.5

        /** Large text and UI components. */
        const val UI = 3.0

        /** WCAG 2 contrast ratio of two opaque colours: (L1 + 0.05) / (L2 + 0.05), lighter over darker. */
        fun contrast(a: Color, b: Color): Double {
            val la = luminance(a)
            val lb = luminance(b)
            return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
        }

        /** WCAG 2 relative luminance of an opaque sRGB colour. */
        fun luminance(color: Color): Double {
            require(color.alpha == 1f) { "contrast needs opaque colours, got $color" }
            fun channel(c: Float): Double = if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
            return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
        }
    }
}
