package com.rhys.financetracker.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Every screen in the app, in one place.
 *
 * Routes are plain strings with typed helpers for the ones that take an
 * argument, so adding a screen means adding one object here and one entry in
 * [FinanceNavHost] — nothing else has to change.
 */
object Routes {
    const val DASHBOARD = "dashboard"
    const val TRANSACTIONS = "transactions"
    const val SAVINGS = "savings"
    const val REPORTS = "reports"
    const val MORE = "more"

    const val TRANSACTION_EDIT = "transaction/edit"
    const val ACCOUNTS = "accounts"
    const val ACCOUNT_EDIT = "account/edit"
    const val PEOPLE = "people"
    const val PERSON_EDIT = "person/edit"
    const val CATEGORIES = "categories"
    const val CATEGORY_EDIT = "category/edit"
    const val RECURRING = "recurring"
    const val RECURRING_EDIT = "recurring/edit"
    const val SAVINGS_EDIT = "savings/edit"
    const val REPORT_DETAIL = "report"
    const val INSIGHTS = "insights"
    const val SETTINGS = "settings"
    const val SETTINGS_APPEARANCE = "settings/appearance"
    const val SETTINGS_SECURITY = "settings/security"
    const val SETTINGS_NOTIFICATIONS = "settings/notifications"
    const val SETTINGS_BACKUP = "settings/backup"
    const val SETTINGS_EXTERNAL_DATA = "settings/external"
    const val SETTINGS_DASHBOARD = "settings/dashboard"
    const val IMPORT = "import"

    /** Argument name shared by every "edit this record" screen. */
    const val ARG_ID = "id"

    /** `0` means "create a new one". */
    const val NEW_ID = 0L

    fun transactionEdit(id: Long = NEW_ID): String = "$TRANSACTION_EDIT/$id"
    fun accountEdit(id: Long = NEW_ID): String = "$ACCOUNT_EDIT/$id"
    fun personEdit(id: Long = NEW_ID): String = "$PERSON_EDIT/$id"
    fun categoryEdit(id: Long = NEW_ID): String = "$CATEGORY_EDIT/$id"
    fun recurringEdit(id: Long = NEW_ID): String = "$RECURRING_EDIT/$id"
    fun savingsEdit(id: Long = NEW_ID): String = "$SAVINGS_EDIT/$id"
    fun reportDetail(type: String): String = "$REPORT_DETAIL/$type"

    /**
     * The importer, already filed against one account.
     *
     * [NEW_ID] means "ask which account", which is what the general Import
     * entries use.
     */
    fun importForAccount(accountId: Long = NEW_ID): String = "$IMPORT?$ARG_ACCOUNT_ID=$accountId"

    /** Route patterns, with the argument placeholder Navigation expects. */
    const val TRANSACTION_EDIT_PATTERN = "$TRANSACTION_EDIT/{$ARG_ID}"
    const val ACCOUNT_EDIT_PATTERN = "$ACCOUNT_EDIT/{$ARG_ID}"
    const val PERSON_EDIT_PATTERN = "$PERSON_EDIT/{$ARG_ID}"
    const val CATEGORY_EDIT_PATTERN = "$CATEGORY_EDIT/{$ARG_ID}"
    const val RECURRING_EDIT_PATTERN = "$RECURRING_EDIT/{$ARG_ID}"
    const val SAVINGS_EDIT_PATTERN = "$SAVINGS_EDIT/{$ARG_ID}"
    const val REPORT_DETAIL_PATTERN = "$REPORT_DETAIL/{type}"

    /** Optional so the plain `import` route still matches. */
    const val ARG_ACCOUNT_ID = "accountId"
    const val IMPORT_PATTERN = "$IMPORT?$ARG_ACCOUNT_ID={$ARG_ACCOUNT_ID}"
}

/** The five tabs along the bottom. */
enum class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    DASHBOARD(Routes.DASHBOARD, "Home", Icons.Outlined.Home),
    TRANSACTIONS(Routes.TRANSACTIONS, "Money", Icons.Outlined.ReceiptLong),
    SAVINGS(Routes.SAVINGS, "Savings", Icons.Outlined.Savings),
    REPORTS(Routes.REPORTS, "Reports", Icons.Outlined.Assessment),
    MORE(Routes.MORE, "More", Icons.Outlined.MoreHoriz),
    ;

    companion object {
        fun fromRoute(route: String?): TopLevelDestination? =
            entries.firstOrNull { it.route == route }
    }
}
