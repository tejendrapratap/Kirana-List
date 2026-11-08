package com.grocerysync.app.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.grocerysync.app.text.Texts
import com.grocerysync.app.ui.SIMPLE_TEXT_SCALE
import com.grocerysync.app.ui.scaledBy
import com.grocerysync.app.ui.theme.GrocerySyncTheme

// The parts of the list screen that only Simple mode has (the rules are in ui/SimpleMode.kt). Its To buy row is
// SimpleToBuyRow, with the other rows in ItemRows.kt.

/** The least height of Simple mode's Add item button: bigger than Material's 56dp. */
private val SimpleAddButtonMinHeight = 64.dp

/**
 * Simple mode's Add button: one big extended button with a + and "Add item", in the marigold of the usual small round
 * + it replaces, opening the same blank Add dialog. With very large text on a narrow phone its label wraps and the
 * button grows, rather than being cut off. TalkBack reads "Add item" (the icon's description: Material's extended
 * button hides its label from TalkBack and takes the icon's), as for the round +.
 */
@Composable
fun SimpleAddItemButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = Texts.current
    ExtendedFloatingActionButton(
        text = { Text(t.common.addItem, style = MaterialTheme.typography.titleMedium) },
        icon = { Icon(Icons.Default.Add, contentDescription = t.common.addItem) },
        onClick = onClick,
        modifier = modifier.heightIn(min = SimpleAddButtonMinHeight),
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    )
}

/**
 * A snackbar of the list screen in Simple mode: the message on top, in full, and its buttons under it (Material's
 * layout for a long action): the action (Undo) and the X that closes it ([SnackbarVisuals.withDismissAction]), which
 * TalkBack reads as "Close" in the language shown. When the two don't fit side by side (Hindi's Undo,
 * "पहले जैसा करें", at very large text) the X goes onto a line of its own, and a label too long even for that wraps,
 * so neither is ever cut off. Otherwise it looks like Material's snackbar, which the usual screen shows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SimpleModeSnackbar(data: SnackbarData, modifier: Modifier = Modifier) {
    val t = Texts.current
    val visuals = data.visuals
    val actionLabel = visuals.actionLabel
    val buttons: (@Composable () -> Unit)? = if (actionLabel != null || visuals.withDismissAction) {
        {
            FlowRow(horizontalArrangement = Arrangement.End, itemVerticalAlignment = Alignment.CenterVertically) {
                if (actionLabel != null) {
                    TextButton(
                        onClick = data::performAction,
                        colors = ButtonDefaults.textButtonColors(contentColor = SnackbarDefaults.actionColor),
                    ) {
                        Text(actionLabel)
                    }
                }
                if (visuals.withDismissAction) {
                    IconButton(onClick = data::dismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = t.common.close,
                            tint = SnackbarDefaults.dismissActionContentColor,
                        )
                    }
                }
            }
        }
    } else {
        null
    }
    // The same 12dp around it as Material's snackbar.
    Snackbar(modifier = modifier.padding(12.dp), action = buttons, actionOnNewLine = true) {
        Text(visuals.message)
    }
}

/** A snackbar's data for the previews; its buttons do nothing. */
private class PreviewSnackbarData(text: String, action: String?) : SnackbarData {
    override val visuals = object : SnackbarVisuals {
        override val message = text
        override val actionLabel = action
        override val withDismissAction = true
        override val duration = SnackbarDuration.Indefinite
    }

    override fun performAction() = Unit
    override fun dismiss() = Unit
}

/** The Undo snackbar and the Add item button with Simple mode's bigger text, on a 360dp phone at 200% text. */
@Preview(showBackground = true, widthDp = 360, fontScale = 2f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SimpleModePartsPreview() {
    GrocerySyncTheme(darkTheme = true, dynamicColor = false) {
        MaterialTheme(typography = MaterialTheme.typography.scaledBy(SIMPLE_TEXT_SCALE)) {
            Column(horizontalAlignment = Alignment.End) {
                SimpleModeSnackbar(PreviewSnackbarData("Got Basmati rice", "Undo"))
                SimpleModeSnackbar(PreviewSnackbarData("Dahi ले लिया", "पहले जैसा करें"))
                SimpleAddItemButton(onClick = {}, modifier = Modifier.padding(16.dp))
            }
        }
    }
}
