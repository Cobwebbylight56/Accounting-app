package com.rhys.financetracker.data.local.seed

import com.rhys.financetracker.data.local.entity.CategoryEntity

/**
 * Puts the categories an older version left loose into the groups they now
 * belong to: Clothes and Household under Shopping, Parking under Transport,
 * Credit & loans under Money. Done once, the first time a version with new
 * groups starts — after that, where somebody keeps a category is up to them.
 */
object CategoryRegrouping {

    /** Category id to the id of the group it goes in. */
    fun moves(existing: List<CategoryEntity>, seeds: List<SeedCategory> = DefaultData.defaultCategories()): Map<Long, Long> {
        val byName = existing.associateBy { it.name.lowercase() to it.kind }
        val heading = existing.mapNotNull { it.parentId }.toSet()
        return seeds.mapNotNull { seed ->
            val parent = seed.parent ?: return@mapNotNull null
            val category = byName[seed.name.lowercase() to seed.kind] ?: return@mapNotNull null
            val group = byName[parent.lowercase() to seed.kind]?.takeIf { it.parentId == null } ?: return@mapNotNull null
            // Only one that is loose, and not heading a group somebody made.
            if (category.parentId != null || category.id in heading || category.id == group.id) null else category.id to group.id
        }.toMap()
    }
}
