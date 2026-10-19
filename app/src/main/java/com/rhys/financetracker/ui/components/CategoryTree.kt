package com.rhys.financetracker.ui.components

import com.rhys.financetracker.data.local.entity.CategoryEntity
import com.rhys.financetracker.domain.model.CategoryKind

/** One group in the category picker: its heading, and what sits inside it. */
data class CategoryBranch(val head: CategoryEntity, val children: List<CategoryEntity>)

/**
 * The categories as groups that open up — Food holding Groceries, Takeaways
 * and Coffee & snacks — instead of one long list where Parking sits between
 * Mobile and Pets. A category whose group isn't on offer (an income category
 * under an expense heading, say) is shown as a group of its own.
 */
object CategoryTree {

    fun of(categories: List<CategoryEntity>): List<CategoryBranch> {
        val ids = categories.map { it.id }.toSet()
        val heads = categories.filter { it.parentId == null || it.parentId !in ids }
        val byParent = categories.filter { it.parentId != null && it.parentId in ids }.groupBy { it.parentId }
        return heads.sortedWith(HEADS).map { head ->
            CategoryBranch(head, byParent[head.id].orEmpty().sortedBy { it.name.lowercase() })
        }
    }

    /**
     * The groups with [query] in them: a group whose own name matches keeps
     * everything in it, any other keeps only the categories that match.
     */
    fun search(branches: List<CategoryBranch>, query: String): List<CategoryBranch> {
        val words = query.trim().lowercase()
        if (words.isEmpty()) return branches
        return branches.mapNotNull { branch ->
            when {
                words in branch.head.name.lowercase() -> branch
                else -> branch.children.filter { words in it.name.lowercase() }
                    .takeIf { it.isNotEmpty() }
                    ?.let { branch.copy(children = it) }
            }
        }
    }

    /** The group [id] is in, or heads, so it can be shown open. */
    fun branchOf(branches: List<CategoryBranch>, id: Long?): CategoryBranch? =
        id?.let { branches.firstOrNull { branch -> branch.head.id == it || branch.children.any { child -> child.id == it } } }

    /** "Food › Takeaways", or just "Food" for a heading. */
    fun labelOf(categories: List<CategoryEntity>, category: CategoryEntity): String {
        val parent = category.parentId?.let { id -> categories.firstOrNull { it.id == id } }
        return if (parent == null) category.name else "${parent.name} › ${category.name}"
    }

    /**
     * Ordinary groups by name; then savings, cash and transfers, which are
     * offered on every side but are rarely what a payment is; "Other" last.
     */
    private val HEADS = compareBy<CategoryEntity>(
        { it.kind.isAPot || it.kind == CategoryKind.TRANSFER },
        { it.name.startsWith("Other", ignoreCase = true) },
        { it.name.lowercase() },
    )
}
