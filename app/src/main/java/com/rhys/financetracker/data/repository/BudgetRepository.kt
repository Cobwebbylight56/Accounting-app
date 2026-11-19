package com.rhys.financetracker.data.repository

import android.net.Uri
import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.importer.BudgetFromSheet
import com.rhys.financetracker.data.importer.SpreadsheetImporter
import com.rhys.financetracker.data.local.dao.AccountDao
import com.rhys.financetracker.data.local.dao.BudgetDao
import com.rhys.financetracker.data.local.dao.CategoryDao
import com.rhys.financetracker.data.local.entity.BudgetChangeEntity
import com.rhys.financetracker.data.local.entity.BudgetLineEntity
import com.rhys.financetracker.data.local.entity.CategoryEntity
import com.rhys.financetracker.data.prefs.SettingsRepository
import com.rhys.financetracker.domain.budget.BudgetMaths
import com.rhys.financetracker.domain.budget.BudgetPlan
import com.rhys.financetracker.domain.budget.LineGoal
import com.rhys.financetracker.domain.budget.LineResult
import com.rhys.financetracker.domain.budget.MonthScore
import com.rhys.financetracker.domain.budget.Suggestion
import com.rhys.financetracker.domain.model.CategoryKind
import com.rhys.financetracker.domain.model.Holding
import com.rhys.financetracker.domain.model.TransactionType
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** A line of the budget for the page: how it did, and what counts towards it. */
data class BudgetLineView(
    val line: BudgetLineEntity,
    val result: LineResult,
    /** "Fuel", "Food and what's in it", "Paid into Car loan"; null when nothing is chosen yet. */
    val countsLabel: String?,
    /** What it usually comes to: the average of the last few finished months; null with none. */
    val usualMinor: Long?,
)

/** Everything the budget page shows for one month. */
data class BudgetOverview(
    val month: YearMonth,
    val inProgress: Boolean,
    val lines: List<BudgetLineView>,
    val score: MonthScore,
    /** Months scored, newest first, the one shown among them; months with no payments in are left out. */
    val history: List<MonthScore>,
    /** Finished months in a row within the spending budget. */
    val streak: Int,
    val suggestions: List<Suggestion>,
)

/** A line offered when setting the budget up, to keep, change or leave out. */
data class DraftLine(
    val name: String,
    val amountMinor: Long,
    val categoryId: Long? = null,
    val accountId: Long? = null,
    val isAim: Boolean = false,
    val isPutAside: Boolean = false,
    val include: Boolean = true,
)

/**
 * The household budget: its lines, what each month spent against them, how
 * well each month kept to it, and where a budget could be better.
 *
 * Set up from the household spreadsheet the app started from — its
 * outgoings are the plan — or from what's been spent lately.
 */
