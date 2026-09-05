package com.rhys.financetracker.core.time

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Date helpers shared by the recurrence engine, the reports and the UI.
 *
 * Everything in the app works in the device's default time zone and uses
 * [LocalDate] — financial records are calendar facts, not instants, so storing
 * them as epoch milliseconds would make them shift across time zones.
 */
object DateUtils {

    val ISO: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val DISPLAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)
    private val DISPLAY_SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
    private val MONTH_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.UK)

    fun today(zone: ZoneId = ZoneId.systemDefault()): LocalDate = LocalDate.now(zone)

    fun currentYearMonth(zone: ZoneId = ZoneId.systemDefault()): YearMonth =
        YearMonth.now(zone)

    fun format(date: LocalDate): String = date.format(DISPLAY)

    fun formatShort(date: LocalDate): String = date.format(DISPLAY_SHORT)

    fun formatMonth(yearMonth: YearMonth): String = yearMonth.atDay(1).format(MONTH_YEAR)

    /** `2026-04` — the canonical key used for monthly archives. */
    fun yearMonthKey(yearMonth: YearMonth): String = yearMonth.toString()

    fun parseYearMonthKey(key: String): YearMonth? = runCatching { YearMonth.parse(key) }.getOrNull()

    fun parseIsoOrNull(text: String?): LocalDate? =
        text?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it, ISO) }.getOrNull() }

    fun startOfMonth(date: LocalDate): LocalDate = date.withDayOfMonth(1)

    fun endOfMonth(date: LocalDate): LocalDate = date.withDayOfMonth(date.lengthOfMonth())

    fun monthRange(yearMonth: YearMonth): ClosedRange<LocalDate> =
        yearMonth.atDay(1)..yearMonth.atEndOfMonth()

    fun yearRange(year: Int): ClosedRange<LocalDate> =
        LocalDate.of(year, 1, 1)..LocalDate.of(year, 12, 31)

    /**
     * Clamps [dayOfMonth] to a month that may be shorter, so that a bill due on
     * the 31st still lands on 28/29 February rather than throwing.
     */
    fun safeDayOfMonth(yearMonth: YearMonth, dayOfMonth: Int): LocalDate =
        yearMonth.atDay(dayOfMonth.coerceIn(1, yearMonth.lengthOfMonth()))

    fun daysBetween(from: LocalDate, to: LocalDate): Long = ChronoUnit.DAYS.between(from, to)

    fun monthsBetween(from: YearMonth, to: YearMonth): Long = ChronoUnit.MONTHS.between(from, to)

    /** Human wording used on dashboard cards: "Today", "Tomorrow", "in 5 days", "3 days ago". */
    fun relativeDescription(date: LocalDate, reference: LocalDate = today()): String {
        val days = daysBetween(reference, date)
        return when {
            days == 0L -> "Today"
            days == 1L -> "Tomorrow"
            days == -1L -> "Yesterday"
            days in 2..13 -> "in $days days"
            days in -13..-2 -> "${-days} days ago"
            else -> format(date)
        }
    }

    fun monthNameShort(month: Int): String =
        java.time.Month.of(month).getDisplayName(TextStyle.SHORT, Locale.UK)

    /** Monday-based week start, matching UK convention. */
    fun startOfWeek(date: LocalDate): LocalDate =
        date.minusDays(((date.dayOfWeek.value - DayOfWeek.MONDAY.value) + 7) % 7L)

    /** Inclusive list of the [count] most recent months, oldest first. */
    fun recentMonths(count: Int, endingAt: YearMonth = currentYearMonth()): List<YearMonth> =
        (count - 1 downTo 0).map { endingAt.minusMonths(it.toLong()) }
}
