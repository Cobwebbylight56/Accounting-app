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

                    name.endsWith(".pdf", ignoreCase = true) ->
                        PdfStatementReader.read(input, name)

                    name.endsWith(".xls", ignoreCase = true) ->
                        error(
                            "That is an older .xls workbook. Open it in Excel or Google " +
                                "Sheets and save it as .xlsx or .csv first.",
                        )

                    else -> error("Choose a .pdf, .csv or .xlsx file")
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
     * Looks for the household layout — a column of figures per person, in
     * blocks — and returns everything needed to import the whole sheet at once.
     * Returns null when the sheet is not that shape, in which case the user
     * maps the columns by hand as before.
     */
    fun detectHouseholdLayout(sheet: SheetData): DetectedLayout? =
        HouseholdLayoutDetector.detect(sheet)?.takeIf { it.isUsable }

    /**
     * The mapping for a downloaded bank statement, or null when the sheet is
     * not one. [accountName] is the account the rows will be filed against.
     */
    fun detectStatement(sheet: SheetData, accountName: String? = null): ImportMapping? =
        StatementDetector.detect(sheet, accountName)

    /**
     * Builds the candidates for every person and every block in one pass.
     *
     * Rows that fail to read are kept and flagged rather than dropped, so the
     * preview shows the whole sheet and nothing disappears silently.
     */
    fun buildCandidatesForLayout(
        sheet: SheetData,
        layout: DetectedLayout,
    ): List<ImportCandidate> =
        HouseholdLayoutDetector.mappingsFor(layout)
            .flatMap { mapping -> buildCandidates(sheet, mapping) }
            // The same row appears once per person; keep them together so the
            // review list reads down the sheet rather than by person.
            .sortedWith(compareBy({ it.sourceRow }, { it.sourceColumn }))

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
        val moneyInColumn = columnFor(ColumnRole.MONEY_IN)
        val moneyOutColumn = columnFor(ColumnRole.MONEY_OUT)

        val range = mapping.firstDataRow..mapping.lastDataRow.coerceAtMost(sheet.rowCount - 1)

        return range.mapNotNull { row ->
            val name = nameColumn?.let { sheet.cell(row, it) }?.trim().orEmpty()

            // A statement puts the direction in which column the figure is in;
            // everything else carries it in the sign, or not at all.
            val statementAmount = readStatementAmount(sheet, row, moneyInColumn, moneyOutColumn)
            val amountColumn = statementAmount?.column
                ?: amountColumns.firstOrNull { sheet.cell(row, it).isNotBlank() }
            val amountText = statementAmount?.text
                ?: amountColumn?.let { sheet.cell(row, it) }.orEmpty()
            val signedAmount = statementAmount?.minor ?: Money.parseOrNull(amountText)

            // A row with neither a name nor a figure is a spacer, not data.
            if (name.isBlank() && signedAmount == null) return@mapNotNull null

            // Amounts are stored positive with the direction held separately,
            // so a signed figure is split into the two here.
            val directionFromSign = when {
                statementAmount != null -> statementAmount.type
                mapping.amountSignIsDirection && signedAmount != null ->
                    if (signedAmount < 0L) TransactionType.EXPENSE else TransactionType.INCOME
                else -> null
            }
            val amount = when {
                statementAmount != null -> statementAmount.minor
                mapping.amountSignIsDirection -> signedAmount?.let { kotlin.math.abs(it) }
                else -> signedAmount
            }

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
                sourceColumn = amountColumn ?: -1,
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
                transactionType = directionFromSign,
                problem = problem,
                isSelected = problem == null,
            )
        }
    }

    /**
     * Candidates with the ones already in the ledger marked and deselected.
     *
     * Statements overlap, so this is what makes importing "the last three
     * months" every month safe. Rows already held are still shown — seeing
     * that 58 of 71 rows were already there is how you know the import
     * worked, whereas silently dropping them looks like a failure.
     */
    suspend fun buildCandidatesWithDuplicates(
        sheet: SheetData,
        mapping: ImportMapping,
    ): List<ImportCandidate> = withContext(ioDispatcher) {
        val candidates = categorise(buildCandidates(sheet, mapping))
        markDuplicates(candidates, mapping.defaultAccountName)
    }

    /**
     * Fills in a category for rows that arrived without one.
     *
     * A category the sheet stated is never overwritten — it was explicit, and
     * a guess should not argue with it.
     */
    internal suspend fun categorise(candidates: List<ImportCandidate>): List<ImportCandidate> {
        if (candidates.none { it.categoryName.isNullOrBlank() }) return candidates

        val learned = transactionDao.getCategorisedDescriptions(LEARNED_PAYEE_LIMIT)
            .associate { TransactionFingerprint.normaliseDescription(it.description) to it.categoryName }

        return candidates.map { candidate ->
            if (!candidate.categoryName.isNullOrBlank()) return@map candidate
            val category = MerchantCategoriser.categoryFor(
                description = candidate.name,
                type = candidate.transactionType ?: TransactionType.EXPENSE,
                learned = learned,
            )
            if (category == null) candidate else candidate.copy(categoryName = category)
        }
    }

    /**
     * Marks candidates that the ledger already holds.
     *
     * Occurrences are counted on both sides rather than matched one at a time:
     * if the file holds three identical rows and two are stored, the third is
     * genuinely new and stays selected.
     */
    internal suspend fun markDuplicates(
        candidates: List<ImportCandidate>,
        accountName: String?,
    ): List<ImportCandidate> {
        // Without a known account there is nothing to compare against: an
        // account about to be created cannot already hold anything.
        val accountId = accountName?.takeIf { it.isNotBlank() }
            ?.let { accountDao.getByName(it) }?.id
            ?: return candidates

        val fingerprints = candidates.map { candidate ->
            if (!candidate.isImportable || candidate.target != ImportTarget.TRANSACTION) {
                null
            } else {
                TransactionFingerprint.of(
                    accountId = accountId,
                    date = candidate.dateIso?.let { DateUtils.parseIsoOrNull(it) }
                        ?: return@map null,
                    amountMinor = candidate.amountMinor,
                    type = candidate.transactionType ?: TransactionType.EXPENSE,
                    description = candidate.name,
                )
            }
        }

        val distinct = fingerprints.filterNotNull().distinct()
        if (distinct.isEmpty()) return candidates

        // IN () has a limit, so ask in batches.
        val stored = mutableMapOf<String, Int>()
        distinct.chunked(FINGERPRINT_BATCH).forEach { batch ->
            transactionDao.countByFingerprint(batch).forEach { row ->
                stored[row.hash] = row.occurrences
            }
        }

        val remaining = stored.toMutableMap()
        return candidates.mapIndexed { index, candidate ->
            val fingerprint = fingerprints[index] ?: return@mapIndexed candidate
            val left = remaining[fingerprint] ?: 0
            if (left <= 0) {
                candidate
            } else {
                remaining[fingerprint] = left - 1
                candidate.copy(isAlreadyPresent = true, isSelected = false)
            }
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
                if (candidate.isAlreadyPresent) {
                    outcome = outcome.copy(duplicatesSkipped = outcome.duplicatesSkipped + 1)
                    continue
                }
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
        // A statement is filed against the account the user picked. Names
        // are unique per person now, so a name alone would be ambiguous.
        val accountId: Long
        if (candidate.accountId != null) {
            accountId = candidate.accountId
        } else {
            val (resolved, afterAccount) = resolveAccount(candidate.accountName, personId, running)
            accountId = resolved
            running = afterAccount
        }
        // A statement says which way the money went; a list of bills does not,
        // and there everything is money going out.
        val type = candidate.transactionType ?: TransactionType.EXPENSE
        val (categoryId, afterCategory) = resolveCategory(candidate.categoryName, type, running)
        running = afterCategory

        val date = candidate.dateIso?.let { DateUtils.parseIsoOrNull(it) } ?: DateUtils.today()

        transactionDao.insert(
            TransactionEntity(
                amountMinor = candidate.amountMinor,
                type = type,
                date = date,
                description = candidate.name,
                accountId = accountId,
                categoryId = categoryId,
                personId = personId,
                notes = candidate.notes,
                // Stamped now so the next statement covering this period
                // recognises the row instead of adding it again.
                importHash = TransactionFingerprint.of(
                    accountId = accountId,
                    date = date,
                    amountMinor = candidate.amountMinor,
                    type = type,
                    description = candidate.name,
                ),
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
            // The owner's account first, since names are unique per person and
            // "Main account" may well exist for more than one of them. Falling
            // back to the name alone keeps sheets that never say who owns what
            // working as they did.
            accountDao.getByNameForPerson(name, personId)?.let { return it.id to outcome }
            if (personId == null) {
                accountDao.getByName(name)?.let { return it.id to outcome }
            }
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

    /** A figure read from a statement, with the direction its column implies. */
    private data class StatementAmount(
        val column: Int,
        val text: String,
        val minor: Long,
        val type: TransactionType,
    )

    /**
     * Reads a row's "paid out" / "paid in" pair.
     *
     * Statements leave the unused side blank, so whichever column holds a
     * figure decides the direction. Banks also write the paid-out column as a
     * negative on occasion, so the magnitude is taken either way. Returns null
     * when this is not a two-column statement or the row is blank in both.
     */
    private fun readStatementAmount(
        sheet: SheetData,
        row: Int,
        moneyInColumn: Int?,
        moneyOutColumn: Int?,
    ): StatementAmount? {
        if (moneyInColumn == null && moneyOutColumn == null) return null

        moneyOutColumn?.let { column ->
            val text = sheet.cell(row, column).trim()
            val minor = Money.parseOrNull(text)
            if (minor != null && minor != 0L) {
                return StatementAmount(column, text, kotlin.math.abs(minor), TransactionType.EXPENSE)
            }
        }
        moneyInColumn?.let { column ->
            val text = sheet.cell(row, column).trim()
            val minor = Money.parseOrNull(text)
            if (minor != null && minor != 0L) {
                return StatementAmount(column, text, kotlin.math.abs(minor), TransactionType.INCOME)
            }
        }
        return null
    }

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

    private companion object {
        /**
         * How many fingerprints to ask about at once. SQLite caps the
         * number of bound parameters in an IN clause, and a statement can
         * easily carry more rows than that.
         */
        const val FINGERPRINT_BATCH = 400

        /**
         * How many previously filed payees to learn from. Beyond this the tail
         * is one-off payments that will never be seen again.
         */
        const val LEARNED_PAYEE_LIMIT = 2_000
    }
}
