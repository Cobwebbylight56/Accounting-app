package com.rhys.financetracker.domain.report

import com.rhys.financetracker.data.local.entity.CategoryEntity
import com.rhys.financetracker.data.local.projection.CategoryTotal

/**
 * Spending by group — Food, Transport, Shopping — rather than by every
 * category: Groceries, Takeaways and Coffee & snacks are one Food slice, so
 * the chart says where the money went at a glance, and tapping a group shows
 * what is in it.
 */
object CategoryRollUp {

    /**
     * [totals] added up by group, biggest first. A category's spending goes
     * to the group it sits in; one in no group, and the uncategorised, stay as
     * themselves.
     */
    fun byGroup(totals: List<CategoryTotal>, categories: List<CategoryEntity>): List<CategoryTotal> {
        val byId = categories.associateBy { it.id }
        return totals
            .groupBy { total -> total.categoryId?.let { id -> groupOf(id, byId) } }
            .map { (groupId, parts) ->
                val group = groupId?.let { byId[it] }
                CategoryTotal(
                    categoryId = groupId,
                    categoryName = group?.name ?: parts.first().categoryName,
                    categoryColor = group?.colorHex ?: parts.first().categoryColor,
                    totalMinor = parts.sumOf { it.totalMinor },
                    transactionCount = parts.sumOf { it.transactionCount },
                )
            }
            .sortedByDescending { it.totalMinor }
    }

    /** [groupId] and every category in it. */
    fun members(groupId: Long, categories: List<CategoryEntity>): Set<Long> =
        setOf(groupId) + categories.filter { it.parentId == groupId }.map { it.id }

    /** Whether any of [totals] sits in a group, so grouping would change the picture. */
    fun anyGrouped(totals: List<CategoryTotal>, categories: List<CategoryEntity>): Boolean {
        val byId = categories.associateBy { it.id }
        return totals.any { total -> total.categoryId?.let { groupOf(it, byId) != it } == true }
    }

    private fun groupOf(id: Long, byId: Map<Long, CategoryEntity>): Long =
        byId[id]?.parentId?.takeIf { it in byId } ?: id
}
