package com.rhys.financetracker.data.local

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.rhys.financetracker.data.local.migration.Migrations
import com.rhys.financetracker.domain.model.CategoryKind
import com.rhys.financetracker.domain.model.Holding
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The savings redesign's migration, run for real.
 *
 * A version 4 database is built holding exactly the situations that were
 * wrong — a saver typed as a current account with its override switched off,
 * a cash account with a transfer into it, a row the importer filed under a
 * second, ordinary "Savings" category — and then opened by the app, which
 * migrates it. Room checks the rebuilt tables against the entities as it
 * opens, so a schema mistake fails here too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class MigrationFourToFiveTest {

    private val context = RuntimeEnvironment.getApplication()
    private val name = "migration-test.db"
    private var database: AppDatabase? = null

    @Before
    fun buildVersionFour() {
        context.deleteDatabase(name)
        // The current schema, exactly as Room makes it…
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
            .also { it.openHelper.writableDatabase }
            .close()
        // …taken back to version 4 by hand.
        val db = SQLiteDatabase.openDatabase(
            context.getDatabasePath(name).path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )
        db.execSQL("DROP TABLE income_changes") // version 6
        db.execSQL("DROP TABLE cash_pot_entries")
        db.execSQL(
            "CREATE TABLE accounts_v4 (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "name TEXT NOT NULL, type TEXT NOT NULL, person_id INTEGER, " +
                "opening_balance_minor INTEGER NOT NULL, opening_balance_date TEXT NOT NULL, " +
                "currency_code TEXT NOT NULL, overdraft_limit_minor INTEGER NOT NULL, " +
                "low_balance_threshold_minor INTEGER, credit_limit_minor INTEGER, " +
                "interest_rate_percent REAL, color_hex TEXT NOT NULL, " +
                "include_in_net_worth INTEGER NOT NULL, counts_as_savings INTEGER, " +
                "is_shared INTEGER NOT NULL, sort_order INTEGER NOT NULL, notes TEXT, " +
                "is_archived INTEGER NOT NULL, created_at INTEGER NOT NULL, " +
                "updated_at INTEGER NOT NULL, FOREIGN KEY(person_id) REFERENCES people(id) " +
                "ON UPDATE NO ACTION ON DELETE SET NULL)",
        )
        db.execSQL("DROP TABLE accounts")
        db.execSQL("ALTER TABLE accounts_v4 RENAME TO accounts")
        db.execSQL(
            "CREATE TABLE people_v4 (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "name TEXT NOT NULL, color_hex TEXT NOT NULL, is_shared INTEGER NOT NULL, " +
                "sort_order INTEGER NOT NULL, notes TEXT, is_archived INTEGER NOT NULL, " +
                "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
        )
        db.execSQL("DROP TABLE people")
        db.execSQL("ALTER TABLE people_v4 RENAME TO people")
        db.execSQL("CREATE UNIQUE INDEX index_people_name ON people (name)")

        db.execSQL(
            "INSERT INTO people VALUES (1, 'Rhys Evans', '#1565C0', 0, 0, NULL, 0, 0, 0)",
        )
        category(db, 1, "Savings", "SAVING")
        category(db, 2, "Cash", "CASH")
        category(db, 3, "Groceries", "EXPENSE")
        category(db, 4, "Savings", "EXPENSE") // made by the old importer
        category(db, 5, "Interest", "INCOME")

        account(db, 1, "current", "CURRENT", countsAsSavings = null, opening = 100_000L)
        // The user's case: a saver typed as a current account, override off.
        account(db, 2, "saver", "CURRENT", countsAsSavings = 0, opening = 0L)
        account(db, 3, "Cash in the house", "CASH", countsAsSavings = null, opening = 1_000L)
        account(db, 4, "Old ISA", "SAVINGS", countsAsSavings = null, opening = 50_000L)
        account(db, 5, "Barclaycard", "CREDIT_CARD", countsAsSavings = null, opening = -2_000L)

        entry(db, 1, 20_000L, "EXPENSE", "TO START TO SAVE", account = 1, category = 4)
        entry(db, 2, 20_000L, "INCOME", "FROM FLEXDIRECT", account = 2, category = null)
        entry(db, 3, 150L, "INCOME", "INTEREST", account = 2, category = 5)
        entry(db, 4, 5_000L, "TRANSFER", "To cash", account = 1, category = null, to = 3)
        entry(db, 5, 1_200L, "EXPENSE", "Chippy", account = 3, category = null)
        entry(db, 6, 3_000L, "EXPENSE", "TESCO", account = 1, category = 3)

        db.version = 4
        db.close()
    }

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(name)
    }

    private fun openMigrated(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*Migrations.ALL)
            .allowMainThreadQueries()
            .build()
            .also { database = it }

    @Test
    fun `every account counts where it should`() = runBlocking {
        val accounts = openMigrated().accountDao().getAll().associateBy { it.name }
        assertEquals(Holding.SPEND, accounts.getValue("current").holding)
        // The saver is set aside, whatever it was typed as.
        assertEquals(Holding.SET_ASIDE, accounts.getValue("saver").holding)
        assertEquals(Holding.SET_ASIDE, accounts.getValue("Old ISA").holding)
        assertEquals(Holding.OWED, accounts.getValue("Barclaycard").holding)
        // Cash is no longer an account.
        assertNull(accounts["Cash in the house"])
    }

    @Test
    fun `the cash pot holds what the cash account held, and no bank balance moves`() =
        runBlocking {
            val db = openMigrated()
            assertEquals(1_000L + 5_000L - 1_200L, db.cashPotDao().getTotal())
            val balances = db.accountDao().observeActiveWithBalances().first()
                .associate { it.account.name to it.balanceMinor }
            assertEquals(100_000L - 20_000L - 5_000L - 3_000L, balances.getValue("current"))
            assertEquals(20_150L, balances.getValue("saver"))
            assertEquals(50_000L, balances.getValue("Old ISA"))
        }

    @Test
    fun `savings are filed as savings and left out of income and spending`() = runBlocking {
        val db = openMigrated()
        val categories = db.categoryDao().getAll().associateBy { it.id }
        val all = db.transactionDao().getAll().associateBy { it.id }
        // The row filed under the importer's copy points at the real one.
        assertEquals(1L, all.getValue(1).categoryId)
        assertTrue(categories.getValue(4).isArchived)
        // Money arriving in the saver is savings; its interest stays income.
        assertEquals(1L, all.getValue(2).categoryId)
        assertEquals(5L, all.getValue(3).categoryId)
        // The transfer into the tin is now cash taken out of the bank.
        assertEquals(2L, all.getValue(4).categoryId)
        assertNull(all.getValue(4).transferAccountId)
        assertEquals(CategoryKind.CASH, categories.getValue(2).kind)

        val start = LocalDate.of(2026, 9, 1)
        val end = LocalDate.of(2026, 9, 30)
        val totals = db.transactionDao().getIncomeExpense(start, end, null, null)!!
        assertEquals(150L, totals.incomeMinor)
        assertEquals(3_000L + 5_000L, totals.expenseMinor)

        val saved = db.transactionDao().getPotFlow("SAVING", start, end, null, null)!!
        assertEquals(20_000L, saved.intoPotMinor)
        assertEquals(0L, saved.outOfPotMinor)
    }

    @Test
    fun `people gain yearly pay, empty to begin with`() = runBlocking {
        val rhys = openMigrated().personDao().getAll().single()
        assertNull(rhys.grossYearlyIncomeMinor)
        assertNull(rhys.netYearlyIncomeMinor)
    }

    private fun category(db: SQLiteDatabase, id: Long, name: String, kind: String) {
        db.execSQL(
            "INSERT INTO categories (id, name, kind, color_hex, icon_key, parent_id, " +
                "monthly_budget_minor, sort_order, is_system, is_archived, created_at, " +
                "updated_at) VALUES ($id, '$name', '$kind', '#000000', NULL, NULL, NULL, " +
                "0, 0, 0, 0, 0)",
        )
    }

    private fun account(
        db: SQLiteDatabase,
        id: Long,
        name: String,
        type: String,
        countsAsSavings: Int?,
        opening: Long,
    ) {
        db.execSQL(
            "INSERT INTO accounts (id, name, type, person_id, opening_balance_minor, " +
                "opening_balance_date, currency_code, overdraft_limit_minor, " +
                "color_hex, include_in_net_worth, counts_as_savings, is_shared, sort_order, " +
                "is_archived, created_at, updated_at) VALUES ($id, '$name', '$type', 1, " +
                "$opening, '2026-08-01', 'GBP', 0, '#000000', 1, " +
                "${countsAsSavings ?: "NULL"}, 0, 0, 0, 0, 0)",
        )
    }

    private fun entry(
        db: SQLiteDatabase,
        id: Long,
        amount: Long,
        type: String,
        description: String,
        account: Long,
        category: Long?,
        to: Long? = null,
    ) {
        db.execSQL(
            "INSERT INTO transactions (id, amount_minor, type, date, description, account_id, " +
                "transfer_account_id, category_id, is_confirmed, is_cleared, source, " +
                "is_archived, created_at, updated_at) VALUES ($id, $amount, '$type', " +
                "'2026-09-0$id', '$description', $account, ${to ?: "NULL"}, " +
                "${category ?: "NULL"}, 1, 1, 'STATEMENT', 0, 0, 0)",
        )
    }
}
