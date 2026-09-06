package com.rhys.financetracker.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import com.rhys.financetracker.data.local.entity.AccountEntity
import com.rhys.financetracker.data.local.entity.CategoryEntity
import com.rhys.financetracker.data.local.entity.PersonEntity
import com.rhys.financetracker.data.local.entity.TransactionEntity
import com.rhys.financetracker.data.local.projection.AccountActivity
import com.rhys.financetracker.data.local.projection.AccountPayee
import com.rhys.financetracker.data.local.projection.CategoryTotal
import com.rhys.financetracker.data.local.projection.DescriptionCategory
import com.rhys.financetracker.data.local.projection.ExistingEntry
import com.rhys.financetracker.data.local.projection.FingerprintCount
import com.rhys.financetracker.data.local.projection.IncomeExpenseTotals
import com.rhys.financetracker.data.local.projection.MonthTotals
import com.rhys.financetracker.data.local.projection.PersonTotals
import com.rhys.financetracker.data.local.projection.PotFlow
import com.rhys.financetracker.data.local.projection.TransactionWithDetails
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/**
 * Transaction queries.
 *
 * Search and filtering go through [searchRaw], whose SQL is assembled by
 * [com.rhys.financetracker.data.local.dao.TransactionQuery].  A raw query is
 * used deliberately: SQLite rejects an empty `IN ()` list, so a single fixed
 * statement cannot express "filter by these accounts, or by none at all"
 * without awkward sentinel values.
 */
@Dao
interface TransactionDao {

    companion object {
        /** Column list shared by every "with details" query. */
        const val DETAIL_COLUMNS = """
            t.*,
            a.name AS account_name,
            ta.name AS transfer_account_name,
            c.name AS category_name,
            c.color_hex AS category_color,
            p.name AS person_name,
            p.color_hex AS person_color
        """

        const val DETAIL_JOINS = """
            FROM transactions t
            LEFT JOIN accounts a ON a.id = t.account_id
            LEFT JOIN accounts ta ON ta.id = t.transfer_account_id
            LEFT JOIN categories c ON c.id = t.category_id
            LEFT JOIN people p ON p.id = COALESCE(t.person_id, a.person_id)
        """
    }

    // ---------------------------------------------------------------- reads

    @Query(
        "SELECT $DETAIL_COLUMNS $DETAIL_JOINS " +
            "WHERE t.is_archived = 0 " +
            "ORDER BY t.date DESC, t.id DESC LIMIT :limit",
    )
    fun observeRecent(limit: Int): Flow<List<TransactionWithDetails>>

    @Query(
        "SELECT $DETAIL_COLUMNS $DETAIL_JOINS " +
            "WHERE t.is_archived = 0 AND t.date BETWEEN :start AND :end " +
            "ORDER BY t.date DESC, t.id DESC",
    )
    fun observeBetween(start: LocalDate, end: LocalDate): Flow<List<TransactionWithDetails>>

    @Query(
        "SELECT $DETAIL_COLUMNS $DETAIL_JOINS " +
            "WHERE t.is_archived = 0 AND (t.account_id = :accountId OR t.transfer_account_id = :accountId) " +
            "ORDER BY t.date DESC, t.id DESC",
    )
    fun observeForAccount(accountId: Long): Flow<List<TransactionWithDetails>>

