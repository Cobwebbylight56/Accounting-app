package com.rhys.financetracker.data.local

import android.app.Application
import androidx.room.Room
import com.rhys.financetracker.data.local.entity.AccountEntity
import com.rhys.financetracker.data.local.entity.CategoryEntity
import com.rhys.financetracker.data.local.entity.TransactionEntity
import com.rhys.financetracker.data.local.entity.TransactionSplitEntity
import com.rhys.financetracker.data.local.migration.Migrations
import com.rhys.financetracker.domain.model.AccountType
import com.rhys.financetracker.domain.model.CategoryKind
import com.rhys.financetracker.domain.model.TransactionType
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A payment split across categories counts each part in its own category,
 * the rest in the payment's, and the payment's total is unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SplitTotalsTest {

    private val context = RuntimeEnvironment.getApplication()
    private val name = "split-totals-test.db"
    private var database: AppDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(name)
    }

    @Test
    fun `a fuel receipt with snacks counts the snacks as snacks`() = runBlocking {
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*Migrations.ALL)
            .allowMainThreadQueries()
            .build()
            .also { database = it }
        val account = db.accountDao().insert(
            AccountEntity(
                name = "Current",
                type = AccountType.CURRENT,
                personId = null,
                openingBalanceDate = LocalDate.of(2026, 1, 1),
                colorHex = "#000000",
            ),
        )
        val fuel = db.categoryDao().insert(CategoryEntity(name = "Fuel", kind = CategoryKind.EXPENSE, colorHex = "#000000"))
        val snacks = db.categoryDao().insert(CategoryEntity(name = "Snacks & drinks", kind = CategoryKind.EXPENSE, colorHex = "#000000"))
        val groceries = db.categoryDao().insert(CategoryEntity(name = "Groceries", kind = CategoryKind.EXPENSE, colorHex = "#000000"))
        val day = LocalDate.of(2026, 10, 3)
        val pump = db.transactionDao().insert(
            TransactionEntity(
                amountMinor = 4_860L,
                type = TransactionType.EXPENSE,
                date = day,
                description = "TESCO PFS 4198",
                accountId = account,
                categoryId = fuel,
            ),
        )
        db.transactionDao().insert(
            TransactionEntity(
                amountMinor = 1_200L,
                type = TransactionType.EXPENSE,
                date = day,
                description = "TESCO STORES 6231",
                accountId = account,
                categoryId = groceries,
            ),
        )
        db.splitDao().insertAll(
            listOf(
                TransactionSplitEntity(transactionId = pump, categoryId = snacks, amountMinor = 185L, label = "COCA COLA 500ML", position = 0),
                TransactionSplitEntity(transactionId = pump, categoryId = snacks, amountMinor = 135L, label = "WALKERS CRISPS", position = 1),
                TransactionSplitEntity(transactionId = pump, categoryId = null, amountMinor = 4_540L, label = "Rest of the shop", position = 2),
            ),
        )

        val totals = db.transactionDao()
            .getCategoryTotals(TransactionType.EXPENSE.name, day, day, null, null)
            .associate { it.categoryName to it.totalMinor }
        assertEquals(mapOf("Fuel" to 4_540L, "Groceries" to 1_200L, "Snacks & drinks" to 320L), totals)

        // Spending overall is unchanged by the split.
        assertEquals(6_060L, db.transactionDao().getIncomeExpense(day, day, null, null)!!.expenseMinor)
        // And the payment knows it is split.
        assertEquals(3, db.transactionDao().observeDetailsById(pump).first()!!.splitCount)
    }
}
