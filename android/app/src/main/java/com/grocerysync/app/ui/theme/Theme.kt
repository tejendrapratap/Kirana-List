package com.grocerysync.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/*
 * Kirana List's "fresh market" palette. Three seed colours, each turned into a tonal palette with Material's
 * HCT colour space (hue, chroma, tone = L*) the way Material builds its tonal palettes, then mapped to the roles:
 *
 *   seed                       palette               roles
 *   curry-leaf green  #3E7D2A  hue 140, chroma 46    primary (P): brand, selection, ticks, switches, links
 *   turmeric (haldi)  #F4B400  hue 84, chroma 60     secondary (S): quantities, the setup banner
 *   marigold          #F28C00  hue 62, chroma 64     tertiary (T): the + button, notices
 *
 * Light neutrals (N, NV) are a warm cream (turmeric hue, chroma 8 / 14); dark ones a deep green charcoal (leaf
 * hue, chroma 10 / 12), not black. Error (E) is Material's standard red. Tones follow Material's schemes (light:
 * P40 / P90 / P10, surfaces N98 and down; dark: P80 / P30 / P90, surfaces N8 and up), with two hand-tuned
 * exceptions: the light turmeric container is S85 (a real haldi yellow rather than a pale butter), and the
 * marigold container is T75 in both themes, so the + button is marigold in light and dark mode alike.
 * The launcher icon and the splash screen use the light primary (res/values/colors.xml, brand_green).
 * ThemeContrastTest checks the text and icon pairs against WCAG (4.5:1; 3:1 for outlines and icons), and
 * docs/images/palette.svg shows the main roles.
 */

internal val LightColorScheme = lightColorScheme(
    // Curry-leaf green.
    primary = Color(0xFF376A26), // P40
    onPrimary = Color(0xFFFFFFFF), // P100
    primaryContainer = Color(0xFFB7F39D), // P90, fresh leaf
    onPrimaryContainer = Color(0xFF012200), // P10
    inversePrimary = Color(0xFF9BD684), // P80
    // Turmeric.
    secondary = Color(0xFF7B5800), // S40
    onSecondary = Color(0xFFFFFFFF), // S100
    secondaryContainer = Color(0xFFFFCD61), // S85, haldi yellow (quantity chips)
    onSecondaryContainer = Color(0xFF261900), // S10
    // Marigold.
    tertiary = Color(0xFF8E4F00), // T40
    onTertiary = Color(0xFFFFFFFF), // T100
    tertiaryContainer = Color(0xFFFFA442), // T75, marigold (+ button)
    onTertiaryContainer = Color(0xFF2D1600), // T10
    // Warm cream neutrals.
    background = Color(0xFFFFF8EE), // N98
    onBackground = Color(0xFF201B10), // N10
    surface = Color(0xFFFFF8EE), // N98, cream
    onSurface = Color(0xFF201B10), // N10
    surfaceVariant = Color(0xFFF3E0C0), // NV90
    onSurfaceVariant = Color(0xFF52452D), // NV30
    surfaceTint = Color(0xFF376A26), // = primary
    inverseSurface = Color(0xFF362F24), // N20 (snackbars)
    inverseOnSurface = Color(0xFFFCEFDD), // N95
    error = Color(0xFFBA1A1A), // E40
    onError = Color(0xFFFFFFFF), // E100
    errorContainer = Color(0xFFFFDAD4), // E90
    onErrorContainer = Color(0xFF410001), // E10
    outline = Color(0xFF85755B), // NV50
    outlineVariant = Color(0xFFD7C4A6), // NV80
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFF8EE), // N98
    surfaceContainer = Color(0xFFF9ECDA), // N94
    surfaceContainerHigh = Color(0xFFF3E7D5), // N92 (dialogs)
    surfaceContainerHighest = Color(0xFFEDE1CF), // N90
    surfaceContainerLow = Color(0xFFFFF2DF), // N96
    surfaceContainerLowest = Color(0xFFFFFFFF), // N100
    surfaceDim = Color(0xFFE5D8C7), // N87
    // Fixed accents: the same in both themes.
    primaryFixed = Color(0xFFB7F39D), // P90
    primaryFixedDim = Color(0xFF9BD684), // P80
    onPrimaryFixed = Color(0xFF012200), // P10
    onPrimaryFixedVariant = Color(0xFF1E510F), // P30
    secondaryFixed = Color(0xFFFFDE9D), // S90
    secondaryFixedDim = Color(0xFFFEBB04), // S80
    onSecondaryFixed = Color(0xFF261900), // S10
    onSecondaryFixedVariant = Color(0xFF5D4200), // S30
    tertiaryFixed = Color(0xFFFFDCBD), // T90
    tertiaryFixedDim = Color(0xFFFFB772), // T80
    onTertiaryFixed = Color(0xFF2D1600), // T10
    onTertiaryFixedVariant = Color(0xFF6C3B00), // T30
)

