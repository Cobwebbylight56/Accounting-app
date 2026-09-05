package com.rhys.financetracker.ui.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.export.ExportedFile
import com.rhys.financetracker.data.local.projection.CategoryTotal
import com.rhys.financetracker.domain.model.ExportFormat
import com.rhys.financetracker.domain.model.PageOrientation
import com.rhys.financetracker.domain.report.MonthPoint
import com.rhys.financetracker.domain.report.ReportScope
import com.rhys.financetracker.domain.report.ReportSection
import com.rhys.financetracker.domain.report.ReportType
import com.rhys.financetracker.ui.components.BarGroup
import com.rhys.financetracker.ui.components.ChartEntry
import com.rhys.financetracker.ui.components.ChartLegend
import com.rhys.financetracker.ui.components.ColorDot
import com.rhys.financetracker.ui.components.DonutChart
import com.rhys.financetracker.ui.components.DropdownField
import com.rhys.financetracker.ui.components.GroupedBarChart
import com.rhys.financetracker.ui.components.LineChart
import com.rhys.financetracker.ui.components.SectionCard
import com.rhys.financetracker.ui.components.colorFromHex
import com.rhys.financetracker.ui.theme.FinanceTheme
import kotlinx.coroutines.flow.first

/**
 * Reports.
 *
 * The controls sit at the top and the report itself below, so changing the
 * period or the scope re-renders in place rather than sending the user through
 * a wizard each time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    onShareFile: (com.rhys.financetracker.data.export.ExportedFile) -> Unit,
    viewModel: ReportsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showMenu by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(state.exportedFile) {
        state.exportedFile?.let {
            onShareFile(it)
            viewModel.consumeExportedFile()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reports") },
                actions = {
                    IconButton(onClick = viewModel::print) {
                        Icon(Icons.Default.Print, contentDescription = "Print")
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Export as PDF") },
                                onClick = {
                                    viewModel.export(ExportFormat.PDF)
                                    showMenu = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Export as CSV") },
                                onClick = {
                                    viewModel.export(ExportFormat.CSV)
                                    showMenu = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Export as Excel") },
                                onClick = {
                                    viewModel.export(ExportFormat.XLSX)
                                    showMenu = false
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (state.orientation == PageOrientation.PORTRAIT) {
                                            "Print landscape instead"
                                        } else {
                                            "Print portrait instead"
                                        },
                                    )
                                },
                                onClick = {
                                    viewModel.setOrientation(
                                        if (state.orientation == PageOrientation.PORTRAIT) {
                                            PageOrientation.LANDSCAPE
                                        } else {
                                            PageOrientation.PORTRAIT
                                        },
                                    )
                                    showMenu = false
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                DropdownField(
                    label = "Report",
                    options = ReportType.entries,
                    selected = state.reportType,
                    onSelect = viewModel::setType,
                    optionLabel = { it.displayName },
                )
            }

            item {
                Text(
                    text = state.reportType.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item { PeriodChips(state = state, viewModel = viewModel) }

            item { ScopeChips(state = state, onScopeChange = viewModel::setScope) }

            if (state.isBuilding) {
                item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            }

            state.report?.let { report ->
                if (report.charts.categoryTotals.isNotEmpty()) {
                    item { CategoryChartCard(report.charts.categoryTotals) }
                }
                if (report.charts.monthlySeries.isNotEmpty()) {
                    item { MonthlyChartCard(report.charts.monthlySeries) }
                }
                if (report.charts.balanceSeries.size >= 2) {
                    item { BalanceChartCard(report.charts.balanceSeries) }
                }

                items(report.sections) { section -> ReportSectionCard(section) }

                item {
                    Text(
                        text = "Produced ${DateUtils.format(report.generatedOn)} · " +
                            "${report.scope.label} · ${report.period.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Quick period choices, plus the months and years actually worth offering. */
