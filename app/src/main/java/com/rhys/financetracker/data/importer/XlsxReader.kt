package com.rhys.financetracker.data.importer

import android.util.Xml
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser

/**
 * Reads `.xlsx` workbooks without any third-party library.
 *
 * An xlsx file is a ZIP archive of XML.  Both `java.util.zip` and an XML pull
 * parser are part of Android, so the whole reader is a few hundred lines and
 * adds nothing to the APK — where Apache POI would add several megabytes and a
 * long list of desugaring problems on Android.
 *
 * What it understands:
 *  * `xl/sharedStrings.xml` — the string table most text cells point at;
 *  * `xl/worksheets/sheet*.xml` — the cells themselves;
 *  * `xl/workbook.xml` — the sheet names, in the order the tabs appear.
 *
 * What it deliberately ignores: formatting, formulas (the cached *result* of a
 * formula is read, which is what the user sees on screen), charts and macros.
 */
object XlsxReader {

    private const val SHARED_STRINGS = "xl/sharedStrings.xml"
    private const val WORKBOOK = "xl/workbook.xml"
    private const val SHEET_PREFIX = "xl/worksheets/sheet"

    /** Guard against a malicious or corrupt archive expanding without limit. */
    private const val MAX_CELLS_PER_SHEET = 200_000
    private const val MAX_ENTRY_BYTES = 32L * 1024 * 1024

    /**
     * Excel stores dates as a number of days since 1899-12-30 (the "1900 date
     * system", including its deliberate leap-year bug, which is why the epoch
     * is the 30th and not the 31st).
     */
    private val EXCEL_EPOCH: LocalDate = LocalDate.of(1899, 12, 30)

    /** Numbers within this range are plausible dates rather than amounts. */
    private val DATE_SERIAL_RANGE = 20_000.0..80_000.0

