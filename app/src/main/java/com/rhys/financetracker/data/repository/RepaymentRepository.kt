package com.rhys.financetracker.data.repository

import androidx.room.withTransaction
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.importer.RepaymentPairing
import com.rhys.financetracker.data.importer.SpreadsheetImporter
import com.rhys.financetracker.data.local.AppDatabase
import com.rhys.financetracker.data.local.dao.AccountDao
import com.rhys.financetracker.data.local.dao.CategoryDao
import com.rhys.financetracker.data.local.dao.TransactionDao
import com.rhys.financetracker.data.local.entity.TransactionEntity
import com.rhys.financetracker.domain.model.Holding
import com.rhys.financetracker.domain.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Makes a card or loan payment held twice — once from each statement —
 * count once, as the move from the bank to the card. See [RepaymentPairing].
 *
 * Run after every import and whenever Home opens, so it doesn't matter
 * which statement came in first.
 */
@Singleton
class RepaymentRepository @Inject constructor(
    private val database: AppDatabase,
    private val transactionDao: TransactionDao,
    private val accountDao: AccountDao,
    private val categoryDao: CategoryDao,
    private val importer: SpreadsheetImporter,
) {

    /** Puts right every payment held twice; returns how many. */
    suspend fun countOnce(today: LocalDate = DateUtils.today()): Int {
        val accounts = accountDao.getAllActive()
        val owed = accounts.filter { it.holding == Holding.OWED }
            .map { RepaymentPairing.Owed(it.id, it.name, it.openingBalanceDate) }
        if (owed.isEmpty()) return 0
        val now = Instant.now().toEpochMilli()
        bringBackUnpaired(owed.map { it.id }.toSet(), now)

        val categories = categoryDao.getAll().associate { it.id to it.name }
        val rows = transactionDao.liveSince(today.minusDays(LOOK_BACK_DAYS))
        val entries = rows.map { row ->
            RepaymentPairing.Entry(
                id = row.id,
                accountId = row.accountId,
                type = row.type,
                transferAccountId = row.transferAccountId,
                amountMinor = row.amountMinor,
                date = row.date,
                description = row.description,
                source = row.source,
                categoryName = row.categoryId?.let { categories[it] },
                categoryByUser = row.categoryByUser,
            )
        }
        val payFrom = accounts.filter { it.holding != Holding.OWED }
            .associate { account -> account.id to importer.ownAccountTargets(account.id).map { it.id }.toSet() }
        val mayPayFrom = { from: Long, to: Long -> payFrom[from]?.contains(to) == true }
        val byId = rows.associateBy { it.id }

        // A payment that names one of the cards or loans pays it, whether or
        // not the card's statement is in yet: a move, not spending.
        val named = RepaymentPairing.namedRepayments(owed, entries, mayPayFrom)
        val moved = named.mapNotNull { pair ->
            byId[pair.expenseId]?.let { row ->
                TidyUpRepository.asTransfer(row, pair.owedId, now).copy(
                    notes = listOfNotNull(row.notes?.takeIf { it.isNotBlank() }, NAMED_NOTE).joinToString(" "),
                )
            }
        }
        if (moved.isNotEmpty()) database.withTransaction { moved.forEach { transactionDao.update(it) } }
        val movedIds = moved.map { it.id }.toSet()
        val afterMoves = entries.map { entry ->
            if (entry.id !in movedIds) return@map entry
            val pair = named.first { it.expenseId == entry.id }
            entry.copy(type = TransactionType.TRANSFER, transferAccountId = pair.owedId, categoryName = null)
        }

        val steps = RepaymentPairing.find(owed, afterMoves, mayPayFrom)
        if (steps.isEmpty()) return moved.size

        database.withTransaction {
            steps.forEach { step ->
                when (step) {
                    is RepaymentPairing.Step.AlreadyMoved -> byId[step.incomeId]?.let { putAway(it, now) }
                    is RepaymentPairing.Step.Paired -> {
                        val income = byId[step.incomeId] ?: return@forEach
                        val expense = byId[step.expenseId] ?: return@forEach
                        transactionDao.update(TidyUpRepository.asTransfer(expense, step.owedId, now))
                        putAway(income, now)
                    }
                }
            }
        }
        return moved.size + steps.size
    }

    /** Money in on the card, now counted as the move into it instead. */
    private suspend fun putAway(income: TransactionEntity, now: Long) {
        val notes = listOfNotNull(income.notes?.takeIf { it.isNotBlank() }, RepaymentPairing.NOTE).joinToString(" ")
        transactionDao.update(income.copy(isArchived = true, notes = notes, updatedAt = now))
    }

    /**
     * Money in put away when it was paired, whose move has since gone —
     * the bank statement's import undone — comes back, so the card's
     * balance still has it.
     */
    private suspend fun bringBackUnpaired(owedIds: Set<Long>, now: Long) {
        val margin = RepaymentPairing.MARGIN_DAYS
        transactionDao.putAwayWithNote(RepaymentPairing.NOTE)
            .filter { it.accountId in owedIds }
            .forEach { income ->
                val moved = transactionDao.transfersTouching(income.accountId, income.date.minusDays(margin), income.date.plusDays(margin))
                    .any { it.transferAccountId == income.accountId && it.amountMinor == income.amountMinor && it.type == TransactionType.TRANSFER }
                if (!moved) {
                    val notes = income.notes?.replace(RepaymentPairing.NOTE, "")?.trim()?.ifBlank { null }
                    transactionDao.update(income.copy(isArchived = false, notes = notes, updatedAt = now))
                }
            }
    }

    private companion object {
        /** A little over a year of statements. */
        const val LOOK_BACK_DAYS = 400L

        /** On a payment out that names a card or loan, once it is counted as paying it. */
        const val NAMED_NOTE = "Pays off one of your cards or loans, so it isn't counted as spending."
    }
}
