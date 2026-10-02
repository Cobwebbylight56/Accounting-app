package com.rhys.financetracker.ui.navigation

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.rhys.financetracker.ui.theme.FinanceTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.rhys.financetracker.ui.accounts.AccountDetailScreen
import com.rhys.financetracker.ui.accounts.AccountEditScreen
import com.rhys.financetracker.ui.accounts.AccountsScreen
import com.rhys.financetracker.ui.categories.CategoriesScreen
import com.rhys.financetracker.ui.categories.CategoryEditScreen
import com.rhys.financetracker.ui.dashboard.DashboardScreen
import com.rhys.financetracker.ui.importer.ImportScreen
import com.rhys.financetracker.ui.insights.InsightsScreen
import com.rhys.financetracker.ui.people.PeopleScreen
import com.rhys.financetracker.ui.people.PersonEditScreen
import com.rhys.financetracker.ui.people.PersonHubScreen
import com.rhys.financetracker.ui.people.SetupScreen
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
import com.rhys.financetracker.ui.spending.SentToPeopleScreen
import com.rhys.financetracker.ui.tidy.SortEverythingScreen
import com.rhys.financetracker.ui.spending.SortSpendingScreen
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
    importFile: Uri? = null,
    onImportFileHandled: () -> Unit = {},
    navController: NavHostController = rememberNavController(),
) {
    // A statement opened from outside the app goes straight to the importer,
    // wherever the user happened to be.
    LaunchedEffect(importFile) {
        if (importFile != null) navController.navigate(Routes.importForAccount())
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isTopLevel = TopLevelDestination.fromRoute(currentRoute) != null

    Scaffold(
        bottomBar = {
            if (isTopLevel) {
                SoftBottomBar(
                    currentRoute = currentRoute,
                    onSelect = { navController.navigateToTab(it.route) },
                )
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
                // A page opening glides in a little from the side and fades
                // up; going back reverses it. Short, so it never holds you up.
                enterTransition = {
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) +
                        slideIntoContainer(
                            androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection.Start,
                            androidx.compose.animation.core.tween(260),
                            initialOffset = { it / 10 },
                        )
                },
                exitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)) },
                popEnterTransition = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) },
                popExitTransition = {
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)) +
                        slideOutOfContainer(
                            androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection.End,
                            androidx.compose.animation.core.tween(240),
                            targetOffset = { it / 10 },
                        )
                },
            ) {
                topLevelDestinations(navController, onShareFile)
                editorDestinations(navController, importFile, onImportFileHandled)
                settingsDestinations(navController)
            }
        }
    }
}

/**
 * The bar along the bottom: icons on a soft rounded bar, with the tab you are
 * on lifted into a coloured circle.
 */
