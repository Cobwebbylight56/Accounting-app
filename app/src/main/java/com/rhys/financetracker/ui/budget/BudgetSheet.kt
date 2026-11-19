package com.rhys.financetracker.ui.budget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.data.repository.BudgetLineView
import com.rhys.financetracker.data.repository.BudgetOverview
import com.rhys.financetracker.domain.budget.Grade
import com.rhys.financetracker.domain.budget.LineGoal
import com.rhys.financetracker.domain.budget.LineStatus
import com.rhys.financetracker.domain.budget.MonthScore
import com.rhys.financetracker.ui.components.BarGroup
import com.rhys.financetracker.ui.components.ChartEntry
import com.rhys.financetracker.ui.components.GroupedBarChart
import com.rhys.financetracker.ui.components.SectionCard
import com.rhys.financetracker.ui.theme.FinanceTheme
import java.time.format.TextStyle
import java.util.Locale

/**
 * The budget laid out like the household spreadsheet: a row for each line,
 * a column for each person and one for the total. Each cell is what was
 * spent, with the budget under it, coloured by how it's going. A line's
 * name opens it; a person's cell changes their part.
 */
@Composable
internal fun BudgetSheet(
    overview: BudgetOverview,
    onLine: (BudgetLineView) -> Unit,
    onCell: (BudgetLineView, Int) -> Unit,
) {
    val columns = overview.columns
    val groups = listOf(
        "Limits" to overview.lines.filter { !it.line.isAim && !it.line.isPutAside },
        "Aims" to overview.lines.filter { it.line.isAim && !it.line.isPutAside },
        "Putting aside" to overview.lines.filter { it.line.isPutAside },
    ).filter { it.second.isNotEmpty() }

    SectionCard(
        title = "The sheet",
        subtitle = if (columns.isEmpty()) {
            "Spent, with the budget under it"
        } else if (overview.splitJoint) {
            "Spent, with the budget under it. Joint spending is shared between you."
        } else {
            "Spent, with the budget under it."
        },
    ) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            // Headings: the people, then everyone together.
            Row(verticalAlignment = Alignment.CenterVertically) {
                HeaderCell("", NAME_WIDTH, TextAlign.Start)
                columns.forEach { HeaderCell(it.name, PERSON_WIDTH) }
                HeaderCell(if (columns.isEmpty()) "Amount" else "Total", TOTAL_WIDTH)
            }
            HorizontalDivider(color = FinanceTheme.colors.cardEdge)
            groups.forEach { (title, lines) ->
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                )
                lines.forEach { view ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Column(Modifier.width(NAME_WIDTH).clickable { onLine(view) }.padding(end = 6.dp)) {
                            Text(
                                view.line.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (view.countsLabel == null) {
                                Text("Choose what counts", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        view.cells.forEachIndexed { index, cell ->
                            FigureCell(
                                spent = cell.actualMinor,
                                budget = cell.budgetMinor,
                                status = cell.status,
                                width = PERSON_WIDTH,
                                modifier = Modifier.clickable { onCell(view, index) },
                            )
                        }
                        FigureCell(
                            spent = view.result.actualMinor,
                            budget = view.result.budgetMinor,
                            status = view.result.status,
                            width = TOTAL_WIDTH,
                            bold = true,
                            modifier = Modifier.clickable { onLine(view) },
                        )
                    }
                }
            }
            HorizontalDivider(color = FinanceTheme.colors.cardEdge, modifier = Modifier.padding(top = 6.dp))
            // Spending added up, as the sheet's "spent" row.
            val spending = overview.lines.filter { it.result.plan.goal == LineGoal.SPEND_AT_MOST }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                Text("Spent", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(NAME_WIDTH))
                columns.indices.forEach { c ->
                    val spent = spending.sumOf { it.cells.getOrNull(c)?.actualMinor ?: 0L }
                    val budget = spending.sumOf { it.cells.getOrNull(c)?.budgetMinor ?: 0L }
                    FigureCell(spent, budget, if (spent <= budget) LineStatus.UNDER else LineStatus.OVER, PERSON_WIDTH, bold = true)
                }
                FigureCell(overview.score.spentMinor, overview.score.budgetMinor, if (overview.score.leftMinor >= 0) LineStatus.UNDER else LineStatus.OVER, TOTAL_WIDTH, bold = true)
            }
            // How well each of you kept to it.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                Text("Score", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(NAME_WIDTH))
                overview.columnScores.forEach { ScoreCell(it, PERSON_WIDTH) }
                ScoreCell(overview.score, TOTAL_WIDTH)
            }
        }
    }
}

