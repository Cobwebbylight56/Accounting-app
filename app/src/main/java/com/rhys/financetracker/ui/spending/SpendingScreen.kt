package com.rhys.financetracker.ui.spending

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.importer.PayeeNames
import com.rhys.financetracker.data.local.dao.TransactionFilter
import com.rhys.financetracker.data.local.entity.PersonEntity
import com.rhys.financetracker.data.local.projection.CategoryTotal
import com.rhys.financetracker.data.local.projection.IncomeExpenseTotals
import com.rhys.financetracker.data.repository.PeopleRepository
import com.rhys.financetracker.data.repository.TransactionRepository
import com.rhys.financetracker.domain.model.TransactionType
import com.rhys.financetracker.domain.report.MonthPoint
import com.rhys.financetracker.ui.components.BreakdownView
import com.rhys.financetracker.ui.components.CATEGORY_VIEWS
import com.rhys.financetracker.ui.components.CategoryBreakdown
import com.rhys.financetracker.ui.components.MonthTrend
import com.rhys.financetracker.ui.components.SectionCard
import com.rhys.financetracker.ui.components.TREND_VIEWS
import com.rhys.financetracker.ui.components.ViewSwitchButton
import com.rhys.financetracker.ui.components.chartColorAt
import com.rhys.financetracker.ui.components.rememberCardView
import com.rhys.financetracker.ui.theme.FinanceTheme
import com.rhys.financetracker.ui.transactions.LedgerRequests
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** One payee's spending over the month. */
data class PayeeTotal(val name: String, val totalMinor: Long, val count: Int)

/** One line on a graph: a name, its own colour, and a figure per point. */
data class GraphLine(val name: String, val colorHex: String?, val values: List<Long>)

/** The Spending tab's extra graphs. */
data class SpendingGraphs(
    /** Day labels for the month shown. */
    val days: List<String> = emptyList(),
    /** Running total spent, day by day: this month (to today) and last month. */
    val paceThis: List<Long> = emptyList(),
    val paceLast: List<Long> = emptyList(),
    /** The months the over-time graphs cover. */
    val months: List<YearMonth> = emptyList(),
    /** Each person's spending, month by month, for everyone side by side. */
    val byPerson: List<GraphLine> = emptyList(),
    /** The biggest categories, month by month. */
    val byCategory: List<GraphLine> = emptyList(),
)

data class SpendingState(
    val isLoading: Boolean = true,
    val month: YearMonth = DateUtils.currentYearMonth(),
    val people: List<PersonEntity> = emptyList(),
    val personId: Long? = null,
    val totals: IncomeExpenseTotals = IncomeExpenseTotals.EMPTY,
    val lastMonthSpentMinor: Long = 0L,
    val categories: List<CategoryTotal> = emptyList(),
    /** Last month's spending by category id, for "▲ £20 on last month". */
    val lastMonthByCategory: Map<Long?, Long> = emptyMap(),
    val trend: List<MonthPoint> = emptyList(),
    val payees: List<PayeeTotal> = emptyList(),
) {
    val isCurrentMonth: Boolean get() = month == DateUtils.currentYearMonth()
}

