package com.rhys.financetracker.data.repository

import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.core.result.runCatchingApp
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.importer.RecurringDetector
import com.rhys.financetracker.data.local.dao.AccountDao
import com.rhys.financetracker.data.local.dao.CategoryDao
import com.rhys.financetracker.data.local.dao.RecurringRuleDao
import com.rhys.financetracker.data.local.dao.TransactionDao
import com.rhys.financetracker.data.local.entity.RecurringRuleEntity
import com.rhys.financetracker.domain.model.CategoryKind
import com.rhys.financetracker.domain.model.RecurrenceMode
import com.rhys.financetracker.domain.model.TransactionType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds the bills in what has been imported, and sets up the ones chosen.
 *
 * See [RecurringDetector] for what counts as a bill.
 */
@Singleton
class BillFinderRepository @Inject constructor(
    private val transactionDao: TransactionDao,
    private val recurringRuleDao: RecurringRuleDao,
    private val categoryDao: CategoryDao,
    private val accountDao: AccountDao,
) {

    /** Regular payments on [accountId] (every account when null) not yet set up as bills. */
    suspend fun find(accountId: Long? = null): List<RecurringDetector.RegularPayment> {
        val since = DateUtils.today().minusDays(LOOK_BACK_DAYS)
        val payments = transactionDao.paymentsOutSince(since, accountId).map {
            RecurringDetector.Payment(
                description = it.description,
                amountMinor = it.amountMinor,
                date = it.date,
                categoryName = it.categoryName,
                accountId = it.accountId,
            )
        }
        val known = recurringRuleDao.getAll()
            .filterNot { it.isArchived }
            .map { RecurringDetector.KnownBill(it.name, it.amountMinor, it.accountId) }
        return RecurringDetector.find(payments, known)
    }

    /**
     * Sets up each of [bills] as a regular payment from its account.
     *
     * A bill whose amount moves about asks to be confirmed each time; a fixed
     * one is added by itself on its day. Either way, when the statement for
     * that month is imported it corrects the entry rather than adding a
     * second one.
     */
    suspend fun addAsBills(bills: List<RecurringDetector.RegularPayment>): AppResult<Int> =
        runCatchingApp("Could not add those bills") {
            val today = DateUtils.today()
            bills.forEach { bill ->
                val next = bill.nextDue(today)
                val category = bill.categoryName?.let {
                    categoryDao.getByNameAndKind(it, CategoryKind.EXPENSE)
                }
                recurringRuleDao.insert(
                    RecurringRuleEntity(
                        name = bill.name,
                        amountMinor = bill.amountMinor,
                        type = TransactionType.EXPENSE,
                        frequency = bill.frequency,
                        startDate = next,
                        nextDueDate = next,
                        accountId = bill.accountId,
                        categoryId = category?.id,
                        personId = accountDao.getById(bill.accountId)?.personId,
                        mode = if (bill.isVariable) RecurrenceMode.CONFIRM else RecurrenceMode.AUTO_POST,
                        isVariableAmount = bill.isVariable,
                        notes = "Found in a statement: ${bill.reason.lowercase()}.",
                    ),
                )
            }
            bills.size
        }

    private companion object {
        /** A little over a year, so a yearly bill can be seen twice. */
        const val LOOK_BACK_DAYS = 400L
    }
}
