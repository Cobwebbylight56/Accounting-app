package com.rhys.financetracker.data.importer

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.core.result.runCatchingApp
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.local.dao.AccountDao
import com.rhys.financetracker.data.local.dao.CategoryDao
import com.rhys.financetracker.data.local.dao.PersonDao
import com.rhys.financetracker.data.local.dao.RecurringRuleDao
import com.rhys.financetracker.data.local.dao.TransactionDao
import com.rhys.financetracker.data.local.entity.AccountEntity
import com.rhys.financetracker.data.local.entity.PersonEntity
import com.rhys.financetracker.data.local.entity.RecurringRuleEntity
import com.rhys.financetracker.data.local.entity.TransactionEntity
import com.rhys.financetracker.data.local.seed.DefaultData
import com.rhys.financetracker.data.repository.CategoryRepository
import com.rhys.financetracker.domain.model.AccountType
import com.rhys.financetracker.domain.model.CategoryKind
import com.rhys.financetracker.domain.model.Frequency
import com.rhys.financetracker.domain.model.TransactionType
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Turns a spreadsheet into records in the app.
 *
 * The import is a three-step conversation, not a single button:
 *  1. **Read** the file into a [WorkbookData] grid ([readWorkbook]).
 *  2. **Map** columns to meanings, with a first guess from [ColumnDetector]
 *     that the user can correct ([buildCandidates]).
 *  3. **Apply** only the rows the user ticked ([applyCandidates]).
 *
 * Nothing is written until step 3, so a wrong guess costs nothing.  Existing
 * people, accounts and categories are matched by name and reused, so importing
 * the same workbook again next month tops the data up rather than duplicating
 * it.
 */
