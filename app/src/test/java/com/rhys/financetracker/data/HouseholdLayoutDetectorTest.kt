package com.rhys.financetracker.data

import com.rhys.financetracker.data.importer.HouseholdLayoutDetector
import com.rhys.financetracker.data.importer.ImportTarget
import com.rhys.financetracker.data.importer.SheetData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixture is the original "Book r and h" sheet, laid out exactly as it is
 * in the workbook: a column of figures per person, a derived "both" column that
 * must be ignored, headings that introduce blocks, and total rows at the foot
 * of each block that must not become records.
 */
class HouseholdLayoutDetectorTest {

    private fun row(vararg cells: String) = cells.toList()

    private val sheet = SheetData(
        name = "Sheet1",
        rows = listOf(
            /* 0 */ row("", "", "", "", "", "", ""),
            /* 1 */ row("income", "befor tax", "27455.76", "", "16692.48", "", ""),
            /* 2 */ row("", "", "Rhys", "", "Hannah", "", "both"),
            /* 3 */ row("income", "", "1862.23", "", "1447", "", "3309.23"),
            /* 4 */ row("", "", "", "", "", "", ""),
            /* 5 */ row("savings &", "", "", "", "", "", ""),
            /* 6 */ row("bank", "", "3508.37", "", "3000", "", "6508.37"),
            /* 7 */ row("cash", "", "1510", "", "", "", "1510"),
            /* 8 */ row("saver mum", "", "1900", "", "", "", "1900"),
            /* 9 */ row("main account", "", "1000", "", "", "", "1000"),
            /*10 */ row("ALL SAVINGS", "", "", "", "", "", "11418.37"),
            /*11 */ row("", "", "", "", "", "", ""),
            /*12 */ row("OUTGOINGS", "", "", "", "", "", ""),
            /*13 */ row("car", "", "60.75", "", "80.59", "", "141.34"),
            /*14 */ row("road tax", "", "37.62", "", "24.06", "", "61.68"),
            /*15 */ row("fuel", "", "80", "", "70", "", "150"),
            /*16 */ row("energy", "", "200", "", "", "", "200"),
            /*17 */ row("water", "", "45", "", "", "", "45"),
            /*18 */ row("food", "", "250", "", "250", "", "500"),
            /*19 */ row("life insur", "", "32.39", "", "35.59", "", "67.98"),
            /*20 */ row("spent", "", "1941.63", "", "1558.14", "", ""),
        ),
    )

    @Test
    fun `the person header row is found`() {
        assertEquals(2, HouseholdLayoutDetector.findPersonHeaderRow(sheet))
    }

    @Test
    fun `both people are found and the totals column is ignored`() {
        val layout = HouseholdLayoutDetector.detect(sheet)
        assertNotNull(layout)
        assertEquals(listOf("Rhys", "Hannah"), layout!!.people.map { it.name })
        assertTrue("the \"both\" column must not become a person", layout.people.none {
            it.name.equals("both", ignoreCase = true)
        })
    }

    @Test
    fun `the name column is the one holding the row labels`() {
        val layout = HouseholdLayoutDetector.detect(sheet)!!
        assertEquals(0, layout.nameColumn)
    }

    @Test
    fun `the single-row income block is kept, not swallowed by the minimum size`() {
        val layout = HouseholdLayoutDetector.detect(sheet)!!
        val income = layout.blocks.first { it.firstRow == 3 }
        assertEquals(ImportTarget.RECURRING_INCOME, income.target)
        assertEquals(3, income.lastRow)
    }

    @Test
    fun `all three blocks are found`() {
        val layout = HouseholdLayoutDetector.detect(sheet)!!
        assertEquals(3, layout.blocks.size)
        assertEquals(
            listOf(
                ImportTarget.RECURRING_INCOME,
                ImportTarget.ACCOUNT_BALANCE,
                ImportTarget.RECURRING_EXPENSE,
            ),
            layout.blocks.map { it.target },
        )
    }

    @Test
    fun `a column of dates is not mistaken for a column of figures`() {
        // Money.parseOrNull strips the dashes out of "2026-03-01" and returns a
        // number, so the detector has to rule dates out itself.
        val dates = SheetData(
            name = "Dates",
            rows = listOf(
                row("", "Rhys", "Hannah"),
                row("car", "2026-03-01", "2026-03-02"),
                row("fuel", "2026-03-03", "2026-03-04"),
                row("food", "2026-03-05", "2026-03-06"),
                row("water", "2026-03-07", "2026-03-08"),
            ),
        )
        assertNull(HouseholdLayoutDetector.detect(dates))
    }