@Composable
private fun SoftBottomBar(
    currentRoute: String?,
    onSelect: (TopLevelDestination) -> Unit,
) {
    val colors = FinanceTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 10.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = colors.navBar,
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TopLevelDestination.entries.forEach { destination ->
                    val selected = currentRoute == destination.route
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable { onSelect(destination) }
                            .semantics { this.selected = selected },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Surface(
                                shape = CircleShape,
                                color = colors.navSelected,
                                shadowElevation = 6.dp,
                                border = BorderStroke(3.dp, MaterialTheme.colorScheme.background),
                                modifier = Modifier
                                    .size(48.dp)
                                    .offset(y = (-14).dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        destination.icon,
                                        contentDescription = destination.label,
                                        tint = colors.onNavSelected,
                                    )
                                }
                            }
                        } else {
                            Icon(
                                destination.icon,
                                contentDescription = destination.label,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
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
            onOpenSetup = { navController.navigate(Routes.SETUP) },
            onOpenPerson = { navController.navigate(Routes.personHub(it)) },
            onOpenPeople = { navController.navigate(Routes.PEOPLE) },
            onOpenSortSpending = { navController.navigate(Routes.SORT_SPENDING) },
            onOpenSentToPeople = { navController.navigate(Routes.SENT_TO_PEOPLE) },
            onOpenPeopleMoneyFor = { navController.navigate(Routes.sentToPeople(it)) },
            onOpenBackup = { navController.navigate(Routes.SETTINGS_BACKUP) },
        )
    }

    composable(Routes.SPENDING) {
        com.rhys.financetracker.ui.spending.SpendingScreen(
            onOpenLedger = { navController.navigate(Routes.LEDGER) },
            onOpenSubscriptions = { navController.navigate(Routes.SUBSCRIPTIONS) },
        )
    }

    composable(
        route = Routes.LEDGER_PATTERN,
        arguments = listOf(
            navArgument(Routes.ARG_DRILL_DOWN) {
                type = NavType.BoolType
                defaultValue = true
            },
        ),
    ) {
        TransactionListScreen(
            onOpenTransaction = { navController.navigate(Routes.transactionEdit(it)) },
            onAddTransaction = { navController.navigate(Routes.transactionEdit()) },
            onOpenImport = { navController.navigate(Routes.importForAccount()) },
            onShareFile = onShareFile,
            onBack = { navController.popBackStack() },
        )
    }

    composable(Routes.TRANSACTIONS) {
        TransactionListScreen(
            onOpenTransaction = { navController.navigate(Routes.transactionEdit(it)) },
            onAddTransaction = { navController.navigate(Routes.transactionEdit()) },
            onOpenImport = { navController.navigate(Routes.importForAccount()) },
            onShareFile = onShareFile,
        )
    }

    composable(Routes.SAVINGS) {
        SavingsScreen(
            onEditGoal = { navController.navigate(Routes.savingsEdit(it)) },
            onAddGoal = { navController.navigate(Routes.savingsEdit()) },
            onOpenAccount = { navController.navigate(Routes.accountView(it)) },
        )
    }

    composable(Routes.REPORTS) {
        ReportsScreen(
            onShareFile = onShareFile,
            onOpenLedger = { navController.navigate(Routes.LEDGER) },
        )
    }

    composable(Routes.MORE) {
        MoreScreen(
            onOpenInsights = { navController.navigate(Routes.INSIGHTS) },
            onOpenAccounts = { navController.navigate(Routes.ACCOUNTS) },
            onOpenPeople = { navController.navigate(Routes.PEOPLE) },
            onOpenRecurring = { navController.navigate(Routes.RECURRING) },
            onOpenCategories = { navController.navigate(Routes.CATEGORIES) },
            onOpenImport = { navController.navigate(Routes.importForAccount()) },
            onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            onOpenSortSpending = { navController.navigate(Routes.SORT_SPENDING) },
            onOpenSentToPeople = { navController.navigate(Routes.SENT_TO_PEOPLE) },
            onOpenSortEverything = { navController.navigate(Routes.SORT_EVERYTHING) },
            onOpenSubscriptions = { navController.navigate(Routes.SUBSCRIPTIONS) },
            onOpenCards = { navController.navigate(Routes.CARDS) },
        )
    }

    composable(Routes.SUBSCRIPTIONS) {
        com.rhys.financetracker.ui.subscriptions.SubscriptionsScreen(
            onBack = { navController.popBackStack() },
            onOpenLedger = { navController.navigate(Routes.LEDGER) },
        )
    }

    composable(Routes.CARDS) {
        com.rhys.financetracker.ui.accounts.CardsScreen(
            onBack = { navController.popBackStack() },
            onOpenAccount = { navController.navigate(Routes.accountView(it)) },
            onImportStatement = { navController.navigate(Routes.importForAccount(it)) },
            onAddCard = { navController.navigate(Routes.accountEdit()) },
        )
    }

    composable(Routes.SORT_EVERYTHING) {
        SortEverythingScreen(
            onBack = { navController.popBackStack() },
            onOpenSortSpending = { navController.navigate(Routes.SORT_SPENDING) },
        )
    }

    composable(Routes.SORT_SPENDING) {
        SortSpendingScreen(onBack = { navController.popBackStack() })
    }

    composable(
        route = Routes.SENT_TO_PEOPLE_PATTERN,
        arguments = listOf(
            navArgument(Routes.ARG_PERSON_ID) {
                type = NavType.LongType
                defaultValue = Routes.NEW_ID
            },
        ),
    ) { entry ->
        val personId = entry.arguments?.getLong(Routes.ARG_PERSON_ID) ?: Routes.NEW_ID
        SentToPeopleScreen(
            onBack = { navController.popBackStack() },
            onOpenTransaction = { navController.navigate(Routes.transactionEdit(it)) },
            personId = personId.takeIf { it != Routes.NEW_ID },
        )
    }
}

