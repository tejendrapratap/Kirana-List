package com.grocerysync.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButtonColors
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable

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