    @Query(
        "SELECT $DETAIL_COLUMNS $DETAIL_JOINS WHERE t.id = :id",
    )
    fun observeDetailsById(id: Long): Flow<TransactionWithDetails?>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): TransactionEntity?

    @Query(
        "SELECT * FROM transactions WHERE is_archived = 0 AND date BETWEEN :start AND :end " +
            "ORDER BY date ASC, id ASC",
    )
    suspend fun getBetween(start: LocalDate, end: LocalDate): List<TransactionEntity>

    @Query("SELECT * FROM transactions ORDER BY date ASC, id ASC")
    suspend fun getAll(): List<TransactionEntity>

    /**
     * How many stored transactions carry each of [hashes].
     *
     * Counted rather than tested for existence so the importer can add the
     * surplus when a day genuinely holds two identical purchases.
     */
    @Query(
        """
        SELECT import_hash, COUNT(*) AS occurrences
        FROM transactions
        WHERE import_hash IN (:hashes)
        GROUP BY import_hash
        """,
    )
    suspend fun countByFingerprint(hashes: List<String>): List<FingerprintCount>

    /**
     * Entries on [accountId] between [from] and [to] that a statement is
     * allowed to correct.
     *
     * Rows already taken from a statement are left out: the bank has spoken
     * about those, and a second statement covering them is recognised by its
     * fingerprint rather than by this. Fetched as a range in one query, rather
     * than asked row by row, because the pairing has to consider all of them
     * at once to spot the ambiguous cases and refuse them.
     */
    @Query(
        """
        SELECT id, date, amount_minor, type, description, category_id, notes, source
        FROM transactions
        WHERE is_archived = 0
          AND account_id = :accountId
          AND source != 'STATEMENT'
          AND date BETWEEN :from AND :to
        ORDER BY date ASC, id ASC
        """,
    )
    suspend fun correctableBetween(
        accountId: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<ExistingEntry>

    /**
     * Applies a statement's version of a transaction to the row already held.
     *
     * Written as one statement so the whole correction lands together: a row
     * left half updated — new date, old description — would be worse than
     * either version on its own.
     */
    @Query(
        """
        UPDATE transactions
        SET date = :date,
            description = :description,
            category_id = :categoryId,
            notes = :notes,
            import_hash = :importHash,
            source = 'STATEMENT',
            is_cleared = 1,
            updated_at = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun applyStatementVersion(
        id: Long,
        date: LocalDate,
        description: String,
        categoryId: Long?,
        notes: String?,
        importHash: String,
        updatedAt: Long,
    )

    /**
     * Every payee on every account, with how often it appears.
     *
     * Grouped in SQL so what comes back is one row per payee per account —
     * hundreds, not the whole ledger — which is small enough to compare a
     * statement against in memory.
     */
    @Query(
        """
        SELECT account_id, description, COUNT(*) AS occurrences
        FROM transactions
        WHERE is_archived = 0 AND description != ''
        GROUP BY account_id, description
        """,
    )
    suspend fun payeesByAccount(): List<AccountPayee>

    /**
     * Payees that have already been filed, commonest first.
     *
     * This is what lets the importer follow decisions rather than repeat
     * guesses: correct one Sainsbury's fuel stop to Fuel and every later
     * import of it follows. Capped because it is read into memory, and the
     * long tail of one-off payees adds nothing.
     */
    @Query(
        """
        SELECT t.description AS description, c.name AS category_name
        FROM transactions t
        JOIN categories c ON c.id = t.category_id
        WHERE t.is_archived = 0 AND t.category_id IS NOT NULL AND t.description != ''
        GROUP BY t.description, c.name
        ORDER BY COUNT(*) DESC
        LIMIT :limit
        """,
    )
    suspend fun getCategorisedDescriptions(limit: Int): List<DescriptionCategory>

    @Query("SELECT COUNT(*) FROM transactions")
    suspend fun count(): Int

    @Query("SELECT MIN(date) FROM transactions WHERE is_archived = 0")
    suspend fun earliestDate(): LocalDate?

    /** Entries generated by a rule but awaiting the user's confirmation. */
    @Query(
        "SELECT $DETAIL_COLUMNS $DETAIL_JOINS " +
            "WHERE t.is_archived = 0 AND t.is_confirmed = 0 ORDER BY t.date ASC",
    )
    fun observeUnconfirmed(): Flow<List<TransactionWithDetails>>

    @RawQuery(
        observedEntities = [
            TransactionEntity::class,
            AccountEntity::class,
            CategoryEntity::class,
            PersonEntity::class,
        ],
    )
    fun searchRaw(query: SupportSQLiteQuery): Flow<List<TransactionWithDetails>>

    @RawQuery
    suspend fun searchRawOnce(query: SupportSQLiteQuery): List<TransactionWithDetails>

    // ----------------------------------------------------------- aggregates

    @Query(
        """
        SELECT
            IFNULL(SUM(CASE WHEN t.type = 'INCOME' THEN t.amount_minor ELSE 0 END), 0) AS income_minor,
            IFNULL(SUM(CASE WHEN t.type = 'EXPENSE' THEN t.amount_minor ELSE 0 END), 0) AS expense_minor
        FROM transactions t
        LEFT JOIN accounts a ON a.id = t.account_id
        WHERE t.is_archived = 0
          AND t.date BETWEEN :start AND :end
          AND (:accountId IS NULL OR t.account_id = :accountId)
          AND (:personId IS NULL OR COALESCE(t.person_id, a.person_id) = :personId)
        """,
    )
    fun observeIncomeExpense(
        start: LocalDate,
        end: LocalDate,
        accountId: Long?,
        personId: Long?,
    ): Flow<IncomeExpenseTotals?>

    @Query(
        """
        SELECT
            IFNULL(SUM(CASE WHEN t.type = 'INCOME' THEN t.amount_minor ELSE 0 END), 0) AS income_minor,
            IFNULL(SUM(CASE WHEN t.type = 'EXPENSE' THEN t.amount_minor ELSE 0 END), 0) AS expense_minor
        FROM transactions t
        LEFT JOIN accounts a ON a.id = t.account_id
        WHERE t.is_archived = 0
          AND t.date BETWEEN :start AND :end
          AND (:accountId IS NULL OR t.account_id = :accountId)
          AND (:personId IS NULL OR COALESCE(t.person_id, a.person_id) = :personId)
        """,
    )
    suspend fun getIncomeExpense(
        start: LocalDate,
        end: LocalDate,
        accountId: Long?,
        personId: Long?,
    ): IncomeExpenseTotals?

    @Query(
        """
        SELECT t.category_id AS category_id,
               IFNULL(c.name, 'Uncategorised') AS category_name,
               c.color_hex AS category_color,
               SUM(t.amount_minor) AS total_minor,
               COUNT(*) AS transaction_count
        FROM transactions t
        LEFT JOIN categories c ON c.id = t.category_id
        LEFT JOIN accounts a ON a.id = t.account_id
        WHERE t.is_archived = 0
          AND t.type = :type
          AND t.date BETWEEN :start AND :end
          AND (:accountId IS NULL OR t.account_id = :accountId)
          AND (:personId IS NULL OR COALESCE(t.person_id, a.person_id) = :personId)
        GROUP BY t.category_id
        ORDER BY total_minor DESC
        """,
    )
    fun observeCategoryTotals(
        type: String,
        start: LocalDate,
        end: LocalDate,
        accountId: Long?,
        personId: Long?,
    ): Flow<List<CategoryTotal>>

    /**
     * Money moved into and out of a pot — savings, or cash — over a period.
     *
     * Savings are not only an account balance. Somebody whose saver is with
     * another bank has nothing in this app to hold that balance, and every
     * standing order into it looked like ordinary spending — so the app showed
     * nothing saved by a household that was saving every month.
     *
     * What it can see is the payment moving, and the category on it says where
     * it went. Both directions are counted: money out of the account is money
     * into the pot, money back in is money out of it. Counting only the first
     * would report a month that emptied its saver as a month that saved.
     *
     * Cash works the same way and is the same query — taking £50 out of a
     * machine is not spending £50, it is £50 in a pocket.
     */
    @Query(
        """
        SELECT IFNULL(SUM(CASE WHEN t.type = 'EXPENSE' THEN t.amount_minor ELSE 0 END), 0)
                   AS into_pot_minor,
               IFNULL(SUM(CASE WHEN t.type = 'INCOME' THEN t.amount_minor ELSE 0 END), 0)
                   AS out_of_pot_minor
        FROM transactions t
        JOIN categories c ON c.id = t.category_id
        LEFT JOIN accounts a ON a.id = t.account_id
        WHERE t.is_archived = 0
          AND c.kind = :kind
          AND t.date BETWEEN :start AND :end
          AND (:accountId IS NULL OR t.account_id = :accountId)
          AND (:personId IS NULL OR COALESCE(t.person_id, a.person_id) = :personId)
        """,
    )
    fun observePotFlow(
        kind: String,
        start: LocalDate,
        end: LocalDate,
        accountId: Long?,
        personId: Long?,
    ): Flow<PotFlow?>

    @Query(
        """
        SELECT t.category_id AS category_id,
               IFNULL(c.name, 'Uncategorised') AS category_name,
               c.color_hex AS category_color,
               SUM(t.amount_minor) AS total_minor,
               COUNT(*) AS transaction_count
        FROM transactions t
        LEFT JOIN categories c ON c.id = t.category_id
        LEFT JOIN accounts a ON a.id = t.account_id
        WHERE t.is_archived = 0
          AND t.type = :type
          AND t.date BETWEEN :start AND :end
          AND (:accountId IS NULL OR t.account_id = :accountId)
          AND (:personId IS NULL OR COALESCE(t.person_id, a.person_id) = :personId)
        GROUP BY t.category_id
        ORDER BY total_minor DESC
        """,
    )
    suspend fun getCategoryTotals(
        type: String,
        start: LocalDate,
        end: LocalDate,
        accountId: Long?,
        personId: Long?,
    ): List<CategoryTotal>

    @Query(
        """
        SELECT substr(t.date, 1, 7) AS year_month,
               IFNULL(SUM(CASE WHEN t.type = 'INCOME' THEN t.amount_minor ELSE 0 END), 0) AS income_minor,
               IFNULL(SUM(CASE WHEN t.type = 'EXPENSE' THEN t.amount_minor ELSE 0 END), 0) AS expense_minor
        FROM transactions t
        LEFT JOIN accounts a ON a.id = t.account_id
        WHERE t.is_archived = 0
          AND t.date BETWEEN :start AND :end
          AND (:accountId IS NULL OR t.account_id = :accountId)
          AND (:personId IS NULL OR COALESCE(t.person_id, a.person_id) = :personId)
        GROUP BY year_month
        ORDER BY year_month ASC
        """,
    )
    fun observeMonthlyTotals(
        start: LocalDate,
        end: LocalDate,
        accountId: Long?,
        personId: Long?,
    ): Flow<List<MonthTotals>>

    @Query(
        """
        SELECT substr(t.date, 1, 7) AS year_month,
               IFNULL(SUM(CASE WHEN t.type = 'INCOME' THEN t.amount_minor ELSE 0 END), 0) AS income_minor,
               IFNULL(SUM(CASE WHEN t.type = 'EXPENSE' THEN t.amount_minor ELSE 0 END), 0) AS expense_minor
        FROM transactions t
        LEFT JOIN accounts a ON a.id = t.account_id
        WHERE t.is_archived = 0
          AND t.date BETWEEN :start AND :end
          AND (:accountId IS NULL OR t.account_id = :accountId)
          AND (:personId IS NULL OR COALESCE(t.person_id, a.person_id) = :personId)
        GROUP BY year_month
        ORDER BY year_month ASC
        """,
    )
    suspend fun getMonthlyTotals(
        start: LocalDate,
        end: LocalDate,
        accountId: Long?,
        personId: Long?,
    ): List<MonthTotals>

    @Query(
        """
        SELECT COALESCE(t.person_id, a.person_id) AS person_id,
               p.name AS person_name,
               p.color_hex AS person_color,
               IFNULL(SUM(CASE WHEN t.type = 'INCOME' THEN t.amount_minor ELSE 0 END), 0) AS income_minor,
               IFNULL(SUM(CASE WHEN t.type = 'EXPENSE' THEN t.amount_minor ELSE 0 END), 0) AS expense_minor
        FROM transactions t
        LEFT JOIN accounts a ON a.id = t.account_id
        LEFT JOIN people p ON p.id = COALESCE(t.person_id, a.person_id)
        WHERE t.is_archived = 0 AND t.date BETWEEN :start AND :end
        -- Grouped on the whole expression, not the output alias: both
        -- `transactions` and `accounts` carry a `person_id`, so a bare
        -- `GROUP BY person_id` is ambiguous to SQLite.
        GROUP BY COALESCE(t.person_id, a.person_id)
        ORDER BY income_minor DESC
        """,
    )
    fun observePersonTotals(start: LocalDate, end: LocalDate): Flow<List<PersonTotals>>

    /**
     * Money in and out of every account over a period, in one pass.
     *
     * The home screen shows a row per account, so asking per account would be
     * one query per row.
     */
    @Query(
        """
        SELECT account_id,
               IFNULL(SUM(CASE WHEN type = 'INCOME' THEN amount_minor ELSE 0 END), 0) AS income_minor,
               IFNULL(SUM(CASE WHEN type = 'EXPENSE' THEN amount_minor ELSE 0 END), 0) AS expense_minor
        FROM transactions
        WHERE is_archived = 0 AND date BETWEEN :start AND :end
        GROUP BY account_id
        """,
    )
    fun observeAccountActivity(start: LocalDate, end: LocalDate): Flow<List<AccountActivity>>

    /** Per-account totals for the month, used by the monthly rollover. */
    @Query(
        """
        SELECT
            IFNULL(SUM(CASE WHEN type = 'INCOME' THEN amount_minor ELSE 0 END), 0) AS income_minor,
            IFNULL(SUM(CASE WHEN type = 'EXPENSE' THEN amount_minor ELSE 0 END), 0) AS expense_minor
        FROM transactions
        WHERE is_archived = 0 AND account_id = :accountId AND date BETWEEN :start AND :end
        """,
    )
    suspend fun getAccountIncomeExpense(
        accountId: Long,
        start: LocalDate,
        end: LocalDate,
    ): IncomeExpenseTotals?

    @Query(
        """
        SELECT IFNULL(SUM(amount_minor), 0) FROM transactions
        WHERE is_archived = 0 AND type = 'TRANSFER' AND transfer_account_id = :accountId
          AND date BETWEEN :start AND :end
        """,
    )
    suspend fun getTransfersIn(accountId: Long, start: LocalDate, end: LocalDate): Long

    @Query(
        """
        SELECT IFNULL(SUM(amount_minor), 0) FROM transactions
        WHERE is_archived = 0 AND type = 'TRANSFER' AND account_id = :accountId
          AND date BETWEEN :start AND :end
        """,
    )
    suspend fun getTransfersOut(accountId: Long, start: LocalDate, end: LocalDate): Long

    @Query(
        """
        SELECT COUNT(*) FROM transactions
        WHERE is_archived = 0 AND (account_id = :accountId OR transfer_account_id = :accountId)
          AND date BETWEEN :start AND :end
        """,
    )
    suspend fun countForAccountBetween(accountId: Long, start: LocalDate, end: LocalDate): Int

    /**
     * True when an identical entry already exists.  The recurrence engine uses
     * this so that generating twice — after a restore, say — cannot create
     * duplicates.
     */
    @Query(
        """
        SELECT COUNT(*) > 0 FROM transactions
        WHERE recurring_rule_id = :ruleId AND date = :date AND is_archived = 0
        """,
    )
    suspend fun existsForRuleOnDate(ruleId: Long, date: LocalDate): Boolean

    // --------------------------------------------------------------- writes

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(transaction: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(transactions: List<TransactionEntity>): List<Long>

    @Update
    suspend fun update(transaction: TransactionEntity)

    @Delete
    suspend fun delete(transaction: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Deletes a named set of entries.
     *
     * Sent in batches by the repository: SQLite caps how many values an IN
     * clause may bind, and undoing a whole statement is well past it.
     */
    @Query("DELETE FROM transactions WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("UPDATE transactions SET is_archived = :archived, updated_at = :updatedAt WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean, updatedAt: Long)

    @Query("UPDATE transactions SET is_confirmed = 1, updated_at = :updatedAt WHERE id = :id")
    suspend fun confirm(id: Long, updatedAt: Long)

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()
}
