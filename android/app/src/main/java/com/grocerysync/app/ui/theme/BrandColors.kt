package com.grocerysync.app.ui.theme

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
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
 * The item dialog's − / + buttons: the bright turmeric of the quantity chips on To buy (secondaryFixedDim), in both
 * themes, so "turmeric = amounts" holds everywhere. Material's tonal default, the turmeric container, turns brown
 * in the dark theme. Disabled (at 0, or at the largest amount), they keep Material's grey.
 */
@Composable
fun quantityStepperColors(): IconButtonColors {
    val colors = MaterialTheme.colorScheme
    return IconButtonDefaults.filledTonalIconButtonColors(
        containerColor = colors.secondaryFixedDim,
        contentColor = colors.onSecondaryFixed,
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
 * The colours of each shop section ([SectionAccent]): a small mark in front of its name on To buy's section headers,
 * in Add common items and in the Edit dialog's Shop section picker, and the background of To buy's section header.
 * Each colour hints at what the section holds: leaf green for sabzi, milk-packet blue for dairy, haldi for masala,
 * marigold for puja, ... The marks are mid tones in the light theme and soft pastels in the dark one, so a mark
 * stands out (3:1) from every surface it sits on. The headers are the mark's hue at one pale (light theme) or deep
 * (dark theme) tone, so each section keeps its own colour and the header's text keeps the usual onSurface /
 * onSurfaceVariant (ThemeContrastTest checks both). Colour is only an accent: the section's name always shows.
 */
@Immutable
class SectionAccents internal constructor(private val accents: Map<ShopSection, SectionAccent>) {
    init {
        require(accents.keys == ShopSection.entries.toSet()) { "every shop section needs an accent" }
    }

    /** [section]'s mark: the bar or dot in front of its name. */
    operator fun get(section: ShopSection): Color = accents.getValue(section).mark

    /** [section]'s header background on To buy. Opaque, as the header sticks. */
    fun headerColor(section: ShopSection): Color = accents.getValue(section).header
}

/** One shop section's [mark] (bar, dot) and [header] (the background of its header on To buy). */
@Immutable
data class SectionAccent(val mark: Color, val header: Color)

// Picked in OKLCH, one hue per section. The marks: the lightness solved for about 3.6:1 on the light theme's header
// and 6.5:1 on the dark theme's; Tea, sugar & salt is a darker kadak-chai brown, so it stays apart from Atta's
// wheat (ThemeContrastTest keeps every two marks at least 0.05 apart in OKLab). The headers: the mark's hue at
// L 0.95, C 0.03 (light) and L 0.29, C 0.04 (dark); Other, a grey, keeps its low chroma. Then rounded to sRGB.
// Laying a translucent accent over the cream surface instead turned the cool hues grey and the warm ones all the
// same beige.
internal val LightSectionAccents = SectionAccents(
    mapOf(
        ShopSection.SABZI_FRUIT to SectionAccent(Color(0xFF4F8A39), Color(0xFFE5F4E0)), // leaf green
        ShopSection.DAIRY_EGGS_BREAD to SectionAccent(Color(0xFF3B81B8), Color(0xFFDFF1FF)), // milk-packet blue
        ShopSection.MEAT_FISH to SectionAccent(Color(0xFFC65966), Color(0xFFFFE7E8)), // rose
        ShopSection.ATTA_RICE_DAL to SectionAccent(Color(0xFF98764E), Color(0xFFFCECD9)), // wheat
        ShopSection.OIL_GHEE to SectionAccent(Color(0xFF818032), Color(0xFFF0F0DA)), // mustard-oil olive
        ShopSection.MASALA to SectionAccent(Color(0xFFA37401), Color(0xFFF9EDD9)), // haldi
        ShopSection.TEA_SUGAR_SALT to SectionAccent(Color(0xFF9A5238), Color(0xFFFFE8E0)), // kadak chai
        ShopSection.DRY_FRUITS_NUTS to SectionAccent(Color(0xFFAA678E), Color(0xFFFEE7F3)), // kishmish plum
        ShopSection.SNACKS_PACKAGED to SectionAccent(Color(0xFF6B7D9B), Color(0xFFE3EFFF)), // packet slate
        ShopSection.CLEANING_HOUSEHOLD to SectionAccent(Color(0xFF238A85), Color(0xFFD9F5F3)), // cool teal
        ShopSection.PERSONAL_CARE to SectionAccent(Color(0xFF8B6EBC), Color(0xFFF1EAFF)), // soap lavender
        ShopSection.PUJA to SectionAccent(Color(0xFFBC6603), Color(0xFFFFEADB)), // marigold saffron
        ShopSection.OTHER to SectionAccent(Color(0xFF807C73), Color(0xFFF1EEE8)), // warm grey
    ),
)

internal val DarkSectionAccents = SectionAccents(
    mapOf(
        ShopSection.SABZI_FRUIT to SectionAccent(Color(0xFF80C369), Color(0xFF21301C)),
        ShopSection.DAIRY_EGGS_BREAD to SectionAccent(Color(0xFF79B8EC), Color(0xFF192D3E)),
        ShopSection.MEAT_FISH to SectionAccent(Color(0xFFEE9BA0), Color(0xFF3D2325)),
        ShopSection.ATTA_RICE_DAL to SectionAccent(Color(0xFFD0AC82), Color(0xFF382814)),
        ShopSection.OIL_GHEE to SectionAccent(Color(0xFFB7B668), Color(0xFF2D2D14)),
        ShopSection.MASALA to SectionAccent(Color(0xFFE7A503), Color(0xFF362913)),
        ShopSection.TEA_SUGAR_SALT to SectionAccent(Color(0xFFC7856B), Color(0xFF3C251B)),
        ShopSection.DRY_FRUITS_NUTS to SectionAccent(Color(0xFFDBA1C1), Color(0xFF3A2330)),
        ShopSection.SNACKS_PACKAGED to SectionAccent(Color(0xFFA0B3D2), Color(0xFF232C3A)),
        ShopSection.CLEANING_HOUSEHOLD to SectionAccent(Color(0xFF63C1BB), Color(0xFF0F3230)),
        ShopSection.PERSONAL_CARE to SectionAccent(Color(0xFFBDA7E6), Color(0xFF2E273C)),
        ShopSection.PUJA to SectionAccent(Color(0xFFF69C51), Color(0xFF3A2617)),
        ShopSection.OTHER to SectionAccent(Color(0xFFB5B2A7), Color(0xFF2D2B26)),
    ),
)

/** [section]'s accent in the current theme ([SectionAccents]): the colour of its mark. */
@Composable
@ReadOnlyComposable
fun sectionAccent(section: ShopSection): Color = GrocerySyncTheme.extendedColors.sectionAccents[section]

/** The background of [section]'s header on To buy: a pale (light theme) or deep (dark theme) tone of its accent. */
@Composable
@ReadOnlyComposable
fun sectionHeaderColor(section: ShopSection): Color =
    GrocerySyncTheme.extendedColors.sectionAccents.headerColor(section)
