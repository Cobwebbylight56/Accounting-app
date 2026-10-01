package com.rhys.financetracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.DonutLarge
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.local.projection.CategoryTotal
import com.rhys.financetracker.data.prefs.SettingsRepository
import com.rhys.financetracker.domain.report.MonthPoint
import com.rhys.financetracker.ui.theme.FinanceTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The ways a card can draw its figures. */
enum class BreakdownView(val label: String, val icon: ImageVector) {
    CHART("chart", Icons.Outlined.DonutLarge),
    BARS("bars", Icons.Outlined.BarChart),
    TILES("tiles", Icons.Outlined.GridView),
    LIST("list", Icons.AutoMirrored.Outlined.List),
}

/** Holds how each card was last drawn, so the choice survives leaving the screen. */
@HiltViewModel
class CardViewsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val views: StateFlow<Map<String, String>> = settingsRepository.settings
        .map { it.cardViews }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun set(card: String, view: BreakdownView) {
        viewModelScope.launch { settingsRepository.setCardView(card, view.name) }
    }
}

/**
 * How the card [card] is drawn, and a way to change it. The choice is
 * remembered, so a card stays as you left it.
 */
@Composable
fun rememberCardView(
    card: String,
    default: BreakdownView,
    viewModel: CardViewsViewModel = hiltViewModel(),
): Pair<BreakdownView, (BreakdownView) -> Unit> {
    val views by viewModel.views.collectAsStateWithLifecycle()
    val stored = views[card]?.let { name -> BreakdownView.entries.firstOrNull { it.name == name } }
    return (stored ?: default) to { view -> viewModel.set(card, view) }
}

/**
 * The quick button in a card's corner: one tap moves to the next way of
 * showing it. Its icon is the view it will switch to.
 */
@Composable
fun ViewSwitchButton(
    current: BreakdownView,
    options: List<BreakdownView>,
    onChange: (BreakdownView) -> Unit,
) {
    val next = options[(options.indexOf(current).coerceAtLeast(0) + 1) % options.size]
    IconButton(onClick = { onChange(next) }) {
        Icon(next.icon, contentDescription = "Show as ${next.label}")
    }
}

/** The views a category breakdown can take. */
val CATEGORY_VIEWS = listOf(BreakdownView.CHART, BreakdownView.BARS, BreakdownView.TILES, BreakdownView.LIST)

/** The views a month-by-month card can take. */
val TREND_VIEWS = listOf(BreakdownView.BARS, BreakdownView.LIST)

/**
 * Spending by category, drawn as [view].
 *
 * [previous] holds last period's totals by category id, when there is one, so
 * the bars and list can say what went up and what came down.
 */
