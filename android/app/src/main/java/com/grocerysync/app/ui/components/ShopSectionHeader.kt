package com.grocerysync.app.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.grocerysync.app.data.ShopSection
import com.grocerysync.app.ui.sectionHeaderDescription
import com.grocerysync.app.ui.theme.GrocerySyncTheme

/**
 * The header of a shop section on To buy: its name and how many items in it are to buy. Opaque, as it sticks to
 * the top while its rows scroll under it. A long name wraps rather than hiding the count. TalkBack reads it as one
 * heading ("Masala, 3 items"), so moving by headings jumps from section to section.
 */
@Composable
fun ShopSectionHeader(section: ShopSection, count: Int, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                heading()
                contentDescription = sectionHeaderDescription(section, count)
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = section.label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PreviewHeaders() {
    Column {
        ShopSectionHeader(ShopSection.SABZI_FRUIT, 4)
        ShopSectionHeader(ShopSection.SNACKS_PACKAGED, 12)
        ShopSectionHeader(ShopSection.OTHER, 1)
    }
}

/** The narrowest case: a 360dp phone with large text, in the dark theme. */
@Preview(showBackground = true, widthDp = 360, fontScale = 1.3f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ShopSectionHeaderPreview() {
    GrocerySyncTheme(darkTheme = true, dynamicColor = false) { PreviewHeaders() }
}
