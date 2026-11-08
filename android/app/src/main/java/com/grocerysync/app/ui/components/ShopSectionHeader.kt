package com.grocerysync.app.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.grocerysync.app.text.Texts
import com.grocerysync.app.ui.sectionHeaderDescription
import com.grocerysync.app.ui.theme.GrocerySyncTheme
import com.grocerysync.app.ui.theme.sectionAccent
import com.grocerysync.app.ui.theme.sectionHeaderColor

/**
 * The header of a shop section on To buy: the section's accent mark ([SectionAccentBar]), its name (in the language
 * shown) and how many items in it are to buy, on a pale (dark theme: deep) tone of the accent ([sectionHeaderColor]).
 * Opaque, as it sticks to the top while its rows scroll under it. A long name wraps rather than hiding the count.
 * TalkBack reads it as one heading ("Masala, 3 items"), so moving by headings jumps from section to section.
 */
@Composable
fun ShopSectionHeader(section: ShopSection, count: Int, modifier: Modifier = Modifier) {
    Surface(
        color = sectionHeaderColor(section),
        contentColor = MaterialTheme.colorScheme.onSurface,
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
            SectionAccentBar(section)
            Spacer(Modifier.width(12.dp))
            Text(
                text = Texts.current.common.sectionName(section),
                style = MaterialTheme.typography.titleSmall,
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

/** A shop section's accent as a short rounded bar in front of its name (section headers). Decoration only. */
@Composable
fun SectionAccentBar(section: ShopSection, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(width = 4.dp, height = 18.dp)
            .background(sectionAccent(section), RoundedCornerShape(2.dp)),
    )
}

/** A shop section's accent as a dot (the Edit dialog's Shop section picker and its menu). Decoration only. */
@Composable
fun SectionAccentDot(section: ShopSection, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(12.dp)
            .background(sectionAccent(section), CircleShape),
    )
}

@Composable
private fun PreviewHeaders() {
    Column {
        ShopSection.entries.forEachIndexed { index, section -> ShopSectionHeader(section, index + 1) }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun ShopSectionHeaderLightPreview() {
    GrocerySyncTheme(darkTheme = false, dynamicColor = false) { PreviewHeaders() }
}

/** The narrowest case: a 360dp phone with large text, in the dark theme. */
@Preview(showBackground = true, widthDp = 360, fontScale = 1.3f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ShopSectionHeaderPreview() {
    GrocerySyncTheme(darkTheme = true, dynamicColor = false) { PreviewHeaders() }
}
