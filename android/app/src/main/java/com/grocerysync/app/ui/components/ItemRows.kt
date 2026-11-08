package com.grocerysync.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import com.grocerysync.app.data.Amount
import com.grocerysync.app.data.GroceryItem
import com.grocerysync.app.data.ItemValue
import com.grocerysync.app.data.Units
import com.grocerysync.app.data.displayText
import com.grocerysync.app.text.Texts
import com.grocerysync.app.ui.SIMPLE_TEXT_SCALE
import com.grocerysync.app.ui.SimpleRowMinHeight
import com.grocerysync.app.ui.scaledBy
import com.grocerysync.app.ui.stepDescription
import com.grocerysync.app.ui.theme.GrocerySyncTheme
import com.grocerysync.app.ui.theme.leafTonalButtonColors
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.roundToInt

/** How long a change made on another phone (or in the sheet) stays highlighted. */
private const val HIGHLIGHT_WINDOW_MS = 2 * 60 * 1_000L

/**
 * Widest a unit gets in a row before it wraps (a custom unit can have 16 characters): the amount and
 * unit stay fully visible, the item name gets the rest of the row.
 */
private val MaxUnitWidth = 96.dp

/** Widest the "To buy" badge gets before its text wraps onto a second line. */
private val MaxBadgeWidth = 168.dp

/** A ticked "Got it" box whose row is still there after this long is cleared again. */
private const val TICK_RESET_MS = 3_000L

/** True for a recent change made by someone else, so the shopper notices what was just added at home. */
internal fun GroceryItem.isRecentChangeByOthers(myDeviceName: String, now: Long): Boolean =
    !pendingSync &&
        updatedAt > 0L &&
        updatedAt >= now - HIGHLIGHT_WINDOW_MS &&
        updatedBy.isNotBlank() &&
        !updatedBy.trim().equals(myDeviceName.trim(), ignoreCase = true)

/** For screen readers: "2 kg", "1.5 L"; without a unit just the amount ("2", not "times 2"). */
internal fun ItemValue.Quantity.spokenText(): String =
    if (unitText.isEmpty()) amount.format() else "${amount.format()} $unitText"

/** Lines the "who - when" line of an All items row may take before the name is shortened. */
private const val MAX_META_LINES = 2

/** Size of the "Not synced yet" icon in front of it. */
private val SyncIconSize = 16.dp

@Composable
private fun rowColors(highlighted: Boolean, needed: Boolean): ListItemColors {
    val colors = MaterialTheme.colorScheme
    return ListItemDefaults.colors(
        containerColor = rowContainerColor(highlighted),
        headlineColor = if (needed) colors.onSurface else colors.onSurfaceVariant,
    )
}

/** A row's background: a pale leaf green while it shows a recent change by someone else. */
@Composable
private fun rowContainerColor(highlighted: Boolean): Color {
    val colors = MaterialTheme.colorScheme
    return if (highlighted) colors.primaryContainer.copy(alpha = 0.45f) else colors.surface
}

/**
 * Row of the "To buy" view: a "Got it" checkbox, the name and, for quantities, a badge with the amount
 * and unit ("2 kg", "1.5 L"; "× 2" without a unit). [now] is read here (not by the list) so the clock
 * only recomposes rows.
 */
