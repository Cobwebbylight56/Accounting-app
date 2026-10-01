package com.rhys.financetracker.data.local

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.data.local.entity.AccountEntity
import com.rhys.financetracker.data.local.entity.TransactionEntity
import com.rhys.financetracker.data.local.migration.Migrations
import com.rhys.financetracker.data.repository.ImportHistoryRepository
import com.rhys.financetracker.domain.model.AccountType
import com.rhys.financetracker.domain.model.RecordSource
import com.rhys.financetracker.domain.model.TransactionType
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Recording an import and taking it back out, on a real database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ImportHistoryTest {

    private val context = RuntimeEnvironment.getApplication()
    private val name = "import-history-test.db"
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

    private fun payment(account: Long, day: Int, pence: Long, text: String) = TransactionEntity(
        amountMinor = pence,
        type = TransactionType.EXPENSE,
        date = LocalDate.of(2026, 1, day),
        description = text,
        accountId = account,
        source = RecordSource.STATEMENT,
    )

    @Test
    fun `undo takes out what the import added and puts the balance back`() = runBlocking {
        val db = open()
        val history = ImportHistoryRepository(db, db.importBatchDao(), db.transactionDao(), db.accountDao())
        val accountId = db.accountDao().insert(
            AccountEntity(
                name = "Current",
                type = AccountType.CURRENT,
                personId = null,
                openingBalanceMinor = 10_000L,
                openingBalanceDate = LocalDate.of(2026, 1, 1),
                colorHex = "#000000",
            ),
        )
        val kept = db.transactionDao().insert(payment(accountId, 2, 500L, "Typed in before"))
        val added = listOf(
            db.transactionDao().insert(payment(accountId, 10, 1_000L, "TESCO")),
            db.transactionDao().insert(payment(accountId, 11, 2_000L, "ASDA")),
        )
        // The statement set the balance, as an import does.
        db.accountDao().setBalanceAsOf(accountId, 6_500L, LocalDate.of(2026, 1, 31), 0L)

        val batchId = history.record(
            accountId = accountId,
            fileName = "January.pdf",
            addedIds = added,
            rowsUpdated = 0,
            firstDate = LocalDate.of(2026, 1, 10),
            lastDate = LocalDate.of(2026, 1, 31),
            balanceBefore = 10_000L to LocalDate.of(2026, 1, 1),
            balanceAfter = 6_500L to LocalDate.of(2026, 1, 31),
        )
        val listed = history.observeForAccount(accountId).first().single()
        assertEquals(2, listed.remainingRows)
        assertEquals("January.pdf", listed.batch.fileName)

        val result = history.undo(batchId)
        assertTrue(result is AppResult.Success)
        result as AppResult.Success
        assertEquals(2, result.data.removed)
        assertTrue(result.data.balanceRestored)

        assertNull(db.transactionDao().getById(added[0]))
        assertNull(db.transactionDao().getById(added[1]))
        assertNotNull(db.transactionDao().getById(kept))
        val account = db.accountDao().getById(accountId)!!
        assertEquals(10_000L, account.openingBalanceMinor)
        assertEquals(LocalDate.of(2026, 1, 1), account.openingBalanceDate)
        assertTrue(history.observeForAccount(accountId).first().isEmpty())
    }

    @Test
    fun `a balance set again since is left alone`() = runBlocking {
        val db = open()
        val history = ImportHistoryRepository(db, db.importBatchDao(), db.transactionDao(), db.accountDao())
        val accountId = db.accountDao().insert(
            AccountEntity(
                name = "Saver",
                type = AccountType.SAVINGS,
                personId = null,
                openingBalanceMinor = 0L,
                openingBalanceDate = LocalDate.of(2026, 1, 1),
                colorHex = "#000000",
            ),
        )
        val batchId = history.record(
            accountId, "Jan.pdf", emptyList(), 1, null, null,
            0L to LocalDate.of(2026, 1, 1), 300L to LocalDate.of(2026, 1, 31),
        )
        // A later statement moved it on.
        db.accountDao().setBalanceAsOf(accountId, 900L, LocalDate.of(2026, 2, 28), 0L)

        val result = history.undo(batchId) as AppResult.Success
        assertEquals(false, result.data.balanceRestored)
        assertEquals(900L, db.accountDao().getById(accountId)!!.openingBalanceMinor)
    }

    @Test
    fun `a version 8 database gains the import history`() {
        context.deleteDatabase(name)
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
            .also { it.openHelper.writableDatabase }
            .close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE)
            .apply {
                execSQL("DROP TABLE import_batch_entries")
                execSQL("DROP TABLE import_batches")
                version = 8
                close()
            }
        // Room checks the new tables against the entities as it opens.
        val db = open()
        runBlocking { assertTrue(db.importBatchDao().observeForAccount(1L).first().isEmpty()) }
    }
}
