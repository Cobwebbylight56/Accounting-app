package com.rhys.financetracker.data.local.seed

import com.rhys.financetracker.data.local.entity.CategoryEntity
import com.rhys.financetracker.data.local.entity.DashboardWidgetEntity
import com.rhys.financetracker.data.local.entity.PersonEntity
import com.rhys.financetracker.domain.model.CategoryKind
import com.rhys.financetracker.domain.model.DashboardWidget

/**
 * The rows written into an empty database the first time the app starts.
 *
 * Everything here is editable afterwards — these are sensible starting points,
 * not fixed structure.  Categories marked `isSystem` can be renamed and
 * recoloured but not deleted, because reports and the importer fall back to
 * them.
 */
object DefaultData {

    /** Colour palette used for seeded categories; also offered in the colour picker. */
    val PALETTE = listOf(
        "#1B5E4B", "#2E7D6B", "#00796B", "#00838F", "#0277BD", "#1565C0",
        "#3949AB", "#5E35B1", "#7B1FA2", "#AD1457", "#C2185B", "#D32F2F",
        "#E64A19", "#EF6C00", "#F9A825", "#9E9D24", "#558B2F", "#2E7D32",
        "#5D4037", "#455A64", "#616161", "#37474F",
    )

    val PERSON_COLORS = listOf(
        "#1565C0", "#AD1457", "#2E7D32", "#EF6C00", "#5E35B1", "#00838F",
    )

    /** The shared/household person, present in every database. */
    const val SHARED_PERSON_NAME = "Joint"

    fun defaultPeople(): List<PersonEntity> = listOf(
        PersonEntity(
            name = SHARED_PERSON_NAME,
            colorHex = "#455A64",
            isShared = true,
            sortOrder = 100,
            notes = "Shared household money. Accounts owned by both of you live here.",
        ),
    )