@Composable
fun ToBuyRow(
    item: GroceryItem,
    myDeviceName: String,
    now: () -> Long,
    onGotIt: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = Texts.current
    val highlighted = item.isRecentChangeByOthers(myDeviceName, now())
    // Show the tick immediately; the row leaves the view once the repository emits the change.
    var ticked by remember(item.key, item.value) { mutableStateOf(false) }
    // If the change could not be made, the row stays: clear the tick again so it can be retried.
    LaunchedEffect(ticked) {
        if (ticked) {
            delay(TICK_RESET_MS)
            ticked = false
        }
    }

    ListItem(
        modifier = modifier.clickable(onClickLabel = t.list.editItem, onClick = onClick),
        colors = rowColors(highlighted, needed = true),
        leadingContent = {
            Checkbox(
                checked = ticked,
                onCheckedChange = { checked ->
                    if (checked && !ticked) {
                        ticked = true
                        onGotIt()
                    }
                },
                modifier = Modifier.semantics { contentDescription = t.list.gotItDescription(item.name) },
            )
        },
        headlineContent = {
            Text(item.name, style = MaterialTheme.typography.bodyLarge)
        },
        supportingContent = if (highlighted) {
            { UpdatedByText(item.updatedBy) }
        } else {
            null
        },
        trailingContent = when (val value = item.value) {
            is ItemValue.Quantity -> {
                { QuantityBadge(value) }
            }
            is ItemValue.BoolValue -> null
        },
    )
}

/**
 * Row of To buy in Simple mode ([SimpleRowMinHeight] or taller): the name with its amount badge after it (under it
 * when the name needs the whole line), and a big labelled "Got it" button instead of [ToBuyRow]'s small checkbox.
 * Tapping the row still opens the Edit dialog. With very large text the name, the amount and the button's label wrap
 * and the row grows, and the button takes at most [GOT_IT_MAX_WIDTH_FRACTION] of the row, so the name always keeps
 * room. TalkBack reads the row as [ToBuyRow] (name, amount, "double-tap to Edit item") and the button with the name,
 * like the checkbox ("Got it: Rice").
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SimpleToBuyRow(
    item: GroceryItem,
    myDeviceName: String,
    now: () -> Long,
    onGotIt: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = Texts.current
    val highlighted = item.isRecentChangeByOthers(myDeviceName, now())
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(rowContainerColor(highlighted))
            .clickable(onClickLabel = t.list.editItem, onClick = onClick)
            .heightIn(min = SimpleRowMinHeight)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(item.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                (item.value as? ItemValue.Quantity)?.let { QuantityBadge(it, maxLines = Int.MAX_VALUE) }
            }
            if (highlighted) UpdatedByText(item.updatedBy, maxLines = Int.MAX_VALUE)
        }
        Spacer(Modifier.width(12.dp))
        GotItButton(
            itemName = item.name,
            onClick = onGotIt,
            modifier = Modifier.atMostFractionOfWidth(GOT_IT_MAX_WIDTH_FRACTION),
        )
    }
}

/** The most of a [SimpleToBuyRow] its "Got it" button takes: enough for its label at 200% text in Hindi too. */
private const val GOT_IT_MAX_WIDTH_FRACTION = 0.6f

/**
 * Simple mode's "Got it": a big leaf-green tonal button (green is the app's colour for ticks) with a tick and the
 * label, which TalkBack reads with the item's name ([com.grocerysync.app.text.ListTexts.gotItDescription]). The
 * label wraps when the button may not be as wide as it would like, and the button grows.
 */
