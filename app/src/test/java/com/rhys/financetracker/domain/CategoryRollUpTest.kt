package com.rhys.financetracker.domain

import com.rhys.financetracker.data.local.entity.CategoryEntity
import com.rhys.financetracker.data.local.projection.CategoryTotal
import com.rhys.financetracker.domain.model.CategoryKind
import com.rhys.financetracker.domain.report.CategoryRollUp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spending by group: Groceries, Takeaways and Coffee are one Food slice. */
class CategoryRollUpTest {

    private fun category(id: Long, name: String, parentId: Long? = null) =
        CategoryEntity(id = id, name = name, kind = CategoryKind.EXPENSE, colorHex = "#00000$id", parentId = parentId)

    private val categories = listOf(
        category(1, "Food"),
        category(2, "Groceries", 1),
        category(3, "Takeaways", 1),
        category(4, "Transport"),
        category(5, "Fuel", 4),
        category(6, "Pets"),
    )

    private fun spent(id: Long?, name: String?, pence: Long, count: Int = 1) = CategoryTotal(id, name, null, pence, count)

    @Test
    fun `each group's categories are added together, biggest first`() {
        val rolled = CategoryRollUp.byGroup(
            listOf(spent(2, "Groceries", 300_00, 6), spent(3, "Takeaways", 80_00, 3), spent(5, "Fuel", 120_00, 2), spent(1, "Food", 20_00)),
            categories,
        )
        assertEquals(listOf("Food" to 400_00L, "Transport" to 120_00L), rolled.map { it.categoryName to it.totalMinor })
        assertEquals(10, rolled.first().transactionCount)
        assertEquals(1L, rolled.first().categoryId)
        assertEquals("#000001", rolled.first().categoryColor)
    }

    @Test
    fun `ungrouped and uncategorised spending stays as it is`() {
        val rolled = CategoryRollUp.byGroup(listOf(spent(6, "Pets", 40_00), spent(null, null, 15_00)), categories)
        assertEquals(listOf(6L to 40_00L, null to 15_00L), rolled.map { it.categoryId to it.totalMinor })
    }

    @Test
    fun `a group's members are itself and what's in it`() {
        assertEquals(setOf(1L, 2L, 3L), CategoryRollUp.members(1, categories))
        assertEquals(setOf(6L), CategoryRollUp.members(6, categories))
    }

    @Test
    fun `grouping is only offered when it changes something`() {
        assertTrue(CategoryRollUp.anyGrouped(listOf(spent(2, "Groceries", 1)), categories))
        assertFalse(CategoryRollUp.anyGrouped(listOf(spent(6, "Pets", 1), spent(1, "Food", 1)), categories))
    }
}
