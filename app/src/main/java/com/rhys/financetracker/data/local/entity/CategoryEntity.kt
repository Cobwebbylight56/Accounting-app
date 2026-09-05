package com.rhys.financetracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.rhys.financetracker.domain.model.CategoryKind
import java.time.Instant

/**
 * A user-definable grouping for income and spending.  Categories may be nested
 * one level deep ([parentId]) so that, for example, "Utilities" can contain
 * "Electric", "Gas" and "Water" while still rolling up on reports.
 */
@Entity(
    tableName = "categories",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("parent_id"), Index(value = ["name", "kind"], unique = true)],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val kind: CategoryKind,
    @ColumnInfo(name = "color_hex") val colorHex: String,
    /** Name of a Material icon, resolved at runtime; see [com.rhys.financetracker.ui.components.CategoryIcons]. */
    @ColumnInfo(name = "icon_key") val iconKey: String? = null,
    @ColumnInfo(name = "parent_id") val parentId: Long? = null,
    /** Optional monthly budget for this category, in minor units. */
    @ColumnInfo(name = "monthly_budget_minor") val monthlyBudgetMinor: Long? = null,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    /** Seeded categories the app relies on; they can be renamed but not deleted. */
    @ColumnInfo(name = "is_system") val isSystem: Boolean = false,
    @ColumnInfo(name = "is_archived") val isArchived: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = Instant.now().toEpochMilli(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = Instant.now().toEpochMilli(),
)
