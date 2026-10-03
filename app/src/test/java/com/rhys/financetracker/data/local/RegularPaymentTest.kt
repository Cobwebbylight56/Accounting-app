package com.rhys.financetracker.data.local

import android.app.Application
import androidx.room.Room
import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.data.local.entity.AccountEntity
import com.rhys.financetracker.data.local.entity.TransactionEntity
import com.rhys.financetracker.data.local.migration.Migrations
import com.rhys.financetracker.data.repository.RecurringRepository
import com.rhys.financetracker.domain.model.AccountType
import com.rhys.financetracker.domain.model.Frequency
import com.rhys.financetracker.domain.model.PaymentKind
import com.rhys.financetracker.domain.model.RecordSource
import com.rhys.financetracker.domain.model.TransactionType
import com.rhys.financetracker.domain.recurrence.RecurringTransactionGenerator
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A payment made regular is added by itself on its day, is never added twice
 * for one month, and a statement line already in for that month is taken as it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RegularPaymentTest {

    private val context = RuntimeEnvironment.getApplication()
    private val name = "regular-payment-test.db"
    private var database: AppDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(name)
    }

    @Test
    fun `the car loan goes in by itself each month, once`() = runBlocking {
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*Migrations.ALL)
            .allowMainThreadQueries()
            .build()
            .also { database = it }
        fun account(name: String, type: AccountType) = AccountEntity(
            name = name,
            type = type,
            personId = null,
            openingBalanceDate = LocalDate.of(2026, 1, 1),
            colorHex = "#000000",
        )
        val current = db.accountDao().insert(account("Nationwide Current", AccountType.CURRENT))
        val loan = db.accountDao().insert(account("Car loan", AccountType.LOAN))
        val transactions = db.transactionDao()
        val paidId = transactions.insert(
            TransactionEntity(
                amountMinor = 22_201L,
                type = TransactionType.TRANSFER,
                date = LocalDate.of(2026, 9, 1),
                description = "car loan",
                accountId = current,
                transferAccountId = loan,
                source = RecordSource.STATEMENT,
            ),
        )
        // October's is already in from the statement, two days late.
        val october = transactions.insert(
            TransactionEntity(
                amountMinor = 22_201L,
                type = TransactionType.EXPENSE,
                date = LocalDate.of(2026, 10, 3),
                description = "CAR FINANCE LTD DD",
                accountId = current,
                source = RecordSource.STATEMENT,
            ),
        )

        val repository = RecurringRepository(db.recurringRuleDao(), transactions)
        val generator = RecurringTransactionGenerator(db.recurringRuleDao(), transactions)
        val paid = transactions.getById(paidId)!!
        val made = repository.makeRegular(
            payment = paid,
            kind = PaymentKind.DIRECT_DEBIT,
            frequency = Frequency.MONTHLY,
            day = 1,
            addByItself = true,
            amountChanges = false,
            today = LocalDate.of(2026, 10, 5),
        )
        assertTrue(made is AppResult.Success)
        val ruleId = (made as AppResult.Success).data

        // Up to the end of November, run twice: October is the statement's
        // line, November is added once.
        generator.generateDue(through = LocalDate.of(2026, 11, 30))
        generator.generateDue(through = LocalDate.of(2026, 11, 30))

        val all = transactions.observeBetween(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 12, 31)).first()
        assertEquals(3, all.size)
        assertTrue(all.all { it.transaction.recurringRuleId == ruleId })
        assertTrue(all.all { it.regularKind == PaymentKind.DIRECT_DEBIT })
        assertEquals(ruleId, transactions.getById(october)!!.recurringRuleId)
        val november = all.single { it.transaction.date == LocalDate.of(2026, 11, 1) }
        assertEquals(TransactionType.TRANSFER, november.transaction.type)
        assertEquals(loan, november.transaction.transferAccountId)

        // The bank's alert for November's says it went: not added again.
        assertTrue(
            transactions.findScheduledNear(
                accountId = current,
                amountMinor = 22_201L,
                incoming = false,
                from = LocalDate.of(2026, 10, 29),
                until = LocalDate.of(2026, 11, 6),
                around = LocalDate.of(2026, 11, 2),
            ) != null,
        )
    }
}
