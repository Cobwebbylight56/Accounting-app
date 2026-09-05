package com.rhys.financetracker.ui.insights

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.domain.insight.Forecast
import com.rhys.financetracker.domain.insight.Insight
import com.rhys.financetracker.domain.insight.InsightKind
import com.rhys.financetracker.domain.insight.InsightSeverity
import com.rhys.financetracker.ui.components.ChartEntry
import com.rhys.financetracker.ui.components.ColorDot
import com.rhys.financetracker.ui.components.EmptyState
import com.rhys.financetracker.ui.components.GroupedBarChart
import com.rhys.financetracker.ui.components.BarGroup
import com.rhys.financetracker.ui.components.LineChart
import com.rhys.financetracker.ui.components.ProgressBarRow
import com.rhys.financetracker.ui.components.SectionCard
import com.rhys.financetracker.ui.components.colorFromHex
import com.rhys.financetracker.ui.theme.FinanceTheme
import kotlin.math.roundToInt

/**
 * Advice and projections.
 *
 * Ordered hardest-first: anything that needs acting on is at the top, then
 * what is worth watching, then what is going well. Every item carries the
 * figure it is talking about, and — where it can be worked out — what acting on
 * it would be worth over a year, because £15 a week does not sound like £780.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen(
    onBack: () -> Unit,
    onOpenCategory: (Long?, String) -> Unit,
    viewModel: InsightsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val report = state.report

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Advice") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::showPreviousMonth) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = "Previous month")
                    }
                    IconButton(
                        onClick = viewModel::showNextMonth,
                        enabled = !state.isCurrentMonth,
                    ) {
                        Icon(Icons.Default.ChevronRight, contentDescription = "Next month")
                    }
                },
            )
        },
    ) { padding ->
        if (state.isLoading && report.insights.isEmpty()) {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            return@Scaffold
        }

        if (report.insights.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Lightbulb,
                title = "Nothing to say yet",
                message = "Once there is a month or two of records, this is where the app " +
                    "tells you where the money is going and what is likely to happen next.",
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text(
                    text = DateUtils.formatMonth(state.month),
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            item { SavingsRateCard(report.savingsRate) }

            if (report.forecast.isReliable) {
                item { ForecastCard(report.forecast) }
            }

            item {
                Text("What stands out", style = MaterialTheme.typography.titleSmall)
            }

            items(report.insights, key = { it.id }) { insight ->
                InsightCard(
                    insight = insight,
                    onClick = insight.categoryId?.let {
                        { onOpenCategory(insight.categoryId, insight.categoryName.orEmpty()) }
                    },
                )
            }

            if (report.trends.isNotEmpty()) {
                item { TrendsCard(report.trends) }
            }
        }
    }
}

/** One piece of advice. */
@Composable
private fun InsightCard(insight: Insight, onClick: (() -> Unit)?) {
    val colors = FinanceTheme.colors
    val accent = when (insight.severity) {
        InsightSeverity.ACT -> colors.negative
        InsightSeverity.WATCH -> colors.warning
        InsightSeverity.GOOD -> colors.positive
        InsightSeverity.INFO -> colors.neutral
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            // A colour bar rather than an icon: severity is the only thing it
            // needs to convey, and it stays legible at any text size.
            Surface(
                color = accent,
                shape = CircleShape,
                modifier = Modifier.width(4.dp).height(48.dp),
            ) {}
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    insight.colorHex?.let {
                        ColorDot(colorFromHex(it), size = 10.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = insight.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = accent,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = insight.message,
                    style = MaterialTheme.typography.bodyMedium,
                )
                insight.annualImpactMinor?.takeIf { it > 0L }?.let { impact ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Worth ${Money.format(impact)} over a year",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** How much of what comes in is being kept. */
@Composable
private fun SavingsRateCard(rate: Float) {
    val colors = FinanceTheme.colors
    val percent = (rate * 100).roundToInt()
    val colour = when {
        rate >= 0.15f -> colors.positive
        rate >= 0.05f -> colors.warning
        else -> colors.negative
    }

    SectionCard(
        title = "Savings rate",
        subtitle = "The share of your income you keep",
    ) {
        ProgressBarRow(
            label = "This month",
            value = "$percent%",
            fraction = rate.coerceIn(0f, 1f),
            color = colour,
            secondary = when {
                rate >= 0.15f -> "Comfortably above the 15% that is usually suggested."
                rate >= 0.05f -> "A useful habit. 15% is the figure usually suggested."
                else -> "Anything put by regularly counts, however small."
            },
        )
    }
}

/** Where the balance is heading. */
@Composable
private fun ForecastCard(forecast: Forecast) {
    val colors = FinanceTheme.colors
    val points = forecast.monthlyProjection.map {
        DateUtils.monthNameShort(it.month.monthValue) to it.closingBalanceMinor
    }

    SectionCard(
        title = "Looking ahead",
        subtitle = "Based on your regular payments and what you normally spend",
    ) {
        LineChart(
            points = points,
            lineColor = if (forecast.firstShortfallMonth != null) colors.negative else colors.positive,
        )
        Spacer(Modifier.height(12.dp))

        forecast.monthlyProjection.take(4).forEach { month ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = DateUtils.formatMonth(month.month) +
                        if (!month.isProjected) " (so far)" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = Money.format(month.closingBalanceMinor),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (month.closingBalanceMinor < 0L) {
                        colors.negative
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "A projection, not a promise: it assumes your regular payments carry on " +
                "and that everything else stays near its recent average.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Which categories have moved most against their own average. */
@Composable
private fun TrendsCard(
    trends: List<com.rhys.financetracker.domain.insight.CategoryTrend>,
) {
    val colors = FinanceTheme.colors

    SectionCard(
        title = "Against your usual",
        subtitle = "This month compared with your own recent average",
    ) {
        trends.take(8).forEach { trend ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ColorDot(colorFromHex(trend.colorHex), size = 10.dp)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(trend.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = if (trend.monthsCompared == 0) {
                            "No history to compare with yet"
                        } else {
                            "Usually ${Money.format(trend.averageMinor)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = Money.format(trend.thisMonthMinor),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    trend.percentChange?.let { change ->
                        Text(
                            text = if (change > 0) "+$change%" else "$change%",
                            style = MaterialTheme.typography.labelMedium,
                            color = when {
                                change > 0 -> colors.negative
                                change < 0 -> colors.positive
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}
