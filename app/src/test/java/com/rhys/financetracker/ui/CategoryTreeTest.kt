package com.rhys.financetracker.ui

import com.rhys.financetracker.data.local.entity.CategoryEntity
import com.rhys.financetracker.domain.model.CategoryKind
import com.rhys.financetracker.ui.components.CategoryTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The category picker's groups: Food opening up to Takeaways, and the search across them. */
class CategoryTreeTest {

    private fun category(id: Long, name: String, parentId: Long? = null, kind: CategoryKind = CategoryKind.EXPENSE) =
        CategoryEntity(id = id, name = name, kind = kind, colorHex = "#000000", parentId = parentId)

    private val all = listOf(
        category(1, "Transport"),
        category(2, "Parking", parentId = 1),
        category(3, "Fuel", parentId = 1),
        category(4, "Food"),
        category(5, "Takeaways", parentId = 4),
        category(6, "Groceries", parentId = 4),
        category(7, "Other"),
        category(8, "Savings", kind = CategoryKind.SAVING),
        category(9, "Emergency fund", parentId = 8, kind = CategoryKind.SAVING),
        category(10, "Bank charges", parentId = 11),
        category(11, "Money"),
    )

    @Test
    fun `each group holds its own categories, by name`() {
        val tree = CategoryTree.of(all)
        val transport = tree.first { it.head.name == "Transport" }
        assertEquals(listOf("Fuel", "Parking"), transport.children.map { it.name })
        assertEquals(listOf("Groceries", "Takeaways"), tree.first { it.head.name == "Food" }.children.map { it.name })
    }

    @Test
    fun `groups by name, savings after them, Other at the end of each`() {
        assertEquals(listOf("Food", "Money", "Transport", "Other", "Savings"), CategoryTree.of(all).map { it.head.name })
    }

    @Test
    fun `a category whose group isn't offered stands on its own`() {
        val tree = CategoryTree.of(all.filterNot { it.name == "Money" })
        assertEquals(true, tree.any { it.head.name == "Bank charges" && it.children.isEmpty() })
    }

    @Test
    fun `searching keeps a whole group by its name, or just the matches inside`() {
        val tree = CategoryTree.of(all)
        assertEquals(listOf("Fuel", "Parking"), CategoryTree.search(tree, "transport").single().children.map { it.name })
        val parking = CategoryTree.search(tree, "park").single()
        assertEquals("Transport", parking.head.name)
        assertEquals(listOf("Parking"), parking.children.map { it.name })
        assertEquals(emptyList<Any>(), CategoryTree.search(tree, "zzz"))
    }

    @Test
    fun `the chosen category's group is found, and it reads with its group`() {
        val tree = CategoryTree.of(all)
        assertEquals("Food", CategoryTree.branchOf(tree, 5)?.head?.name)
        assertNull(CategoryTree.branchOf(tree, null))
        assertEquals("Food › Takeaways", CategoryTree.labelOf(all, all[4]))
        assertEquals("Food", CategoryTree.labelOf(all, all[3]))
    }
}
