package com.rhys.financetracker.data.repository

import android.net.Uri
import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.importer.BudgetFromSheet
import com.rhys.financetracker.data.importer.SpreadsheetImporter
import com.rhys.financetracker.data.local.dao.AccountDao
import com.rhys.financetracker.data.local.dao.BudgetDao
import com.rhys.financetracker.data.local.dao.CategoryDao
import com.rhys.financetracker.data.local.dao.PersonDao
import com.rhys.financetracker.data.local.entity.BudgetChangeEntity
import com.rhys.financetracker.data.local.entity.BudgetLineEntity
import com.rhys.financetracker.data.local.entity.CategoryEntity
import com.rhys.financetracker.data.prefs.SettingsRepository
import com.rhys.financetracker.domain.budget.BudgetMaths
import com.rhys.financetracker.domain.budget.BudgetPlan
import com.rhys.financetracker.domain.budget.BudgetShares
import com.rhys.financetracker.domain.budget.LineStatus
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** A column of the budget sheet: one person, or the joint money (no person). */
data class BudgetColumn(val personId: Long?, val name: String)

/** One person's cell of a line: their part of its budget, and what they spent. */
data class BudgetCell(val budgetMinor: Long, val actualMinor: Long, val status: LineStatus)

/** A line of the budget for the page: how it did, and what counts towards it. */
data class BudgetLineView(
    val line: BudgetLineEntity,
    val result: LineResult,
    /** "Fuel", "Food and what's in it", "Paid into Car loan"; null when nothing is chosen yet. */
    val countsLabel: String?,
    /** What it usually comes to: the average of the last few finished months; null with none. */
    val usualMinor: Long?,
    /** One per column of [BudgetOverview.columns]. */
    val cells: List<BudgetCell> = emptyList(),
    /** Each person's usual part, by person; empty when the line isn't split. */
    val shares: Map<Long, Long> = emptyMap(),
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
    /** The sheet's person columns, before the total; empty with only one person. */
    val columns: List<BudgetColumn> = emptyList(),
    /** How each column did, one per column. */
    val columnScores: List<MonthScore> = emptyList(),
    /** Joint spending shared between the people, rather than a column of its own. */
    val splitJoint: Boolean = true,
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
    /** Each person's part, from the sheet's columns: person id to pence. */
    val shares: Map<Long, Long> = emptyMap(),
    /** "Rhys £60.75 · Hannah £80.59", to show who it's for. */
    val sharesText: String? = null,
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
    private val personDao: PersonDao,
) {

    val lines: Flow<List<BudgetLineEntity>> = budgetDao.observeLines()
    val changes: Flow<List<BudgetChangeEntity>> = budgetDao.observeChanges()
    val shares: Flow<List<com.rhys.financetracker.data.local.entity.BudgetShareEntity>> = budgetDao.observeShares()

    /** Whether joint spending is shared between the people on the sheet. */
    val splitJoint: Flow<Boolean> = settingsRepository.settings.map { it.budgetSplitJoint }.distinctUntilChanged()

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

        // The sheet: a column for each person, their part of each line and
        // what they spent on it, joint spending shared between them or in a
        // column of its own.
        val people = personDao.getAll().filter { !it.isArchived }
        val individuals = people.filterNot { it.isShared }.sortedBy { it.sortOrder }
        val splitJoint = settingsRepository.settings.first().budgetSplitJoint
        val sharesByLine = budgetDao.getShares().groupBy { it.lineId }.mapValues { (_, list) -> list.associate { it.personId to it.amountMinor } }
        val ids = individuals.map { it.id }
        val columns = if (individuals.size < 2) emptyList() else {
            individuals.map { BudgetColumn(it.id, it.name.substringBefore(' ')) } +
                if (splitJoint) emptyList() else listOf(BudgetColumn(null, people.firstOrNull { it.isShared }?.name ?: "Joint"))
        }
        val byPerson = if (columns.isEmpty()) emptyMap() else ids.associateWith { id -> personActuals(lines, categories, month, today, id) }

        val views = lines.zip(shown).map { (line, result) ->
            val usual = finished.mapNotNull { actual[it]?.byLine?.get(line.id) }.filter { it > 0 }
            val shares = sharesByLine[line.id].orEmpty()
            val cells = if (columns.isEmpty()) emptyList() else {
                val budgets = BudgetShares.forMonth(shares, line.amountMinor, result.budgetMinor, ids)
                val jointBudget = if (shares.isEmpty()) 0L else Math.round(
                    BudgetShares.unassigned(shares, line.amountMinor, ids).toDouble() * result.budgetMinor / line.amountMinor.coerceAtLeast(1L),
                )
                val own = ids.associateWith { id -> byPerson[id]?.get(line.id) ?: 0L }
                val joint = (result.actualMinor - own.values.sum()).coerceAtLeast(0L)
                val jointSpent = BudgetShares.evenly(joint, ids)
                val jointBudgets = BudgetShares.evenly(jointBudget, ids)
                val inProgress = month == thisMonth
                val personCells = ids.map { id ->
                    val budget = (budgets[id] ?: 0L) + if (splitJoint) jointBudgets[id] ?: 0L else 0L
                    val spent = (own[id] ?: 0L) + if (splitJoint) jointSpent[id] ?: 0L else 0L
                    BudgetCell(budget, spent, BudgetMaths.status(result.plan, budget, spent, inProgress))
                }
                personCells + if (splitJoint) emptyList() else listOf(
                    BudgetCell(jointBudget, joint, BudgetMaths.status(result.plan, jointBudget, joint, inProgress)),
                )
            }
            BudgetLineView(
                line = line,
                result = result,
                countsLabel = countsLabel(line, categories, accounts.mapValues { it.value.name }),
                usualMinor = if (usual.isEmpty()) null else usual.sum() / usual.size,
                cells = cells,
                shares = shares,
            )
        }
        val columnScores = columns.indices.map { c ->
            BudgetMaths.score(
                month,
                views.map { view ->
                    val cell = view.cells[c]
                    view.result.copy(budgetMinor = cell.budgetMinor, actualMinor = cell.actualMinor, status = cell.status)
                },
                inProgress = month == thisMonth,
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
            columns = columns,
            columnScores = columnScores,
            splitJoint = splitJoint,
        )
    }

    /** What [personId]'s own accounts put towards each line in [month]. */
    private suspend fun personActuals(
        lines: List<BudgetLineEntity>,
        categories: List<CategoryEntity>,
        month: YearMonth,
        today: LocalDate,
        personId: Long,
    ): Map<Long, Long> {
        val start = month.atDay(1)
        if (start.isAfter(today)) return emptyMap()
        val end = minOf(month.atEndOfMonth(), today)
        val people = setOf(personId)
        val byCategory = transactionRepository.observeCategoryTotals(TransactionType.EXPENSE, start, end, null, people).first()
            .mapNotNull { total -> total.categoryId?.let { it to total.totalMinor } }.toMap()
        val kinds = categories.associate { it.id to it.kind }
        val saved = if (lines.any { it.accountId == null && kinds[it.categoryId] == CategoryKind.SAVING }) {
            transactionRepository.observePotFlow(CategoryKind.SAVING, start, end, null, people).first().netMinor
        } else {
            0L
        }
        return lines.associate { line ->
            line.id to when {
                line.accountId != null -> budgetDao.paidIntoBy(line.accountId, personId, start, end)
                line.categoryId != null && kinds[line.categoryId] == CategoryKind.SAVING -> saved
                line.categoryId != null -> withinCategory(line.categoryId, categories).sumOf { byCategory[it] ?: 0L }
                else -> 0L
            }
        }
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
        // Each column of the sheet is someone in the app: "Rhys" is Rhys Evans.
        val people = personDao.getAll().filter { !it.isArchived && !it.isShared }
        fun personFor(heading: String) = people.firstOrNull { it.name.equals(heading, true) }
            ?: people.firstOrNull { it.name.substringBefore(' ').equals(heading.substringBefore(' '), true) }
        return AppResult.Success(
            rows.map { row ->
                val match = BudgetFromSheet.match(row.name, categories, accounts)
                val shares = row.byPerson.mapNotNull { (heading, amount) -> personFor(heading)?.let { it.id to amount } }
                    .groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
                DraftLine(
                    name = row.name,
                    amountMinor = row.amountMinor,
                    categoryId = match.categoryId,
                    accountId = match.accountId,
                    isAim = match.aim,
                    isPutAside = match.goal == LineGoal.PUT_ASIDE_AT_LEAST,
                    shares = shares,
                    sharesText = people.filter { it.id in shares }
                        .joinToString(" · ") { "${it.name.substringBefore(' ')} ${com.rhys.financetracker.core.money.Money.format(shares.getValue(it.id))}" }
                        .ifBlank { null },
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
        val kept = drafts.filter { it.include && it.amountMinor > 0 }
        budgetDao.replaceLines(
            kept.mapIndexed { i, draft ->
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
            // A total changed while checking: everyone's part changes alike.
            kept.map { draft ->
                val shares = draft.shares.filterValues { amount -> amount > 0 }
                val sum = shares.values.sum()
                if (sum > 0 && sum != draft.amountMinor && shares.size == draft.shares.size) {
                    BudgetShares.scaled(shares, sum, draft.amountMinor)
                } else {
                    shares
                }
            },
        )
    }

    // -------------------------------------------------------------- editing

    suspend fun saveLine(line: BudgetLineEntity): Long {
        if (line.id == 0L) {
            return budgetDao.insertLine(line.copy(sortOrder = (budgetDao.getLines().maxOfOrNull { it.sortOrder } ?: -1) + 1))
        }
        // A new total for a split line: everyone's part goes up or down alike.
        val before = budgetDao.getLine(line.id)
        val shares = budgetDao.getShares().filter { it.lineId == line.id }.associate { it.personId to it.amountMinor }
        if (before != null && shares.isNotEmpty() && before.amountMinor != line.amountMinor) {
            budgetDao.updateLine(line)
            budgetDao.setShares(line.id, BudgetShares.scaled(shares, before.amountMinor, line.amountMinor), line.amountMinor)
        } else {
            budgetDao.updateLine(line)
        }
        return line.id
    }

    /** [personId]'s usual part of [lineId] becomes [amountMinor]; the line's total follows. */
    suspend fun setPersonShare(lineId: Long, personId: Long, amountMinor: Long) {
        val line = budgetDao.getLine(lineId) ?: return
        val people = personDao.getAll().filter { !it.isArchived && !it.isShared }.map { it.id }
        val shares = budgetDao.getShares().filter { it.lineId == lineId }.associate { it.personId to it.amountMinor }
        val (updated, total) = BudgetShares.withPerson(shares, line.amountMinor, people, personId, amountMinor)
        budgetDao.setShares(lineId, updated, total)
    }

    /** Joint spending shared between the people on the sheet, or a column of its own. */
    suspend fun setSplitJoint(on: Boolean) = settingsRepository.setBudgetSplitJoint(on)

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