@Composable
private fun HeaderCell(text: String, width: Dp, align: TextAlign = TextAlign.End) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        textAlign = align,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(width).padding(vertical = 6.dp),
    )
}

@Composable
private fun FigureCell(
    spent: Long,
    budget: Long,
    status: LineStatus,
    width: Dp,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
) {
    Column(modifier.width(width).padding(start = 4.dp), horizontalAlignment = Alignment.End) {
        Text(
            whole(spent),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
            color = sheetColour(status),
            maxLines = 1,
        )
        Text(
            if (budget > 0) "of ${whole(budget)}" else "—",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun ScoreCell(score: MonthScore, width: Dp) {
    Text(
        score.percent?.let { "$it%" } ?: "—",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        color = gradeColourOf(score.grade),
        textAlign = TextAlign.End,
        modifier = Modifier.width(width),
    )
}

/**
 * Month by month: what was budgeted and what was spent, the months that
 * went over in red. Tapping a month shows it.
 */
@Composable
internal fun BudgetGraph(history: List<MonthScore>, selected: java.time.YearMonth, onMonth: (java.time.YearMonth) -> Unit) {
    val months = history.reversed()
    val colors = FinanceTheme.colors
    val budgetColour = MaterialTheme.colorScheme.outlineVariant
    SectionCard(title = "Month by month", subtitle = "Budget beside what was spent — red went over") {
        GroupedBarChart(
            groups = months.map { score ->
                val over = score.spentMinor > score.budgetMinor
                BarGroup(
                    score.month.month.getDisplayName(TextStyle.SHORT, Locale.UK),
                    listOf(
                        ChartEntry("Budget", score.budgetMinor.toFloat(), budgetColour, Money.format(score.budgetMinor)),
                        ChartEntry("Spent", score.spentMinor.toFloat(), if (over) colors.chartOut else colors.chartIn, Money.format(score.spentMinor)),
                    ),
                )
            },
            height = 170.dp,
            selectedIndex = months.indexOfFirst { it.month == selected }.takeIf { it >= 0 },
            onGroupClick = { index -> months.getOrNull(index)?.let { onMonth(it.month) } },
        )
        val over = months.filter { !it.inProgress && it.spentMinor > it.budgetMinor }
        Text(
            if (over.isEmpty()) {
                "Within budget every month shown."
            } else {
                "Went over in " + over.joinToString(", ") { it.month.month.getDisplayName(TextStyle.FULL, Locale.UK) } + "."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (over.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else colors.chartOut,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
}

/** "£1,234": whole pounds, as a sheet shows them. */
private fun whole(minor: Long): String =
    (if (minor < 0) "−£" else "£") + String.format(Locale.UK, "%,d", Math.round(kotlin.math.abs(minor) / 100.0))

private val SHEET_GREEN = Color(0xFF2E7D32)
private val SHEET_AMBER = Color(0xFFEF6C00)
private val SHEET_RED = Color(0xFFC62828)

@Composable
internal fun sheetColour(status: LineStatus): Color = when (status) {
    LineStatus.UNDER, LineStatus.MET -> SHEET_GREEN
    LineStatus.ABOUT_RIGHT, LineStatus.OVER_AIM, LineStatus.NOT_YET -> SHEET_AMBER
    LineStatus.OVER, LineStatus.SHORT -> SHEET_RED
    LineStatus.NO_BUDGET -> MaterialTheme.colorScheme.onSurface
}

@Composable
internal fun gradeColourOf(grade: Grade?): Color = when (grade) {
    Grade.EXCELLENT, Grade.GOOD -> SHEET_GREEN
    Grade.GETTING_THERE -> SHEET_AMBER
    Grade.NEEDS_WORK -> SHEET_RED
    null -> MaterialTheme.colorScheme.onSurfaceVariant
}

private val NAME_WIDTH = 104.dp
private val PERSON_WIDTH = 74.dp
private val TOTAL_WIDTH = 80.dp
