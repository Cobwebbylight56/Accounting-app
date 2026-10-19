package com.rhys.financetracker.data.repository

import androidx.room.withTransaction
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.importer.AccountNaming
import com.rhys.financetracker.data.importer.CardSpendingCheck
import com.rhys.financetracker.data.importer.CardStatementJunk
import com.rhys.financetracker.data.importer.MerchantCategoriser
import com.rhys.financetracker.data.importer.PdfStatementParser
import com.rhys.financetracker.data.importer.RepaymentPairing
import com.rhys.financetracker.data.importer.RepeatedHeadingsReader
import com.rhys.financetracker.data.importer.TransactionFingerprint
import com.rhys.financetracker.data.local.AppDatabase
import com.rhys.financetracker.data.local.dao.AccountDao
import com.rhys.financetracker.data.local.dao.CategoryDao
import com.rhys.financetracker.data.local.dao.RecurringRuleDao
import com.rhys.financetracker.data.local.dao.TransactionDao
import com.rhys.financetracker.data.local.entity.TransactionEntity
import com.rhys.financetracker.domain.model.CategoryKind
import com.rhys.financetracker.domain.model.Holding
import com.rhys.financetracker.domain.model.RecordSource
import com.rhys.financetracker.domain.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puts right what older versions read wrongly from statements already
 * imported, so nothing has to be undone and imported again by hand:
 *
 * * **A date in the future.** A statement of December's payments, printed
 *   in January, names only the new year, and "15 Dec" came out as next
 *   December. A statement can't hold a payment that hasn't happened yet:
 *   it was the year before.
 * * **Card spending read as money in.** See [CardSpendingCheck].
 * * **A card's regular payment called a direct debit.** A card can't pay a
 *   direct debit or a standing order; it's a card payment.
 * * **A card statement's own writing taken for payments** — "New
 *   transactions", "Dear Mr …" — put away; see [CardStatementJunk].
 * * **Paying a card off read as spending on it** — "Direct Debit Payment -
 *   Thank You" as money out — turned round to money in.
 * * **A card, pay-later plan, loan or mortgage set up as an ordinary
 *   account** — "Capital One", "Klarna", "Car loan" counted as money to
 *   spend — put in its place: what's on it is owed. See
 *   [AccountNaming.owedTypeFor].
 * * **A Lloyds statement with its headings over every payment** — the
 *   summary at the top ("Money In. . Balance on . .") put away, and the
 *   jumbled heading scraps taken out of a payee, so the same payment
 *   imported again is known to be there already. See [RepeatedHeadingsReader].
 *
 * Run after every import and whenever Home opens.
 */
