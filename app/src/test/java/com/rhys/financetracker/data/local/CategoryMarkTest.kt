package com.rhys.financetracker.data.local

import android.app.Application
import androidx.room.Room
import com.rhys.financetracker.data.importer.Refiling
import com.rhys.financetracker.data.importer.TransactionFingerprint
import com.rhys.financetracker.data.local.entity.AccountEntity
import com.rhys.financetracker.data.local.entity.CategoryEntity
import com.rhys.financetracker.data.local.entity.TransactionEntity
import com.rhys.financetracker.data.local.migration.Migrations
import com.rhys.financetracker.domain.model.AccountType
import com.rhys.financetracker.domain.model.CategoryKind
import com.rhys.financetracker.domain.model.RecordSource
import com.rhys.financetracker.domain.model.TransactionType
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The hidden "category set by user" mark, on a real database: the upgrade
 * gives it to what the user most likely filed, the app's re-sort only ever
 * sees entries without it, and filing or putting back sets it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class CategoryMarkTest {

    private val context = RuntimeEnvironment.getApplication()
    private val name = "category-mark-test.db"
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

    private class Setup(val db: AppDatabase, val account: Long, val groceries: Long, val fuel: Long)

    private fun setUp(): Setup = runBlocking {
        val db = open()
        val account = db.accountDao().insert(
            AccountEntity(
                name = "Current",
                type = AccountType.CURRENT,
                personId = null,
                openingBalanceMinor = 0L,
                openingBalanceDate = LocalDate.of(2026, 1, 1),
                colorHex = "#000000",
            ),
        )
        val groceries = db.categoryDao().insert(CategoryEntity(name = "Groceries", kind = CategoryKind.EXPENSE, colorHex = "#000000"))
        val fuel = db.categoryDao().insert(CategoryEntity(name = "Fuel", kind = CategoryKind.EXPENSE, colorHex = "#000000"))
        Setup(db, account, groceries, fuel)
    }

    private fun entry(
        s: Setup,
        text: String,
        category: Long?,
        source: RecordSource = RecordSource.STATEMENT,
        byUser: Boolean = false,
        hash: String? = "h-$text",
    ): Long = runBlocking {
        s.db.transactionDao().insert(
            TransactionEntity(
                amountMinor = 4_000L,
                type = TransactionType.EXPENSE,
                date = LocalDate.of(2026, 3, 1),
                description = text,
                accountId = s.account,
                categoryId = category,
                source = source,
                importHash = hash,
                categoryByUser = byUser,
            ),
        )
    }

    @Test
    fun `the upgrade marks what the user most likely filed`() = runBlocking {
        val s = setUp()
        val statement = entry(s, "TESCO PFS 3012", s.groceries)
        val typed = entry(s, "Petrol", s.fuel, source = RecordSource.MANUAL, hash = null)
        val sheet = entry(s, "Rhys fuel", s.fuel, source = RecordSource.SPREADSHEET)
        val oldTyped = entry(s, "Old typed", s.fuel, source = RecordSource.UNKNOWN, hash = null)
        val oldImported = entry(s, "Old imported", s.fuel, source = RecordSource.UNKNOWN)
        val uncategorised = entry(s, "Nothing", null, source = RecordSource.MANUAL, hash = null)

        Migrations.MIGRATION_9_10.migrate(s.db.openHelper.writableDatabase)

        val dao = s.db.transactionDao()
        assertFalse(dao.getById(statement)!!.categoryByUser)
        assertTrue(dao.getById(typed)!!.categoryByUser)
        assertTrue(dao.getById(sheet)!!.categoryByUser)
        assertTrue(dao.getById(oldTyped)!!.categoryByUser)
        assertFalse(dao.getById(oldImported)!!.categoryByUser)
        assertFalse(dao.getById(uncategorised)!!.categoryByUser)
    }

    @Test
    fun `the re-sort only sees what the app filed, and the user's choice wins`() = runBlocking {
        val s = setUp()
        val dao = s.db.transactionDao()
        // The app filed both under Groceries; the user then said one Tesco PFS is Groceries.
        val appFiled = entry(s, "CARD PAYMENT TESCO PFS 3012", s.groceries)
        val userFiled = entry(s, "CARD PAYMENT TESCO PFS 4471", s.groceries, byUser = true)
        val asda = entry(s, "ASDA LIVING 88", s.groceries)

        assertEquals(setOf(appFiled, asda), dao.getAppFiled().map { it.id }.toSet())

        val learned = dao.getUserFiledDescriptions(100)
            .associate { TransactionFingerprint.normaliseDescription(it.description) to it.categoryName }
        // Tesco PFS stays in Groceries: the user said so, over the list's Fuel.
        assertNull(Refiling.better("CARD PAYMENT TESCO PFS 3012", TransactionType.EXPENSE, "Groceries", learned))
        // Asda Living is not the user's, so the list moves it.
        assertEquals("Shopping", Refiling.better("ASDA LIVING 88", TransactionType.EXPENSE, "Groceries", learned))
        assertTrue(dao.getById(userFiled)!!.categoryByUser)
    }

    @Test
    fun `filing and putting back set the mark`() = runBlocking {
        val s = setUp()
        val dao = s.db.transactionDao()
        val a = entry(s, "TESCO PFS 1", s.groceries)
        val b = entry(s, "TESCO PFS 2", s.groceries)

        // The app's own move leaves it unmarked.
        dao.setCategory(listOf(a), s.fuel, 1L)
        assertFalse(dao.getById(a)!!.categoryByUser)

        // Putting the app's move back is the user's choice.
        dao.restoreCategory(a, s.groceries)
        assertEquals(s.groceries, dao.getById(a)!!.categoryId)
        assertTrue(dao.getById(a)!!.categoryByUser)

        // Filed by the user, and never touched by a later put-back.
        dao.setCategoryByUser(listOf(b), s.fuel, 2L)
        dao.restoreCategory(b, s.groceries)
        assertEquals(s.fuel, dao.getById(b)!!.categoryId)
        assertTrue(dao.getById(b)!!.categoryByUser)
    }

    @Test
    fun `the upgrade makes money with people a transfer and keeps PayPal as spending`() = runBlocking {
        val s = setUp()
        val dao = s.db.categoryDao()
        val oldOut = dao.insert(CategoryEntity(name = "Transfers & payments", kind = CategoryKind.EXPENSE, colorHex = "#000000"))
        val oldIn = dao.insert(CategoryEntity(name = "Transfers & payments", kind = CategoryKind.INCOME, colorHex = "#000000"))
        val paypal = entry(s, "PAYPAL PAYMENT 1", oldOut)
        val fromHannah = entry(s, "Bank credit H Payne", oldIn)

        Migrations.MIGRATION_10_11.migrate(s.db.openHelper.writableDatabase)

        val transfer = dao.getByNameAndKind("Transfers & payments", CategoryKind.TRANSFER)!!
        val apps = dao.getByNameAndKind("Payment apps", CategoryKind.EXPENSE)!!
        assertEquals(transfer.id, s.db.transactionDao().getById(fromHannah)!!.categoryId)
        assertEquals(apps.id, s.db.transactionDao().getById(paypal)!!.categoryId)
        assertNull(dao.getByNameAndKind("Transfers & payments", CategoryKind.INCOME))
        assertNull(dao.getByNameAndKind("Transfers & payments", CategoryKind.EXPENSE))
    }

    @Test
    fun `money under a transfer category is not counted as spending`() = runBlocking {
        val s = setUp()
        val transfer = s.db.categoryDao().insert(
            CategoryEntity(name = "Transfers & payments", kind = CategoryKind.TRANSFER, colorHex = "#000000"),
        )
        entry(s, "FASTER PAYMENT TO HANNAH PAYNE", transfer)
        entry(s, "TESCO STORES", s.groceries)
        val totals = s.db.transactionDao().getIncomeExpense(
            LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), null, null,
        )!!
        assertEquals(4_000L, totals.expenseMinor)
    }
}
