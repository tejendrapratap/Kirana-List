package com.grocerysync.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.grocerysync.app.R
import com.grocerysync.app.ui.theme.GrocerySyncTheme

/**
 * The list's app bar title: a turmeric cart, then the app's name (R.string.app_name, so a rename is one line in
 * strings.xml; the qa build says "Kirana QA"). The cart is decoration only.
 */
@Composable
fun AppBarTitle(modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.ShoppingCart,
            contentDescription = null,
            tint = GrocerySyncTheme.extendedColors.appBarIcon,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.app_name), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