@Singleton
class StatementRepairRepository @Inject constructor(
    private val database: AppDatabase,
    private val transactionDao: TransactionDao,
    private val accountDao: AccountDao,
    private val categoryDao: CategoryDao,
    private val recurringRuleDao: RecurringRuleDao,
) {

    /** Puts right whatever needs it; returns how many entries changed. */
    suspend fun putRight(today: LocalDate = DateUtils.today()): Int =
        owedAccounts() + backFromTheFuture(today) + cardStatementText(today) + cardSpendingReadAsIn(today) + repeatedHeadings(today) +
            recurringRuleDao.cardPaymentsAsCard(Instant.now().toEpochMilli())

    /** On cards: the statement's own lines put away, and paying it off turned to money in. */
    private suspend fun cardStatementText(today: LocalDate): Int {
        val cards = accountDao.getAllActive().filter { it.holding == Holding.OWED }.map { it.id }.toSet()
        if (cards.isEmpty()) return 0
        val rows = transactionDao.liveSince(today.minusDays(LOOK_BACK_DAYS))
            .filter { it.accountId in cards && it.source == RecordSource.STATEMENT && it.type != TransactionType.TRANSFER }
        val junk = rows.filter { CardStatementJunk.isJunk(it.description) }
        val paidOff = rows.filter { row ->
            row.type == TransactionType.EXPENSE && row !in junk &&
                PdfStatementParser.cardDirectionFromWording(row.description) == false
        }
        if (junk.isEmpty() && paidOff.isEmpty()) return 0
        val transfers = categoryDao.getByNameAndKind(MerchantCategoriser.PEOPLE_IN, CategoryKind.TRANSFER)
        val now = Instant.now().toEpochMilli()
        database.withTransaction {
            junk.forEach { row ->
                transactionDao.update(row.copy(isArchived = true, notes = noted(row.notes, NOT_A_PAYMENT), updatedAt = now))
            }
            paidOff.forEach { row ->
                transactionDao.update(
                    row.copy(
                        type = TransactionType.INCOME,
                        categoryId = transfers?.id ?: row.categoryId,
                        importHash = row.importHash?.let {
                            TransactionFingerprint.of(row.accountId, row.date, row.amountMinor, TransactionType.INCOME, row.description)
                        },
                        notes = noted(row.notes, PAID_OFF),
                        updatedAt = now,
                    ),
                )
            }
        }
        return junk.size + paidOff.size
    }

    private suspend fun owedAccounts(): Int {
        val now = Instant.now().toEpochMilli()
        val wrong = accountDao.getAllActive().mapNotNull { account ->
            if (account.holding == Holding.OWED) return@mapNotNull null
            val type = AccountNaming.owedTypeFor(account.name) ?: return@mapNotNull null
            account.copy(
                type = type,
                holding = Holding.OWED,
                notes = listOfNotNull(account.notes?.takeIf { it.isNotBlank() }, OWED_NOTE).joinToString(" "),
                updatedAt = now,
            )
        }
        wrong.forEach { accountDao.update(it) }
        return wrong.size
    }

    private suspend fun repeatedHeadings(today: LocalDate): Int {
        val rows = transactionDao.liveSince(today.minusDays(LOOK_BACK_DAYS)).filter { it.source == RecordSource.STATEMENT }
        val summary = rows.filter { RepeatedHeadingsReader.isSummary(it.description) }
        val jumbled = rows.filter { it !in summary }.mapNotNull { row ->
            RepeatedHeadingsReader.tidy(row.description)?.takeIf { it != row.description }?.let { row to it }
        }
        if (summary.isEmpty() && jumbled.isEmpty()) return 0
        val now = Instant.now().toEpochMilli()
        database.withTransaction {
            summary.forEach { row ->
                transactionDao.update(row.copy(isArchived = true, notes = noted(row.notes, NOT_A_PAYMENT), updatedAt = now))
            }
            jumbled.forEach { (row, description) ->
                transactionDao.update(
                    row.copy(
                        description = description,
                        importHash = row.importHash?.let {
                            TransactionFingerprint.of(row.accountId, row.date, row.amountMinor, row.type, description)
                        },
                        updatedAt = now,
                    ),
                )
            }
        }
        return summary.size + jumbled.size
    }

    private suspend fun backFromTheFuture(today: LocalDate): Int {
        val ahead = transactionDao.statementRowsAfter(today.plusDays(1))
        if (ahead.isEmpty()) return 0
        val now = Instant.now().toEpochMilli()
        database.withTransaction {
            ahead.forEach { row ->
                var date = row.date
                while (date.isAfter(today.plusDays(1))) date = date.minusYears(1)
                val hash = TransactionFingerprint.of(row.accountId, date, row.amountMinor, row.type, row.description)
                // Imported again since, with the year right: it's already here.
                val already = transactionDao.countByFingerprint(listOf(hash)).sumOf { it.occurrences } > 0
                transactionDao.update(
                    if (already) {
                        row.copy(isArchived = true, notes = noted(row.notes, ALREADY_HERE), updatedAt = now)
                    } else {
                        row.copy(date = date, importHash = hash, updatedAt = now)
                    },
                )
            }
        }
        return ahead.size
    }

    private suspend fun cardSpendingReadAsIn(today: LocalDate): Int {
        val cards = accountDao.getAllActive().filter { it.holding == Holding.OWED }.map { it.id }.toSet()
        if (cards.isEmpty()) return 0
        val categories = categoryDao.getAll().associate { it.id to it.name }
        val rows = transactionDao.liveSince(today.minusDays(LOOK_BACK_DAYS)).filter { it.accountId in cards }
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
        val misread = CardSpendingCheck.find(entries, cards)
        if (misread.isEmpty()) return 0
        val byId = rows.associateBy { it.id }
        val now = Instant.now().toEpochMilli()
        database.withTransaction {
            misread.forEach { found ->
                val row = byId[found.id] ?: return@forEach
                val like = byId[found.likeId]
                transactionDao.update(
                    row.copy(
                        type = TransactionType.EXPENSE,
                        categoryId = like?.categoryId,
                        importHash = row.importHash?.let {
                            TransactionFingerprint.of(row.accountId, row.date, row.amountMinor, TransactionType.EXPENSE, row.description)
                        },
                        notes = noted(row.notes, READ_AS_IN),
                        updatedAt = now,
                    ),
                )
            }
        }
        return misread.size
    }

    private fun noted(notes: String?, add: String): String =
        listOfNotNull(notes?.takeIf { it.isNotBlank() }, add).joinToString(" ")

    private companion object {
        const val LOOK_BACK_DAYS = 400L
        const val ALREADY_HERE = "Dated a year ahead by mistake; the same payment is already here."
        const val READ_AS_IN = "Read as money in by mistake: it was spent on the card."
        const val NOT_A_PAYMENT = "Part of the statement's own writing, read as a payment by mistake."
        const val PAID_OFF = "Read as money out by mistake: it paid the card off."
        const val OWED_NOTE = "Set as owed: its name says it's a card, pay-later plan, loan or mortgage."
    }
}
