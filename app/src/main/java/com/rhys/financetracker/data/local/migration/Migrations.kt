package com.rhys.financetracker.data.local.migration

import androidx.room.migration.Migration

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

    /** Registered with Room in `di/DatabaseModule.kt`. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
}