@Composable
fun CategoryBreakdown(
    totals: List<CategoryTotal>,
    view: BreakdownView,
    onOpen: (CategoryTotal) -> Unit,
    modifier: Modifier = Modifier,
    previous: Map<Long?, Long> = emptyMap(),
    centreLabel: String = "spent",
    maxRows: Int = 12,
) {
    val shown = totals.filter { it.totalMinor > 0L }
    if (shown.isEmpty()) {
        Text(
            text = "Nothing spent in this period.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }
    val total = shown.sumOf { it.totalMinor }
    Column(modifier = modifier) {
        when (view) {
            BreakdownView.CHART -> ChartView(shown, total, centreLabel, onOpen, maxRows)
            BreakdownView.BARS -> shown.take(maxRows).forEachIndexed { index, item ->
                BarRow(item, index, total, previous[item.categoryId], onOpen)
            }
            BreakdownView.TILES -> TilesView(shown.take(maxRows), onOpen)
            BreakdownView.LIST -> shown.take(maxRows).forEachIndexed { index, item ->
                ListRow(item, index, total, previous[item.categoryId], onOpen)
            }
        }
        if (shown.size > maxRows && view != BreakdownView.CHART) {
            Text(
                text = "and ${shown.size - maxRows} more, " +
                    Money.format(shown.drop(maxRows).sumOf { it.totalMinor }),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

private fun nameOf(item: CategoryTotal) = item.categoryName ?: "Uncategorised"

@Composable
private fun ChartView(
    shown: List<CategoryTotal>,
    total: Long,
    centreLabel: String,
    onOpen: (CategoryTotal) -> Unit,
    maxRows: Int,
) {
    var selected by remember(shown) { mutableStateOf<Int?>(null) }
    val entries = shown.mapIndexed { index, item ->
        ChartEntry(
            label = nameOf(item),
            value = item.totalMinor.toFloat(),
            color = colorFromHex(item.categoryColor, index),
            displayValue = Money.format(item.totalMinor),
        )
    }
    DonutChart(
        entries = entries,
        centreLabel = selected?.let { "${shown[it].totalMinor * 100 / total}% of it" } ?: centreLabel,
        centreValue = selected?.let { Money.format(shown[it].totalMinor) } ?: Money.format(total),
        selectedIndex = selected,
        onSliceClick = { index -> selected = if (index == selected) null else index },
    )
    selected?.let { index ->
        val item = shown[index]
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            ColorDot(colorFromHex(item.categoryColor, index))
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${nameOf(item)} · ${item.transactionCount} " +
                    if (item.transactionCount == 1) "payment" else "payments",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onOpen(item) }) { Text("See them") }
        }
    }
    Spacer(Modifier.height(10.dp))
    ChartLegend(
        entries = entries,
        maxItems = maxRows,
        selectedIndex = selected,
        onEntryClick = { index -> selected = if (index == selected) null else index },
    )
}

@Composable
private fun BarRow(item: CategoryTotal, index: Int, total: Long, before: Long?, onOpen: (CategoryTotal) -> Unit) {
    val color = colorFromHex(item.categoryColor, index)
    val share = item.totalMinor.toFloat() / total
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(item) }
            .padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(nameOf(item), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Change(item.totalMinor, before)
            Spacer(Modifier.width(8.dp))
            Text(Money.format(item.totalMinor), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(5.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(share.coerceIn(0.02f, 1f))
                    .fillMaxHeight()
                    .background(color, RoundedCornerShape(5.dp)),
            )
        }
        Text(
            text = "${(share * 100).toInt()}% · ${item.transactionCount} " +
                if (item.transactionCount == 1) "payment" else "payments",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ListRow(item: CategoryTotal, index: Int, total: Long, before: Long?, onOpen: (CategoryTotal) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(item) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColorDot(colorFromHex(item.categoryColor, index))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(nameOf(item), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = "${item.totalMinor * 100 / total}% · ${item.transactionCount} " +
                    if (item.transactionCount == 1) "payment" else "payments",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Change(item.totalMinor, before)
        Spacer(Modifier.width(8.dp))
        Text(Money.format(item.totalMinor), style = MaterialTheme.typography.bodyMedium)
    }
}

/** "▲ £20" or "▼ £15" against last period; nothing when there is no last period. */
@Composable
private fun Change(now: Long, before: Long?) {
    if (before == null) return
    val diff = now - before
    if (diff == 0L) return
    Text(
        text = (if (diff > 0) "▲ " else "▼ ") + Money.format(kotlin.math.abs(diff)),
        style = MaterialTheme.typography.labelSmall,
        // More spent is the thing to notice.
        color = if (diff > 0) FinanceTheme.colors.expense else FinanceTheme.colors.income,
    )
}

@Composable
private fun TilesView(shown: List<CategoryTotal>, onOpen: (CategoryTotal) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        shown.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { item ->
                    StatTile(
                        label = nameOf(item),
                        value = Money.format(item.totalMinor),
                        caption = "${item.transactionCount} " +
                            if (item.transactionCount == 1) "payment" else "payments",
                        modifier = Modifier.weight(1f),
                        onClick = { onOpen(item) },
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * Money in and out month by month, as bars or a list. [selected] is the
 * month to pick out; tapping one calls [onMonth].
 */
@Composable
fun MonthTrend(
    points: List<MonthPoint>,
    view: BreakdownView,
    modifier: Modifier = Modifier,
    selected: java.time.YearMonth? = null,
    onMonth: ((java.time.YearMonth) -> Unit)? = null,
) {
    val colors = FinanceTheme.colors
    Column(modifier = modifier) {
        if (view == BreakdownView.LIST) {
            points.reversed().forEach { point ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (onMonth != null) Modifier.clickable { onMonth(point.yearMonth) } else Modifier)
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = DateUtils.formatMonth(point.yearMonth),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (point.yearMonth == selected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                    Text("+" + Money.format(point.incomeMinor), color = colors.income, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(10.dp))
                    Text("−" + Money.format(point.expenseMinor), color = colors.expense, style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            GroupedBarChart(
                groups = points.map { point ->
                    BarGroup(
                        label = DateUtils.monthNameShort(point.yearMonth.monthValue),
                        bars = listOf(
                            ChartEntry("In", point.incomeMinor.toFloat(), colors.income, Money.format(point.incomeMinor)),
                            ChartEntry("Out", point.expenseMinor.toFloat(), colors.expense, Money.format(point.expenseMinor)),
                        ),
                    )
                },
                selectedIndex = points.indexOfFirst { it.yearMonth == selected }.takeIf { it >= 0 },
                onGroupClick = if (onMonth == null) {
                    null
                } else {
                    { index: Int ->
                        val point = points.getOrNull(index)
                        if (point != null) onMonth(point.yearMonth)
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ColorDot(colors.income)
                    Spacer(Modifier.width(6.dp))
                    Text("Money in", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ColorDot(colors.expense)
                    Spacer(Modifier.width(6.dp))
                    Text("Money out", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