@Composable
private fun GotItButton(itemName: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = Texts.current
    FilledTonalButton(
        onClick = onClick,
        colors = leafTonalButtonColors(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        modifier = modifier
            .heightIn(min = GotItMinHeight)
            .semantics { contentDescription = t.list.gotItDescription(itemName) },
    ) {
        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(t.list.gotIt, textAlign = TextAlign.Center)
    }
}

/** The least height of Simple mode's "Got it" button, taller than Material's 40dp. */
private val GotItMinHeight = 56.dp

/**
 * Takes at most [fraction] of the width it is offered. In a Row, a child without a weight is measured before the
 * weighted ones and offered the row's width (less the fixed children in front of it), so this keeps it to about
 * that share of the row and leaves the rest to the weighted child.
 */
private fun Modifier.atMostFractionOfWidth(fraction: Float): Modifier = layout { measurable, constraints ->
    val limited = if (constraints.hasBoundedWidth) {
        constraints.copy(maxWidth = (constraints.maxWidth * fraction).roundToInt().coerceAtLeast(constraints.minWidth))
    } else {
        constraints
    }
    val placeable = measurable.measure(limited)
    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
}

/**
 * Row of the "All items" view: name, "who - when" ("Added by Home - 2 hours ago" / "Updated by Home -
 * 5 minutes ago", see [ItemMetaText]), a pending-sync marker and an inline control (Need switch for yes/no
 * items; for quantities a - / + stepper whose step fits the unit, with the amount above its unit in the middle).
 */
@Composable
fun AllItemsRow(
    item: GroceryItem,
    myDeviceName: String,
    now: () -> Long,
    onNeededChange: (Boolean) -> Unit,
    onQuantityStep: (up: Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val nowMillis = now()
    val highlighted = item.isRecentChangeByOthers(myDeviceName, nowMillis)
    val needed = item.value.isNeeded

    ListItem(
        modifier = modifier.clickable(onClickLabel = Texts.current.list.editItem, onClick = onClick),
        colors = rowColors(highlighted, needed),
        headlineContent = {
            Text(item.name, style = MaterialTheme.typography.bodyLarge)
        },
        supportingContent = {
            ItemMeta(item, highlighted, nowMillis)
        },
        trailingContent = {
            when (val value = item.value) {
                is ItemValue.BoolValue -> NeedToggle(
                    needed = value.value,
                    itemName = item.name,
                    onNeededChange = onNeededChange,
                )
                is ItemValue.Quantity -> QuantityStepper(
                    quantity = value,
                    itemName = item.name,
                    onStep = onQuantityStep,
                    onEdit = onClick,
                )
            }
        },
    )
}

/**
 * "Added by Home - 2 hours ago" / "Updated by Home - 2 hours ago" ([ItemMetaText]) after the "Not synced
 * yet" icon, in the colour and weight of a recent change by others when [highlighted]. The text wraps onto a
 * second line rather than losing the time (see [MetaLine]).
 */
@Composable
private fun ItemMeta(item: GroceryItem, highlighted: Boolean, now: Long) {
    val meta = ItemMetaText.of(item, now)
    val baseStyle = LocalTextStyle.current
    val style = baseStyle.merge(
        TextStyle(
            color = if (highlighted) MaterialTheme.colorScheme.primary else baseStyle.color.takeOrElse { LocalContentColor.current },
            fontWeight = if (highlighted) FontWeight.Medium else null,
        ),
    )

    Row(verticalAlignment = Alignment.Top) {
        if (item.pendingSync) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = Texts.current.list.notSyncedYet,
                // Centred on the first line of the text, which may wrap.
                modifier = Modifier
                    .padding(top = ((lineHeight(style) - SyncIconSize) / 2).coerceAtLeast(0.dp))
                    .size(SyncIconSize),
            )
            Spacer(Modifier.width(4.dp))
        }
        if (meta != null) MetaLine(meta, style)
    }
}

/** Height of one line of [style] (its line height, or about 1.2 times the font size without one). */
@Composable
private fun lineHeight(style: TextStyle): Dp = with(LocalDensity.current) {
    when {
        style.lineHeight.isSp -> style.lineHeight.toDp()
        style.fontSize.isSp -> style.fontSize.toDp() * 1.2f
        else -> 20.dp
    }
}

/**
 * Draws [meta] in [style] on at most [MAX_META_LINES] lines without ever cutting the time off. The text wraps
 * (keeping the time in one piece where that costs no extra line); only when it still needs more lines is the
 * name shortened, just as much as needed ("Updated by Grandma's ki... - 2 minutes ago"). With very large text on
 * a narrow screen, where even a one-letter name doesn't fit in two lines, it takes one more line at a time. See
 * [fitMetaLine] and [fittedName]. The number of lines doesn't change as the time moves on ("just now", "2 minutes
 * ago", ...) or the highlight ends: when the widest of those texts needs a second line, the time always starts
 * one ([breaksBeforeTime]), so a row never grows or shrinks (moving its switch and the rows below) by itself.
 *
 * The text is measured with a [TextMeasurer] inside the layout and drawn directly, because the right
 * wording depends on the width. (BoxWithConstraints can't be used: ListItem asks its supporting content for
 * intrinsic sizes, which a SubcomposeLayout can't answer.) TalkBack reads the whole line, full name included.
 */