@Singleton
class SpreadsheetImporter @Inject constructor(
    private val context: Context,
    private val personDao: PersonDao,
    private val accountDao: AccountDao,
    private val categoryDao: CategoryDao,
    private val recurringRuleDao: RecurringRuleDao,
    private val transactionDao: TransactionDao,
    private val categoryRepository: CategoryRepository,
    @com.rhys.financetracker.di.IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /** Reads the file the user picked. The format is chosen by file extension. */
    suspend fun readWorkbook(uri: Uri): AppResult<WorkbookData> = withContext(ioDispatcher) {
        runCatchingApp("Could not open that file") {
            val name = DocumentFile.fromSingleUri(context, uri)?.name ?: "Spreadsheet"
            val stream = context.contentResolver.openInputStream(uri)
                ?: error("The file could not be opened. Try copying it to your device first.")

            stream.use { input ->
                when {
                    name.endsWith(".xlsx", ignoreCase = true) ||
                        name.endsWith(".xlsm", ignoreCase = true) ->
                        XlsxReader.read(input, name)

                    name.endsWith(".csv", ignoreCase = true) ||
                        name.endsWith(".txt", ignoreCase = true) ||
                        name.endsWith(".tsv", ignoreCase = true) ->
                        CsvReader.read(input, name)

                    name.endsWith(".xls", ignoreCase = true) ->
                        error(
                            "That is an older .xls workbook. Open it in Excel or Google " +
                                "Sheets and save it as .xlsx or .csv first.",
                        )

                    else -> error("Choose an .xlsx or .csv file")
                }
            }.also { workbook ->
                if (workbook.isEmpty) error("That file did not contain any data")
            }
        }
    }

    /**
     * Suggests a mapping for [sheet] — a starting point the user edits on the
     * import screen.
     */
    fun suggestMapping(sheet: SheetData, target: ImportTarget): ImportMapping {
        val headerRow = ColumnDetector.detectHeaderRow(sheet)
        val firstDataRow = (headerRow?.plus(1)) ?: 0
        val sampleRows = sheet.nonEmptyRows()
            .filter { it >= firstDataRow }
            .take(20)

        return ImportMapping(
            sheetName = sheet.name,
            headerRow = headerRow ?: -1,
            firstDataRow = firstDataRow,
            lastDataRow = sheet.rowCount - 1,
            columnRoles = ColumnDetector.detect(sheet, headerRow, sampleRows),
            target = target,
        )
    }

    /**
     * Interprets the mapped rows without saving them, so the user can review
     * exactly what would be created.
     */
    fun buildCandidates(sheet: SheetData, mapping: ImportMapping): List<ImportCandidate> {
        val roleColumns = mapping.columnRoles.entries
            .groupBy({ it.value }, { it.key })

        fun columnFor(role: ColumnRole): Int? = roleColumns[role]?.firstOrNull()

        val nameColumn = columnFor(ColumnRole.NAME)
        val amountColumns = roleColumns[ColumnRole.AMOUNT].orEmpty()
        val dateColumn = columnFor(ColumnRole.DATE)
        val categoryColumn = columnFor(ColumnRole.CATEGORY)
        val personColumn = columnFor(ColumnRole.PERSON)
        val accountColumn = columnFor(ColumnRole.ACCOUNT)
        val notesColumn = columnFor(ColumnRole.NOTES)
        val frequencyColumn = columnFor(ColumnRole.FREQUENCY)
        val dayColumn = columnFor(ColumnRole.DAY_OF_MONTH)

        val range = mapping.firstDataRow..mapping.lastDataRow.coerceAtMost(sheet.rowCount - 1)

        return range.mapNotNull { row ->
            val name = nameColumn?.let { sheet.cell(row, it) }?.trim().orEmpty()
            val amountText = amountColumns
                .map { sheet.cell(row, it) }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
            val amount = Money.parseOrNull(amountText)

            // A row with neither a name nor a figure is a spacer, not data.
            if (name.isBlank() && amount == null) return@mapNotNull null

            val problem = when {
                name.isBlank() -> "No name in this row"
                amount == null -> "\"$amountText\" is not an amount"
                amount == 0L && mapping.skipZeroAmounts -> "The amount is zero"
                amount < 0L && mapping.target != ImportTarget.ACCOUNT_BALANCE ->
                    "Negative amounts are not expected here"
                else -> null
            }

            ImportCandidate(
                sourceRow = row,
                name = name.ifBlank { "Row ${row + 1}" },
                amountMinor = amount ?: 0L,
                target = mapping.target,
                personName = personColumn?.let { sheet.cell(row, it) }?.trim()
                    ?.takeIf { it.isNotBlank() } ?: mapping.defaultPersonName,
                accountName = accountColumn?.let { sheet.cell(row, it) }?.trim()
                    ?.takeIf { it.isNotBlank() } ?: mapping.defaultAccountName,
                categoryName = categoryColumn?.let { sheet.cell(row, it) }?.trim()
                    ?.takeIf { it.isNotBlank() } ?: mapping.defaultCategoryName,
                notes = notesColumn?.let { sheet.cell(row, it) }?.trim()?.takeIf { it.isNotBlank() },
                dayOfMonth = dayColumn?.let { sheet.cell(row, it) }?.trim()?.toIntOrNull(),
                dateIso = dateColumn?.let { parseDate(sheet.cell(row, it)) }?.toString(),
                frequencyName = frequencyColumn?.let { normaliseFrequency(sheet.cell(row, it)) }
                    ?: mapping.defaultFrequency,
                problem = problem,
                isSelected = problem == null,
            )
        }
    }

    /**
     * Writes the selected candidates.
     *
     * People, accounts and categories named in the sheet are created on demand,
     * which is what makes a one-click import of a hand-built spreadsheet
     * possible at all.
     */
    suspend fun applyCandidates(
        candidates: List<ImportCandidate>,
        startMonth: YearMonth = DateUtils.currentYearMonth(),
    ): AppResult<ImportOutcome> = withContext(ioDispatcher) {
        runCatchingApp("Could not complete the import") {
            var outcome = ImportOutcome()
            val problems = mutableListOf<String>()

            for (candidate in candidates) {
                if (!candidate.isSelected || !candidate.isImportable) {
                    outcome = outcome.copy(skipped = outcome.skipped + 1)
                    continue
                }
                try {
                    outcome = when (candidate.target) {
                        ImportTarget.ACCOUNT_BALANCE -> importAccount(candidate, outcome)
                        ImportTarget.RECURRING_EXPENSE ->
                            importRule(candidate, TransactionType.EXPENSE, startMonth, outcome)
                        ImportTarget.RECURRING_INCOME ->
                            importRule(candidate, TransactionType.INCOME, startMonth, outcome)
                        ImportTarget.TRANSACTION -> importTransaction(candidate, outcome)
                    }
                } catch (error: IllegalStateException) {
                    problems += "Row ${candidate.sourceRow + 1}: ${error.message}"
                    outcome = outcome.copy(skipped = outcome.skipped + 1)
                }
            }
            outcome.copy(problems = problems)
        }
    }

    // ------------------------------------------------------------- writers

    private suspend fun importAccount(
        candidate: ImportCandidate,
        outcome: ImportOutcome,
    ): ImportOutcome {
        val existing = accountDao.getByName(candidate.name)
        if (existing != null) {
            // Top up rather than duplicate: the balance in the sheet is newer.
            accountDao.update(existing.copy(openingBalanceMinor = candidate.amountMinor))
            return outcome
        }
        val personResult = resolvePerson(candidate.personName, outcome)
        accountDao.insert(
            AccountEntity(
                name = candidate.name,
                type = guessAccountType(candidate.name),
                personId = personResult.first,
                openingBalanceMinor = candidate.amountMinor,
                openingBalanceDate = DateUtils.today(),
                colorHex = DefaultData.PALETTE.random(),
                notes = candidate.notes,
            ),
        )
        return personResult.second.copy(accountsCreated = personResult.second.accountsCreated + 1)
    }

    private suspend fun importRule(
        candidate: ImportCandidate,
        type: TransactionType,
        startMonth: YearMonth,
        outcome: ImportOutcome,
    ): ImportOutcome {
        var running = outcome
        val (personId, afterPerson) = resolvePerson(candidate.personName, running)
        running = afterPerson
        val (accountId, afterAccount) = resolveAccount(candidate.accountName, personId, running)
        running = afterAccount
        val (categoryId, afterCategory) = resolveCategory(candidate.categoryName, type, running)
        running = afterCategory

        val dueDate = candidate.dateIso?.let { DateUtils.parseIsoOrNull(it) }
            ?: DateUtils.safeDayOfMonth(startMonth, candidate.dayOfMonth ?: 1)
        val frequency = runCatching { Frequency.valueOf(candidate.frequencyName) }
            .getOrDefault(Frequency.MONTHLY)

        recurringRuleDao.insert(
            RecurringRuleEntity(
                name = candidate.name,
                amountMinor = candidate.amountMinor,
                type = type,
                frequency = frequency,
                startDate = dueDate,
                nextDueDate = dueDate,
                accountId = accountId,
                categoryId = categoryId,
                personId = personId,
                notes = candidate.notes,
            ),
        )
        return running.copy(recurringCreated = running.recurringCreated + 1)
    }

    private suspend fun importTransaction(
        candidate: ImportCandidate,
        outcome: ImportOutcome,
    ): ImportOutcome {
        var running = outcome
        val (personId, afterPerson) = resolvePerson(candidate.personName, running)
        running = afterPerson
        val (accountId, afterAccount) = resolveAccount(candidate.accountName, personId, running)
        running = afterAccount
        val (categoryId, afterCategory) =
            resolveCategory(candidate.categoryName, TransactionType.EXPENSE, running)
        running = afterCategory

        transactionDao.insert(
            TransactionEntity(
                amountMinor = candidate.amountMinor,
                type = TransactionType.EXPENSE,
                date = candidate.dateIso?.let { DateUtils.parseIsoOrNull(it) } ?: DateUtils.today(),
                description = candidate.name,
                accountId = accountId,
                categoryId = categoryId,
                personId = personId,
                notes = candidate.notes,
            ),
        )
        return running.copy(transactionsCreated = running.transactionsCreated + 1)
    }

    // ------------------------------------------------------------ resolvers

    private suspend fun resolvePerson(
        name: String?,
        outcome: ImportOutcome,
    ): Pair<Long?, ImportOutcome> {
        if (name.isNullOrBlank()) return null to outcome
        personDao.getByName(name)?.let { return it.id to outcome }
        val id = personDao.insert(
            PersonEntity(name = name.trim(), colorHex = DefaultData.PERSON_COLORS.random()),
        )
        return id to outcome.copy(peopleCreated = outcome.peopleCreated + 1)
    }

    /**
     * Finds or creates the account a row belongs to.  When the sheet names no
     * account — the usual case for a household budget — the person's first
     * account is used, and one is created for them if they have none, because
     * every transaction must live somewhere.
     */
    private suspend fun resolveAccount(
        name: String?,
        personId: Long?,
        outcome: ImportOutcome,
    ): Pair<Long, ImportOutcome> {
        if (!name.isNullOrBlank()) {
            accountDao.getByName(name)?.let { return it.id to outcome }
            val id = accountDao.insert(
                AccountEntity(
                    name = name.trim(),
                    type = guessAccountType(name),
                    personId = personId,
                    colorHex = DefaultData.PALETTE.random(),
                ),
            )
            return id to outcome.copy(accountsCreated = outcome.accountsCreated + 1)
        }

        if (personId != null) {
            accountDao.getAllActive().firstOrNull { it.personId == personId }
                ?.let { return it.id to outcome }
        }
        accountDao.getAllActive().firstOrNull()?.let { return it.id to outcome }

        val id = accountDao.insert(
            AccountEntity(
                name = "Main account",
                type = AccountType.CURRENT,
                personId = personId,
                colorHex = DefaultData.PALETTE.first(),
            ),
        )
        return id to outcome.copy(accountsCreated = outcome.accountsCreated + 1)
    }

    private suspend fun resolveCategory(
        name: String?,
        type: TransactionType,
        outcome: ImportOutcome,
    ): Pair<Long?, ImportOutcome> {
        if (name.isNullOrBlank()) return null to outcome
        val kind = if (type == TransactionType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
        categoryDao.getByNameAndKind(name, kind)?.let { return it.id to outcome }
        val created = categoryRepository.findOrCreate(name, kind, DefaultData.PALETTE.random())
        return created.id to outcome.copy(categoriesCreated = outcome.categoriesCreated + 1)
    }

    // -------------------------------------------------------------- helpers

    /** Reads the date formats a UK spreadsheet is likely to contain. */
    internal fun parseDate(raw: String): LocalDate? {
        val text = raw.trim()
        if (text.isBlank()) return null
        DateUtils.parseIsoOrNull(text)?.let { return it }

        val parts = text.split('/', '-', '.')
        if (parts.size != 3) return null
        val numbers = parts.mapNotNull { it.trim().toIntOrNull() }
        if (numbers.size != 3) return null

        val (first, second, third) = numbers
        // A four-digit first number is a year; otherwise assume day/month/year,
        // which is what a UK spreadsheet will hold.
        val (year, month, day) = when {
            parts[0].length == 4 -> Triple(first, second, third)
            third < 100 -> Triple(2000 + third, second, first)
            else -> Triple(third, second, first)
        }
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }

    /** Maps the words people use to the app's frequency names. */
    internal fun normaliseFrequency(raw: String): String {
        val text = raw.trim().lowercase()
        return when {
            text.isBlank() -> Frequency.MONTHLY.name
            text.contains("fortnight") || text.contains("2 week") ||
                text.contains("two week") -> Frequency.FORTNIGHTLY.name
            text.contains("4 week") || text.contains("four week") -> Frequency.FOUR_WEEKLY.name
            text.contains("week") -> Frequency.WEEKLY.name
            text.contains("quarter") || text.contains("3 month") -> Frequency.QUARTERLY.name
            text.contains("6 month") || text.contains("half") -> Frequency.HALF_YEARLY.name
            text.contains("year") || text.contains("annual") -> Frequency.YEARLY.name
            text.contains("day") || text.contains("daily") -> Frequency.DAILY.name
            text.contains("once") || text.contains("one") -> Frequency.ONE_OFF.name
            else -> Frequency.MONTHLY.name
        }
    }

    /** Picks a sensible account type from the name the sheet uses. */
    internal fun guessAccountType(name: String): AccountType {
        val text = name.lowercase()
        return when {
            text.contains("cash") || text.contains("coin") || text.contains("wallet") ->
                AccountType.CASH
            text.contains("credit") || text.contains("card") -> AccountType.CREDIT_CARD
            text.contains("mortgage") -> AccountType.MORTGAGE
            text.contains("loan") || text.contains("finance") -> AccountType.LOAN
            text.contains("isa") || text.contains("invest") || text.contains("share") ->
                AccountType.INVESTMENT
            text.contains("pension") -> AccountType.PENSION
            text.contains("saver") || text.contains("saving") -> AccountType.SAVINGS
            else -> AccountType.CURRENT
        }
    }
}
