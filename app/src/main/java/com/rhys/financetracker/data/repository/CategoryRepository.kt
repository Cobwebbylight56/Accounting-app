package com.rhys.financetracker.data.repository

import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.core.result.runCatchingApp
import com.rhys.financetracker.core.validation.Validators
import com.rhys.financetracker.data.local.dao.CategoryDao
import com.rhys.financetracker.data.local.entity.CategoryEntity
import com.rhys.financetracker.domain.model.CategoryKind
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class CategoryRepository @Inject constructor(
    private val categoryDao: CategoryDao,
) {

    fun observeActive(): Flow<List<CategoryEntity>> = categoryDao.observeActive()

    fun observeAll(): Flow<List<CategoryEntity>> = categoryDao.observeAll()

    fun observeByKind(kind: CategoryKind): Flow<List<CategoryEntity>> =
        categoryDao.observeByKind(kind)

    /** Top-level categories with their children, for the settings list. */
    fun observeGrouped(): Flow<List<CategoryGroup>> =
        categoryDao.observeAll().map { all ->
            val byParent = all.groupBy { it.parentId }
            all.filter { it.parentId == null }
                .map { parent -> CategoryGroup(parent, byParent[parent.id].orEmpty()) }
        }

    suspend fun get(id: Long): CategoryEntity? = categoryDao.getById(id)

    suspend fun getAll(): List<CategoryEntity> = categoryDao.getAll()

    /**
     * Finds a category by name, creating it if it does not exist.  The importer
     * relies on this so that a spreadsheet row naming an unknown category
     * produces a real, correctly coloured category rather than "Uncategorised".
     */
    suspend fun findOrCreate(name: String, kind: CategoryKind, colorHex: String): CategoryEntity {
        categoryDao.getByNameAndKind(name, kind)?.let { return it }
        val entity = CategoryEntity(name = name.trim(), kind = kind, colorHex = colorHex)
        val id = categoryDao.insert(entity)
        return entity.copy(id = id)
    }

    suspend fun save(category: CategoryEntity): AppResult<Long> =
        runCatchingApp("Could not save this category") {
            Validators.validateName(category.name, "Category name").errorOrNull?.let { error(it) }
            val existing = categoryDao.getByNameAndKind(category.name, category.kind)
            if (existing != null && existing.id != category.id) {
                error("There is already a ${category.kind.displayName.lowercase()} " +
                    "category called \"${category.name}\"")
            }
            if (category.parentId == category.id && category.id != 0L) {
                error("A category cannot be inside itself")
            }
            if (category.id == 0L) {
                categoryDao.insert(category)
            } else {
                categoryDao.update(category.copy(updatedAt = Instant.now().toEpochMilli()))
                category.id
            }
        }

    suspend fun duplicate(id: Long): AppResult<Long> =
        runCatchingApp("Could not duplicate this category") {
            val original = categoryDao.getById(id) ?: error("That category no longer exists")
            categoryDao.insert(
                original.copy(
                    id = 0L,
                    name = uniqueName(original.name, original.kind),
                    isSystem = false,
                    createdAt = Instant.now().toEpochMilli(),
                    updatedAt = Instant.now().toEpochMilli(),
                ),
            )
        }

    suspend fun setArchived(id: Long, archived: Boolean): AppResult<Unit> =
        runCatchingApp("Could not archive this category") {
            categoryDao.setArchived(id, archived, Instant.now().toEpochMilli())
        }

    /**
     * Deletes a category.  Transactions that used it keep their history and
     * simply become uncategorised (the foreign key sets the column to null),
     * so no money ever disappears from a report.
     */
    suspend fun delete(category: CategoryEntity): AppResult<Unit> =
        runCatchingApp("Could not delete this category") {
            if (category.isSystem) {
                error("\"${category.name}\" is a built-in category. Archive it instead.")
            }
            categoryDao.delete(category)
        }

    private suspend fun uniqueName(base: String, kind: CategoryKind): String {
        var candidate = "$base (copy)"
        var counter = 2
        while (categoryDao.getByNameAndKind(candidate, kind) != null) {
            candidate = "$base (copy $counter)"
            counter++
        }
        return candidate
    }
}

/** A top-level category together with its children. */
data class CategoryGroup(
    val parent: CategoryEntity,
    val children: List<CategoryEntity>,
)