@Composable
private fun PeriodChips(state: ReportsState, viewModel: ReportsViewModel) {
    val months = remember { DateUtils.recentMonths(12) }
    val years = remember {
        val thisYear = DateUtils.today().year
        (thisYear downTo thisYear - 4).toList()
    }
    val isYearly = state.reportType == ReportType.YEARLY_SPENDING ||
        state.reportType == ReportType.INCOME_VS_EXPENSES

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Period", style = MaterialTheme.typography.titleSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isYearly) {
                items(years) { year ->
                    FilterChip(
                        selected = state.period.label == year.toString(),
                        onClick = { viewModel.setYear(year) },
                        label = { Text(year.toString()) },
                    )
                }
            } else {
                items(months.reversed()) { month ->
                    FilterChip(
                        selected = state.period.label == DateUtils.formatMonth(month),
                        onClick = { viewModel.setMonth(month) },
                        label = { Text(DateUtils.formatMonth(month)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ScopeChips(state: ReportsState, onScopeChange: (ReportScope) -> Unit) {
    if (state.people.isEmpty() && state.accounts.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Covering", style = MaterialTheme.typography.titleSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = state.scope == ReportScope.HOUSEHOLD,
                    onClick = { onScopeChange(ReportScope.HOUSEHOLD) },
                    label = { Text("Whole household") },
                )
            }
            items(state.people) { person ->
                FilterChip(
                    selected = state.scope.personId == person.id,
                    onClick = {
                        onScopeChange(ReportScope(personId = person.id, label = person.name))
                    },
                    label = { Text(person.name) },
                )
            }
            items(state.accounts) { account ->
                FilterChip(
                    selected = state.scope.accountId == account.id,
                    onClick = {
                        onScopeChange(ReportScope(accountId = account.id, label = account.name))
                    },
                    label = { Text(account.name) },
                )
            }
        }
    }
}

@Composable
private fun CategoryChartCard(
    totals: List<com.rhys.financetracker.data.local.projection.CategoryTotal>,
) {
    val entries = totals.mapIndexed { index, total ->
        ChartEntry(
            label = total.categoryName ?: "Uncategorised",
            value = total.totalMinor.toFloat(),
            color = colorFromHex(total.categoryColor, index),
            displayValue = Money.format(total.totalMinor),
        )
    }
    SectionCard(title = "By category") {
        DonutChart(
            entries = entries,
            centreLabel = "total",
            centreValue = Money.formatCompact(totals.sumOf { it.totalMinor }),
        )
        Spacer(Modifier.height(14.dp))
        ChartLegend(entries, maxItems = 12)
    }
}

@Composable
private fun MonthlyChartCard(
    series: List<com.rhys.financetracker.domain.report.MonthPoint>,
) {
    val colors = FinanceTheme.colors
    SectionCard(title = "Month by month") {
        GroupedBarChart(
            groups = series.map { point ->
                BarGroup(
                    label = DateUtils.monthNameShort(point.yearMonth.monthValue),
                    bars = listOf(
                        ChartEntry(
                            "In",
                            point.incomeMinor.toFloat(),
                            colors.income,
                            Money.format(point.incomeMinor),
                        ),
                        ChartEntry(
                            "Out",
                            point.expenseMinor.toFloat(),
                            colors.expense,
                            Money.format(point.expenseMinor),
                        ),
                    ),
                )
            },
        )
        Spacer(Modifier.height(10.dp))
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

@Composable
private fun BalanceChartCard(
    series: List<com.rhys.financetracker.domain.report.DatedAmount>,
) {
    SectionCard(
        title = "Running balance",
        subtitle = "From ${DateUtils.formatShort(series.first().date)} to " +
            DateUtils.formatShort(series.last().date),
    ) {
        LineChart(
            points = series.map { DateUtils.formatShort(it.date) to it.amountMinor },
        )
    }
}

/** One section of the report, rendered the same way it prints. */
@Composable
private fun ReportSectionCard(section: ReportSection) {
    SectionCard(title = section.title, subtitle = section.note) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            section.rows.forEach { row ->
                if (row.isTotal) HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = if (row.isSubRow) 16.dp else 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    row.colorHex?.let {
                        ColorDot(colorFromHex(it), size = 10.dp)
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = row.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (row.isTotal) FontWeight.SemiBold else null,
                        )
                        row.secondary?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        text = row.value,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (row.isTotal) FontWeight.SemiBold else null,
                    )
                }
            }
        }
    }
}
