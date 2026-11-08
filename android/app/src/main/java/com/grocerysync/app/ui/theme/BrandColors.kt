package com.grocerysync.app.ui.theme

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButtonColors
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.grocerysync.app.data.ShopSection

/**
 * Every app bar of the app (the list, Settings, full-screen dialogs): deep green with light title and icons in
 * both themes ([ExtendedColors.appBar]). It draws behind the status bar, whose icons MainActivity keeps light.
 */
@Composable
fun appBarColors(): TopAppBarColors {
    val brand = GrocerySyncTheme.extendedColors
    return TopAppBarDefaults.topAppBarColors(
        containerColor = brand.appBar,
        scrolledContainerColor = brand.appBar,
        navigationIconContentColor = brand.onAppBar,
        titleContentColor = brand.onAppBar,
        actionIconContentColor = brand.onAppBar,
        subtitleContentColor = brand.onAppBar,
    )
}

/**
 * Segmented buttons (To buy / All items, and the item dialog's choices): the selected segment in leaf green
 * (primaryContainer). Material's default would be the secondary container, which is turmeric here.
 */
@Composable
fun selectorColors(): SegmentedButtonColors {
    val colors = MaterialTheme.colorScheme
    return SegmentedButtonDefaults.colors(
        activeContainerColor = colors.primaryContainer,
        activeContentColor = colors.onPrimaryContainer,
    )
}

/**
 * Filter chips that pick one of a few choices (Add common items' sets): the selected chip in leaf green like a
 * selected segment ([selectorColors]), not Material's default secondary container (turmeric here).
 */
@Composable
fun choiceChipColors(): SelectableChipColors {
    val colors = MaterialTheme.colorScheme
    return FilterChipDefaults.filterChipColors(
        selectedContainerColor = colors.primaryContainer,
        selectedLabelColor = colors.onPrimaryContainer,
        selectedLeadingIconColor = colors.onPrimaryContainer,
        selectedTrailingIconColor = colors.onPrimaryContainer,
    )
}

/**
 * A tonal button that leads somewhere new (the empty list's "Add common items"): leaf green, like the selected
 * segments, rather than Material's tonal default (the turmeric container, which is the setup banner's colour).
 */
@Composable
fun leafTonalButtonColors(): ButtonColors {
    val colors = MaterialTheme.colorScheme
    return ButtonDefaults.filledTonalButtonColors(
        containerColor = colors.primaryContainer,
        contentColor = colors.onPrimaryContainer,
    )
}

/**
 * An outlined field with a warning that doesn't block saving (the item dialog's "Similar: Dahi is already on the
 * list"): outline, label and supporting text in marigold (tertiary, the colour of the app's notices), as an error
 * colours them red. The cursor and the typed text stay as usual.
 */
@Composable
fun warningFieldColors(): TextFieldColors {
    val warning = MaterialTheme.colorScheme.tertiary
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = warning,
        unfocusedBorderColor = warning,
        focusedLabelColor = warning,
        unfocusedLabelColor = warning,
        focusedSupportingTextColor = warning,
        unfocusedSupportingTextColor = warning,
    )
}

/**
 * The accent of each shop section:a small mark in front of its name on To buy's section headers, in Add common
 * items and in the Edit dialog's Shop section picker, and (lightly, [headerTint]) the background of To buy's
 * section header. Each colour hints at what the section holds: leaf green for sabzi, milk-packet blue for dairy,
 * haldi for masala, marigold for puja, ... They are mid tones in the light theme and soft pastels in the dark one,
 * so a mark stands out (3:1) from every surface it sits on, while the header's text keeps the usual onSurface /
 * onSurfaceVariant (ThemeContrastTest checks both). Colour is only an accent: the section's name always shows.
 */
@Immutable
class SectionAccents internal constructor(
    private val colors: Map<ShopSection, Color>,
    /** How much of the accent tints a section header's background (over the surface). */
    val headerTint: Float,
) {
    init {
        require(colors.keys == ShopSection.entries.toSet()) { "every shop section needs an accent" }
    }

    operator fun get(section: ShopSection): Color = colors.getValue(section)

    /** [section]'s header background: its accent at [headerTint] over [surface]. Opaque, as the header sticks. */
    fun headerColor(section: ShopSection, surface: Color): Color =
        get(section).copy(alpha = headerTint).compositeOver(surface)
}

// Picked in OKLCH (one hue per section; the lightness solved for about 3.6:1 on the light theme's tinted header
// and 6.5:1 on the dark theme's), then rounded to sRGB.
internal val LightSectionAccents = SectionAccents(
    colors = mapOf(
        ShopSection.SABZI_FRUIT to Color(0xFF4F8A39), // leaf green
        ShopSection.DAIRY_EGGS_BREAD to Color(0xFF3B81B8), // milk-packet blue
        ShopSection.MEAT_FISH to Color(0xFFC65966), // rose
        ShopSection.ATTA_RICE_DAL to Color(0xFF98764E), // wheat
        ShopSection.OIL_GHEE to Color(0xFF818032), // mustard-oil olive
        ShopSection.MASALA to Color(0xFFA37401), // haldi
        ShopSection.TEA_SUGAR_SALT to Color(0xFFB06B4B), // chai
        ShopSection.DRY_FRUITS_NUTS to Color(0xFFAA678E), // kishmish plum
        ShopSection.SNACKS_PACKAGED to Color(0xFF6B7D9B), // packet slate
        ShopSection.CLEANING_HOUSEHOLD to Color(0xFF238A85), // cool teal
        ShopSection.PERSONAL_CARE to Color(0xFF8B6EBC), // soap lavender
        ShopSection.PUJA to Color(0xFFBC6603), // marigold saffron
        ShopSection.OTHER to Color(0xFF807C73), // warm grey
    ),
    headerTint = 0.08f,
)

internal val DarkSectionAccents = SectionAccents(
    colors = mapOf(
        ShopSection.SABZI_FRUIT to Color(0xFF80C369),
        ShopSection.DAIRY_EGGS_BREAD to Color(0xFF79B8EC),
        ShopSection.MEAT_FISH to Color(0xFFEE9BA0),
        ShopSection.ATTA_RICE_DAL to Color(0xFFD0AC82),
        ShopSection.OIL_GHEE to Color(0xFFB7B668),
        ShopSection.MASALA to Color(0xFFE7A503),
        ShopSection.TEA_SUGAR_SALT to Color(0xFFE0A48A),
        ShopSection.DRY_FRUITS_NUTS to Color(0xFFDBA1C1),
        ShopSection.SNACKS_PACKAGED to Color(0xFFA0B3D2),
        ShopSection.CLEANING_HOUSEHOLD to Color(0xFF63C1BB),
        ShopSection.PERSONAL_CARE to Color(0xFFBDA7E6),
        ShopSection.PUJA to Color(0xFFF69C51),
        ShopSection.OTHER to Color(0xFFB5B2A7),
    ),
    headerTint = 0.14f,
)

/** [section]'s accent in the current theme ([SectionAccents]): the colour of its mark. */
@Composable
@ReadOnlyComposable
fun sectionAccent(section: ShopSection): Color = GrocerySyncTheme.extendedColors.sectionAccents[section]

/** The background of [section]'s header on To buy: the surface, lightly tinted with its accent. */
@Composable
@ReadOnlyComposable
fun sectionHeaderColor(section: ShopSection): Color =
    GrocerySyncTheme.extendedColors.sectionAccents.headerColor(section, MaterialTheme.colorScheme.surface)
