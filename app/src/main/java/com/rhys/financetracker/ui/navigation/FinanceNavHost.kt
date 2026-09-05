package com.rhys.financetracker.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rhys.financetracker.data.export.ExportedFile
import com.rhys.financetracker.ui.accounts.AccountEditScreen
import com.rhys.financetracker.ui.accounts.AccountsScreen
import com.rhys.financetracker.ui.categories.CategoriesScreen
import com.rhys.financetracker.ui.categories.CategoryEditScreen
import com.rhys.financetracker.ui.dashboard.DashboardScreen
import com.rhys.financetracker.ui.importer.ImportScreen
import com.rhys.financetracker.ui.insights.InsightsScreen
import com.rhys.financetracker.ui.people.PeopleScreen
import com.rhys.financetracker.ui.people.PersonEditScreen
import com.rhys.financetracker.ui.recurring.RecurringEditScreen
import com.rhys.financetracker.ui.recurring.RecurringScreen
import com.rhys.financetracker.ui.reports.ReportsScreen
import com.rhys.financetracker.ui.savings.SavingsEditScreen
import com.rhys.financetracker.ui.savings.SavingsScreen
import com.rhys.financetracker.ui.settings.AppearanceSettingsScreen
import com.rhys.financetracker.ui.settings.BackupSettingsScreen
import com.rhys.financetracker.ui.settings.DashboardLayoutScreen
import com.rhys.financetracker.ui.settings.ExternalDataSettingsScreen
import com.rhys.financetracker.ui.settings.NotificationSettingsScreen
import com.rhys.financetracker.ui.settings.SecuritySettingsScreen
import com.rhys.financetracker.ui.settings.SettingsScreen
import com.rhys.financetracker.ui.transactions.TransactionEditScreen
import com.rhys.financetracker.ui.transactions.TransactionListScreen

/**
 * The whole navigation graph.
 *
 * The bottom bar is only shown on the five top-level destinations; every other
 * screen is a full-page push with its own back arrow, so the user always knows
 * whether they are "somewhere" or "in something".
 */
