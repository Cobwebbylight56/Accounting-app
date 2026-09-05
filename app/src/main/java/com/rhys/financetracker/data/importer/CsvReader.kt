package com.rhys.financetracker.data.importer

import java.io.InputStream

/**
 * Reads CSV and tab-separated files.
 *
 * CSV matters because it is the format every spreadsheet program can export,
 * including Google Sheets and the mobile version of Excel, so it is the escape
 * hatch when an `.xlsx` will not open.
 *
 * The parser handles the awkward parts of real-world CSV: quoted fields,
 * embedded commas and newlines, and doubled quotes (`""`) meaning one quote.
 */
object CsvReader {

    private const val MAX_ROWS = 100_000

    fun read(input: InputStream, fileName: String): WorkbookData {
        val text = input.bufferedReader().use { it.readText() }
        val delimiter = detectDelimiter(text)
        return WorkbookData(
            fileName = fileName,
            sheets = listOf(SheetData(name = fileName, rows = parse(text, delimiter))),
        )
    }

    /**
     * Picks the delimiter by counting candidates on the first few lines — files
     * exported in a European locale often use `;`, and copy-pasted data is
     * usually tab separated.
     */
    internal fun detectDelimiter(text: String): Char {
        val sample = text.lineSequence().take(5).joinToString("\n")
        val counts = listOf(',', ';', '\t', '|').associateWith { candidate ->
            sample.count { it == candidate }
        }
        return counts.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key ?: ','
    }

    internal fun parse(text: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var index = 0

        while (index < text.length) {
            val character = text[index]
            when {
                inQuotes && character == '"' ->
                    if (index + 1 < text.length && text[index + 1] == '"') {
                        field.append('"')
                        index++
                    } else {
                        inQuotes = false
                    }

                character == '"' -> inQuotes = true
                !inQuotes && character == delimiter -> {
                    row.add(field.toString().trim())
                    field.setLength(0)
                }
                !inQuotes && (character == '\n' || character == '\r') -> {
                    // Swallow the second half of a CRLF pair.
                    if (character == '\r' && index + 1 < text.length && text[index + 1] == '\n') {
                        index++
                    }
                    row.add(field.toString().trim())
                    field.setLength(0)
                    rows.add(row.toList())
                    row = mutableListOf()
                    if (rows.size >= MAX_ROWS) return rows
                }
                else -> field.append(character)
            }
            index++
        }

        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString().trim())
            rows.add(row.toList())
        }
        return rows
    }
}
