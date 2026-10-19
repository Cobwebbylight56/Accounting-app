package com.rhys.financetracker.data

import com.rhys.financetracker.data.local.entity.CategoryEntity
import com.rhys.financetracker.data.local.seed.CategoryRegrouping
import com.rhys.financetracker.data.local.seed.DefaultData
import com.rhys.financetracker.domain.model.CategoryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Categories from before the groups, put into them once. */
class CategoryRegroupingTest {

    private fun category(id: Long, name: String, parentId: Long? = null, kind: CategoryKind = CategoryKind.EXPENSE) =
        CategoryEntity(id = id, name = name, kind = kind, colorHex = "#000000", parentId = parentId)

    @Test
    fun `every group a category is seeded into is itself a top-level category of the same kind`() {
        val seeds = DefaultData.defaultCategories()
        seeds.filter { it.parent != null }.forEach { seed ->
            val parent = seeds.firstOrNull { it.name == seed.parent && it.kind == seed.kind }
            assertTrue("${seed.name}'s group ${seed.parent} is missing", parent != null)
            assertEquals("${seed.parent} is inside another group", null, parent!!.parent)
        }
        val names = seeds.map { it.name.lowercase() to it.kind }
        assertEquals(names.distinct(), names)
    }

    @Test
    fun `loose categories from an older version move into their groups`() {
        val existing = listOf(
            category(1, "Shopping"),
            category(2, "Clothes"),
            category(3, "Household"),
            category(4, "Transport"),
            // Made by an older importer, at the top level.
            category(5, "Parking"),
            category(6, "Money"),
            category(7, "Credit & loans"),
            category(8, "Pension", kind = CategoryKind.INCOME),
        )
        assertEquals(mapOf(2L to 1L, 3L to 1L, 5L to 4L, 7L to 6L), CategoryRegrouping.moves(existing))
    }

    @Test
    fun `a category somebody already placed, or made a group of, stays put`() {
        val existing = listOf(
            category(1, "Entertainment"),
            category(2, "Hobbies"),
            category(3, "Fishing", parentId = 2),
            category(4, "Shopping"),
            category(5, "Personal"),
            category(6, "Clothes", parentId = 5),
        )
        assertEquals(emptyMap<Long, Long>(), CategoryRegrouping.moves(existing))
    }
}