    fun read(input: InputStream, fileName: String): WorkbookData {
        var sharedStrings: List<String> = emptyList()
        val sheetXml = sortedMapOf<Int, String>()
        var sheetNames: List<String> = emptyList()

        ZipInputStream(input.buffered()).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                when {
                    name == SHARED_STRINGS -> sharedStrings = parseSharedStrings(zip.readLimited())
                    name == WORKBOOK -> sheetNames = parseSheetNames(zip.readLimited())
                    name.startsWith(SHEET_PREFIX) && name.endsWith(".xml") -> {
                        val index = name.removePrefix(SHEET_PREFIX).removeSuffix(".xml")
                            .toIntOrNull() ?: 1
                        sheetXml[index] = zip.readLimited()
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val sheets = sheetXml.entries.mapIndexed { position, (index, xml) ->
            SheetData(
                name = sheetNames.getOrNull(index - 1) ?: "Sheet${position + 1}",
                rows = parseSheet(xml, sharedStrings),
            )
        }
        return WorkbookData(fileName = fileName, sheets = sheets)
    }

    // ------------------------------------------------------------- parsing

    private fun parseSharedStrings(xml: String): List<String> {
        val result = mutableListOf<String>()
        val parser = newParser(xml)
        val current = StringBuilder()
        var insideItem = false
        var insideText = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> { insideItem = true; current.setLength(0) }
                    // <t> holds the text; rich text splits one string over several.
                    "t" -> insideText = true
                }
                XmlPullParser.TEXT -> if (insideItem && insideText) current.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "t" -> insideText = false
                    "si" -> { result += current.toString(); insideItem = false }
                }
            }
            event = parser.next()
        }
        return result
    }

    private fun parseSheetNames(xml: String): List<String> {
        val result = mutableListOf<String>()
        val parser = newParser(xml)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "sheet") {
                result += parser.getAttributeValue(null, "name") ?: "Sheet${result.size + 1}"
            }
            event = parser.next()
        }
        return result
    }

    private fun parseSheet(xml: String, sharedStrings: List<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val parser = newParser(xml)

        var currentRow = mutableListOf<String>()
        var cellReference: String? = null
        var cellType: String? = null
        var cellStyle: String? = null
        var value = StringBuilder()
        var insideValue = false
        var cellsSeen = 0

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> currentRow = mutableListOf()
                    "c" -> {
                        cellReference = parser.getAttributeValue(null, "r")
                        cellType = parser.getAttributeValue(null, "t")
                        cellStyle = parser.getAttributeValue(null, "s")
                        value = StringBuilder()
                    }
                    // <v> is the raw value; <t> appears in inline strings.
                    "v", "t" -> insideValue = true
                }
                XmlPullParser.TEXT -> if (insideValue) value.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "v", "t" -> insideValue = false
                    "c" -> {
                        if (cellsSeen++ < MAX_CELLS_PER_SHEET) {
                            // Honour the cell's column letter so that gaps in the
                            // row do not shift every later column left.
                            val columnIndex = columnIndexOf(cellReference)
                            while (currentRow.size < columnIndex) currentRow.add("")
                            val text = resolveCell(
                                raw = value.toString(),
                                type = cellType,
                                style = cellStyle,
                                sharedStrings = sharedStrings,
                            )
                            if (currentRow.size == columnIndex) {
                                currentRow.add(text)
                            } else {
                                currentRow[columnIndex] = text
                            }
                        }
                    }
                    "row" -> rows.add(currentRow.toList())
                }
            }
            event = parser.next()
        }
        return rows
    }

    /**
     * Turns a raw cell value into display text.
     *
     * `t="s"` means the value is an index into the shared string table; `t="b"`
     * is a boolean; `t="inlineStr"` and `t="str"` are already text.  Anything
     * else is a number, which may really be a date.
     */
    private fun resolveCell(
        raw: String,
        type: String?,
        style: String?,
        sharedStrings: List<String>,
    ): String {
        if (raw.isBlank()) return ""
        return when (type) {
            "s" -> raw.toIntOrNull()?.let { sharedStrings.getOrNull(it) }.orEmpty()
            "b" -> if (raw == "1") "TRUE" else "FALSE"
            "str", "inlineStr" -> raw
            "e" -> "" // a formula error such as #DIV/0! — treat as empty
            else -> formatNumber(raw, style)
        }
    }

    /**
     * Formats a numeric cell.  Values in the date-serial range are rendered as
     * ISO dates, because a bare 45000 in a "due date" column is not useful to
     * the mapping step.
     */
    private fun formatNumber(raw: String, style: String?): String {
        val number = raw.toDoubleOrNull() ?: return raw
        if (style != null && number in DATE_SERIAL_RANGE && number == Math.floor(number)) {
            return EXCEL_EPOCH.plusDays(number.toLong()).toString()
        }
        // Trim the floating-point noise Excel writes ("1862.2299999999998").
        return BigDecimal(raw).stripTrailingZeros().toPlainString()
    }

    /** `C7` -> 2. Column letters are base-26 with A = 1. */
    internal fun columnIndexOf(reference: String?): Int {
        if (reference.isNullOrEmpty()) return 0
        var index = 0
        for (character in reference) {
            if (!character.isLetter()) break
            index = index * 26 + (character.uppercaseChar() - 'A' + 1)
        }
        return (index - 1).coerceAtLeast(0)
    }

    private fun newParser(xml: String): XmlPullParser = Xml.newPullParser().apply {
        // Never resolve external entities: a hostile file must not be able to
        // make the app read other files or call out to the network.
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(xml.reader())
    }

    /**
     * Reads a zip entry with a hard size cap.
     *
     * Bytes are collected first and decoded once at the end: decoding each
     * chunk separately would corrupt any multi-byte character that happened to
     * straddle a chunk boundary.
     */
    private fun ZipInputStream.readLimited(): String {
        val buffer = ByteArray(16 * 1024)
        val output = java.io.ByteArrayOutputStream()
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            total += read
            if (total > MAX_ENTRY_BYTES) error("That spreadsheet is too large to read")
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }
}
