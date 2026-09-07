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
import androidx.core.content.getSystemService
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
 *
 * ## Why the context is a parameter
 *
 * Printing puts a window on the screen, and Android will only do that for a
 * context that belongs to an activity. This class was injected with the
 * application context, so every print either threw or did nothing at all —
 * the button worked, and no printer dialog ever appeared. The caller passes
 * the context it is drawn in instead, which is the activity's.
 */
@Singleton
class ReportPrinter @Inject constructor() {

    /**
     * @param context must come from an activity — `LocalContext.current` in a
     *   composable is one. An application context silently prints nothing.
     * @param file a PDF produced by [PdfReportGenerator].
     * @param jobName shown in the print queue.
     * @return null on success, or a message to show the user.
     */
    fun print(context: Context, file: File, jobName: String, landscape: Boolean): String? {
        val printManager = context.getSystemService<PrintManager>()
            ?: return "This device has no printing service."
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

        // Printing needs an activity to put its dialog on. Reaching here with
        // anything else throws, and the throw used to take the app down rather
        // than say what went wrong.
        return runCatching {
            printManager.print(jobName, FilePrintAdapter(file, jobName), attributes)
        }.exceptionOrNull()?.let {
            "Android would not open the print dialog. Export as PDF and print that instead."
        }
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
