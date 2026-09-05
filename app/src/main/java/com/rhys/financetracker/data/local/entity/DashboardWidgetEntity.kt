package com.rhys.financetracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The user's dashboard layout: which cards are shown and in what order.
 * One row per [com.rhys.financetracker.domain.model.DashboardWidget].
 */
@Entity(tableName = "dashboard_widgets")
data class DashboardWidgetEntity(
    @PrimaryKey @ColumnInfo(name = "widget_key") val widgetKey: String,
    val position: Int,
    @ColumnInfo(name = "is_visible") val isVisible: Boolean,
)
