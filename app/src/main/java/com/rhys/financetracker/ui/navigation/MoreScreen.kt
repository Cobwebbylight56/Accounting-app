package com.rhys.financetracker.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.rhys.financetracker.ui.settings.SettingsGroupHeader
import com.rhys.financetracker.ui.settings.SettingsItem
import com.rhys.financetracker.ui.settings.SettingsNote

/**
 * The "More" tab: everything that does not deserve a tab of its own.
 *
 * Keeping the bottom bar to five items and putting the rest here is what stops
 * the app feeling cluttered while still exposing every feature within two taps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    onOpenInsights: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenPeople: () -> Unit,
    onOpenRecurring: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("More") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsGroupHeader("Set things up")
            SettingsItem(
                title = "Accounts",
                subtitle = "Current accounts, savings, cash, cards, loans",
                icon = Icons.Outlined.AccountBalance,
                onClick = onOpenAccounts,
            )
            SettingsItem(
                title = "People",
                subtitle = "Everyone whose money you are keeping track of",
                icon = Icons.Outlined.People,
                onClick = onOpenPeople,
            )
            SettingsItem(
                title = "Regular payments",
                subtitle = "Salary, bills and standing orders that repeat",
                icon = Icons.Outlined.EventRepeat,
                onClick = onOpenRecurring,
            )
            SettingsItem(
                title = "Categories",
                subtitle = "How your spending is grouped",
                icon = Icons.Outlined.Category,
                onClick = onOpenCategories,
            )

            SettingsGroupHeader("Tools")
            SettingsItem(
                title = "Advice",
                subtitle = "Where you could spend less, and what is coming",
                icon = Icons.Outlined.Lightbulb,
                onClick = onOpenInsights,
            )
            SettingsItem(
                title = "Import a spreadsheet",
                subtitle = "Bring in an existing Excel or CSV budget",
                icon = Icons.Outlined.UploadFile,
                onClick = onOpenImport,
            )
            SettingsItem(
                title = "Settings",
                subtitle = "Appearance, security, notifications, backup",
                icon = Icons.Outlined.Settings,
                onClick = onOpenSettings,
            )

            SettingsNote(
                "Everything is saved automatically as you go, and the app works with no " +
                    "internet connection at all.",
            )
        }
    }
}
