package com.rhys.financetracker.domain.report

import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.local.projection.AccountWithBalance
import com.rhys.financetracker.data.local.projection.CategoryTotal
import com.rhys.financetracker.domain.model.AccountType
import java.time.LocalDate
import java.time.YearMonth

/**
 * Which slice of the household a report covers.  Both fields null means the
 * whole household; the UI never sets both at once.
 */
data class ReportScope(
    val accountId: Long? = null,
    val personId: Long? = null,
    val label: String = "Whole household",
) {
    companion object {
        val HOUSEHOLD = ReportScope()
    }
}

/** The period a report covers, with a label for the printed header. */
data class ReportPeriod(
    val start: LocalDate,
    val end: LocalDate,
    val label: String,
) {
    companion object {
        fun month(yearMonth: YearMonth): ReportPeriod = ReportPeriod(
            start = yearMonth.atDay(1),
            end = yearMonth.atEndOfMonth(),
            label = com.rhys.financetracker.core.time.DateUtils.formatMonth(yearMonth),
        )

        fun year(year: Int): ReportPeriod = ReportPeriod(
            start = LocalDate.of(year, 1, 1),
            end = LocalDate.of(year, 12, 31),
            label = year.toString(),
        )

        fun custom(start: LocalDate, end: LocalDate): ReportPeriod = ReportPeriod(
            start = start,
            end = end,
            label = "${com.rhys.financetracker.core.time.DateUtils.format(start)} to " +
                com.rhys.financetracker.core.time.DateUtils.format(end),
        )
    }
}

/** The kinds of report the app can produce. */
enum class ReportType(val displayName: String, val description: String) {
    MONTHLY_SPENDING("Monthly spending", "Everything spent in one month, grouped by category"),
    YEARLY_SPENDING("Yearly spending", "A month-by-month view of a whole year"),
    CATEGORY_BREAKDOWN("Category breakdown", "Where the money went, largest first"),
    INCOME_VS_EXPENSES("Income vs expenses", "What came in against what went out"),
    ACCOUNT_BALANCES("Account balances", "The balance of every account"),
    NET_WORTH("Net worth", "Everything owned less everything owed"),
    CASH_FLOW("Cash flow", "The running balance day by day"),
    SAVINGS_HISTORY("Savings history", "How each goal has grown"),
    FULL_SUMMARY("Full financial summary", "Every section above in one document"),
}

/** One line on a printed or exported report. */
data class ReportRow(
    val label: String,
    val value: String,
    /** Optional second column, e.g. a percentage or a count. */
    val secondary: String? = null,
    /** Hex colour used for the swatch beside category rows. */
    val colorHex: String? = null,
    /** Rendered in bold; used for totals. */
    val isTotal: Boolean = false,
    /** Indented under the previous row. */
    val isSubRow: Boolean = false,
)

/** A titled block of rows. Reports are lists of these, which print predictably. */
data class ReportSection(
    val title: String,
    val rows: List<ReportRow>,
    val note: String? = null,
)

/** A complete report, ready to render on screen, print, or export. */
data class Report(
    val type: ReportType,
    val title: String,
    val period: ReportPeriod,
    val scope: ReportScope,
    val generatedOn: LocalDate,
    val sections: List<ReportSection>,
    /** Chart data, used on screen; the PDF renders its own simple charts. */
    val charts: ReportCharts = ReportCharts(),
)

/** Chart-ready data that accompanies a report. */
data class ReportCharts(
    val categoryTotals: List<CategoryTotal> = emptyList(),
    val monthlySeries: List<MonthPoint> = emptyList(),
    val balanceSeries: List<DatedAmount> = emptyList(),
    val accountBalances: List<AccountWithBalance> = emptyList(),
    val typeTotals: List<AccountTypeSlice> = emptyList(),
)

/** One month on the income/expense bar chart. */
data class MonthPoint(
    val yearMonth: YearMonth,
    val incomeMinor: Long,
    val expenseMinor: Long,
) {
    val netMinor: Long get() = incomeMinor - expenseMinor
}

/** One point on a line chart. */
data class DatedAmount(
    val date: LocalDate,
    val amountMinor: Long,
)

/** Net worth split by account type. */
data class AccountTypeSlice(
    val type: AccountType,
    val totalMinor: Long,
)

/** The headline figures shown on the dashboard and at the top of reports. */
data class FinancialSummary(
    val totalBalanceMinor: Long,
    val totalSavingsMinor: Long,
    val totalLiabilitiesMinor: Long,
    val netWorthMinor: Long,
    val monthIncomeMinor: Long,
    val monthExpenseMinor: Long,
    val committedRecurringMinor: Long,
    /**
     * Paid into savings this month, read from the categories on the payments
     * rather than from any account balance — so a saver held at another bank,
     * which this app has no account for, still shows up as saving.
     */
    val savingsPaidInMinor: Long = 0L,
) {
    val monthNetMinor: Long get() = monthIncomeMinor - monthExpenseMinor

    /**
     * Money genuinely free to spend: what has come in this month, less what has
     * gone out, less the bills still to be paid before the month ends.
     */
    val disposableMinor: Long get() = monthNetMinor - committedRecurringMinor

    companion object {
        val EMPTY = FinancialSummary(0, 0, 0, 0, 0, 0, 0)
    }
}
