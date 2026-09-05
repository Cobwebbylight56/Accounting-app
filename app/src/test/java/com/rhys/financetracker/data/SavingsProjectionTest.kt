package com.rhys.financetracker.data

import com.rhys.financetracker.data.local.entity.SavingsGoalEntity
import com.rhys.financetracker.data.local.projection.SavingsGoalWithProgress
import com.rhys.financetracker.data.repository.SavingsProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * A progress bar tells you where you are; these calculations tell you whether
 * you will actually get there, which is the part that changes behaviour.
 */
class SavingsProjectionTest {

    private val today = LocalDate.of(2026, 3, 1)

    private fun goal(
        targetMinor: Long = 100_000L,
        currentMinor: Long = 20_000L,
        monthlyMinor: Long = 10_000L,
        targetDate: LocalDate? = null,
    ) = SavingsGoalWithProgress(
        goal = SavingsGoalEntity(
            id = 1L,
            name = "Holiday",
            targetAmountMinor = targetMinor,
            monthlyContributionMinor = monthlyMinor,
            targetDate = targetDate,
            startDate = today,
            colorHex = "#1B5E4B",
        ),
        currentAmountMinor = currentMinor,
        accountName = null,
    )

    @Test
    fun `progress is a fraction between zero and one`() {
        assertEquals(0.2f, goal().progressFraction, 0.001f)
        assertEquals(20, goal().percentComplete)
    }

    @Test
    fun `progress cannot exceed one when the goal is overshot`() {
        val overshot = goal(currentMinor = 150_000L)
        assertEquals(1f, overshot.progressFraction, 0.001f)
        assertEquals(0L, overshot.remainingMinor)
    }

    @Test
    fun `a zero target does not divide by zero`() {
        val empty = goal(targetMinor = 0L)
        assertEquals(0f, empty.progressFraction, 0.001f)
    }

    @Test
    fun `the required monthly amount spreads the remainder over the months left`() {
        // £800 still to find over 8 months is £100 a month.
        val withDate = goal(targetDate = LocalDate.of(2026, 11, 1))
        assertEquals(10_000L, SavingsProjection.requiredMonthlyMinor(withDate, today))
    }

    @Test
    fun `there is no required monthly amount without a target date`() {
        assertNull(SavingsProjection.requiredMonthlyMinor(goal(), today))
    }

    @Test
    fun `the projected completion follows the planned contribution`() {
        // £800 to go at £100 a month is 8 months.
        val projected = SavingsProjection.projectedCompletion(goal(), today)
        assertEquals(LocalDate.of(2026, 11, 1), projected)
    }

    @Test
    fun `there is no projection when nothing is being put by`() {
        assertNull(SavingsProjection.projectedCompletion(goal(monthlyMinor = 0L), today))
    }

    @Test
    fun `a goal contributing too little is reported as behind schedule`() {
        val behind = goal(monthlyMinor = 1_000L, targetDate = LocalDate.of(2026, 6, 1))
        assertTrue(SavingsProjection.isBehindSchedule(behind, today))
    }

    @Test
    fun `a goal contributing enough is not behind schedule`() {
        val onTrack = goal(monthlyMinor = 40_000L, targetDate = LocalDate.of(2026, 6, 1))
        assertFalse(SavingsProjection.isBehindSchedule(onTrack, today))
    }

    @Test
    fun `a goal with no target date is never behind schedule`() {
        assertFalse(SavingsProjection.isBehindSchedule(goal(monthlyMinor = 1L), today))
    }
}