@Composable
fun FinanceNavHost(
    onShareFile: (ExportedFile) -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isTopLevel = TopLevelDestination.fromRoute(currentRoute) != null

    Scaffold(
        bottomBar = {
            if (isTopLevel) {
                NavigationBar {
                    TopLevelDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = { navController.navigateToTab(destination.route) },
                            icon = {
                                Icon(destination.icon, contentDescription = destination.label)
                            },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = padding.calculateBottomPadding()),
        ) {
            NavHost(
                navController = navController,
                startDestination = Routes.DASHBOARD,
                modifier = Modifier.fillMaxSize(),
            ) {
                topLevelDestinations(navController, onShareFile)
                editorDestinations(navController)
                settingsDestinations(navController)
            }
        }
    }
}

/** The five tabs. */
private fun NavGraphBuilder.topLevelDestinations(
    navController: NavHostController,
    onShareFile: (ExportedFile) -> Unit,
) {
    composable(Routes.DASHBOARD) {
        DashboardScreen(
            onOpenTransaction = { navController.navigate(Routes.transactionEdit(it)) },
            onAddTransaction = { navController.navigate(Routes.transactionEdit()) },
            onOpenAccounts = { navController.navigate(Routes.ACCOUNTS) },
            onOpenRecurring = { navController.navigate(Routes.RECURRING) },
            onOpenSavings = { navController.navigateToTab(Routes.SAVINGS) },
            onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            onOpenDashboardSettings = { navController.navigate(Routes.SETTINGS_DASHBOARD) },
            onOpenExternalData = { navController.navigate(Routes.SETTINGS_EXTERNAL_DATA) },
            onOpenInsights = { navController.navigate(Routes.INSIGHTS) },
        )
    }

    composable(Routes.TRANSACTIONS) {
        TransactionListScreen(
            onOpenTransaction = { navController.navigate(Routes.transactionEdit(it)) },
            onAddTransaction = { navController.navigate(Routes.transactionEdit()) },
            onShareFile = onShareFile,
        )
    }

    composable(Routes.SAVINGS) {
        SavingsScreen(
            onEditGoal = { navController.navigate(Routes.savingsEdit(it)) },
            onAddGoal = { navController.navigate(Routes.savingsEdit()) },
        )
    }

    composable(Routes.REPORTS) {
        ReportsScreen(onShareFile = onShareFile)
    }

    composable(Routes.MORE) {
        MoreScreen(
            onOpenInsights = { navController.navigate(Routes.INSIGHTS) },
            onOpenAccounts = { navController.navigate(Routes.ACCOUNTS) },
            onOpenPeople = { navController.navigate(Routes.PEOPLE) },
            onOpenRecurring = { navController.navigate(Routes.RECURRING) },
            onOpenCategories = { navController.navigate(Routes.CATEGORIES) },
            onOpenImport = { navController.navigate(Routes.IMPORT) },
            onOpenSettings = { navController.navigate(Routes.SETTINGS) },
        )
    }
}

/** Everything that adds or edits a record. */
private fun NavGraphBuilder.editorDestinations(navController: NavHostController) {
    composable(
        route = Routes.TRANSACTION_EDIT_PATTERN,
        arguments = listOf(navArgument(Routes.ARG_ID) { type = NavType.StringType }),
    ) {
        TransactionEditScreen(onBack = { navController.popBackStack() })
    }

    composable(Routes.ACCOUNTS) {
        AccountsScreen(
            onBack = { navController.popBackStack() },
            onEditAccount = { navController.navigate(Routes.accountEdit(it)) },
            onAddAccount = { navController.navigate(Routes.accountEdit()) },
            onOpenPeople = { navController.navigate(Routes.PEOPLE) },
        )
    }

    composable(
        route = Routes.ACCOUNT_EDIT_PATTERN,
        arguments = listOf(navArgument(Routes.ARG_ID) { type = NavType.StringType }),
    ) {
        AccountEditScreen(onBack = { navController.popBackStack() })
    }

    composable(Routes.PEOPLE) {
        PeopleScreen(
            onBack = { navController.popBackStack() },
            onEditPerson = { navController.navigate(Routes.personEdit(it)) },
            onAddPerson = { navController.navigate(Routes.personEdit()) },
        )
    }

    composable(
        route = Routes.PERSON_EDIT_PATTERN,
        arguments = listOf(navArgument(Routes.ARG_ID) { type = NavType.StringType }),
    ) {
        PersonEditScreen(onBack = { navController.popBackStack() })
    }

    composable(Routes.CATEGORIES) {
        CategoriesScreen(
            onBack = { navController.popBackStack() },
            onEditCategory = { navController.navigate(Routes.categoryEdit(it)) },
            onAddCategory = { navController.navigate(Routes.categoryEdit()) },
        )
    }

    composable(
        route = Routes.CATEGORY_EDIT_PATTERN,
        arguments = listOf(navArgument(Routes.ARG_ID) { type = NavType.StringType }),
    ) {
        CategoryEditScreen(onBack = { navController.popBackStack() })
    }

    composable(Routes.RECURRING) {
        RecurringScreen(
            onBack = { navController.popBackStack() },
            onEditRule = { navController.navigate(Routes.recurringEdit(it)) },
            onAddRule = { navController.navigate(Routes.recurringEdit()) },
        )
    }

    composable(
        route = Routes.RECURRING_EDIT_PATTERN,
        arguments = listOf(navArgument(Routes.ARG_ID) { type = NavType.StringType }),
    ) {
        RecurringEditScreen(onBack = { navController.popBackStack() })
    }

    composable(
        route = Routes.SAVINGS_EDIT_PATTERN,
        arguments = listOf(navArgument(Routes.ARG_ID) { type = NavType.StringType }),
    ) {
        SavingsEditScreen(onBack = { navController.popBackStack() })
    }

    composable(Routes.INSIGHTS) {
        InsightsScreen(
            onBack = { navController.popBackStack() },
            onOpenCategory = { _, _ ->
                // Tapping through from advice lands on the ledger, where the
                // full filter set is available.
                navController.navigateToTab(Routes.TRANSACTIONS)
            },
        )
    }

    composable(Routes.IMPORT) {
        ImportScreen(
            onBack = { navController.popBackStack() },
            onFinished = { navController.navigateToTab(Routes.DASHBOARD) },
        )
    }
}

/** Settings and its sub-screens. */
private fun NavGraphBuilder.settingsDestinations(navController: NavHostController) {
    composable(Routes.SETTINGS) {
        SettingsScreen(
            onBack = { navController.popBackStack() },
            onOpenAppearance = { navController.navigate(Routes.SETTINGS_APPEARANCE) },
            onOpenSecurity = { navController.navigate(Routes.SETTINGS_SECURITY) },
            onOpenNotifications = { navController.navigate(Routes.SETTINGS_NOTIFICATIONS) },
            onOpenBackup = { navController.navigate(Routes.SETTINGS_BACKUP) },
            onOpenExternalData = { navController.navigate(Routes.SETTINGS_EXTERNAL_DATA) },
            onOpenDashboardLayout = { navController.navigate(Routes.SETTINGS_DASHBOARD) },
            onOpenCategories = { navController.navigate(Routes.CATEGORIES) },
            onOpenImport = { navController.navigate(Routes.IMPORT) },
        )
    }
    composable(Routes.SETTINGS_APPEARANCE) {
        AppearanceSettingsScreen(onBack = { navController.popBackStack() })
    }
    composable(Routes.SETTINGS_SECURITY) {
        SecuritySettingsScreen(onBack = { navController.popBackStack() })
    }
    composable(Routes.SETTINGS_NOTIFICATIONS) {
        NotificationSettingsScreen(onBack = { navController.popBackStack() })
    }
    composable(Routes.SETTINGS_BACKUP) {
        BackupSettingsScreen(onBack = { navController.popBackStack() })
    }
    composable(Routes.SETTINGS_EXTERNAL_DATA) {
        ExternalDataSettingsScreen(onBack = { navController.popBackStack() })
    }
    composable(Routes.SETTINGS_DASHBOARD) {
        DashboardLayoutScreen(onBack = { navController.popBackStack() })
    }
}

/**
 * Switches tabs without growing the back stack: the standard bottom-navigation
 * behaviour, where pressing back from any tab returns to the dashboard rather
 * than walking through every tab visited.
 */
private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