/**
 * Where the money went, as a tab of its own: the month's spending by
 * category, how it has moved month by month, and who it went to — each card
 * drawn however the user likes it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SpendingViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    peopleRepository: PeopleRepository,
    private val ledgerRequests: LedgerRequests,
) : ViewModel() {

    private val month = MutableStateFlow(DateUtils.currentYearMonth())
    private val personId = MutableStateFlow<Long?>(null)

    private val people = peopleRepository.observeActive().map { list -> list.filterNot { it.isShared } }

    private val figures = combine(month, personId) { m, p -> m to p }.flatMapLatest { (m, p) ->
        val ids = p?.let { setOf(it) }
        val range = DateUtils.monthRange(m)
        val last = DateUtils.monthRange(m.minusMonths(1))
        val months = DateUtils.recentMonths(TREND_MONTHS, m)
        combine(
            transactionRepository.observeIncomeExpense(range.start, range.endInclusive, personIds = ids),
            transactionRepository.observeCategoryTotals(TransactionType.EXPENSE, range.start, range.endInclusive, personIds = ids),
            transactionRepository.observeCategoryTotals(TransactionType.EXPENSE, last.start, last.endInclusive, personIds = ids),
            transactionRepository.observeMonthlyTotals(months.first().atDay(1), months.last().atEndOfMonth(), personIds = ids),
            transactionRepository.search(
                TransactionFilter(
                    types = setOf(TransactionType.EXPENSE),
                    dateFrom = range.start,
                    dateTo = range.endInclusive,
                    personIds = ids.orEmpty(),
                ),
            ),
        ) { totals, categories, lastCategories, monthly, payments ->
            val byKey = monthly.associateBy { it.yearMonth }
            SpendingState(
                isLoading = false,
                month = m,
                personId = p,
                totals = totals,
                lastMonthSpentMinor = lastCategories.sumOf { it.totalMinor },
                categories = categories,
                lastMonthByCategory = lastCategories.associate { it.categoryId to it.totalMinor },
                trend = months.map { candidate ->
                    val row = byKey[DateUtils.yearMonthKey(candidate)]
                    MonthPoint(candidate, row?.incomeMinor ?: 0L, row?.expenseMinor ?: 0L)
                },
                // Money to people is a transfer, not spending; Money with
                // people is where it is shown.
                payees = payments
                    .filterNot { it.isPersonTransfer }
                    .groupBy { PayeeNames.of(it.transaction.description).ifBlank { it.transaction.description.trim() } }
                    .map { (name, rows) -> PayeeTotal(name, rows.sumOf { it.transaction.amountMinor }, rows.size) }
                    .sortedByDescending { it.totalMinor }
                    .take(TOP_PAYEES),
            )
        }
    }

    val state: StateFlow<SpendingState> = combine(figures, people) { current, everyone ->
        current.copy(people = everyone)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpendingState())

    /** Running totals by day, this month against last. */
    private val pace = combine(month, personId) { m, p -> m to p }.flatMapLatest { (m, p) ->
        val ids = p?.let { setOf(it) }
        fun spentIn(target: YearMonth) = transactionRepository.search(
            TransactionFilter(
                types = setOf(TransactionType.EXPENSE),
                dateFrom = target.atDay(1),
                dateTo = target.atEndOfMonth(),
                personIds = ids.orEmpty(),
            ),
        )
        combine(spentIn(m), spentIn(m.minusMonths(1))) { now, before ->
            val days = m.lengthOfMonth()
            // This month runs to today; a past month runs to its end.
            val upTo = if (m == DateUtils.currentYearMonth()) DateUtils.today().dayOfMonth else days
            fun running(rows: List<com.rhys.financetracker.data.local.projection.TransactionWithDetails>, length: Int): List<Long> {
                val byDay = LongArray(length)
                rows.filterNot { it.isPersonTransfer }.forEach { row ->
                    val day = row.transaction.date.dayOfMonth
                    if (day in 1..length) byDay[day - 1] += row.transaction.amountMinor
                }
                var total = 0L
                return byDay.map { total += it; total }
            }
            Triple((1..days).map { it.toString() }, running(now, upTo), running(before, minOf(days, m.minusMonths(1).lengthOfMonth())))
        }
    }

    /** Each person's spending over the months, and the biggest categories over them. */
    private val overTime = combine(month, personId, people) { m, p, everyone -> Triple(m, p, everyone) }
        .flatMapLatest { (m, p, everyone) ->
            val months = DateUtils.recentMonths(TREND_MONTHS, m)
            val from = months.first().atDay(1)
            val to = months.last().atEndOfMonth()
            val personFlows = everyone.map { person ->
                transactionRepository.observeMonthlyTotals(from, to, personIds = setOf(person.id)).map { rows ->
                    val byKey = rows.associateBy { it.yearMonth }
                    GraphLine(person.name.substringBefore(' '), person.colorHex, months.map { byKey[DateUtils.yearMonthKey(it)]?.expenseMinor ?: 0L })
                }
            }
            val ids = p?.let { setOf(it) }
            val categoryFlows = months.map { target ->
                transactionRepository.observeCategoryTotals(TransactionType.EXPENSE, target.atDay(1), target.atEndOfMonth(), personIds = ids)
            }
            val byPerson = if (personFlows.isEmpty()) {
                kotlinx.coroutines.flow.flowOf(emptyList<GraphLine>())
            } else {
                combine(personFlows) { it.toList() }
            }
            val byCategory = combine(categoryFlows) { perMonth ->
                val totals = perMonth.flatMap { it.toList() }
                    .groupBy { it.categoryId }
                    .mapValues { (_, rows) -> rows.sumOf { it.totalMinor } }
                val top = totals.entries.sortedByDescending { it.value }.take(TOP_CATEGORY_LINES).map { it.key }
                top.map { id ->
                    val sample = perMonth.flatMap { it.toList() }.first { it.categoryId == id }
                    GraphLine(
                        name = sample.categoryName ?: "Uncategorised",
                        colorHex = sample.categoryColor,
                        values = perMonth.map { month -> month.firstOrNull { it.categoryId == id }?.totalMinor ?: 0L },
                    )
                }
            }
            combine(byPerson, byCategory) { lines, categories -> Triple(months, lines, categories) }
        }

    val graphs: StateFlow<SpendingGraphs> = combine(pace, overTime) { (days, now, before), (months, lines, categories) ->
        SpendingGraphs(
            days = days,
            paceThis = now,
            paceLast = before,
            months = months,
            byPerson = lines,
            byCategory = categories,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpendingGraphs())

    fun previousMonth() {
        month.value = month.value.minusMonths(1)
    }

    fun nextMonth() {
        if (month.value.isBefore(DateUtils.currentYearMonth())) month.value = month.value.plusMonths(1)
    }

    fun showMonth(target: YearMonth) {
        if (!target.isAfter(DateUtils.currentYearMonth())) month.value = target
    }

    fun showPerson(id: Long?) {
        personId.value = id
    }

    /** Leaves the Money tab a filter for this category in this month. */
    fun openCategory(item: CategoryTotal) {
        ledgerRequests.openCategory(item.categoryId, month.value)
    }

    /** Leaves the Money tab a search for this payee in this month. */
    fun openPayee(name: String) {
        ledgerRequests.openSearch(name, month.value)
    }

    private companion object {
        const val TREND_MONTHS = 6
        const val TOP_PAYEES = 10
        const val TOP_CATEGORY_LINES = 5
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpendingScreen(
    onOpenLedger: () -> Unit,
    onOpenSubscriptions: () -> Unit = {},
    viewModel: SpendingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val graphs by viewModel.graphs.collectAsStateWithLifecycle()
    val (whereView, setWhereView) = rememberCardView("spending_where", BreakdownView.CHART)
    val (trendView, setTrendView) = rememberCardView("spending_trend_line", BreakdownView.LINE)
    val (payeeView, setPayeeView) = rememberCardView("spending_payees", BreakdownView.BARS)

    Scaffold(topBar = { TopAppBar(title = { Text("Spending") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = viewModel::previousMonth) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = "Previous month")
                    }
                    Text(
                        text = DateUtils.formatMonth(state.month),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = viewModel::nextMonth, enabled = !state.isCurrentMonth) {
                        Icon(Icons.Default.ChevronRight, contentDescription = "Next month")
                    }
                }
            }

            if (state.people.size > 1) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = state.personId == null,
                            onClick = { viewModel.showPerson(null) },
                            label = { Text("Everyone") },
                        )
                        state.people.forEach { person ->
                            FilterChip(
                                selected = state.personId == person.id,
                                onClick = { viewModel.showPerson(person.id) },
                                label = { Text(person.name.substringBefore(' ')) },
                            )
                        }
                    }
                }
            }

            item { SpentHeadline(state) }

            item {
                SectionCard(
                    title = "Where it went",
                    subtitle = "Tap one to see its payments",
                    action = { ViewSwitchButton(whereView, CATEGORY_VIEWS, setWhereView) },
                ) {
                    CategoryBreakdown(
                        totals = state.categories,
                        view = whereView,
                        previous = state.lastMonthByCategory,
                        onOpen = { item ->
                            viewModel.openCategory(item)
                            onOpenLedger()
                        },
                    )
                }
            }

            item {
                SectionCard(
                    title = "Month by month",
                    subtitle = "Tap a month to look at it",
                    action = { ViewSwitchButton(trendView, TREND_VIEWS, setTrendView) },
                ) {
                    MonthTrend(
                        points = state.trend,
                        view = trendView,
                        selected = state.month,
                        onMonth = viewModel::showMonth,
                    )
                }
            }

            item {
                androidx.compose.material3.OutlinedButton(onClick = onOpenSubscriptions, modifier = Modifier.fillMaxWidth()) {
                    Text("Subscriptions — what's still being paid")
                }
            }

            if (graphs.paceThis.isNotEmpty() || graphs.paceLast.isNotEmpty()) {
                item { PaceCard(graphs = graphs, isCurrentMonth = state.isCurrentMonth) }
            }

            // Everyone on one graph, when the tab is showing everyone.
            if (state.personId == null && graphs.byPerson.size > 1) {
                item { LinesCard("Everyone's spending", "Each person, month by month", "spending_everyone", graphs.months, graphs.byPerson) }
            }

            if (graphs.byCategory.isNotEmpty()) {
                item {
                    LinesCard(
                        "Categories over time",
                        "The ${graphs.byCategory.size} biggest, month by month",
                        "spending_categories_time",
                        graphs.months,
                        graphs.byCategory,
                    )
                }
            }

            item {
                SectionCard(
                    title = "Who it went to",
                    subtitle = "The ${state.payees.size} biggest this month",
                    action = { ViewSwitchButton(payeeView, listOf(BreakdownView.BARS, BreakdownView.LIST), setPayeeView) },
                ) {
                    if (state.payees.isEmpty()) {
                        Text(
                            "Nothing spent in this month.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val biggest = state.payees.maxOfOrNull { it.totalMinor } ?: 1L
                    state.payees.forEachIndexed { index, payee ->
                        PayeeRow(
                            payee = payee,
                            index = index,
                            asBar = payeeView == BreakdownView.BARS,
                            share = payee.totalMinor.toFloat() / biggest,
                            onClick = {
                                viewModel.openPayee(payee.name)
                                onOpenLedger()
                            },
                        )
                    }
                }
            }
        }
    }
}

