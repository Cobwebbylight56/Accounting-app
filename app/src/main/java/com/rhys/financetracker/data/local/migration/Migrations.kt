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

    /** Registered with Room in `di/DatabaseModule.kt`. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
