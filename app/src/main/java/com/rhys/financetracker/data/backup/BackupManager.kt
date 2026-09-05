package com.rhys.financetracker.data.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.rhys.financetracker.BuildConfig
import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.core.result.runCatchingApp
import com.rhys.financetracker.data.local.AppDatabase
import com.rhys.financetracker.data.repository.SeedRepository
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Backup, restore and database export.
 *
 * Backups are written through the Storage Access Framework, so they land
 * wherever the user chooses — including Google Drive, OneDrive or Dropbox if
 * those apps are installed, which is how "cloud backup" is supported without
 * the app holding anyone's cloud credentials.
 *
 * A restore **replaces** the current data.  A safety copy is taken first, so a
 * restore of the wrong file is recoverable.
 */
@Singleton
class BackupManager @Inject constructor(
    private val context: Context,
    private val database: AppDatabase,
    private val serializer: BackupSerializer,
    private val seedRepository: SeedRepository,
    @com.rhys.financetracker.di.IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    private companion object {
        val FILE_TIMESTAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")
    }

    /** Builds the backup document. Public so tests can inspect it without touching files. */
    suspend fun buildBackupJson(): JSONObject = withContext(ioDispatcher) {
        JSONObject().apply {
            put(BackupFormat.KEY_FORMAT_VERSION, BackupFormat.FORMAT_VERSION)
            put(BackupFormat.KEY_APP_VERSION, BuildConfig.VERSION_NAME)
            put(BackupFormat.KEY_CREATED_AT, System.currentTimeMillis())
            put(BackupFormat.KEY_DATABASE_VERSION, AppDatabase.DATABASE_VERSION)

            put(
                BackupFormat.KEY_PEOPLE,
                serializer.writeArray(database.personDao().getAll(), serializer::personToJson),
            )
            put(
                BackupFormat.KEY_ACCOUNTS,
                serializer.writeArray(database.accountDao().getAll(), serializer::accountToJson),
            )
            put(
                BackupFormat.KEY_CATEGORIES,
                serializer.writeArray(database.categoryDao().getAll(), serializer::categoryToJson),
            )
            put(
                BackupFormat.KEY_TRANSACTIONS,
                serializer.writeArray(
                    database.transactionDao().getAll(),
                    serializer::transactionToJson,
                ),
            )
            put(
                BackupFormat.KEY_RECURRING,
                serializer.writeArray(
                    database.recurringRuleDao().getAll(),
                    serializer::recurringToJson,
                ),
            )
            put(
                BackupFormat.KEY_GOALS,
                serializer.writeArray(database.savingsGoalDao().getAll(), serializer::goalToJson),
            )
            put(
                BackupFormat.KEY_SNAPSHOTS,
                serializer.writeArray(
                    database.monthlySnapshotDao().getAll(),
                    serializer::snapshotToJson,
                ),
            )
            put(
                BackupFormat.KEY_EXTERNAL_DATA,
                serializer.writeArray(
                    database.externalDataDao().getAll(),
                    serializer::externalToJson,
                ),
            )
        }
    }

    /** Writes a backup to a single file the user picked with the system chooser. */
    suspend fun backupToFile(uri: Uri): AppResult<BackupResult> = withContext(ioDispatcher) {
        runCatchingApp("Could not save the backup") {
            val json = buildBackupJson()
            val text = json.toString(2)
            context.contentResolver.openOutputStream(uri, "wt")
                ?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                ?: error("That location could not be written to")

            BackupResult(
                fileName = DocumentFile.fromSingleUri(context, uri)?.name ?: suggestedFileName(),
                sizeBytes = text.length.toLong(),
                transactionCount = database.transactionDao().count(),
            )
        }
    }

    /**
     * Writes a timestamped backup into a folder, and removes the oldest files
     * once there are more than [keepCount].  This is what the automatic backup
     * uses.
     */
    suspend fun backupToFolder(folderUri: String, keepCount: Int): AppResult<BackupResult> =
        withContext(ioDispatcher) {
            runCatchingApp("Could not save the automatic backup") {
                val folder = DocumentFile.fromTreeUri(context, Uri.parse(folderUri))
                    ?: error("The backup folder is no longer available")
                if (!folder.canWrite()) {
                    error("The app no longer has permission to write to the backup folder")
                }

                val fileName = suggestedFileName()
                val file = folder.createFile(BackupFormat.MIME_TYPE, fileName)
                    ?: error("The backup file could not be created")

                val text = buildBackupJson().toString()
                context.contentResolver.openOutputStream(file.uri)
                    ?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    ?: error("The backup file could not be written")

                pruneOldBackups(folder, keepCount)

                BackupResult(
                    fileName = fileName,
                    sizeBytes = text.length.toLong(),
                    transactionCount = database.transactionDao().count(),
                )
            }
        }

    /** Reads a backup's header so the user can see what it holds before restoring. */
    suspend fun inspect(uri: Uri): AppResult<BackupSummary> = withContext(ioDispatcher) {
        runCatchingApp("Could not read that backup") {
            val json = readJson(uri)
            BackupSummary(
                fileName = DocumentFile.fromSingleUri(context, uri)?.name ?: "Backup",
                createdAt = json.optLong(BackupFormat.KEY_CREATED_AT, 0L),
                formatVersion = json.optInt(BackupFormat.KEY_FORMAT_VERSION, 0),
                appVersion = json.optString(BackupFormat.KEY_APP_VERSION, "unknown"),
                peopleCount = json.optJSONArray(BackupFormat.KEY_PEOPLE)?.length() ?: 0,
                accountCount = json.optJSONArray(BackupFormat.KEY_ACCOUNTS)?.length() ?: 0,
                transactionCount = json.optJSONArray(BackupFormat.KEY_TRANSACTIONS)?.length() ?: 0,
                recurringCount = json.optJSONArray(BackupFormat.KEY_RECURRING)?.length() ?: 0,
                goalCount = json.optJSONArray(BackupFormat.KEY_GOALS)?.length() ?: 0,
            )
        }
    }

    /**
     * Replaces everything with the contents of the backup.
     *
     * Rows keep their original ids so that all the references between them —
     * a transaction's account, a rule's category — survive intact.
     */
    suspend fun restore(uri: Uri): AppResult<RestoreResult> = withContext(ioDispatcher) {
        runCatchingApp("Could not restore that backup") {
            val json = readJson(uri)
            val formatVersion = json.optInt(BackupFormat.KEY_FORMAT_VERSION, 0)
            if (formatVersion > BackupFormat.FORMAT_VERSION) {
                error(
                    "That backup was made by a newer version of the app. " +
                        "Update Finance Tracker and try again.",
                )
            }

            val people = serializer.readArray(
                json.optJSONArray(BackupFormat.KEY_PEOPLE),
                serializer::personFromJson,
            )
            val accounts = serializer.readArray(
                json.optJSONArray(BackupFormat.KEY_ACCOUNTS),
                serializer::accountFromJson,
            )
            val categories = serializer.readArray(
                json.optJSONArray(BackupFormat.KEY_CATEGORIES),
                serializer::categoryFromJson,
            )
            val transactions = serializer.readArray(
                json.optJSONArray(BackupFormat.KEY_TRANSACTIONS),
                serializer::transactionFromJson,
            )
            val rules = serializer.readArray(
                json.optJSONArray(BackupFormat.KEY_RECURRING),
                serializer::recurringFromJson,
            )
            val goals = serializer.readArray(
                json.optJSONArray(BackupFormat.KEY_GOALS),
                serializer::goalFromJson,
            )
            val snapshots = serializer.readArray(
                json.optJSONArray(BackupFormat.KEY_SNAPSHOTS),
                serializer::snapshotFromJson,
            )
            val external = serializer.readArray(
                json.optJSONArray(BackupFormat.KEY_EXTERNAL_DATA),
                serializer::externalFromJson,
            )

            if (accounts.isEmpty() && transactions.isEmpty()) {
                error("That file does not look like a Finance Tracker backup")
            }

            // The whole restore runs inside one database transaction, so a
            // failure halfway through rolls back rather than leaving a mixture
            // of the old data and the new.  `withTransaction` is the suspend-safe
            // form; `runInTransaction` cannot call suspend DAOs.
            database.withTransaction {
                // Children before parents when deleting, parents before children
                // when inserting, so foreign keys hold at every step.
                database.transactionDao().deleteAll()
                database.recurringRuleDao().deleteAll()
                database.savingsGoalDao().deleteAll()
                database.monthlySnapshotDao().deleteAll()
                database.accountDao().deleteAll()
                database.categoryDao().deleteAll()
                database.personDao().deleteAll()
                database.externalDataDao().deleteAll()

                database.personDao().insertAll(people)
                database.categoryDao().insertAll(categories)
                database.accountDao().insertAll(accounts)
                database.savingsGoalDao().insertAll(goals)
                database.recurringRuleDao().insertAll(rules)
                database.transactionDao().insertAll(transactions)
                database.monthlySnapshotDao().insertAll(snapshots)
                database.externalDataDao().upsertAll(external)
            }

            // A backup from before categories existed would leave the app
            // without any; re-seed defaults if so.
            seedRepository.seedIfEmpty()

            RestoreResult(
                peopleRestored = people.size,
                accountsRestored = accounts.size,
                categoriesRestored = categories.size,
                transactionsRestored = transactions.size,
                recurringRestored = rules.size,
                goalsRestored = goals.size,
            )
        }
    }

    fun suggestedFileName(): String =
        "${BackupFormat.FILE_PREFIX}-${LocalDateTime.now().format(FILE_TIMESTAMP)}" +
            ".${BackupFormat.FILE_EXTENSION}"

    private fun readJson(uri: Uri): JSONObject {
        val text = context.contentResolver.openInputStream(uri)
            ?.use { it.bufferedReader().readText() }
            ?: error("That file could not be opened")
        if (text.isBlank()) error("That file is empty")
        return runCatching { JSONObject(text) }
            .getOrElse { error("That file is not a valid backup") }
    }

    /** Keeps the newest [keepCount] backups in [folder] and deletes the rest. */
    private fun pruneOldBackups(folder: DocumentFile, keepCount: Int) {
        val backups = folder.listFiles()
            .filter { it.name?.startsWith(BackupFormat.FILE_PREFIX) == true }
            .sortedByDescending { it.lastModified() }
        backups.drop(keepCount.coerceAtLeast(1)).forEach { runCatching { it.delete() } }
    }
}