@Singleton
class BudgetRepository @Inject constructor(
    private val budgetDao: BudgetDao,
    private val categoryDao: CategoryDao,
    private val accountDao: AccountDao,
    private val transactionRepository: TransactionRepository,
    private val importer: SpreadsheetImporter,
    private val settingsRepository: SettingsRepository,
) {

    val lines: Flow<List<BudgetLineEntity>> = budgetDao.observeLines()
    val changes: Flow<List<BudgetChangeEntity>> = budgetDao.observeChanges()

    /** [month] in full, with the months before it scored. Months after today have nothing in yet. */
    suspend fun overview(month: YearMonth, today: LocalDate = DateUtils.today(), historyMonths: Int = HISTORY_MONTHS): BudgetOverview {
        val thisMonth = YearMonth.from(today)
        val lines = budgetDao.getLines()
        val plans = lines.map(::planOf)
        val changes = budgetDao.getChanges().associate { (it.lineId to it.month) to it.amountMinor }
        val categories = categoryDao.getAll()
        val accounts = accountDao.getAll().associateBy { it.id }

        // Actual figures from far enough back to know each scored month's
        // average-following budget, and the latest finished months for suggestions.
        val newest = maxOf(month, thisMonth.minusMonths(1)).coerceAtMost(thisMonth)
        val oldest = minOf(month, newest).minusMonths((historyMonths + BudgetMaths.AVERAGE_MONTHS).toLong())
        val months = generateSequence(oldest) { it.plusMonths(1) }.takeWhile { it <= newest }.toList()
        val actual = months.associateWith { m -> actuals(lines, categories, m, today) }

        fun before(lineId: Long, m: YearMonth): List<Long> =
            (BudgetMaths.AVERAGE_MONTHS downTo 1).mapNotNull { back -> actual[m.minusMonths(back.toLong())]?.takeIf { it.hasPayments }?.byLine?.get(lineId) }

        fun resultsFor(m: YearMonth): List<LineResult> = plans.map { plan ->
            BudgetMaths.result(
                plan = plan,
                month = m,
                actual = actual[m]?.byLine?.get(plan.lineId) ?: 0L,
                change = changes[plan.lineId to m.toString()],
                before = before(plan.lineId, m),
                inProgress = m == thisMonth,
            )
        }

        val shown = resultsFor(month)
        val score = BudgetMaths.score(month, shown, inProgress = month == thisMonth)
        val history = (0 until historyMonths)
            .map { month.minusMonths(it.toLong()) }
            .filter { m -> m == month || actual[m]?.hasPayments == true }
            .map { m -> if (m == month) score else BudgetMaths.score(m, resultsFor(m), inProgress = m == thisMonth) }
        val recent = (0 until historyMonths).map { thisMonth.minusMonths(it.toLong()) }
            .filter { m -> actual[m]?.hasPayments == true }
            .map { m -> BudgetMaths.score(m, resultsFor(m), inProgress = m == thisMonth) }

        // Suggestions and "usually" come from the last few finished months with payments in.
        val finished = months.filter { it < thisMonth && actual[it]?.hasPayments == true }.takeLast(BudgetMaths.AVERAGE_MONTHS)
        val seen = settingsRepository.settings.first().budgetSuggestionsSeen
        val suggestions = plans.mapNotNull { plan ->
            val history = finished.map { m ->
                BudgetMaths.budgetFor(plan, changes[plan.lineId to m.toString()], before(plan.lineId, m)).first to
                    (actual[m]?.byLine?.get(plan.lineId) ?: 0L)
            }
            BudgetMaths.suggestion(plan, history)?.takeIf { "${it.lineId}:${it.toMinor}" !in seen }
        }

        val views = lines.zip(shown).map { (line, result) ->
            val usual = finished.mapNotNull { actual[it]?.byLine?.get(line.id) }.filter { it > 0 }
            BudgetLineView(
                line = line,
                result = result,
                countsLabel = countsLabel(line, categories, accounts.mapValues { it.value.name }),
                usualMinor = if (usual.isEmpty()) null else usual.sum() / usual.size,
            )
        }
        return BudgetOverview(
            month = month,
            inProgress = month == thisMonth,
            lines = views,
            score = score,
            history = history,
            streak = BudgetMaths.streak(recent),
            suggestions = suggestions,
        )
    }

    /** What each line came to in [month]: its category's spending, or what was paid into its account. */
    private suspend fun actuals(
        lines: List<BudgetLineEntity>,
        categories: List<CategoryEntity>,
        month: YearMonth,
        today: LocalDate,
    ): MonthActuals {
        val start = month.atDay(1)
        if (start.isAfter(today)) return MonthActuals(emptyMap(), hasPayments = false)
        val end = minOf(month.atEndOfMonth(), today)
        val totals = transactionRepository.observeCategoryTotals(TransactionType.EXPENSE, start, end).first()
        val byCategory = totals.mapNotNull { total -> total.categoryId?.let { it to total.totalMinor } }.toMap()
        val kinds = categories.associate { it.id to it.kind }
        // What went into savings, only looked up when a line is for the Savings category.
        val saved = if (lines.any { it.accountId == null && kinds[it.categoryId] == CategoryKind.SAVING }) {
            transactionRepository.observePotFlow(CategoryKind.SAVING, start, end).first().netMinor
        } else {
            0L
        }
        val byLine = lines.associate { line ->
            line.id to when {
                line.accountId != null -> budgetDao.paidInto(line.accountId, start, end)
                line.categoryId != null && kinds[line.categoryId] == CategoryKind.SAVING -> saved
                line.categoryId != null -> withinCategory(line.categoryId, categories).sumOf { byCategory[it] ?: 0L }
                else -> 0L
            }
        }
        return MonthActuals(byLine, hasPayments = totals.isNotEmpty())
    }

    private data class MonthActuals(val byLine: Map<Long, Long>, val hasPayments: Boolean)

    /** [categoryId] and every category inside it, at any depth. */
    private fun withinCategory(categoryId: Long, categories: List<CategoryEntity>): Set<Long> {
        val children = categories.groupBy { it.parentId }
        val found = mutableSetOf(categoryId)
        var frontier = listOf(categoryId)
        while (frontier.isNotEmpty()) {
            frontier = frontier.flatMap { id -> children[id].orEmpty().map { it.id } }.filter { found.add(it) }
        }
        return found
    }

    private fun countsLabel(line: BudgetLineEntity, categories: List<CategoryEntity>, accounts: Map<Long, String>): String? {
        line.accountId?.let { id -> return accounts[id]?.let { "Paid into $it" } }
        val category = categories.firstOrNull { it.id == line.categoryId } ?: return null
        return if (categories.any { it.parentId == category.id }) "${category.name} and what's in it" else category.name
    }

    // ------------------------------------------------------------ setting up

    /**
     * The household spreadsheet's outgoings as budget lines, each matched to
     * where its money goes — to be checked before anything is saved.
     */
    suspend fun readSpreadsheet(uri: Uri): AppResult<List<DraftLine>> {
        val workbook = when (val read = importer.readWorkbook(uri)) {
            is AppResult.Success -> read.data
            is AppResult.Failure -> return AppResult.Failure(read.message)
        }
        val rows = workbook.sheets.firstNotNullOfOrNull { sheet ->
            importer.detectHouseholdLayout(sheet)?.let { layout -> BudgetFromSheet.rows(sheet, layout) }?.takeIf { it.isNotEmpty() }
        } ?: return AppResult.Failure(
            "That doesn't look like a household budget sheet — a column of figures for each person, under a heading like OUTGOINGS.",
        )
        val (categories, accounts) = matchingLists()
        return AppResult.Success(
            rows.map { row ->
                val match = BudgetFromSheet.match(row.name, categories, accounts)
                DraftLine(
                    name = row.name,
                    amountMinor = row.amountMinor,
                    categoryId = match.categoryId,
                    accountId = match.accountId,
                    isAim = match.aim,
                    isPutAside = match.goal == LineGoal.PUT_ASIDE_AT_LEAST,
                )
            },
        )
    }

    /**
     * Lines from what's been spent lately: each category's average over the
     * last few finished months with payments in, and what went into each
     * loan and saver.
     */
    suspend fun fromSpending(today: LocalDate = DateUtils.today()): List<DraftLine> {
        val thisMonth = YearMonth.from(today)
        val categories = categoryDao.getAll().filter { !it.isArchived }
        val months = (1..MONTHS_LOOKED_BACK).map { thisMonth.minusMonths(it.toLong()) }
        val totals = months.map { m ->
            transactionRepository.observeCategoryTotals(TransactionType.EXPENSE, m.atDay(1), m.atEndOfMonth()).first()
        }.filter { it.isNotEmpty() }.take(BudgetMaths.AVERAGE_MONTHS)
        if (totals.isEmpty()) return emptyList()
        val byId = categories.associateBy { it.id }
        val spending = totals.flatten()
            .mapNotNull { total -> total.categoryId?.let { it to total.totalMinor } }
            .groupBy({ it.first }, { it.second })
            .mapNotNull { (id, amounts) ->
                val category = byId[id] ?: return@mapNotNull null
                val average = amounts.sum() / totals.size
                if (average < MIN_LINE) return@mapNotNull null
                DraftLine(
                    name = category.name,
                    amountMinor = BudgetMaths.roundUpToFive(average),
                    categoryId = id,
                    isAim = BudgetFromSheet.isAim(category.name, category.name),
                )
            }
            .sortedByDescending { it.amountMinor }
        val start = months[totals.size - 1].atDay(1)
        val end = months.first().atEndOfMonth()
        val putAside = accountDao.getAllActive().filter { it.holding != Holding.SPEND }.mapNotNull { account ->
            val average = budgetDao.paidInto(account.id, start, end) / totals.size
            if (average < MIN_LINE) null else DraftLine(
                name = account.name,
                amountMinor = BudgetMaths.roundUpToFive(average),
                accountId = account.id,
                isPutAside = true,
            )
        }
        return spending + putAside
    }

    /** Categories and accounts as matching and choosing need them. */
    suspend fun matchingLists(): Pair<List<BudgetFromSheet.Category>, List<BudgetFromSheet.Account>> =
        categoryDao.getAll().filter { !it.isArchived }.map { BudgetFromSheet.Category(it.id, it.name, it.kind, it.parentId) } to
            accountDao.getAllActive().map { BudgetFromSheet.Account(it.id, it.name, it.holding) }

    /** Saves the chosen [drafts] as the budget, in place of what was there. */
    suspend fun saveSetup(drafts: List<DraftLine>, fromSheet: Boolean) {
        budgetDao.replaceLines(
            drafts.filter { it.include && it.amountMinor > 0 }.mapIndexed { i, draft ->
                BudgetLineEntity(
                    name = draft.name.trim(),
                    categoryId = draft.categoryId.takeIf { draft.accountId == null },
                    accountId = draft.accountId,
                    amountMinor = draft.amountMinor,
                    isAim = draft.isAim && !draft.isPutAside,
                    isPutAside = draft.isPutAside,
                    sortOrder = i,
                    fromSheet = fromSheet,
                )
            },
        )
    }

    // -------------------------------------------------------------- editing

    suspend fun saveLine(line: BudgetLineEntity): Long =
        if (line.id == 0L) {
            budgetDao.insertLine(line.copy(sortOrder = (budgetDao.getLines().maxOfOrNull { it.sortOrder } ?: -1) + 1))
        } else {
            budgetDao.updateLine(line)
            line.id
        }

    suspend fun deleteLine(id: Long) = budgetDao.deleteLine(id)

    /** [lineId]'s budget for [month] only; null puts it back to usual. */
    suspend fun changeForMonth(lineId: Long, month: YearMonth, amountMinor: Long?) {
        if (amountMinor == null) {
            budgetDao.clearChange(lineId, month.toString())
        } else {
            budgetDao.setChange(BudgetChangeEntity(lineId, month.toString(), amountMinor))
        }
    }

    /** Takes [suggestion]: the line's usual amount becomes the suggested one. */
    suspend fun accept(suggestion: Suggestion) {
        val line = budgetDao.getLine(suggestion.lineId) ?: return
        budgetDao.updateLine(line.copy(amountMinor = suggestion.toMinor))
    }

    suspend fun dismiss(suggestion: Suggestion) = settingsRepository.markBudgetSuggestionSeen("${suggestion.lineId}:${suggestion.toMinor}")

    private fun planOf(line: BudgetLineEntity) = BudgetPlan(
        lineId = line.id,
        name = line.name,
        setMinor = line.amountMinor,
        aim = line.isAim,
        followAverage = line.followAverage,
        goal = if (line.isPutAside) LineGoal.PUT_ASIDE_AT_LEAST else LineGoal.SPEND_AT_MOST,
    )

    private companion object {
        /** Months scored on the page. */
        const val HISTORY_MONTHS = 6

        /** How far back to look for months with payments in, setting up from spending. */
        const val MONTHS_LOOKED_BACK = 6

        /** Less than this a month isn't worth a line of its own. */
        const val MIN_LINE = 500L
    }
}