internal val DarkColorScheme = darkColorScheme(
    // Curry-leaf green.
    primary = Color(0xFF9BD684), // P80
    onPrimary = Color(0xFF043900), // P20
    primaryContainer = Color(0xFF1E510F), // P30, deep leaf
    onPrimaryContainer = Color(0xFFB7F39D), // P90
    inversePrimary = Color(0xFF376A26), // P40
    // Turmeric.
    secondary = Color(0xFFFEBB04), // S80
    onSecondary = Color(0xFF412D00), // S20
    secondaryContainer = Color(0xFF5D4200), // S30, roasted haldi (quantity chips)
    onSecondaryContainer = Color(0xFFFFDE9D), // S90
    // Marigold.
    tertiary = Color(0xFFFFB772), // T80
    onTertiary = Color(0xFF4B2700), // T20
    tertiaryContainer = Color(0xFFFFA442), // T75, marigold (+ button), as in light
    onTertiaryContainer = Color(0xFF2D1600), // T10
    // Green charcoal neutrals.
    background = Color(0xFF13190F), // N8
    onBackground = Color(0xFFDEE5D4), // N90
    surface = Color(0xFF13190F), // N8, green charcoal
    onSurface = Color(0xFFDEE5D4), // N90
    surfaceVariant = Color(0xFF404A3B), // NV30
    onSurfaceVariant = Color(0xFFBFCAB6), // NV80
    surfaceTint = Color(0xFF9BD684), // = primary
    inverseSurface = Color(0xFFDEE5D4), // N90 (snackbars)
    inverseOnSurface = Color(0xFF2B3227), // N20
    error = Color(0xFFFFB4A9), // E80
    onError = Color(0xFF680003), // E20
    errorContainer = Color(0xFF930006), // E30
    onErrorContainer = Color(0xFFFFDAD4), // E90
    outline = Color(0xFF899482), // NV60
    outlineVariant = Color(0xFF404A3B), // NV30
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF384034), // N26
    surfaceContainer = Color(0xFF1F261B), // N14
    surfaceContainerHigh = Color(0xFF293025), // N19 (dialogs)
    surfaceContainerHighest = Color(0xFF343B30), // N24
    surfaceContainerLow = Color(0xFF1B2117), // N12
    surfaceContainerLowest = Color(0xFF0F150C), // N6
    surfaceDim = Color(0xFF13190F), // N8
    // Fixed accents: the same in both themes.
    primaryFixed = Color(0xFFB7F39D), // P90
    primaryFixedDim = Color(0xFF9BD684), // P80
    onPrimaryFixed = Color(0xFF012200), // P10
    onPrimaryFixedVariant = Color(0xFF1E510F), // P30
    secondaryFixed = Color(0xFFFFDE9D), // S90
    secondaryFixedDim = Color(0xFFFEBB04), // S80
    onSecondaryFixed = Color(0xFF261900), // S10
    onSecondaryFixedVariant = Color(0xFF5D4200), // S30
    tertiaryFixed = Color(0xFFFFDCBD), // T90
    tertiaryFixedDim = Color(0xFFFFB772), // T80
    onTertiaryFixed = Color(0xFF2D1600), // T10
    onTertiaryFixedVariant = Color(0xFF6C3B00), // T30
)

/**
 * Colours outside the Material scheme:
 * - [live]: the dot of a working connection ("Live").
 * - [appBar] / [onAppBar]: the app bars, deep green with light text and icons in both themes (light: primary,
 *   dark: primaryContainer), so the status bar icons over them are always light (see MainActivity).
 * - [appBarIcon]: the turmeric cart in front of the app's name.
 */
@Immutable
data class ExtendedColors(
    val live: Color,
    val appBar: Color,
    val onAppBar: Color,
    val appBarIcon: Color,
)

internal val LightExtendedColors = ExtendedColors(
    live = Color(0xFF4E843C), // P50
    appBar = LightColorScheme.primary,
    onAppBar = LightColorScheme.onPrimary,
    appBarIcon = Color(0xFFFEBB04), // S80, turmeric
)

internal val DarkExtendedColors = ExtendedColors(
    live = DarkColorScheme.primary,
    appBar = DarkColorScheme.primaryContainer,
    onAppBar = DarkColorScheme.onPrimaryContainer,
    appBarIcon = Color(0xFFFEBB04), // S80, turmeric
)

private val LocalExtendedColors = staticCompositionLocalOf { LightExtendedColors }

object GrocerySyncTheme {
    val extendedColors: ExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalExtendedColors.current
}

/**
 * The app's theme: the brand palette, light or dark with the system setting. [dynamicColor] (wallpaper colours,
 * Android 12+) is off: the app always shows its own palette. Turning it on swaps the Material scheme only.
 */
@Composable
fun GrocerySyncTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    CompositionLocalProvider(
        LocalExtendedColors provides if (darkTheme) DarkExtendedColors else LightExtendedColors,
    ) {
        MaterialTheme(colorScheme = colorScheme, typography = Typography(), content = content)
    }
}
