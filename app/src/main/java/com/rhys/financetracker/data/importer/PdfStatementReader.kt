package com.rhys.financetracker.data.importer

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.InputStream

/**
 * Reads a PDF bank statement into the same shape as a CSV export.
 *
 * Most banks only offer PDF in their app, so for a lot of people this is the
 * only statement they can actually get hold of.
 *
 * PDFBox does the text extraction. It is the one third-party library in the
 * app, and it earns its place: a PDF stores glyph codes and font encodings
 * rather than text, and getting that wrong produces plausible-looking wrong
 * figures rather than an obvious failure. That is the worst possible outcome
 * for financial data, so the extraction is left to a library that has been
 * tested against far more real documents than this app will ever see.
 *
 * What is *not* delegated is meaning: turning lines of text back into dated
 * amounts is [PdfStatementParser]'s job, and it checks its own work against
 * the running balance.
 */
object PdfStatementReader {

    /**
     * The statement in [input] as a one-sheet workbook.
     *
     * @throws IllegalStateException with a message meant for the user when the
     *   file cannot be read or does not look like a statement.
     */
    fun read(input: InputStream, fileName: String): WorkbookData {
        val text = extractText(input)
        val lines = text.split('\n').map { it.trimEnd() }

        val sheet = PdfStatementParser.toSheet(lines, fileName.removeSuffix(".pdf"))
            ?: error(
                "No transactions were found in that PDF. If it is a scanned or " +
                    "photographed statement the text cannot be read — download the " +
                    "statement again from your bank, or use a CSV export if one is offered.",
            )

        return WorkbookData(fileName = fileName, sheets = listOf(sheet))
    }

    private fun extractText(input: InputStream): String =
        try {
            PDDocument.load(input).use { document ->
                // Sorting by position keeps a row's date, payee and figures on
                // one line. Without it the text comes out in the order the page
                // happens to draw it, which for a table is not reading order.
                val stripper = PDFTextStripper().apply {
                    sortByPosition = true
                    lineSeparator = "\n"
                }
                stripper.getText(document)
            }
        } catch (locked: InvalidPasswordException) {
            error(
                "That PDF is password protected. Open it and save an unprotected " +
                    "copy, then import that.",
            )
        } catch (tooBig: OutOfMemoryError) {
            // A statement is small; anything that exhausts memory is not one.
            error("That PDF is too large to read on this device.")
        }
}
