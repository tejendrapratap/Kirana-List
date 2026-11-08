package com.grocerysync.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.grocerysync.app.data.ShopSection
import com.grocerysync.app.ui.components.SectionAccentDot
import com.grocerysync.app.ui.theme.GrocerySyncTheme

/**
 * "Shop section" in the Edit dialog: which section of To buy the item is in on this phone, with every section to
 * choose from in walking order. It looks like an outlined field (as the unit picker does) and says "This phone
 * only" under it, as the choice is never synced. Each section shows its accent dot, the colour of its header on
 * To buy ([SectionAccentDot]), and the chosen one a leaf-green tick in the menu. [labelGap]: the space under its
 * label, as the dialog's other labels have.
 */
@Composable
internal fun ShopSectionPicker(section: ShopSection, onSectionChange: (ShopSection) -> Unit, labelGap: Dp) {
    var expanded by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Text(
        text = SHOP_SECTION_LABEL,
        style = MaterialTheme.typography.labelLarge,
        color = colors.onSurfaceVariant,
        modifier = Modifier.padding(bottom = labelGap),
    )
    Box {
        Surface(
            shape = OutlinedTextFieldDefaults.shape,
            border = BorderStroke(1.dp, if (expanded) colors.primary else colors.outline),
            color = Color.Transparent,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = OutlinedTextFieldDefaults.MinHeight)
                .clip(OutlinedTextFieldDefaults.shape)
                .clickable(onClickLabel = "Choose shop section") { expanded = true }
                .semantics {
                    role = Role.DropdownList
                    contentDescription = "$SHOP_SECTION_LABEL: ${section.label}"
                },
        ) {
            Row(
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionAccentDot(section)
                Spacer(Modifier.width(12.dp))
                // Wraps at large text sizes rather than being cut.
                Text(
                    section.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = colors.onSurfaceVariant)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = colors.surfaceContainer,
        ) {
            ShopSection.entries.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.label) },
                    onClick = {
                        expanded = false
                        onSectionChange(choice)
                    },
                    leadingIcon = { SectionAccentDot(choice) },
                    trailingIcon = if (choice == section) {
                        { Icon(Icons.Default.Check, contentDescription = "Selected", tint = colors.primary) }
                    } else {
                        null
                    },
                )
            }
        }
    }
    Text(
        text = "This phone only",
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 4.dp),
    )
}

/** The picker's label, also the start of what TalkBack reads for it ("Shop section: Masala"). */
internal const val SHOP_SECTION_LABEL = "Shop section"

/** The narrowest case: a 360dp phone with large text, in the dark theme. */
@Preview(showBackground = true, widthDp = 360, fontScale = 1.3f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ShopSectionPickerPreview() {
    GrocerySyncTheme(darkTheme = true, dynamicColor = false) {
        Surface {
            Column(Modifier.width(280.dp).padding(16.dp)) {
                ShopSectionPicker(ShopSection.SNACKS_PACKAGED, onSectionChange = {}, labelGap = 8.dp)
            }
        }
    }
}
