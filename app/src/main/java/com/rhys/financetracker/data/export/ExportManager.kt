package com.rhys.financetracker.data.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.core.result.runCatchingApp
import com.rhys.financetracker.data.local.projection.TransactionWithDetails
import com.rhys.financetracker.domain.model.ExportFormat
import com.rhys.financetracker.domain.model.PageOrientation
import com.rhys.financetracker.domain.report.Report
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Produces the files the user shares, prints or keeps.
 *
 * Two destinations are supported:
 *  * a **cache file** that is then shared through [FileProvider] — the quickest
 *    route to "send this to my accountant"; and
 *  * a **document the user picked** with the system file chooser, for saving
 *    somewhere permanent.
 */
@Singleton
class ExportManager @Inject constructor(
    private val context: Context,
    private val csvExporter: CsvExporter,
    private val xlsxWriter: XlsxWriter,
    private val pdfGenerator: PdfReportGenerator,
    @com.rhys.financetracker.di.IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    private companion object {
        const val EXPORT_DIRECTORY = "exports"
        val FILE_TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")
    }

    /** Writes [report] into the cache and returns a shareable file. */
    suspend fun exportReportToCache(
        report: Report,
        format: ExportFormat,
        orientation: PageOrientation = PageOrientation.PORTRAIT,
    ): AppResult<ExportedFile> = withContext(ioDispatcher) {
        runCatchingApp("Could not create the ${format.displayName} file") {
            val file = newCacheFile(report.title, format)
            file.outputStream().use { output ->
                when (format) {
                    ExportFormat.PDF -> pdfGenerator.write(report, output, orientation)
                    ExportFormat.CSV ->
                        output.write(csvExporter.exportReport(report).toByteArray(Charsets.UTF_8))
                    ExportFormat.XLSX -> xlsxWriter.writeReport(output, report)
                    ExportFormat.JSON ->
                        error("Use Backup to produce a JSON file")
                }
            }
            ExportedFile(file = file, uri = shareUri(file), format = format)
        }
    }

    /** Writes the transaction ledger into the cache. */
    suspend fun exportTransactionsToCache(
        transactions: List<TransactionWithDetails>,
        format: ExportFormat,
    ): AppResult<ExportedFile> = withContext(ioDispatcher) {
        runCatchingApp("Could not create the ${format.displayName} file") {
            val file = newCacheFile("transactions", format)
            file.outputStream().use { output ->
                when (format) {
                    ExportFormat.CSV -> output.write(
                        csvExporter.exportTransactions(transactions).toByteArray(Charsets.UTF_8),
                    )
                    ExportFormat.XLSX -> xlsxWriter.writeTransactions(output, transactions)
                    else -> error("Transactions can be exported as CSV or Excel")
                }
            }
            ExportedFile(file = file, uri = shareUri(file), format = format)
        }
    }

    /** Writes a report straight into a document the user chose. */
    suspend fun exportReportToUri(
        report: Report,
        format: ExportFormat,
        uri: Uri,
        orientation: PageOrientation = PageOrientation.PORTRAIT,
    ): AppResult<Unit> = withContext(ioDispatcher) {
        runCatchingApp("Could not save the file") {
            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                when (format) {
                    ExportFormat.PDF -> pdfGenerator.write(report, output, orientation)
                    ExportFormat.CSV ->
                        output.write(csvExporter.exportReport(report).toByteArray(Charsets.UTF_8))
                    ExportFormat.XLSX -> xlsxWriter.writeReport(output, report)
                    ExportFormat.JSON -> error("Use Backup to produce a JSON file")
                }
            } ?: error("That location could not be written to")
        }
    }

    /** Builds the chooser intent for sending a produced file to another app. */
    fun shareIntent(exported: ExportedFile, subject: String): Intent {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = exported.format.mimeType
            putExtra(Intent.EXTRA_STREAM, exported.uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(intent, "Share $subject")
    }

    /** A suggested file name for the system "save as" dialogue. */
    fun suggestedFileName(title: String, format: ExportFormat): String =
        "${slug(title)}-${LocalDateTime.now().format(FILE_TIMESTAMP)}.${format.extension}"

    private fun newCacheFile(title: String, format: ExportFormat): File {
        val directory = File(context.cacheDir, EXPORT_DIRECTORY).apply { mkdirs() }
        // Old exports are disposable; clearing them keeps the cache from growing.
        directory.listFiles()
            ?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L }
            ?.forEach { it.delete() }
        return File(directory, suggestedFileName(title, format))
    }

    private fun shareUri(file: File): Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )

    private fun slug(value: String): String = value
        .lowercase()
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .replace(Regex("-+"), "-")
        .trim('-')
        .ifBlank { "export" }
}

/** A file that has been written and is ready to share or print. */
data class ExportedFile(
    val file: File,
    val uri: Uri,
    val format: ExportFormat,
) {
    val name: String get() = file.name
    val sizeBytes: Long get() = file.length()
}