    @Test
    fun `the savings block is recognised as account balances`() {
        val layout = HouseholdLayoutDetector.detect(sheet)!!
        val savings = layout.blocks.first { it.heading == "savings &" }
        assertEquals(ImportTarget.ACCOUNT_BALANCE, savings.target)
    }

    @Test
    fun `the outgoings block is recognised as regular bills`() {
        val layout = HouseholdLayoutDetector.detect(sheet)!!
        val outgoings = layout.blocks.first { it.heading == "OUTGOINGS" }
        assertEquals(ImportTarget.RECURRING_EXPENSE, outgoings.target)
    }

    @Test
    fun `trailing total rows are excluded from their block`() {
        val layout = HouseholdLayoutDetector.detect(sheet)!!
        val outgoings = layout.blocks.first { it.heading == "OUTGOINGS" }
        // Rows 13..19 are the bills; row 20 is "spent", a total.
        assertEquals(13, outgoings.firstRow)
        assertEquals(19, outgoings.lastRow)

        val savings = layout.blocks.first { it.heading == "savings &" }
        // Row 10 is "ALL SAVINGS", a total.
        assertEquals(9, savings.lastRow)
    }

    @Test
    fun `total row labels are recognised`() {
        assertTrue(HouseholdLayoutDetector.isTotalRow("spent"))
        assertTrue(HouseholdLayoutDetector.isTotalRow("ALL SAVINGS"))
        assertTrue(HouseholdLayoutDetector.isTotalRow("left over"))
        assertTrue(HouseholdLayoutDetector.isTotalRow("Total"))
        assertTrue(!HouseholdLayoutDetector.isTotalRow("road tax"))
        assertTrue(!HouseholdLayoutDetector.isTotalRow("fuel"))
    }

    @Test
    fun `a mapping is produced for every person in every block`() {
        val layout = HouseholdLayoutDetector.detect(sheet)!!
        val mappings = HouseholdLayoutDetector.mappingsFor(layout)
        assertEquals(layout.blocks.size * 2, mappings.size)
        assertTrue(mappings.all { it.defaultPersonName in setOf("Rhys", "Hannah") })
    }

    @Test
    fun `each mapping reads one person's column only`() {
        val layout = HouseholdLayoutDetector.detect(sheet)!!
        val mappings = HouseholdLayoutDetector.mappingsFor(layout)
        mappings.forEach { mapping ->
            val amountColumns = mapping.columnRoles.filterValues {
                it == com.rhys.financetracker.data.importer.ColumnRole.AMOUNT
            }
            assertEquals(
                "a mapping that reads two columns would double-count",
                1,
                amountColumns.size,
            )
        }
    }

    @Test
    fun `balance blocks get no default account, because each row names one`() {
        assertNull(
            HouseholdLayoutDetector.accountNameFor("Rhys", ImportTarget.ACCOUNT_BALANCE),
        )
        assertEquals(
            "Rhys's account",
            HouseholdLayoutDetector.accountNameFor("Rhys", ImportTarget.RECURRING_EXPENSE),
        )
    }

    @Test
    fun `a plain one-record-per-row sheet is not mistaken for this layout`() {
        val ordinary = SheetData(
            name = "Export",
            rows = listOf(
                row("Date", "Description", "Amount"),
                row("2026-03-01", "Tesco", "42.10"),
                row("2026-03-02", "Fuel", "60.00"),
                row("2026-03-03", "Council tax", "162.00"),
                row("2026-03-04", "Broadband", "30.00"),
            ),
        )
        assertNull(HouseholdLayoutDetector.detect(ordinary))
    }

    @Test
    fun `person names are told apart from structural words`() {
        assertTrue(HouseholdLayoutDetector.looksLikePersonName("Rhys"))
        assertTrue(HouseholdLayoutDetector.looksLikePersonName("Hannah"))
        assertTrue(!HouseholdLayoutDetector.looksLikePersonName("both"))
        assertTrue(!HouseholdLayoutDetector.looksLikePersonName("total"))
        assertTrue(!HouseholdLayoutDetector.looksLikePersonName("income"))
        assertTrue(!HouseholdLayoutDetector.looksLikePersonName("1862.23"))
        assertTrue(!HouseholdLayoutDetector.looksLikePersonName(""))
        assertTrue(!HouseholdLayoutDetector.looksLikePersonName("2026-03-01"))
    }

    @Test
    fun `the summary reads as plain words`() {
        val layout = HouseholdLayoutDetector.detect(sheet)!!
        val description = layout.describe()
        assertTrue(description.contains("Rhys"))
        assertTrue(description.contains("Hannah"))
    }
}