@Composable
private fun MetaLine(meta: ItemMetaText, style: TextStyle, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val policy = remember(meta, style, measurer) { MetaLinePolicy(meta, style, measurer) }
    Layout(
        modifier = modifier
            .semantics { text = AnnotatedString(meta.text) }
            .drawBehind { policy.shown?.let { drawText(it) } },
        measurePolicy = policy,
    )
}

/** Measures [MetaLine]: picks the wording for the width ([fit]) and keeps the result to draw. */
private class MetaLinePolicy(
    private val meta: ItemMetaText,
    private val style: TextStyle,
    private val measurer: TextMeasurer,
) : MeasurePolicy {

    /** What the last real measurement chose; read when drawing (snapshot state, so a change redraws). */
    var shown: TextLayoutResult? by mutableStateOf(null)
        private set

    /** [style] as a recent change by others shows it (heavier): the widest the row's line gets. */
    private val widestStyle = style.merge(TextStyle(fontWeight = FontWeight.Medium))

    // The last fit and what it was made for: measuring after the intrinsic sizes asks for the same again.
    private var lastWidth = -1
    private var lastDensity = 0f
    private var lastFontScale = 0f
    private var lastDirection: LayoutDirection? = null
    private var lastFit: TextLayoutResult? = null

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val result = fit(constraints.maxWidth, this, layoutDirection)
        shown = result
        return layout(
            width = constraints.constrainWidth(result.size.width),
            height = constraints.constrainHeight(result.size.height),
            // ListItem compares these to tell a one-line supporting text from a longer one (more padding).
            alignmentLines = mapOf(
                FirstBaseline to result.firstBaseline.roundToInt(),
                LastBaseline to result.lastBaseline.roundToInt(),
            ),
        ) {}
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        fit(width, this, layoutDirection).size.height

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        fit(width, this, layoutDirection).size.height

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        ceil(fit(Constraints.Infinity, this, layoutDirection).multiParagraph.minIntrinsicWidth).toInt()

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        fit(Constraints.Infinity, this, layoutDirection).size.width

    /** The layout to show in [width] px (or unbounded): see [MetaLine]. */
    private fun fit(width: Int, density: Density, direction: LayoutDirection): TextLayoutResult {
        lastFit?.let {
            val same = width == lastWidth && density.density == lastDensity && density.fontScale == lastFontScale &&
                direction == lastDirection
            if (same) return it
        }
        val constraints = if (width == Constraints.Infinity) Constraints() else Constraints(maxWidth = width)
        // Whether the time gets a line of its own is decided for the widest text the row can show, so the
        // row keeps its height as the time moves on (see breaksBeforeTime).
        val breakBeforeTime = width != Constraints.Infinity && breaksBeforeTime(meta) { text ->
            measurer.measure(text = text, style = widestStyle, constraints = constraints, layoutDirection = direction, density = density)
                .lineCount <= 1
        }
        val result = fitMetaLine(
            meta = meta,
            maxLines = MAX_META_LINES,
            layout = { text ->
                measurer.measure(text = text, style = style, constraints = constraints, layoutDirection = direction, density = density)
            },
            lineCount = { it.lineCount },
            breaksInsideWord = { it.breaksInsideWord() },
            breakBeforeTime = breakBeforeTime,
        )
        lastWidth = width
        lastDensity = density.density
        lastFontScale = density.fontScale
        lastDirection = direction
        lastFit = result
        return result
    }
}

