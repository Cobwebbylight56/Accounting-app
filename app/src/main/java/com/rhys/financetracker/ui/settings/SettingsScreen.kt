package com.rhys.financetracker.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhys.financetracker.BuildConfig
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.ui.components.ConfirmDialog
import java.time.Instant
import java.time.ZoneId

/**
 * The settings hub.
 *
 * Sub-screens are separate destinations rather than expanding panels, so each
 * one stays short enough to read without scrolling past things that do not
 * matter right now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenSecurity: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenExternalData: () -> Unit,
    onOpenDashboardLayout: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenImport: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showClearConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            if (state.isBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            SettingsGroupHeader("How the app looks and behaves")
            SettingsItem(
                title = "Appearance",
                subtitle = "Light or dark, colours, text size",
                icon = Icons.Outlined.Palette,
                onClick = onOpenAppearance,
            )
            SettingsItem(
                title = "Dashboard layout",
                subtitle = "Choose which cards appear on the home screen",
                icon = Icons.Outlined.Dashboard,
                onClick = onOpenDashboardLayout,
            )
            SettingsItem(
                title = "Categories",
                subtitle = "Add, rename and colour the groups your money falls into",
                icon = Icons.Outlined.Category,
                onClick = onOpenCategories,
            )

            SettingsGroupHeader("Privacy and reminders")
            SettingsItem(
                title = "Lock and security",
                subtitle = if (state.settings.isLockEnabled) {
                    state.settings.lockMethod.displayName
                } else {
                    "The app is not locked"
                },
                icon = Icons.Outlined.Lock,
                onClick = onOpenSecurity,
            )
            SettingsItem(
                title = "Notifications",
                subtitle = "Bill reminders, overdue payments, low balances",
                icon = Icons.Outlined.Notifications,
                onClick = onOpenNotifications,
            )

            SettingsGroupHeader("Your data")
            SettingsItem(
                title = "Backup and restore",
                subtitle = state.settings.lastBackupAt?.let {
                    "Last backup " + DateUtils.format(
                        Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate(),
                    )
                } ?: "No backup taken yet",
                icon = Icons.Outlined.Backup,
                onClick = onOpenBackup,
            )
            SettingsItem(
                title = "Import a spreadsheet",
                subtitle = "Bring in an existing Excel or CSV budget",
                icon = Icons.Outlined.UploadFile,
                onClick = onOpenImport,
            )
            SettingsItem(
                title = "Rates and figures",
                subtitle = if (state.settings.externalDataEnabled) {
                    "Updating automatically"
                } else {
                    "Turned off"
                },
                icon = Icons.Outlined.CloudDownload,
                onClick = onOpenExternalData,
            )

            SettingsGroupHeader("Housekeeping")
            SettingsItem(
                title = "Run the monthly update now",
                subtitle = "Adds anything due and archives finished months",
                icon = Icons.Outlined.Refresh,
                onClick = viewModel::runMonthlyUpdate,
            )
            SettingsItem(
                title = "Rebuild the monthly archive",
                subtitle = "Recalculates every archived month from your transactions",
                icon = Icons.Outlined.Refresh,
                onClick = viewModel::rebuildArchive,
            )
            SettingsItem(
                title = "Load the example household",
                subtitle = "Adds a worked example so you can see how everything fits together",
                icon = Icons.Outlined.PlayCircle,
                onClick = viewModel::loadSampleData,
            )
            SettingsItem(
                title = "Delete everything",
                subtitle = "Clears all accounts, transactions and goals",
                icon = Icons.Outlined.DeleteForever,
                onClick = { showClearConfirm = true },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            SettingsItem(
                title = "About",
                subtitle = "Finance Tracker ${BuildConfig.VERSION_NAME} · " +
                    "everything is stored on this device",
                icon = Icons.Outlined.Info,
            )
            SettingsNote(
                "Your financial data never leaves this phone unless you export or back it " +
                    "up yourself. The only optional internet use is fetching exchange rates " +
                    "and the bank holiday calendar.",
            )
        }
    }

    if (showClearConfirm) {
        ConfirmDialog(
            title = "Delete everything?",
            message = "Every account, transaction, regular payment and savings goal will be " +
                "removed. This cannot be undone. Take a backup first if you are not sure.",
            confirmLabel = "Delete everything",
            isDestructive = true,
            onConfirm = viewModel::clearAllData,
            onDismiss = { showClearConfirm = false },
        )
    }
}
