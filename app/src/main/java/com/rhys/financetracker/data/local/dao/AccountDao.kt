package com.rhys.financetracker.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.rhys.financetracker.data.local.entity.AccountEntity
import com.rhys.financetracker.data.local.projection.AccountOption
import com.rhys.financetracker.data.local.projection.AccountWithBalance
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/**
 * Account queries.
 *
 * Balances are always computed in SQL from the opening balance plus every
 * transaction, so they cannot drift away from the entries that explain them.
 * The `BALANCE_EXPRESSION` fragment is repeated verbatim in each query because
 * Room requires literal SQL — keep the copies in step when changing it.
 */
@Dao
interface AccountDao {

    @Query(
        """
        SELECT a.*, p.name AS person_name,
            a.opening_balance_minor
            + IFNULL((
                SELECT SUM(CASE WHEN t.type = 'INCOME' THEN t.amount_minor ELSE -t.amount_minor END)
                FROM transactions t
                WHERE t.account_id = a.id AND t.is_archived = 0
            ), 0)
            + IFNULL((
                SELECT SUM(t2.amount_minor)
                FROM transactions t2
                WHERE t2.transfer_account_id = a.id AND t2.type = 'TRANSFER' AND t2.is_archived = 0
            ), 0) AS balance_minor
        FROM accounts a
        LEFT JOIN people p ON p.id = a.person_id
        WHERE a.is_archived = 0
        ORDER BY a.sort_order ASC, a.name ASC
        """,
    )
    fun observeActiveWithBalances(): Flow<List<AccountWithBalance>>

    @Query(
        """
        SELECT a.*, p.name AS person_name,
            a.opening_balance_minor
            + IFNULL((
                SELECT SUM(CASE WHEN t.type = 'INCOME' THEN t.amount_minor ELSE -t.amount_minor END)
                FROM transactions t
                WHERE t.account_id = a.id AND t.is_archived = 0
            ), 0)
            + IFNULL((
                SELECT SUM(t2.amount_minor)
                FROM transactions t2
                WHERE t2.transfer_account_id = a.id AND t2.type = 'TRANSFER' AND t2.is_archived = 0
            ), 0) AS balance_minor
        FROM accounts a
        LEFT JOIN people p ON p.id = a.person_id
        ORDER BY a.is_archived ASC, a.sort_order ASC, a.name ASC
        """,
    )
    fun observeAllWithBalances(): Flow<List<AccountWithBalance>>

    /**
     * Balance as it stood at the close of [asOf], used by reports and by the
     * monthly rollover.  Transactions dated after [asOf] are ignored.
     */
    @Query(
        """
        SELECT a.opening_balance_minor
            + IFNULL((
                SELECT SUM(CASE WHEN t.type = 'INCOME' THEN t.amount_minor ELSE -t.amount_minor END)
                FROM transactions t
                WHERE t.account_id = a.id AND t.is_archived = 0 AND t.date <= :asOf
            ), 0)
            + IFNULL((
                SELECT SUM(t2.amount_minor)
                FROM transactions t2
                WHERE t2.transfer_account_id = a.id AND t2.type = 'TRANSFER'
                  AND t2.is_archived = 0 AND t2.date <= :asOf
            ), 0)
        FROM accounts a
        WHERE a.id = :accountId
        """,
    )
    suspend fun getBalanceAsOf(accountId: Long, asOf: LocalDate): Long?

    @Query("SELECT * FROM accounts WHERE is_archived = 0 ORDER BY sort_order ASC, name ASC")
    fun observeActive(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE person_id = :personId AND is_archived = 0 ORDER BY sort_order ASC")
    fun observeForPerson(personId: Long): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE id = :id")
    fun observeById(id: Long): Flow<AccountEntity?>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: Long): AccountEntity?

    /** Active accounts with their owner's name, for pickers. */
    @Query(
        """
        SELECT a.id AS id, a.name AS name, a.color_hex AS color_hex, p.name AS person_name
        FROM accounts a
        LEFT JOIN people p ON p.id = a.person_id
        WHERE a.is_archived = 0
        ORDER BY a.sort_order ASC, a.name ASC
        """,
    )
    fun observeActiveOptions(): Flow<List<AccountOption>>

    @Query("SELECT * FROM accounts WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun getByName(name: String): AccountEntity?

    /**
     * What this account's transactions add up to, without its opening balance.
     *
     * The difference between a balance somebody states and one the app works
     * out. A spreadsheet says what is in the account *now*; the app derives
     * that from the opening balance plus every transaction, so to make the two
     * agree the opening balance has to be the stated figure less this.
     */
    @Query(
        """
        SELECT IFNULL((
                SELECT SUM(CASE WHEN t.type = 'INCOME' THEN t.amount_minor ELSE -t.amount_minor END)
                FROM transactions t
                WHERE t.account_id = :accountId AND t.is_archived = 0
            ), 0)
            + IFNULL((
                SELECT SUM(t2.amount_minor)
                FROM transactions t2
                WHERE t2.transfer_account_id = :accountId AND t2.type = 'TRANSFER'
                  AND t2.is_archived = 0
            ), 0)
        """,
    )
    suspend fun getRecordedMovementMinor(accountId: Long): Long

    /**
     * The account with this name belonging to this person.
     *
     * Names are unique per owner rather than across the whole app, so Rhys and
     * Hannah can each have a "Main account" — which is what they are both
     * called in real life. `personId IS NULL` matches shared accounts, which
     * form their own group for the same reason.
     */
    @Query(
        """
        SELECT * FROM accounts
        WHERE name = :name COLLATE NOCASE
          AND ((:personId IS NULL AND person_id IS NULL) OR person_id = :personId)
        LIMIT 1
        """,
    )
    suspend fun getByNameForPerson(name: String, personId: Long?): AccountEntity?

    @Query("SELECT * FROM accounts ORDER BY sort_order ASC, name ASC")
    suspend fun getAll(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE is_archived = 0 ORDER BY sort_order ASC, name ASC")
    suspend fun getAllActive(): List<AccountEntity>

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(account: AccountEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(accounts: List<AccountEntity>): List<Long>

    @Update
    suspend fun update(account: AccountEntity)

    @Delete
    suspend fun delete(account: AccountEntity)

    @Query("UPDATE accounts SET is_archived = :archived, updated_at = :updatedAt WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean, updatedAt: Long)

    @Query("DELETE FROM accounts")
    suspend fun deleteAll()
}
