package com.rhys.financetracker.domain

import com.rhys.financetracker.domain.report.ProgressFigures
import com.rhys.financetracker.domain.report.ProgressReport
import com.rhys.financetracker.domain.report.ProgressReportCodec
import com.rhys.financetracker.domain.report.ProgressReporter
import com.rhys.financetracker.domain.report.ProgressSchedule
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressReportTest {

    @Test
    fun `early in the month it's about the month just gone`() {
        val period = ProgressSchedule.current(LocalDate.of(2026, 10, 3))
        assertEquals(ProgressReport.Kind.MONTH, period.kind)
        assertEquals(YearMonth.of(2026, 9), period.month)
        assertEquals(LocalDate.of(2026, 9, 1), period.now.start)
        assertEquals(LocalDate.of(2026, 9, 30), period.now.endInclusive)
        assertEquals(LocalDate.of(2026, 8, 31), period.before.endInclusive)
        assertEquals("August", period.beforeName)
        assertEquals("2026-09-month", period.key)
    }

    @Test
    fun `from the 15th it's about the first half of this month`() {
        val period = ProgressSchedule.current(LocalDate.of(2026, 10, 16))
        assertEquals(ProgressReport.Kind.MID_MONTH, period.kind)
        assertEquals(YearMonth.of(2026, 10), period.month)
        assertEquals(LocalDate.of(2026, 10, 14), period.now.endInclusive)
        assertEquals(LocalDate.of(2026, 9, 1), period.before.start)
        assertEquals(LocalDate.of(2026, 9, 14), period.before.endInclusive)
        assertEquals(LocalDate.of(2026, 9, 30), period.beforeWholeMonth?.endInclusive)
        assertEquals("2026-10-mid", period.key)
    }

    @Test
    fun `January looks back across the new year`() {
        val period = ProgressSchedule.current(LocalDate.of(2027, 1, 2))
        assertEquals(YearMonth.of(2026, 12), period.month)
        assertEquals("November", period.beforeName)
    }

    private val september = ProgressSchedule.current(LocalDate.of(2026, 10, 1))

    @Test
    fun `less spent and more saved is better`() {
        val report = ProgressReporter.build(
            september, "Together", "all",
            now = ProgressFigures(300_000, 180_000, 30_000, -5_000, mapOf("Eating out" to 8_000, "Groceries" to 40_000)),
            before = ProgressFigures(300_000, 210_000, 10_000, 4_000, mapOf("Eating out" to 20_000, "Groceries" to 41_000)),
            madeOn = LocalDate.of(2026, 10, 1),
        )
        assertNotNull(report)
        report!!
        assertEquals(ProgressReport.Verdict.BETTER, report.verdict)
        assertEquals("How September went", report.title)
        assertEquals("Better than August: £300 less spent and £200 more put aside.", report.headline)
        assertEquals("2026-09-month|all", report.key)
        assertTrue(report.tips.any { "Eating out (down £120)" in it })
        assertTrue(report.tips.any { "came down £50" in it })
    }

    @Test
    fun `more spent and less saved needs improving, naming what rose`() {
        val report = ProgressReporter.build(
            september, "Rhys", "1",
            now = ProgressFigures(200_000, 190_000, 0, 15_000, mapOf("Shopping" to 60_000)),
            before = ProgressFigures(200_000, 150_000, 20_000, 0, mapOf("Shopping" to 20_000)),
            madeOn = LocalDate.of(2026, 10, 1),
        )!!
        assertEquals(ProgressReport.Verdict.WORSE, report.verdict)
        assertTrue(report.headline.startsWith("Not as good as August: £400 more spent and £200 less put aside"))
        assertTrue(report.tips.first().contains("Shopping up £400"))
        assertTrue(report.tips.any { "grew £150" in it })
    }

    @Test
    fun `a few pounds either way is much the same`() {
        val report = ProgressReporter.build(
            september, "Together", "all",
            now = ProgressFigures(300_000, 200_500, 10_000, 0),
            before = ProgressFigures(300_000, 200_000, 10_000, 0),
            madeOn = LocalDate.of(2026, 10, 1),
        )!!
        assertEquals(ProgressReport.Verdict.STEADY, report.verdict)
        assertTrue(report.lines.none { it.label.startsWith("Owed") })
    }

    @Test
    fun `nothing to compare makes no report`() {
        assertNull(
            ProgressReporter.build(
                september, "Hannah", "2",
                now = ProgressFigures(100_000, 50_000, 0, 0),
                before = ProgressFigures(0, 0, 0, 0),
                madeOn = LocalDate.of(2026, 10, 1),
            ),
        )
    }

    @Test
    fun `halfway says where the month is heading`() {
        val period = ProgressSchedule.current(LocalDate.of(2026, 10, 15))
        val report = ProgressReporter.build(
            period, "Together", "all",
            now = ProgressFigures(10_000, 140_000, 5_000, 0),
            before = ProgressFigures(10_000, 100_000, 5_000, 0),
            madeOn = LocalDate.of(2026, 10, 15),
            beforeWholeMonthSpentMinor = 220_000,
        )!!
        assertEquals("October, halfway", report.title)
        assertEquals(listOf("Spent so far", "Put aside so far"), report.lines.map { it.label })
        // £1,400 over 14 days of a 31-day month.
        assertTrue(report.tips.last(), report.tips.last().contains("about £3,100, against £2,200 for all of September"))
    }

    @Test
    fun `reports keep through the settings`() {
        val reports = listOf(
            ProgressReporter.build(
                september, "Together", "all",
                now = ProgressFigures(300_000, 180_000, 30_000, -5_000, mapOf("Eating out" to 8_000)),
                before = ProgressFigures(300_000, 210_000, 10_000, 4_000, mapOf("Eating out" to 20_000)),
                madeOn = LocalDate.of(2026, 10, 1),
            )!!,
            ProgressReporter.build(
                september, "Rhys", "1",
                now = ProgressFigures(200_000, 200_000, 0, 0),
                before = ProgressFigures(200_000, 200_000, 0, 0),
                madeOn = LocalDate.of(2026, 10, 1),
            )!!,
        )
        assertEquals(reports, ProgressReportCodec.read(ProgressReportCodec.write(reports)))
        assertEquals(emptyList<ProgressReport>(), ProgressReportCodec.read(null))
        assertEquals(emptyList<ProgressReport>(), ProgressReportCodec.read(""))
    }
}
