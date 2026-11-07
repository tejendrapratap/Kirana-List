package com.grocerysync.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
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

/** Brand seed colour (also the launcher icon background). */
val GroceryGreen = Color(0xFF1E8E3E)

// Tonal schemes generated from the green seed (#1E8E3E), used when dynamic colour is unavailable.
private val LightColors = lightColorScheme(
    primary = Color(0xFF006D32),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9DF6A8),
    onPrimaryContainer = Color(0xFF00210A),
    inversePrimary = Color(0xFF81D98E),
    secondary = Color(0xFF4F6352),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD2E8D3),
    onSecondaryContainer = Color(0xFF0D1F12),
    tertiary = Color(0xFF3A646F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBEEAF6),
    onTertiaryContainer = Color(0xFF001F26),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF6FBF3),
    onBackground = Color(0xFF181D18),
    surface = Color(0xFFF6FBF3),
    onSurface = Color(0xFF181D18),
    surfaceVariant = Color(0xFFDDE5DA),
    onSurfaceVariant = Color(0xFF414941),
    surfaceTint = Color(0xFF006D32),
    inverseSurface = Color(0xFF2D322D),
    inverseOnSurface = Color(0xFFEEF2EA),
    outline = Color(0xFF717970),
    outlineVariant = Color(0xFFC1C9BF),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF6FBF3),
    surfaceDim = Color(0xFFD7DBD4),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F5ED),
    surfaceContainer = Color(0xFFEAEFE7),
    surfaceContainerHigh = Color(0xFFE5E9E2),
    surfaceContainerHighest = Color(0xFFDFE4DC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF81D98E),
    onPrimary = Color(0xFF003915),
    primaryContainer = Color(0xFF005322),
    onPrimaryContainer = Color(0xFF9DF6A8),
    inversePrimary = Color(0xFF006D32),
    secondary = Color(0xFFB6CCB8),
    onSecondary = Color(0xFF223526),
    secondaryContainer = Color(0xFF384B3B),
    onSecondaryContainer = Color(0xFFD2E8D3),
    tertiary = Color(0xFFA2CEDA),
    onTertiary = Color(0xFF023640),
    tertiaryContainer = Color(0xFF214C57),
    onTertiaryContainer = Color(0xFFBEEAF6),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF101510),
    onBackground = Color(0xFFDFE4DC),
    surface = Color(0xFF101510),
    onSurface = Color(0xFFDFE4DC),
    surfaceVariant = Color(0xFF414941),
    onSurfaceVariant = Color(0xFFC1C9BF),
    surfaceTint = Color(0xFF81D98E),
    inverseSurface = Color(0xFFDFE4DC),
    inverseOnSurface = Color(0xFF2D322D),
    outline = Color(0xFF8B938A),
    outlineVariant = Color(0xFF414941),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF353A35),
    surfaceDim = Color(0xFF101510),
    surfaceContainerLowest = Color(0xFF0B0F0B),
    surfaceContainerLow = Color(0xFF181D18),
    surfaceContainer = Color(0xFF1C211C),
    surfaceContainerHigh = Color(0xFF262B26),
    surfaceContainerHighest = Color(0xFF313631),
)

/** Colours outside the Material scheme. [live] marks a working connection, independent of dynamic colour. */
@Immutable
data class ExtendedColors(val live: Color)

private val LightExtendedColors = ExtendedColors(live = GroceryGreen)
private val DarkExtendedColors = ExtendedColors(live = Color(0xFF6DD58C))

private val LocalExtendedColors = staticCompositionLocalOf { LightExtendedColors }

object GrocerySyncTheme {
    val extendedColors: ExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalExtendedColors.current
}

@Composable
fun GrocerySyncTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(
        LocalExtendedColors provides if (darkTheme) DarkExtendedColors else LightExtendedColors,
    ) {
        MaterialTheme(colorScheme = colorScheme, typography = Typography(), content = content)
    }
}