/** Everything that adds or edits a record. */
private fun NavGraphBuilder.editorDestinations(
    navController: NavHostController,
    importFile: Uri?,
    onImportFileHandled: () -> Unit,
) {
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
            onImportStatement = { navController.navigate(Routes.importForAccount(it)) },
            onOpenAccount = { navController.navigate(Routes.accountView(it)) },
        )
    }

    composable(
        route = Routes.ACCOUNT_VIEW_PATTERN,
        arguments = listOf(navArgument(Routes.ARG_ID) { type = NavType.StringType }),
    ) {
        AccountDetailScreen(
            onBack = { navController.popBackStack() },
            onEdit = { navController.navigate(Routes.accountEdit(it)) },
            onImportStatement = { navController.navigate(Routes.importForAccount(it)) },
            onOpenTransaction = { navController.navigate(Routes.transactionEdit(it)) },
            onOpenLedger = { navController.navigate(Routes.LEDGER) },
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
            onEditPerson = { navController.navigate(Routes.personHub(it)) },
            // A new person is set up in full — name, pay, accounts, loans —
            // rather than as a name on its own.
            onAddPerson = { navController.navigate(Routes.SETUP) },
        )
    }

    composable(Routes.SETUP) {
        SetupScreen(onFinished = { navController.popBackStack() })
    }

    composable(
        route = Routes.PERSON_HUB_PATTERN,
        arguments = listOf(navArgument(Routes.ARG_ID) { type = NavType.StringType }),
    ) {
        PersonHubScreen(
            onBack = { navController.popBackStack() },
            onEditDetails = { navController.navigate(Routes.personEdit(it)) },
            onOpenAccount = { navController.navigate(Routes.accountView(it)) },
            onImportStatement = { navController.navigate(Routes.importForPerson(it)) },
            onOpenPeopleMoney = { navController.navigate(Routes.sentToPeople(it)) },
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
                // A page of its own, so back returns to the advice.
                navController.navigate(Routes.LEDGER)
            },
        )
    }

    composable(
        route = Routes.IMPORT_PATTERN,
        arguments = listOf(
            navArgument(Routes.ARG_ACCOUNT_ID) {
                type = NavType.LongType
                defaultValue = Routes.NEW_ID
            },
            navArgument(Routes.ARG_PERSON_ID) {
                type = NavType.LongType
                defaultValue = Routes.NEW_ID
            },
        ),
    ) { entry ->
        val accountId = entry.arguments?.getLong(Routes.ARG_ACCOUNT_ID) ?: Routes.NEW_ID
        val personId = entry.arguments?.getLong(Routes.ARG_PERSON_ID) ?: Routes.NEW_ID
        ImportScreen(
            onBack = { navController.popBackStack() },
            // Unwind to the dashboard rather than navigating to it.
            // navigateToTab is the tab-switching pattern — popUpTo(start) with
            // saveState, then restoreState — and using it to reach the very
            // destination it is popping to depends on saved-stack keying that
            // is easy to get wrong. Popping says what is meant and nothing else.
            // Back to wherever the import was started — the account's page,
            // a person's page, Money — not all the way to Home.
            onFinished = { navController.popBackStack() },
            preselectedAccountId = accountId.takeIf { it != Routes.NEW_ID },
            expectedPersonId = personId.takeIf { it != Routes.NEW_ID },
            incomingFile = importFile,
            onIncomingFileHandled = onImportFileHandled,
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
            onOpenImport = { navController.navigate(Routes.importForAccount()) },
            onOpenSortEverything = { navController.navigate(Routes.SORT_EVERYTHING) },
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
