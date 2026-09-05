package com.rhys.financetracker.core

import com.rhys.financetracker.core.time.DateUtils
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DateUtilsTest {

    @Test
    fun `a day of the month is clamped to a short month`() {
        assertEquals(
            LocalDate.of(2026, 2, 28),
            DateUtils.safeDayOfMonth(YearMonth.of(2026, 2), 31),
        )
    }

    @Test
    fun `a day of the month is clamped in a leap year`() {
        assertEquals(
            LocalDate.of(2024, 2, 29),
            DateUtils.safeDayOfMonth(YearMonth.of(2024, 2), 31),
        )
    }

    @Test
    fun `a day below one is clamped to the first`() {
        assertEquals(
            LocalDate.of(2026, 5, 1),
            DateUtils.safeDayOfMonth(YearMonth.of(2026, 5), 0),
        )
    }

    @Test
    fun `the year-month key round trips`() {
        val month = YearMonth.of(2026, 3)
        val key = DateUtils.yearMonthKey(month)
        assertEquals("2026-03", key)
        assertEquals(month, DateUtils.parseYearMonthKey(key))
    }

    @Test
    fun `a bad year-month key returns null rather than throwing`() {
        assertNull(DateUtils.parseYearMonthKey("not a month"))
    }

    @Test
    fun `the month range covers the whole month`() {
        val range = DateUtils.monthRange(YearMonth.of(2026, 2))
        assertEquals(LocalDate.of(2026, 2, 1), range.start)
        assertEquals(LocalDate.of(2026, 2, 28), range.endInclusive)
    }

    @Test
    fun `relative descriptions read naturally`() {
        val today = LocalDate.of(2026, 3, 10)
        assertEquals("Today", DateUtils.relativeDescription(today, today))
        assertEquals("Tomorrow", DateUtils.relativeDescription(today.plusDays(1), today))
        assertEquals("Yesterday", DateUtils.relativeDescription(today.minusDays(1), today))
        assertEquals("in 5 days", DateUtils.relativeDescription(today.plusDays(5), today))
        assertEquals("3 days ago", DateUtils.relativeDescription(today.minusDays(3), today))
    }

    @Test
    fun `a distant date falls back to the full format`() {
        val today = LocalDate.of(2026, 3, 10)
        assertEquals(
            "10 Jun 2026",
            DateUtils.relativeDescription(LocalDate.of(2026, 6, 10), today),
        )
    }

    @Test
    fun `the week starts on Monday`() {
        // 2026-03-11 is a Wednesday.
        assertEquals(
            LocalDate.of(2026, 3, 9),
            DateUtils.startOfWeek(LocalDate.of(2026, 3, 11)),
        )
    }

    @Test
    fun `recent months are returned oldest first`() {
        val months = DateUtils.recentMonths(3, YearMonth.of(2026, 3))
        assertEquals(
            listOf(YearMonth.of(2026, 1), YearMonth.of(2026, 2), YearMonth.of(2026, 3)),
            months,
        )
    }

    @Test
    fun `parsing an ISO date tolerates blanks and nulls`() {
        assertEquals(LocalDate.of(2026, 3, 1), DateUtils.parseIsoOrNull("2026-03-01"))
        assertNull(DateUtils.parseIsoOrNull(""))
        assertNull(DateUtils.parseIsoOrNull(null))
        assertNull(DateUtils.parseIsoOrNull("01/03/2026"))
    }
}
