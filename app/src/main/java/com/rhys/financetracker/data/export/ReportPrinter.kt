package com.rhys.financetracker.data.export

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands a produced PDF to Android's print system, which then offers every
 * printer the phone can see plus "Save as PDF".
 *
 * The PDF is generated first and simply streamed to the printer, rather than
 * being re-laid-out for each print job.  That guarantees the printed page is
 * byte-for-byte what the user previewed and shared.
 */
@Singleton
class ReportPrinter @Inject constructor(
    private val context: Context,
) {

    /**
     * @param file a PDF produced by [PdfReportGenerator].
     * @param jobName shown in the print queue.
     */
    fun print(file: File, jobName: String, landscape: Boolean) {
        val printManager = context.getSystemService<PrintManager>() ?: return
        val attributes = PrintAttributes.Builder()
            .setMediaSize(
                if (landscape) PrintAttributes.MediaSize.ISO_A4.asLandscape()
                else PrintAttributes.MediaSize.ISO_A4.asPortrait(),
            )
            .setResolution(PrintAttributes.Resolution("pdf", "pdf", 300, 300))
            // The PDF already contains its own margins, so the print system
            // should not add a second set and shrink the content.
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()

        printManager.print(jobName, FilePrintAdapter(file, jobName), attributes)
    }

    /** Streams an existing PDF file to the print framework. */
    private class FilePrintAdapter(
        private val file: File,
        private val jobName: String,
    ) : PrintDocumentAdapter() {

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes?,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback?,
            extras: Bundle?,
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback?.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder(jobName)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                .build()
            callback?.onLayoutFinished(info, /* changed = */ true)
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor?,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback?,
        ) {
            if (destination == null) {
                callback?.onWriteFailed("There was nowhere to write the document")
                return
            }
            try {
                FileInputStream(file).use { input ->
                    FileOutputStream(destination.fileDescriptor).use { output ->
                        input.copyTo(output)
                    }
                }
                callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (error: Exception) {
                callback?.onWriteFailed(error.message ?: "The document could not be printed")
            }
        }
    }
}
