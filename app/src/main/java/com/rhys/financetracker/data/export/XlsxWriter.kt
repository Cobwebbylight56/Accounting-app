package com.rhys.financetracker.data.export

import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.local.projection.TransactionWithDetails
import com.rhys.financetracker.domain.report.Report
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes real `.xlsx` workbooks without a third-party library.
 *
 * An xlsx file is a ZIP of a handful of XML parts.  Writing the minimum set by
 * hand keeps the APK small and avoids Apache POI, which does not sit well on
 * Android.  Text is written as an inline string (`t="inlineStr"`), which skips
 * the shared-string table entirely; numbers are written as numbers so that
 * totals and charts work in Excel.
 */
@Singleton
class XlsxWriter @Inject constructor() {

    /** A cell: either text or a number. Numbers keep their type in the sheet. */
    sealed interface Cell {
        data class Text(val value: String) : Cell
        data class Number(val value: Double) : Cell
        data object Empty : Cell
    }

    data class Sheet(val name: String, val rows: List<List<Cell>>)

    fun writeReport(output: OutputStream, report: Report) {
        val rows = mutableListOf<List<Cell>>()
        rows += listOf(Cell.Text(report.title))
        rows += listOf(Cell.Text("Period"), Cell.Text(report.period.label))
        rows += listOf(Cell.Text("Scope"), Cell.Text(report.scope.label))
        rows += listOf(Cell.Text("Produced"), Cell.Text(DateUtils.format(report.generatedOn)))
        rows += emptyList<Cell>()

        report.sections.forEach { section ->
            rows += listOf(Cell.Text(section.title))
            rows += listOf(Cell.Text("Item"), Cell.Text("Amount"), Cell.Text("Detail"))
            section.rows.forEach { row ->
                rows += listOf(
                    Cell.Text(row.label),
                    Cell.Text(row.value),
                    Cell.Text(row.secondary.orEmpty()),
                )
            }
            section.note?.let { rows += listOf(Cell.Text(it)) }
            rows += emptyList<Cell>()
        }

        write(output, listOf(Sheet(sanitiseSheetName(report.title), rows)))
    }

    fun writeTransactions(output: OutputStream, transactions: List<TransactionWithDetails>) {
        val rows = mutableListOf<List<Cell>>()
        rows += listOf(
            "Date", "Description", "Type", "Amount", "Account", "To account",
            "Category", "Person", "Notes",
        ).map { Cell.Text(it) }

        transactions.forEach { item ->
            val entry = item.transaction
            rows += listOf(
                Cell.Text(entry.date.toString()),
                Cell.Text(entry.description),
                Cell.Text(entry.type.displayName),
                // A real number, so Excel can sum the column.
                Cell.Number(Money.toBigDecimal(entry.amountMinor).toDouble()),
                Cell.Text(item.accountName.orEmpty()),
                Cell.Text(item.transferAccountName.orEmpty()),
                Cell.Text(item.categoryName.orEmpty()),
                Cell.Text(item.personName.orEmpty()),
                Cell.Text(entry.notes.orEmpty()),
            )
        }

        write(output, listOf(Sheet("Transactions", rows)))
    }

    /** Writes the complete archive. */
    fun write(output: OutputStream, sheets: List<Sheet>) {
        ZipOutputStream(output.buffered()).use { zip ->
            zip.put("[Content_Types].xml", contentTypes(sheets.size))
            zip.put("_rels/.rels", rootRels())
            zip.put("xl/workbook.xml", workbook(sheets))
            zip.put("xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
            sheets.forEachIndexed { index, sheet ->
                zip.put("xl/worksheets/sheet${index + 1}.xml", sheetXml(sheet))
            }
        }
    }

    // ----------------------------------------------------------- xml parts

    private fun contentTypes(sheetCount: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        for (index in 1..sheetCount) {
            append("""<Override PartName="/xl/worksheets/sheet$index.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        }
        append("</Types>")
    }

    private fun rootRels(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""" +
            "</Relationships>"

    private fun workbook(sheets: List<Sheet>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" """)
        append("""xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
        sheets.forEachIndexed { index, sheet ->
            append("""<sheet name="${escapeXml(sheet.name)}" sheetId="${index + 1}" r:id="rId${index + 1}"/>""")
        }
        append("</sheets></workbook>")
    }

    private fun workbookRels(sheetCount: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        for (index in 1..sheetCount) {
            append("""<Relationship Id="rId$index" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$index.xml"/>""")
        }
        append("</Relationships>")
    }

    private fun sheetXml(sheet: Sheet): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        sheet.rows.forEachIndexed { rowIndex, cells ->
            append("""<row r="${rowIndex + 1}">""")
            cells.forEachIndexed { columnIndex, cell ->
                val reference = "${columnName(columnIndex)}${rowIndex + 1}"
                when (cell) {
                    is Cell.Empty -> Unit
                    is Cell.Number -> append("""<c r="$reference"><v>${cell.value}</v></c>""")
                    is Cell.Text -> if (cell.value.isNotEmpty()) {
                        append("""<c r="$reference" t="inlineStr"><is><t xml:space="preserve">""")
                        append(escapeXml(cell.value))
                        append("""</t></is></c>""")
                    }
                }
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    // ------------------------------------------------------------- helpers

    private fun ZipOutputStream.put(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    /** 0 -> A, 25 -> Z, 26 -> AA. */
    internal fun columnName(index: Int): String {
        var remaining = index
        val builder = StringBuilder()
        while (remaining >= 0) {
            builder.insert(0, ('A' + remaining % 26))
            remaining = remaining / 26 - 1
        }
        return builder.toString()
    }

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
        // Control characters are illegal in XML and would make the file unopenable.
        .filter { it == '\t' || it == '\n' || it == '\r' || it.code >= 0x20 }

    /** Excel rejects these characters in a tab name, and caps it at 31 characters. */
    private fun sanitiseSheetName(name: String): String =
        name.filterNot { it in charArrayOf('\\', '/', '?', '*', '[', ']', ':') }
            .take(31)
            .ifBlank { "Report" }
}
