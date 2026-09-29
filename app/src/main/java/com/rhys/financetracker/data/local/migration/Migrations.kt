package com.rhys.financetracker.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.rhys.financetracker.data.importer.AccountNaming
import com.rhys.financetracker.domain.model.Holding

/**
 * Schema migrations, oldest first.
 *
 * Every migration must be additive or must copy data across to a new table: a
 * migration that drops a column loses history, and not losing history is the
 * point of the application.
 */
object Migrations {

    /**
     * Adds the import fingerprint used to recognise a re-imported statement.
     *
     * Existing rows are left null. They were typed in or came from the
     * spreadsheet import, so there is nothing to match them against, and a
     * null simply never matches — the worst case is that a statement covering
     * a period already entered by hand offers those rows as new, which is
     * visible on the review screen before anything is saved.
     */
    val MIGRATION_1_2 = Migration(1, 2) { db ->
        db.execSQL("ALTER TABLE transactions ADD COLUMN import_hash TEXT")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_transactions_import_hash " +
                "ON transactions (import_hash)",
        )
    }

    /**
     * Records where each transaction came from, so a bank statement can
     * correct a remembered entry rather than sitting beside it as a second
     * copy of the same payment.
     *
     * Existing rows become UNKNOWN rather than being guessed at. A stored
     * import hash says a row was imported but not from what — the spreadsheet
     * import and the statement import both set one — and labelling somebody's
     * hand-built spreadsheet as bank-authoritative would protect it from the
     * very correction this exists to allow. UNKNOWN is the weakest source, so
     * the effect is that everything already in the ledger can be improved by a
     * statement and nothing is wrongly shielded.
     */
    val MIGRATION_2_3 = Migration(2, 3) { db ->
        db.execSQL(
            "ALTER TABLE transactions ADD COLUMN source TEXT NOT NULL DEFAULT 'UNKNOWN'",
        )
    }

    /**
     * Lets an account be counted as savings whatever its type says.
     *
     * Nullable on purpose: null means "no opinion, follow the type", which is
     * true of every account that existed before there was a way to say
     * otherwise. A NOT NULL column would have had to invent an answer for all
     * of them.
     */
    val MIGRATION_3_4 = Migration(3, 4) { db ->
        db.execSQL("ALTER TABLE accounts ADD COLUMN counts_as_savings INTEGER")
    }

    /**
     * The savings redesign: one answer per account to "where does this money
     * count?", a cash pot that is not an account, and yearly pay per person.
     *
     * In order:
     *
     * 1. **Cash accounts become the cash pot.** Their starting balance and
     *    every entry on them are copied into `cash_pot_entries`, so the pot
     *    holds exactly what the accounts held. A transfer between a bank
     *    account and a cash account keeps its effect on the bank account — it
     *    becomes a cash withdrawal or a cash paying-in there — so no bank
     *    balance moves by a penny. Then the cash accounts, and everything
     *    that only existed on them, are removed.
     * 2. **`counts_as_savings` becomes `holding`.** The table is rebuilt
     *    because SQLite on older phones cannot drop a column. Each account
     *    gets the answer it was effectively giving before — the override
     *    where one was set, the type where not — and then one correction:
     *    an account whose *name* says savings (a "saver", an ISA, Start to
     *    Save) is set aside even if it was typed as a current account. That
     *    was the case the old design could not fix, because its suggestion
     *    was suppressed once the override had ever been touched.
     * 3. **Movements on set-aside accounts are filed as savings**, other than
     *    interest. £200 arriving in a saver is money moved, not money earned,
     *    and counting it as income is what put savings "in with everything
     *    else".
     * 4. **People gain yearly gross and net pay**, both empty to begin with.
     *
     * Foreign keys are not enforced while a migration runs, which is what
     * makes dropping and renaming the accounts table safe: the transactions
     * that point at it are untouched, and point at the rebuilt table after.
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `cash_pot_entries` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`date` TEXT NOT NULL, `amount_minor` INTEGER NOT NULL, " +
                    "`is_in` INTEGER NOT NULL, `note` TEXT, `created_at` INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_cash_pot_entries_date` " +
                    "ON `cash_pot_entries` (`date`)",
            )
            moveCashAccountsIntoThePot(db)
            val setAsideByName = accountsNamedAsSavings(db)
            rebuildAccountsWithHolding(db)
            if (setAsideByName.isNotEmpty()) {
                db.execSQL(
                    "UPDATE accounts SET holding = 'SET_ASIDE' " +
                        "WHERE holding = 'SPEND' AND id IN (${setAsideByName.joinToString(",")})",
                )
            }
            mergeLookalikePotCategories(db)
            fileSetAsideMovementsAsSavings(db)
            db.execSQL("ALTER TABLE people ADD COLUMN gross_yearly_income_minor INTEGER")
            db.execSQL("ALTER TABLE people ADD COLUMN net_yearly_income_minor INTEGER")
        }
    }

    private const val CASH_ACCOUNTS = "(SELECT id FROM accounts WHERE type = 'CASH')"
    private const val LIVE_CASH_ACCOUNTS =
        "(SELECT id FROM accounts WHERE type = 'CASH' AND is_archived = 0)"

    private fun moveCashAccountsIntoThePot(db: SupportSQLiteDatabase) {
        val cashCategory = firstId(db, "SELECT id FROM categories WHERE kind = 'CASH' ORDER BY id LIMIT 1")
            ?.toString() ?: "NULL"

        // Cash moved from one tin to another never left the pot.
        db.execSQL(
            "DELETE FROM transactions WHERE type = 'TRANSFER' " +
                "AND account_id IN $CASH_ACCOUNTS AND transfer_account_id IN $CASH_ACCOUNTS",
        )

        // What was already in the tin when it was set up.
        db.execSQL(
            "INSERT INTO cash_pot_entries (date, amount_minor, is_in, note, created_at) " +
                "SELECT opening_balance_date, ABS(opening_balance_minor), " +
                "CASE WHEN opening_balance_minor > 0 THEN 1 ELSE 0 END, " +
                "'Already in the pot', created_at FROM accounts " +
                "WHERE id IN $LIVE_CASH_ACCOUNTS AND opening_balance_minor <> 0",
        )
        // Every entry on a cash account: in for money received, out otherwise.
        db.execSQL(
            "INSERT INTO cash_pot_entries (date, amount_minor, is_in, note, created_at) " +
                "SELECT date, amount_minor, CASE WHEN type = 'INCOME' THEN 1 ELSE 0 END, " +
                "description, created_at FROM transactions " +
                "WHERE is_archived = 0 AND account_id IN $LIVE_CASH_ACCOUNTS",
        )
        // Transfers into a cash account from a bank account.
        db.execSQL(
            "INSERT INTO cash_pot_entries (date, amount_minor, is_in, note, created_at) " +
                "SELECT date, amount_minor, 1, description, created_at FROM transactions " +
                "WHERE is_archived = 0 AND type = 'TRANSFER' " +
                "AND transfer_account_id IN $LIVE_CASH_ACCOUNTS",
        )

        // The bank side of those transfers keeps its effect on the bank:
        // money into the tin is cash taken out, money out of it is cash paid in.
        db.execSQL(
            "UPDATE transactions SET type = 'EXPENSE', transfer_account_id = NULL, " +
                "category_id = $cashCategory " +
                "WHERE type = 'TRANSFER' AND transfer_account_id IN $CASH_ACCOUNTS",
        )
        db.execSQL(
            "UPDATE transactions SET type = 'INCOME', account_id = transfer_account_id, " +
                "transfer_account_id = NULL, category_id = $cashCategory " +
                "WHERE type = 'TRANSFER' AND account_id IN $CASH_ACCOUNTS " +
                "AND transfer_account_id IS NOT NULL",
        )

        // Now nothing on a cash account affects anything else.
        db.execSQL("DELETE FROM transactions WHERE account_id IN $CASH_ACCOUNTS")
        db.execSQL("DELETE FROM monthly_snapshots WHERE account_id IN $CASH_ACCOUNTS")
        db.execSQL("DELETE FROM recurring_rules WHERE account_id IN $CASH_ACCOUNTS")
        db.execSQL(
            "UPDATE recurring_rules SET type = 'EXPENSE', transfer_account_id = NULL, " +
                "category_id = $cashCategory WHERE transfer_account_id IN $CASH_ACCOUNTS",
        )
        db.execSQL("UPDATE savings_goals SET account_id = NULL WHERE account_id IN $CASH_ACCOUNTS")
        db.execSQL("DELETE FROM accounts WHERE type = 'CASH'")
    }

    /** Accounts whose name says savings, whatever they were typed as. */
    private fun accountsNamedAsSavings(db: SupportSQLiteDatabase): List<Long> {
        val ids = mutableListOf<Long>()
        db.query(
            "SELECT id, name FROM accounts " +
                "WHERE type NOT IN ('CREDIT_CARD', 'LOAN', 'MORTGAGE')",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1) ?: continue
                if (AccountNaming.holdingFor(name) == Holding.SET_ASIDE) ids += cursor.getLong(0)
            }
        }
        return ids
    }

    private fun rebuildAccountsWithHolding(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `accounts_new` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                "`type` TEXT NOT NULL, `person_id` INTEGER, " +
                "`opening_balance_minor` INTEGER NOT NULL, `opening_balance_date` TEXT NOT NULL, " +
                "`currency_code` TEXT NOT NULL, `overdraft_limit_minor` INTEGER NOT NULL, " +
                "`low_balance_threshold_minor` INTEGER, `credit_limit_minor` INTEGER, " +
                "`interest_rate_percent` REAL, `color_hex` TEXT NOT NULL, " +
                "`include_in_net_worth` INTEGER NOT NULL, `holding` TEXT NOT NULL, " +
                "`is_shared` INTEGER NOT NULL, `sort_order` INTEGER NOT NULL, `notes` TEXT, " +
                "`is_archived` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, " +
                "`updated_at` INTEGER NOT NULL, " +
                "FOREIGN KEY(`person_id`) REFERENCES `people`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE SET NULL )",
        )
        db.execSQL(
            "INSERT INTO accounts_new (id, name, type, person_id, opening_balance_minor, " +
                "opening_balance_date, currency_code, overdraft_limit_minor, " +
                "low_balance_threshold_minor, credit_limit_minor, interest_rate_percent, " +
                "color_hex, include_in_net_worth, holding, is_shared, sort_order, notes, " +
                "is_archived, created_at, updated_at) " +
                "SELECT id, name, type, person_id, opening_balance_minor, " +
                "opening_balance_date, currency_code, overdraft_limit_minor, " +
                "low_balance_threshold_minor, credit_limit_minor, interest_rate_percent, " +
                "color_hex, include_in_net_worth, " +
                "CASE " +
                "WHEN type IN ('CREDIT_CARD', 'LOAN', 'MORTGAGE') THEN 'OWED' " +
                "WHEN counts_as_savings = 1 THEN 'SET_ASIDE' " +
                "WHEN counts_as_savings = 0 THEN 'SPEND' " +
                "WHEN type IN ('SAVINGS', 'INVESTMENT', 'PENSION') THEN 'SET_ASIDE' " +
                "ELSE 'SPEND' END, " +
                "is_shared, sort_order, notes, is_archived, created_at, updated_at " +
                "FROM accounts",
        )
        db.execSQL("DROP TABLE accounts")
        db.execSQL("ALTER TABLE accounts_new RENAME TO accounts")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_accounts_person_id` ON `accounts` (`person_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_accounts_name` ON `accounts` (`name`)")
    }

    /**
     * Points everything filed under an ordinary category called "Savings" (or
     * "Cash") at the real one.
     *
     * The statement import looked categories up among income or expense
     * only, so a row it had correctly read as savings was filed under a new
     * income or expense category of the same name. Savings never saw any of
     * those rows. The copies are archived rather than deleted, so nothing in
     * history loses its label.
     */
    private fun mergeLookalikePotCategories(db: SupportSQLiteDatabase) {
        val lookalike = "SELECT c.id FROM categories c WHERE c.kind IN ('INCOME', 'EXPENSE') " +
            "AND EXISTS (SELECT 1 FROM categories p WHERE p.kind IN ('SAVING', 'CASH') " +
            "AND LOWER(p.name) = LOWER(c.name))"
        val realOne = "(SELECT p.id FROM categories p WHERE p.kind IN ('SAVING', 'CASH') " +
            "AND LOWER(p.name) = LOWER((SELECT c.name FROM categories c " +
            "WHERE c.id = %s.category_id)) ORDER BY p.id LIMIT 1)"
        db.execSQL(
            "UPDATE transactions SET category_id = ${realOne.format("transactions")} " +
                "WHERE category_id IN ($lookalike)",
        )
        db.execSQL(
            "UPDATE recurring_rules SET category_id = ${realOne.format("recurring_rules")} " +
                "WHERE category_id IN ($lookalike)",
        )
        db.execSQL("UPDATE categories SET is_archived = 1 WHERE id IN ($lookalike)")
    }

    private fun fileSetAsideMovementsAsSavings(db: SupportSQLiteDatabase) {
        val savings = firstId(
            db,
            "SELECT id FROM categories WHERE kind = 'SAVING' ORDER BY id LIMIT 1",
        ) ?: return
        db.execSQL(
            "UPDATE transactions SET category_id = $savings " +
                "WHERE type IN ('INCOME', 'EXPENSE') " +
                "AND account_id IN (SELECT id FROM accounts WHERE holding = 'SET_ASIDE') " +
                "AND (category_id IS NULL OR category_id NOT IN (" +
                "SELECT id FROM categories WHERE kind = 'SAVING' " +
                "OR LOWER(name) LIKE '%interest%'))",
        )
    }

    private fun firstId(db: SupportSQLiteDatabase, sql: String): Long? =
        db.query(sql).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }

    /**
     * Pay history: every pay rise, yearly review or new job, with the pay
     * before and after and the day it started. Purely additive; the people
     * table already holds what everybody earns now.
     */
    val MIGRATION_5_6 = Migration(5, 6) { db ->
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `income_changes` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`person_id` INTEGER NOT NULL, `effective_date` TEXT NOT NULL, " +
                "`previous_gross_minor` INTEGER, `previous_net_minor` INTEGER, " +
                "`new_gross_minor` INTEGER, `new_net_minor` INTEGER, " +
                "`net_is_estimate` INTEGER NOT NULL, `reason` TEXT, " +
                "`is_applied` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, " +
                "FOREIGN KEY(`person_id`) REFERENCES `people`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_income_changes_person_id` " +
                "ON `income_changes` (`person_id`)",
        )
    }

    /** Registered with Room in `di/DatabaseModule.kt`. */
    val ALL: Array<Migration> =
        arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
}
