package com.rhys.financetracker.data.local.migration

import androidx.room.migration.Migration

/**
 * Schema migrations, oldest first.
 *
 * Version 1 is the initial schema, so there is nothing to migrate yet.  When
 * the schema changes, add a migration here — for example:
 *
 * ```kotlin
 * val MIGRATION_1_2 = Migration(1, 2) { db ->
 *     db.execSQL("ALTER TABLE accounts ADD COLUMN sort_code TEXT")
 * }
 * ```
 *
 * and add it to [ALL].  Every migration must be additive or must copy data
 * across to a new table: a migration that drops a column loses history.
 */
object Migrations {

    /** Registered with Room in `di/DatabaseModule.kt`. */
    val ALL: Array<Migration> = emptyArray()
}
