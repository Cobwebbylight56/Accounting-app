package com.rhys.financetracker.data.repository

import androidx.room.withTransaction
import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.core.result.runCatchingApp
import com.rhys.financetracker.data.local.AppDatabase
import com.rhys.financetracker.data.local.dao.AccountDao
import com.rhys.financetracker.data.local.dao.ImportBatchDao
import com.rhys.financetracker.data.local.dao.TransactionDao
import com.rhys.financetracker.data.local.entity.ImportBatchEntity
import com.rhys.financetracker.data.local.entity.ImportBatchEntryEntity
import com.rhys.financetracker.data.local.projection.ImportBatchWithRemaining
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** What an undo took back out. */
data class UndoneImport(val removed: Int, val balanceRestored: Boolean)

/**
 * The statements imported into each account, and the way to take one back out.
 *
 * Before this, a statement filed against the wrong account, or read the wrong
 * way round, could only be cleared a row at a time.
 */
@Singleton
class ImportHistoryRepository @Inject constructor(
    private val database: AppDatabase,
    private val batchDao: ImportBatchDao,
    private val transactionDao: TransactionDao,
    private val accountDao: AccountDao,
) {

    fun observeForAccount(accountId: Long): Flow<List<ImportBatchWithRemaining>> =
        batchDao.observeForAccount(accountId)

    /** Records an import of [fileName] into [accountId] and the payments it added. */
    suspend fun record(
        accountId: Long,
        fileName: String,
        addedIds: List<Long>,
        rowsUpdated: Int,
        firstDate: LocalDate?,
        lastDate: LocalDate?,
        balanceBefore: Pair<Long, LocalDate>?,
        balanceAfter: Pair<Long, LocalDate>?,
    ): Long = database.withTransaction {
        val id = batchDao.insert(
            ImportBatchEntity(
                accountId = accountId,
                fileName = fileName,
                importedAt = Instant.now().toEpochMilli(),
                firstDate = firstDate,
                lastDate = lastDate,
                rowsAdded = addedIds.size,
                rowsUpdated = rowsUpdated,
                balanceBeforeMinor = balanceBefore?.first,
                balanceDateBefore = balanceBefore?.second,
                balanceAfterMinor = balanceAfter?.first,
                balanceDateAfter = balanceAfter?.second,
            ),
        )
        addedIds.chunked(BATCH).forEach { chunk ->
            batchDao.insertEntries(chunk.map { ImportBatchEntryEntity(batchId = id, transactionId = it) })
        }
        id
    }

    /**
     * Takes an import back out: every payment it added goes, and the account's
     * balance goes back to what it was — unless something since (a later
     * statement, an edit) has set it again, in which case that is left alone.
     *
     * Entries the statement corrected keep the statement's version; there is
     * nothing earlier to go back to that is any truer.
     */
    suspend fun undo(batchId: Long): AppResult<UndoneImport> =
        runCatchingApp("Could not undo that import") {
            database.withTransaction {
                val batch = batchDao.getById(batchId) ?: error("That import has already been undone")
                val ids = batchDao.transactionIds(batchId)
                ids.chunked(BATCH).forEach { transactionDao.deleteByIds(it) }

                var restored = false
                val account = accountDao.getById(batch.accountId)
                if (account != null && batch.balanceBeforeMinor != null && batch.balanceDateBefore != null &&
                    account.openingBalanceMinor == batch.balanceAfterMinor &&
                    account.openingBalanceDate == batch.balanceDateAfter
                ) {
                    accountDao.setBalanceAsOf(
                        account.id,
                        batch.balanceBeforeMinor,
                        batch.balanceDateBefore,
                        Instant.now().toEpochMilli(),
                    )
                    restored = true
                }
                batchDao.delete(batchId)
                UndoneImport(removed = ids.size, balanceRestored = restored)
            }
        }

    private companion object {
        /** SQLite caps how many values one IN clause may bind. */
        const val BATCH = 400
    }
}