    /**
     * Expense, income and saving categories covering the everyday cases named
     * in the specification.  Parent/child nesting is applied after insert, in
     * [com.rhys.financetracker.data.repository.SeedRepository].
     */
    fun defaultCategories(): List<SeedCategory> = listOf(
        // ---------------------------------------------------------- income
        SeedCategory("Salary", CategoryKind.INCOME, "#2E7D32", "Payments", isSystem = true),
        SeedCategory("Overtime", CategoryKind.INCOME, "#558B2F", "MoreTime"),
        SeedCategory("Benefits", CategoryKind.INCOME, "#9E9D24", "VolunteerActivism"),
        SeedCategory("Child benefit", CategoryKind.INCOME, "#F9A825", "ChildCare"),
        SeedCategory("Interest", CategoryKind.INCOME, "#00796B", "TrendingUp"),
        SeedCategory("Refunds", CategoryKind.INCOME, "#00838F", "Undo"),
        SeedCategory("Cash received", CategoryKind.INCOME, "#0277BD", "Payments"),
        SeedCategory("Other income", CategoryKind.INCOME, "#455A64", "MoreHoriz", isSystem = true),

        // --------------------------------------------------------- housing
        SeedCategory("Housing", CategoryKind.EXPENSE, "#5D4037", "Home", isSystem = true),
        SeedCategory("Mortgage", CategoryKind.EXPENSE, "#5D4037", "Home", parent = "Housing"),
        SeedCategory("Rent", CategoryKind.EXPENSE, "#6D4C41", "Home", parent = "Housing"),
        SeedCategory("Council tax", CategoryKind.EXPENSE, "#795548", "AccountBalance", parent = "Housing"),
        SeedCategory("Repairs", CategoryKind.EXPENSE, "#8D6E63", "Build", parent = "Housing"),

        // ------------------------------------------------------- utilities
        SeedCategory("Utilities", CategoryKind.EXPENSE, "#0277BD", "Bolt", isSystem = true),
        SeedCategory("Electric", CategoryKind.EXPENSE, "#F9A825", "Bolt", parent = "Utilities"),
        SeedCategory("Gas", CategoryKind.EXPENSE, "#EF6C00", "LocalFireDepartment", parent = "Utilities"),
        SeedCategory("Energy", CategoryKind.EXPENSE, "#F57F17", "Bolt", parent = "Utilities"),
        SeedCategory("Water", CategoryKind.EXPENSE, "#0288D1", "WaterDrop", parent = "Utilities"),
        SeedCategory("Broadband", CategoryKind.EXPENSE, "#1565C0", "Wifi", parent = "Utilities"),
        SeedCategory("Mobile", CategoryKind.EXPENSE, "#3949AB", "Smartphone", parent = "Utilities"),

        // ------------------------------------------------------------ food
        SeedCategory("Food", CategoryKind.EXPENSE, "#558B2F", "ShoppingCart", isSystem = true),
        SeedCategory("Groceries", CategoryKind.EXPENSE, "#558B2F", "ShoppingCart", parent = "Food"),
        SeedCategory("Eating out", CategoryKind.EXPENSE, "#7CB342", "Restaurant", parent = "Food"),

        // ------------------------------------------------------- transport
        SeedCategory("Transport", CategoryKind.EXPENSE, "#C2185B", "DirectionsCar", isSystem = true),
        SeedCategory("Car finance", CategoryKind.EXPENSE, "#AD1457", "DirectionsCar", parent = "Transport"),
        SeedCategory("Fuel", CategoryKind.EXPENSE, "#D32F2F", "LocalGasStation", parent = "Transport"),
        SeedCategory("Road tax", CategoryKind.EXPENSE, "#C62828", "Receipt", parent = "Transport"),
        SeedCategory("MOT & servicing", CategoryKind.EXPENSE, "#E64A19", "Build", parent = "Transport"),
        SeedCategory("Public transport", CategoryKind.EXPENSE, "#EF5350", "Train", parent = "Transport"),

        // ------------------------------------------------------- insurance
        SeedCategory("Insurance", CategoryKind.EXPENSE, "#00838F", "Shield", isSystem = true),
        SeedCategory("Life insurance", CategoryKind.EXPENSE, "#00838F", "Shield", parent = "Insurance"),
        SeedCategory("Car insurance", CategoryKind.EXPENSE, "#0097A7", "Shield", parent = "Insurance"),
        SeedCategory("Home insurance", CategoryKind.EXPENSE, "#00ACC1", "Shield", parent = "Insurance"),

        // --------------------------------------------------- entertainment
        SeedCategory("Entertainment", CategoryKind.EXPENSE, "#7B1FA2", "Celebration", isSystem = true),
        SeedCategory("Days out", CategoryKind.EXPENSE, "#8E24AA", "Celebration", parent = "Entertainment"),
        SeedCategory("Subscriptions", CategoryKind.EXPENSE, "#6A1B9A", "Subscriptions", parent = "Entertainment"),

        // -------------------------------------------------------- children
        SeedCategory("Children", CategoryKind.EXPENSE, "#F9A825", "ChildCare", isSystem = true),
        SeedCategory("Childcare", CategoryKind.EXPENSE, "#F9A825", "ChildCare", parent = "Children"),
        SeedCategory("School", CategoryKind.EXPENSE, "#FBC02D", "School", parent = "Children"),

        // ------------------------------------------------------------ misc
        SeedCategory("Shopping", CategoryKind.EXPENSE, "#00695C", "ShoppingBag"),
        SeedCategory("Pets", CategoryKind.EXPENSE, "#8D6E63", "Pets"),
        SeedCategory("Health", CategoryKind.EXPENSE, "#D81B60", "MedicalServices"),
        SeedCategory("Education", CategoryKind.EXPENSE, "#3949AB", "School"),
        SeedCategory("Credit & loans", CategoryKind.EXPENSE, "#B71C1C", "CreditCard"),
        SeedCategory("Personal", CategoryKind.EXPENSE, "#616161", "Person"),
        SeedCategory("Other", CategoryKind.EXPENSE, "#455A64", "MoreHoriz", isSystem = true),

        // --------------------------------------------------------- savings
        SeedCategory("Savings", CategoryKind.SAVING, "#1B5E4B", "Savings", isSystem = true),
        SeedCategory("Emergency fund", CategoryKind.SAVING, "#2E7D6B", "Savings", parent = "Savings"),
        SeedCategory("Holiday fund", CategoryKind.SAVING, "#00796B", "BeachAccess", parent = "Savings"),
        SeedCategory("Christmas fund", CategoryKind.SAVING, "#C62828", "Redeem", parent = "Savings"),

        // -------------------------------------------------------- transfer
        SeedCategory("Transfer", CategoryKind.TRANSFER, "#455A64", "SwapHoriz", isSystem = true),
    )

    /** The dashboard layout a new install starts with. */
    fun defaultDashboardWidgets(): List<DashboardWidgetEntity> =
        DashboardWidget.entries.mapIndexed { index, widget ->
            DashboardWidgetEntity(
                widgetKey = widget.key,
                position = index,
                isVisible = widget.defaultVisible,
            )
        }
}

/**
 * A category before it has an id.  [parent] refers to another seed category by
 * name and is resolved once every row has been inserted.
 */
data class SeedCategory(
    val name: String,
    val kind: CategoryKind,
    val colorHex: String,
    val iconKey: String,
    val parent: String? = null,
    val isSystem: Boolean = false,
) {
    fun toEntity(sortOrder: Int, parentId: Long?): CategoryEntity = CategoryEntity(
        name = name,
        kind = kind,
        colorHex = colorHex,
        iconKey = iconKey,
        parentId = parentId,
        sortOrder = sortOrder,
        isSystem = isSystem,
    )
}
