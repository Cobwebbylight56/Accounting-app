package com.rhys.financetracker.data

import com.rhys.financetracker.data.importer.CsvReader
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Real exported CSV is messier than the specification suggests: quoted fields,
 * commas inside values, embedded newlines and semicolon separators from
 * European locales all turn up.
 */
class CsvReaderTest {

    @Test
    fun `reads a simple comma separated file`() {
        val rows = CsvReader.parse("name,amount\nFuel,80.00\nWater,45", ',')
        assertEquals(listOf("name", "amount"), rows[0])
        assertEquals(listOf("Fuel", "80.00"), rows[1])
        assertEquals(listOf("Water", "45"), rows[2])
    }

    @Test
    fun `keeps commas that are inside quotes`() {
        val rows = CsvReader.parse("\"Smith, John\",100", ',')
        assertEquals(listOf("Smith, John", "100"), rows[0])
    }

    @Test
    fun `treats a doubled quote as one quote`() {
        val rows = CsvReader.parse("\"He said \"\"hello\"\"\",5", ',')
        assertEquals("He said \"hello\"", rows[0][0])
    }

    @Test
    fun `handles Windows line endings`() {
        val rows = CsvReader.parse("a,b\r\nc,d\r\n", ',')
        assertEquals(2, rows.size)
        assertEquals(listOf("c", "d"), rows[1])
    }

    @Test
    fun `keeps a newline that is inside quotes`() {
        val rows = CsvReader.parse("\"line one\nline two\",5", ',')
        assertEquals(1, rows.size)
        assertEquals("line one\nline two", rows[0][0])
    }

    @Test
    fun `detects a semicolon separator`() {
        assertEquals(';', CsvReader.detectDelimiter("name;amount\nFuel;80,00"))
    }

    @Test
    fun `detects a tab separator`() {
        assertEquals('\t', CsvReader.detectDelimiter("name\tamount\nFuel\t80"))
    }

    @Test
    fun `defaults to a comma when nothing else is present`() {
        assertEquals(',', CsvReader.detectDelimiter("justonecolumn"))
    }

    @Test
    fun `trims whitespace around fields`() {
        val rows = CsvReader.parse(" name , amount \n Fuel , 80 ", ',')
        assertEquals(listOf("name", "amount"), rows[0])
        assertEquals(listOf("Fuel", "80"), rows[1])
    }
}
