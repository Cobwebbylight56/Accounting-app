package com.rhys.financetracker.data.repository

import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.core.result.runCatchingApp
import com.rhys.financetracker.core.validation.Validators
import com.rhys.financetracker.data.local.dao.TransactionDao
import com.rhys.financetracker.data.local.dao.TransactionFilter
import com.rhys.financetracker.data.local.dao.TransactionQuery
import com.rhys.financetracker.data.local.entity.TransactionEntity
import com.rhys.financetracker.data.local.projection.AccountActivity
import com.rhys.financetracker.data.local.projection.CategoryTotal
import com.rhys.financetracker.data.local.projection.IncomeExpenseTotals
import com.rhys.financetracker.data.local.projection.MonthTotals
import com.rhys.financetracker.data.local.projection.PersonTotals
import com.rhys.financetracker.data.local.projection.TransactionWithDetails
import com.rhys.financetracker.domain.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class TransactionRepository @Inject constructor(
    private val transactionDao: TransactionDao,
) {

    fun observeRecent(limit: Int = 10): Flow<List<TransactionWithDetails>> =
        transactionDao.observeRecent(limit)

    fun observeBetween(start: LocalDate, end: LocalDate): Flow<List<TransactionWithDetails>> =
        transactionDao.observeBetween(start, end)

    fun observeForAccount(accountId: Long): Flow<List<TransactionWithDetails>> =
        transactionDao.observeForAccount(accountId)

    fun observe(id: Long): Flow<TransactionWithDetails?> = transactionDao.observeDetailsById(id)

    fun observeUnconfirmed(): Flow<List<TransactionWithDetails>> = transactionDao.observeUnconfirmed()

    /** Drives the search screen; see [TransactionQuery] for how the SQL is built. */
    fun search(filter: TransactionFilter): Flow<List<TransactionWithDetails>> =
        transactionDao.searchRaw(TransactionQuery.build(filter))

    suspend fun searchOnce(filter: TransactionFilter): List<TransactionWithDetails> =
        transactionDao.searchRawOnce(TransactionQuery.build(filter))

    fun observeIncomeExpense(
        start: LocalDate,
        end: LocalDate,
        accountId: Long? = null,
        personId: Long? = null,
    ): Flow<IncomeExpenseTotals> =
        transactionDao.observeIncomeExpense(start, end, accountId, personId)
            .map { it ?: IncomeExpenseTotals.EMPTY }

    fun observeCategoryTotals(
        type: TransactionType,
        start: LocalDate,
        end: LocalDate,
        accountId: Long? = null,
        personId: Long? = null,
    ): Flow<List<CategoryTotal>> =
        transactionDao.observeCategoryTotals(type.name, start, end, accountId, personId)

    /** Money in and out of every account over a period, one row per account. */
    fun observeAccountActivity(start: LocalDate, end: LocalDate): Flow<List<AccountActivity>> =
        transactionDao.observeAccountActivity(start, end)

    fun observeMonthlyTotals(
        start: LocalDate,
        end: LocalDate,
        accountId: Long? = null,
        personId: Long? = null,
    ): Flow<List<MonthTotals>> =
        transactionDao.observeMonthlyTotals(start, end, accountId, personId)

    fun observePersonTotals(start: LocalDate, end: LocalDate): Flow<List<PersonTotals>> =
        transactionDao.observePersonTotals(start, end)

    suspend fun get(id: Long): TransactionEntity? = transactionDao.getById(id)

    suspend fun save(transaction: TransactionEntity): AppResult<Long> =
        runCatchingApp("Could not save this transaction") {
            validate(transaction)
            if (transaction.id == 0L) {
                transactionDao.insert(transaction)
            } else {
                transactionDao.update(transaction.copy(updatedAt = Instant.now().toEpochMilli()))
                transaction.id
            }
        }

    /** Bulk insert used by the importer and the restore, inside one transaction. */
    suspend fun saveAll(transactions: List<TransactionEntity>): AppResult<Int> =
        runCatchingApp("Could not save the transactions") {
            transactionDao.insertAll(transactions).size
        }

    /** Copies an entry onto today's date, for a payment that repeats irregularly. */
    suspend fun duplicate(id: Long, onDate: LocalDate = LocalDate.now()): AppResult<Long> =
        runCatchingApp("Could not duplicate this transaction") {
            val original = transactionDao.getById(id) ?: error("That transaction no longer exists")
            transactionDao.insert(
                original.copy(
                    id = 0L,
                    date = onDate,
                    // A copy is never treated as an instance of the original rule.
                    recurringRuleId = null,
                    createdAt = Instant.now().toEpochMilli(),
                    updatedAt = Instant.now().toEpochMilli(),
                ),
            )
        }

    suspend fun setArchived(id: Long, archived: Boolean): AppResult<Unit> =
        runCatchingApp("Could not archive this transaction") {
            transactionDao.setArchived(id, archived, Instant.now().toEpochMilli())
        }

    suspend fun confirm(id: Long): AppResult<Unit> =
        runCatchingApp("Could not confirm this transaction") {
            transactionDao.confirm(id, Instant.now().toEpochMilli())
        }

    suspend fun delete(transaction: TransactionEntity): AppResult<Unit> =
        runCatchingApp("Could not delete this transaction") {
            transactionDao.delete(transaction)
        }

    suspend fun count(): Int = transactionDao.count()

    suspend fun earliestDate(): LocalDate? = transactionDao.earliestDate()

    /**
     * Rules that keep the ledger honest.  They run on every write, so a bad row
     * cannot enter the database from a form, an import or a restore.
     */
    private fun validate(transaction: TransactionEntity) {
        Validators.validateName(transaction.description, "Description").errorOrNull
            ?.let { error(it) }
        Validators.validateNotes(transaction.notes).errorOrNull?.let { error(it) }
        Validators.validateDate(transaction.date).errorOrNull?.let { error(it) }
        if (transaction.amountMinor < 0L) {
            error("Amounts are stored as positive numbers; choose Income or Expense instead")
        }
        if (transaction.amountMinor == 0L) error("Enter an amount")
        if (transaction.type == TransactionType.TRANSFER) {
            val destination = transaction.transferAccountId
                ?: error("Choose the account the money is going to")
            if (destination == transaction.accountId) {
                error("A transfer must be between two different accounts")
            }
        }
    }
}