/** True when a line ends inside a word: not after a space (where wrapping between words breaks a line). */
private fun TextLayoutResult.breaksInsideWord(): Boolean {
    val text = layoutInput.text.text
    return (0 until lineCount - 1).any { line ->
        val end = getLineEnd(line)
        end in 1..text.length && text[end - 1] != ' '
    }
}

/** "Updated by Home" on To buy: on one line, shortened if need be, unless [maxLines] allows more (Simple mode). */
@Composable
private fun UpdatedByText(updatedBy: String, maxLines: Int = 1) {
    Text(
        text = Texts.current.common.updatedBy(updatedBy.trim()),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * To buy's turmeric chip with the amount and unit, at most [MaxBadgeWidth] wide: its text wraps onto a second line
 * if need be, and onto as many as [maxLines] allows (Simple mode, whose large text must never be cut off).
 */
@Composable
private fun QuantityBadge(quantity: ItemValue.Quantity, maxLines: Int = 2) {
    val text = quantity.displayText()
    Surface(
        shape = RoundedCornerShape(percent = 50),
        // Turmeric, the colour of amounts to buy: the fixed role, so the chip is bright haldi in dark mode too.
        color = MaterialTheme.colorScheme.secondaryFixedDim,
        contentColor = MaterialTheme.colorScheme.onSecondaryFixed,
        modifier = Modifier
            .widthIn(max = MaxBadgeWidth)
            .clearAndSetSemantics { contentDescription = Texts.current.list.amountDescription(quantity.spokenText()) },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = maxLines,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun NeedToggle(needed: Boolean, itemName: String, onNeededChange: (Boolean) -> Unit) {
    val t = Texts.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .toggleable(value = needed, role = Role.Switch, onValueChange = onNeededChange)
            .semantics { contentDescription = t.list.needDescription(itemName) }
            .heightIn(min = 48.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = t.list.need,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        // The row handles the toggle so the label is part of the touch target.
        Switch(checked = needed, onCheckedChange = null)
    }
}

@Composable
private fun QuantityStepper(
    quantity: ItemValue.Quantity,
    itemName: String,
    onStep: (up: Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    val amount = quantity.amount
    val unit = quantity.unitText
    val step = stepDescription(unit)
    val t = Texts.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { onStep(false) },
            enabled = amount.isPositive,
            modifier = Modifier.semantics { contentDescription = t.list.stepDown(step, itemName) },
        ) {
            Text("−", style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics { })
        }
        // The amount above its unit: as narrow as the old whole-number stepper for the usual units, so
        // rows keep their room for the name on a 360dp-wide phone.
        Box(
            modifier = Modifier
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClickLabel = t.list.editAmount, onClick = onEdit)
                .clearAndSetSemantics { contentDescription = t.list.amountDescription(quantity.spokenText()) },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 4.dp),
            ) {
                Text(
                    text = amount.format(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (amount.isPositive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
                if (unit.isNotEmpty()) {
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        modifier = Modifier.widthIn(max = MaxUnitWidth),
                    )
                }
            }
        }
        IconButton(
            onClick = { onStep(true) },
            enabled = amount < Amount.MAX,
            modifier = Modifier.semantics { contentDescription = t.list.stepUp(step, itemName) },
        ) {
            Text("+", style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics { })
        }
    }
}

/** [added]: the item hasn't changed since it was added ("Added by"); else it was added a day earlier. */
private fun previewItem(
    name: String,
    value: ItemValue,
    by: String,
    agoMillis: Long,
    pending: Boolean = false,
    added: Boolean = false,
): GroceryItem {
    val updatedAt = System.currentTimeMillis() - agoMillis
    return GroceryItem(
        key = name.lowercase(),
        name = name,
        value = value,
        updatedAt = updatedAt,
        updatedBy = by,
        pendingSync = pending,
        addedAt = if (added) updatedAt else updatedAt - 86_400_000L,
    )
}

@Composable
private fun PreviewRows() {
    val now = System.currentTimeMillis()
    Column {
        ToBuyRow(
            item = previewItem("Milk", ItemValue.Quantity(Amount(1_500L), Units.L), "Home", 30_000L),
            myDeviceName = "Shop",
            now = { now },
            onGotIt = {},
            onClick = {},
        )
        ToBuyRow(
            item = previewItem("Bread", ItemValue.BoolValue(true), "Shop", 600_000L),
            myDeviceName = "Shop",
            now = { now },
            onGotIt = {},
            onClick = {},
        )
        AllItemsRow(
            item = previewItem("Rice", ItemValue.Quantity(Amount.whole(2L), Units.KG), "Home", 90_000L, added = true),
            myDeviceName = "Shop",
            now = { now },
            onNeededChange = {},
            onQuantityStep = {},
            onClick = {},
        )
        AllItemsRow(
            item = previewItem("Basmati rice extra long grain", ItemValue.Quantity(Amount(1_255L), Units.KG), "Home", 2_820_000L),
            myDeviceName = "Shop",
            now = { now },
            onNeededChange = {},
            onQuantityStep = {},
            onClick = {},
        )
        AllItemsRow(
            item = previewItem("Coriander", ItemValue.Quantity.of(2L, "bunches"), "Grandma's kitchen phone", 7_200_000L),
            myDeviceName = "Shop",
            now = { now },
            onNeededChange = {},
            onQuantityStep = {},
            onClick = {},
        )
        AllItemsRow(
            item = previewItem("Eggs", ItemValue.Quantity.of(12L), "Phone of a person with a very long name", 90_000_000L),
            myDeviceName = "Shop",
            now = { now },
            onNeededChange = {},
            onQuantityStep = {},
            onClick = {},
        )
        AllItemsRow(
            item = previewItem("Coffee", ItemValue.BoolValue(false), "Shop", 0L, pending = true, added = true),
            myDeviceName = "Shop",
            now = { now },
            onNeededChange = {},
            onQuantityStep = {},
            onClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ItemRowsPreview() {
    GrocerySyncTheme(dynamicColor = false) { PreviewRows() }
}

/** The narrowest case: a 360dp phone with large text, rows with a quantity stepper. */
@Preview(showBackground = true, widthDp = 360, fontScale = 1.3f)
@Composable
private fun ItemRowsNarrowLargeTextPreview() {
    GrocerySyncTheme(dynamicColor = false) { PreviewRows() }
}

/** Simple mode's To buy rows, with its bigger text, on a 360dp phone at 200% text: names and labels wrap. */
@Preview(showBackground = true, widthDp = 360, fontScale = 2f)
@Composable
private fun SimpleToBuyRowsPreview() {
    val now = System.currentTimeMillis()
    GrocerySyncTheme(dynamicColor = false) {
        MaterialTheme(typography = MaterialTheme.typography.scaledBy(SIMPLE_TEXT_SCALE)) {
            Column {
                SimpleToBuyRow(
                    item = previewItem("Milk", ItemValue.Quantity(Amount(1_500L), Units.L), "Shop", 600_000L),
                    myDeviceName = "Shop",
                    now = { now },
                    onGotIt = {},
                    onClick = {},
                )
                SimpleToBuyRow(
                    item = previewItem("Coriander", ItemValue.Quantity.of(2L, "bunches"), "Shop", 600_000L),
                    myDeviceName = "Shop",
                    now = { now },
                    onGotIt = {},
                    onClick = {},
                )
                // Just added at home: highlighted, with who did it.
                SimpleToBuyRow(
                    item = previewItem("Basmati rice extra long grain", ItemValue.BoolValue(true), "Home", 30_000L),
                    myDeviceName = "Shop",
                    now = { now },
                    onGotIt = {},
                    onClick = {},
                )
            }
        }
    }
}
