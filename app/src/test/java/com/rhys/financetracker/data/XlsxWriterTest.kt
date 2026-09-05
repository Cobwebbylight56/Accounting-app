package com.rhys.financetracker.data

import com.rhys.financetracker.data.export.XlsxWriter
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Excel writer builds the archive by hand, so the parts a spreadsheet
 * program needs must all be present and the column references must be right.
 */
class XlsxWriterTest {

    private val writer = XlsxWriter()

    @Test
    fun `column names follow the spreadsheet convention`() {
        assertEquals("A", writer.columnName(0))
        assertEquals("Z", writer.columnName(25))
        assertEquals("AA", writer.columnName(26))
        assertEquals("AB", writer.columnName(27))
        assertEquals("BA", writer.columnName(52))
    }

    @Test
    fun `a written workbook contains every required part`() {
        val output = ByteArrayOutputStream()
        writer.write(
            output,
            listOf(
                XlsxWriter.Sheet(
                    name = "Test",
                    rows = listOf(
                        listOf(XlsxWriter.Cell.Text("Name"), XlsxWriter.Cell.Text("Amount")),
                        listOf(XlsxWriter.Cell.Text("Fuel"), XlsxWriter.Cell.Number(80.0)),
                    ),
                ),
            ),
        )

        val entries = mutableListOf<String>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries += entry.name
                entry = zip.nextEntry
            }
        }

        assertTrue("[Content_Types].xml" in entries)
        assertTrue("_rels/.rels" in entries)
        assertTrue("xl/workbook.xml" in entries)
        assertTrue("xl/_rels/workbook.xml.rels" in entries)
        assertTrue("xl/worksheets/sheet1.xml" in entries)
    }

    @Test
    fun `text is escaped so a stray ampersand cannot break the file`() {
        val output = ByteArrayOutputStream()
        writer.write(
            output,
            listOf(
                XlsxWriter.Sheet(
                    name = "Test",
                    rows = listOf(listOf(XlsxWriter.Cell.Text("Gas & Electric <2026>"))),
                ),
            ),
        )

        val sheetXml = readEntry(output.toByteArray(), "xl/worksheets/sheet1.xml")
        assertTrue(sheetXml.contains("Gas &amp; Electric &lt;2026&gt;"))
    }

    private fun readEntry(archive: ByteArray, name: String): String {
        ZipInputStream(archive.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == name) return zip.readBytes().toString(Charsets.UTF_8)
                entry = zip.nextEntry
            }
        }
        return ""
    }
}
