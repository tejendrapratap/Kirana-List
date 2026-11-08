package com.grocerysync.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.grocerysync.app.data.ShopSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * WCAG 2 contrast of the brand schemes, light and dark: 4.5:1 for text and icons on the colour they sit on, 3:1
 * for outlines and other non-text parts (and the bag icon in the app bar, which is decoration).
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
        Triple("onSecondaryFixed on secondaryFixedDim (quantity chips, − / +)", onSecondaryFixed, secondaryFixedDim),
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
     * To buy's shop-section headers (1.5): each section's accent mark stands out (3:1) from its header, which is a
     * pale (dark theme: deep) tone of the same hue, and the name (onSurface) and count (onSurfaceVariant) read on it
     * (4.5:1).
     */
    @Test
    fun sectionHeadersReadInEverySection() {
        assertAll { scheme ->
            val c = scheme.colors
            val accents = scheme.brand.sectionAccents
            ShopSection.entries.flatMap { section ->
                val header = accents.headerColor(section)
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

    /**
     * Every section has its own mark in both themes, and no two are near twins: at least [MIN_MARK_DISTANCE] apart
     * in OKLab (the first 1.5 colours for Atta, a sand, and Tea, a peach, were 0.047 and 0.036 apart). A header is a
     * tint, never a block of colour: close to the surface's lightness and soft in chroma, yet in its mark's hue, so a
     * blue section's header doesn't turn grey and the warm ones don't all turn the same beige.
     */
    @Test
    fun sectionMarksAreDistinctAndHeadersTintInTheirOwnHue() {
        val failures = schemes.flatMap { scheme ->
            val accents = scheme.brand.sectionAccents
            val sections = ShopSection.entries
            val tooClose = sections.flatMapIndexed { i, a ->
                sections.drop(i + 1).mapNotNull { b ->
                    val distance = oklabDistance(accents[a], accents[b])
                    "${a.label} / ${b.label} marks are ${"%.3f".format(distance)} apart"
                        .takeIf { distance < MIN_MARK_DISTANCE }
                }
            }
            val surface = oklch(scheme.colors.surface)
            val headers = sections.mapNotNull { section ->
                val header = oklch(accents.headerColor(section))
                val mark = oklch(accents[section])
                val hueOff = abs((header.h - mark.h + 540.0) % 360.0 - 180.0)
                val name = section.label
                when {
                    abs(header.l - surface.l) > 0.1 -> "$name header's lightness ${header.l}, surface ${surface.l}"
                    header.c > 0.045 -> "$name header's chroma ${header.c} is a block of colour"
                    // Other (a grey) has next to no hue to keep.
                    mark.c >= 0.03 && header.c < 0.02 -> "$name header's chroma ${header.c} looks grey"
                    mark.c >= 0.03 && hueOff > 10.0 -> "$name header's hue is ${"%.0f".format(hueOff)} degrees off"
                    else -> null
                }
            }
            (tooClose + headers).map { "${scheme.name}: $it" }
        }
        assertTrue(failures.joinToString("\n", prefix = "Section colours:\n"), failures.isEmpty())
    }

    /**
     * The full-screen dialogs (Add common items, Paste a list) and the empty list: the filled Add / Apply button
     * (primary) on the bottom bar (surfaceContainer), Paste a list's marigold info icon (tertiary) on a skipped line,
     * the selected set chip and the empty list's tonal button (onPrimaryContainer on primaryContainer, checked with
     * every on-colour), the menus' tick (primary).
     */
    @Test
    fun buttonsAndTicksOnTheDialogs() {
        assertAll { scheme ->
            val c = scheme.colors
            listOf(
                Check("Add / Apply button (primary) on the bottom bar", c.primary, c.surfaceContainer, UI),
                Check("a skipped line's info icon (tertiary) on surface", c.tertiary, c.surface, UI),
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

        /** The smallest OKLab distance between two shop sections' marks. */
        const val MIN_MARK_DISTANCE = 0.05

        class Oklch(val l: Double, val c: Double, val h: Double)

        /** OKLab (Björn Ottosson's) of an opaque sRGB colour: lightness L, then a and b. */
        fun oklab(color: Color): DoubleArray {
            fun linear(c: Float): Double = if (c <= 0.04045f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
            val r = linear(color.red)
            val g = linear(color.green)
            val b = linear(color.blue)
            val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
            val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
            val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
            return doubleArrayOf(
                0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
            )
        }

        fun oklch(color: Color): Oklch {
            val (l, a, b) = oklab(color)
            return Oklch(l, hypot(a, b), (Math.toDegrees(atan2(b, a)) + 360.0) % 360.0)
        }

        fun oklabDistance(x: Color, y: Color): Double {
            val p = oklab(x)
            val q = oklab(y)
            return sqrt((p[0] - q[0]).pow(2) + (p[1] - q[1]).pow(2) + (p[2] - q[2]).pow(2))
        }

        /** WCAG 2 relative luminance of an opaque sRGB colour. */
        fun luminance(color: Color): Double {
            require(color.alpha == 1f) { "contrast needs opaque colours, got $color" }
            fun channel(c: Float): Double = if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
            return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
        }
    }
}
