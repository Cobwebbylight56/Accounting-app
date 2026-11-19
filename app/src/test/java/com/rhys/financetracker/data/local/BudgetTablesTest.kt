package com.rhys.financetracker.data.local

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.rhys.financetracker.data.local.entity.AccountEntity
import com.rhys.financetracker.data.local.entity.BudgetChangeEntity
import com.rhys.financetracker.data.local.entity.BudgetLineEntity
import com.rhys.financetracker.data.local.entity.CategoryEntity
import com.rhys.financetracker.data.local.entity.TransactionEntity
import com.rhys.financetracker.data.local.migration.Migrations
import com.rhys.financetracker.domain.model.AccountType
import com.rhys.financetracker.domain.model.CategoryKind
import com.rhys.financetracker.domain.model.TransactionType
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The budget's tables on a real database: an upgrade from version 15 makes
 * them as the app expects (Room checks as it opens), and lines, changes and
 * money paid into a loan behave.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class BudgetTablesTest {

    private val context = RuntimeEnvironment.getApplication()
    private val name = "budget-tables-test.db"
    private var database: AppDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(name)
    }

    private fun open(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*Migrations.ALL)
            .allowMainThreadQueries()
            .build()
            .also { database = it }

    @Test
    fun `a version 15 database is upgraded with the budget tables, shares and all`() = runBlocking {
        context.deleteDatabase(name)
        // The current schema, exactly as Room makes it, taken back to 15.
        Room.databaseBuilder(context, AppDatabase::class.java, name).allowMainThreadQueries().build()
            .also { it.openHelper.writableDatabase }
            .close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TABLE budget_shares")
            db.execSQL("DROP TABLE budget_changes")
            db.execSQL("DROP TABLE budget_lines")
            db.version = 15
        }
        // Opening migrates, and Room fails here if the tables aren't as it expects.
        val db = open()
        val id = db.budgetDao().insertLine(BudgetLineEntity(name = "Fuel", amountMinor = 15_000, isAim = true))
        assertEquals(listOf("Fuel"), db.budgetDao().getLines().map { it.name })
        assertTrue(db.budgetDao().getLine(id)!!.isAim)
        val person = db.personDao().insert(com.rhys.financetracker.data.local.entity.PersonEntity(name = "Sam", colorHex = "#000000"))
        db.budgetDao().setShares(id, mapOf(person to 9_000L), 15_000)
        assertEquals(listOf(9_000L), db.budgetDao().getShares().map { it.amountMinor })
        assertEquals(15_000L, db.budgetDao().getLine(id)!!.amountMinor)
        // A line's shares go with it.
        db.budgetDao().deleteLine(id)
        assertTrue(db.budgetDao().getShares().isEmpty())
    }

    @Test
    fun `changes go with their line, a deleted category leaves the line, and money paid into a loan counts`() = runBlocking {
        val db = open()
        val current = db.accountDao().insert(account("Current", AccountType.CURRENT))
        val loan = db.accountDao().insert(account("Car loan", AccountType.LOAN))
        val fuel = db.categoryDao().insert(CategoryEntity(name = "Fuel", kind = CategoryKind.EXPENSE, colorHex = "#000000"))

        val fuelLine = db.budgetDao().insertLine(BudgetLineEntity(name = "Fuel", categoryId = fuel, amountMinor = 15_000, isAim = true))
        val loanLine = db.budgetDao().insertLine(BudgetLineEntity(name = "Car", accountId = loan, amountMinor = 20_000, isPutAside = true))
        db.budgetDao().setChange(BudgetChangeEntity(fuelLine, "2026-11", 25_000))
        db.budgetDao().setChange(BudgetChangeEntity(fuelLine, "2026-11", 22_000))
        assertEquals(listOf(22_000L), db.budgetDao().getChanges().map { it.amountMinor })

        // Paid into the loan from the current account, and on the loan's own statement.
        db.transactionDao().insert(entry(current, TransactionType.TRANSFER, 20_000, "2026-11-01", to = loan))
        db.transactionDao().insert(entry(loan, TransactionType.INCOME, 1_000, "2026-11-15"))
        db.transactionDao().insert(entry(loan, TransactionType.INCOME, 5_000, "2026-10-15"))
        assertEquals(21_000L, db.budgetDao().paidInto(loan, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30)))

        db.categoryDao().delete(db.categoryDao().getById(fuel)!!)
        assertNull(db.budgetDao().getLine(fuelLine)!!.categoryId)

        db.budgetDao().deleteLine(fuelLine)
        assertTrue(db.budgetDao().getChanges().isEmpty())
        db.budgetDao().replaceLines(listOf(BudgetLineEntity(name = "Food", amountMinor = 30_000)))
        assertEquals(listOf("Food"), db.budgetDao().observeLines().first().map { it.name })
        assertNull(db.budgetDao().getLine(loanLine))
    }

    private fun account(name: String, type: AccountType) = AccountEntity(
        name = name,
        type = type,
        personId = null,
        openingBalanceMinor = 0L,
        openingBalanceDate = LocalDate.of(2026, 1, 1),
        colorHex = "#000000",
    )

    private fun entry(account: Long, type: TransactionType, amount: Long, date: String, to: Long? = null) = TransactionEntity(
        amountMinor = amount,
        type = type,
        date = LocalDate.parse(date),
        description = "Payment",
        accountId = account,
        transferAccountId = to,
    )
}