/** "£1,234.56 spent" with how it compares with last month. */
@Composable
private fun SpentHeadline(state: SpendingState) {
    val spent = state.totals.expenseMinor
    val diff = spent - state.lastMonthSpentMinor
    Column {
        Text(
            text = com.rhys.financetracker.ui.components.animatedMoney(spent),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = buildString {
                append("spent · ${Money.format(state.totals.incomeMinor)} came in")
                if (state.lastMonthSpentMinor > 0L && diff != 0L) {
                    append(" · ")
                    append(Money.format(kotlin.math.abs(diff)))
                    append(if (diff > 0) " more" else " less")
                    append(" than last month")
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (diff > 0 && state.lastMonthSpentMinor > 0L) {
                FinanceTheme.colors.expense
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun PayeeRow(payee: PayeeTotal, index: Int, asBar: Boolean, share: Float, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = payee.name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${payee.count}× ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(Money.format(payee.totalMinor), style = MaterialTheme.typography.bodyMedium)
        }
        if (asBar) {
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(4.dp)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(share.coerceIn(0.02f, 1f))
                        .fillMaxHeight()
                        .background(chartColorAt(index), RoundedCornerShape(4.dp)),
                )
            }
        }
    }
}

/**
 * The month's spending as a running total, day by day, against the same
 * days last month — whether this month is running ahead or behind.
 */
@Composable
private fun PaceCard(graphs: SpendingGraphs, isCurrentMonth: Boolean) {
    val colors = FinanceTheme.colors
    SectionCard(title = "Spending pace", subtitle = "Running total by day, against last month") {
        com.rhys.financetracker.ui.components.TrendLineChart(
            labels = graphs.days,
            series = listOf(
                com.rhys.financetracker.ui.components.LineSeries(
                    if (isCurrentMonth) "This month" else "That month",
                    graphs.paceThis,
                    colors.chartOut,
                ),
                com.rhys.financetracker.ui.components.LineSeries(
                    "Month before",
                    graphs.paceLast,
                    MaterialTheme.colorScheme.outline,
                    dashed = true,
                    marker = com.rhys.financetracker.ui.components.MarkerShape.SQUARE,
                ),
            ),
        )
        val day = graphs.paceThis.size
        val now = graphs.paceThis.lastOrNull()
        val before = graphs.paceLast.getOrNull(day - 1)
        if (now != null && before != null) {
            Spacer(Modifier.height(6.dp))
            val diff = now - before
            Text(
                text = "${Money.format(now)} by day $day — " + when {
                    diff > 0 -> "${Money.format(diff)} more than by the same day the month before."
                    diff < 0 -> "${Money.format(-diff)} less than by the same day the month before."
                    else -> "the same as the month before."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Several lines over the same months, each in its own colour and, every
 * other one, dashed with square dots — so they can be told apart without
 * colour. Also as bars or a list, from the corner button.
 */
@Composable
private fun LinesCard(title: String, subtitle: String, key: String, months: List<YearMonth>, lines: List<GraphLine>) {
    val (view, setView) = rememberCardView(key, BreakdownView.LINE)
    val labels = months.map { DateUtils.monthNameShort(it.monthValue) }
    val series = lines.mapIndexed { index, line ->
        com.rhys.financetracker.ui.components.LineSeries(
            name = line.name,
            values = line.values,
            color = com.rhys.financetracker.ui.components.colorFromHex(line.colorHex, index),
            dashed = index % 2 == 1,
            marker = if (index % 2 == 1) com.rhys.financetracker.ui.components.MarkerShape.SQUARE else com.rhys.financetracker.ui.components.MarkerShape.CIRCLE,
        )
    }
    SectionCard(
        title = title,
        subtitle = subtitle,
        action = { ViewSwitchButton(view, listOf(BreakdownView.LINE, BreakdownView.BARS, BreakdownView.LIST), setView) },
    ) {
        when (view) {
            BreakdownView.BARS -> {
                com.rhys.financetracker.ui.components.GroupedBarChart(
                    groups = labels.indices.map { i ->
                        com.rhys.financetracker.ui.components.BarGroup(
                            label = labels[i],
                            bars = series.map { line ->
                                com.rhys.financetracker.ui.components.ChartEntry(
                                    line.name,
                                    line.values.getOrElse(i) { 0L }.toFloat(),
                                    line.color,
                                    Money.format(line.values.getOrElse(i) { 0L }),
                                )
                            },
                        )
                    },
                )
                Spacer(Modifier.height(6.dp))
                com.rhys.financetracker.ui.components.LineLegend(series)
            }
            BreakdownView.LIST -> {
                months.indices.reversed().forEach { i ->
                    Text(
                        DateUtils.formatMonth(months[i]),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    series.forEach { line ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(line.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(Money.format(line.values.getOrElse(i) { 0L }), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            else -> com.rhys.financetracker.ui.components.TrendLineChart(labels = labels, series = series)
        }
    }
}
