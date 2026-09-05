package com.rhys.financetracker.data.backup

/**
 * The backup file format.
 *
 * A backup is plain JSON — readable, diffable, and restorable by hand in the
 * worst case.  A copy of the SQLite file would be smaller but would be tied to
 * one schema version and unreadable without the app.
 *
 * ## Compatibility rules
 *  * [FORMAT_VERSION] increases only when the *shape* of the file changes.
 *  * A restore accepts any version up to the current one.  Unknown fields are
 *    ignored and missing fields fall back to defaults, so a backup taken by an
 *    older build still restores into a newer one.
 */
object BackupFormat {
    const val FORMAT_VERSION = 1
    const val FILE_PREFIX = "finance-tracker-backup"
    const val FILE_EXTENSION = "json"
    const val MIME_TYPE = "application/json"

    // Top-level keys.
    const val KEY_FORMAT_VERSION = "formatVersion"
    const val KEY_APP_VERSION = "appVersion"
    const val KEY_CREATED_AT = "createdAt"
    const val KEY_DATABASE_VERSION = "databaseVersion"
    const val KEY_PEOPLE = "people"
    const val KEY_ACCOUNTS = "accounts"
    const val KEY_CATEGORIES = "categories"
    const val KEY_TRANSACTIONS = "transactions"
    const val KEY_RECURRING = "recurringRules"
    const val KEY_GOALS = "savingsGoals"
    const val KEY_SNAPSHOTS = "monthlySnapshots"
    const val KEY_EXTERNAL_DATA = "externalData"
}

/** What a backup contains, shown before a restore so the user knows what they are about to load. */
data class BackupSummary(
    val fileName: String,
    val createdAt: Long,
    val formatVersion: Int,
    val appVersion: String,
    val peopleCount: Int,
    val accountCount: Int,
    val transactionCount: Int,
    val recurringCount: Int,
    val goalCount: Int,
) {
    val isReadable: Boolean get() = formatVersion <= BackupFormat.FORMAT_VERSION

    fun describe(): String =
        "$transactionCount transactions · $accountCount accounts · $recurringCount regular payments"
}

/** The outcome of writing a backup. */
data class BackupResult(
    val fileName: String,
    val sizeBytes: Long,
    val transactionCount: Int,
)

/** The outcome of a restore. */
data class RestoreResult(
    val peopleRestored: Int,
    val accountsRestored: Int,
    val categoriesRestored: Int,
    val transactionsRestored: Int,
    val recurringRestored: Int,
    val goalsRestored: Int,
) {
    fun describe(): String =
        "$transactionsRestored transactions, $accountsRestored accounts and " +
            "$recurringRestored regular payments restored"
}
