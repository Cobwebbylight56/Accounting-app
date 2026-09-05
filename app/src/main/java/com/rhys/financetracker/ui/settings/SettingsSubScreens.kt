package com.rhys.financetracker.ui.settings

import android.Manifest
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhys.financetracker.core.validation.Validators
import com.rhys.financetracker.data.backup.BackupFormat
import com.rhys.financetracker.domain.model.ExternalDataKey
import com.rhys.financetracker.domain.model.LockMethod
import com.rhys.financetracker.domain.model.ThemeMode
import com.rhys.financetracker.security.BiometricAuthenticator
import com.rhys.financetracker.ui.components.ConfirmDialog
import com.rhys.financetracker.ui.components.DropdownField
import com.rhys.financetracker.ui.components.LabelledTextField
import kotlinx.coroutines.launch

/** Shared scaffold for the settings sub-screens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSubScreen(
    title: String,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState,
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
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
            content()
        }
    }
}

// ---------------------------------------------------------------- appearance

@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    SettingsSubScreen("Appearance", onBack, snackbarHostState) {
        SettingsGroupHeader("Theme")
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            DropdownField(
                label = "Light or dark",
                options = ThemeMode.entries,
                selected = state.settings.themeMode,
                onSelect = viewModel::setThemeMode,
                optionLabel = { it.displayName },
            )
        }

        SettingsSwitch(
            title = "Use my wallpaper colours",
            subtitle = "Material You. Income and spending stay green and red either way.",
            checked = state.settings.useDynamicColor,
            onCheckedChange = viewModel::setDynamicColor,
        )
        SettingsSwitch(
            title = "Larger text",
            subtitle = "Increases the text size throughout the app",
            checked = state.settings.largeText,
            onCheckedChange = viewModel::setLargeText,
        )

        SettingsGroupHeader("Currency")
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            DropdownField(
                label = "Currency",
                options = listOf("GBP", "EUR", "USD", "AUD", "CAD", "NZD"),
                selected = state.settings.currencyCode,
                onSelect = viewModel::setCurrency,
                optionLabel = { it },
            )
        }
        SettingsNote(
            "Changing the currency changes how amounts are shown. It does not convert " +
                "anything you have already entered.",
        )
    }
}

// ------------------------------------------------------------------ security

@Composable
fun SecuritySettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var showPinDialog by remember { mutableStateOf(false) }
    var showRemovePin by remember { mutableStateOf(false) }

    val biometricAvailability = remember {
        (context as? FragmentActivity)?.let { BiometricAuthenticator.availability(it) }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    SettingsSubScreen("Lock and security", onBack, snackbarHostState) {
        SettingsGroupHeader("PIN")
        SettingsItem(
            title = if (state.isPinSet) "Change your PIN" else "Set a PIN",
            subtitle = if (state.isPinSet) {
                "A PIN is set"
            } else {
                "Needed before fingerprint or face unlock can be used"
            },
            onClick = { showPinDialog = true },
        )
        if (state.isPinSet) {
            SettingsItem(
                title = "Remove the PIN",
                subtitle = "Turns the lock off completely",
                onClick = { showRemovePin = true },
            )
        }

        SettingsGroupHeader("How to unlock")
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            DropdownField(
                label = "Unlock with",
                options = LockMethod.entries,
                selected = state.settings.lockMethod,
                onSelect = viewModel::setLockMethod,
                optionLabel = { it.displayName },
            )
        }
        biometricAvailability?.let {
            SettingsNote("Fingerprint or face unlock: ${it.message}")
        }

        SettingsGroupHeader("Automatic locking")
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            DropdownField(
                label = "Lock after",
                options = listOf(0, 1, 2, 5, 10, 30, 60),
                selected = state.settings.autoLockMinutes,
                onSelect = viewModel::setAutoLockMinutes,
                optionLabel = { minutes ->
                    when (minutes) {
                        0 -> "Straight away"
                        1 -> "1 minute"
                        else -> "$minutes minutes"
                    }
                },
            )
        }
        SettingsItem(
            title = "Lock now",
            subtitle = "Covers the app until you unlock it again",
            enabled = state.settings.isLockEnabled && state.isPinSet,
            onClick = viewModel::lockNow,
        )

        SettingsNote(
            "Your PIN is never stored. What is stored is a scrambled version of it, in " +
                "encrypted storage protected by this phone's hardware keystore.",
        )
    }

    if (showPinDialog) {
        PinEntryDialog(
            title = if (state.isPinSet) "Change your PIN" else "Choose a PIN",
            onConfirm = { pin ->
                viewModel.setPin(pin)
                showPinDialog = false
            },
            onDismiss = { showPinDialog = false },
        )
    }

    if (showRemovePin) {
        ConfirmDialog(
            title = "Remove the PIN?",
            message = "The app will no longer be locked and anyone with your phone will be " +
                "able to see your finances.",
            confirmLabel = "Remove",
            isDestructive = true,
            onConfirm = viewModel::clearPin,
            onDismiss = { showRemovePin = false },
        )
    }
}

/** Asks for a PIN twice, so a typo cannot lock the user out. */
@Composable
private fun PinEntryDialog(
    title: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LabelledTextField(
                    label = "PIN",
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit) },
                    keyboardType = KeyboardType.NumberPassword,
                    supportingText = "Between 4 and 12 digits",
                )
                LabelledTextField(
                    label = "Type it again",
                    value = confirmation,
                    onValueChange = { confirmation = it.filter(Char::isDigit) },
                    keyboardType = KeyboardType.NumberPassword,
                    error = error,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val validation = Validators.validatePin(pin).errorOrNull
                    error = when {
                        validation != null -> validation
                        pin != confirmation -> "The two PINs do not match"
                        else -> null
                    }
                    if (error == null) onConfirm(pin)
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ------------------------------------------------------------- notifications

@Composable
fun NotificationSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { /* The switches work either way; the system decides whether they show. */ }

    SettingsSubScreen("Notifications", onBack, snackbarHostState) {
        SettingsSwitch(
            title = "Bills due soon",
            subtitle = "A reminder a few days before each payment",
            checked = state.settings.notifyBills,
            onCheckedChange = { enabled ->
                if (enabled) {
                    permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
                viewModel.setNotifyBills(enabled)
            },
        )
        SettingsSwitch(
            title = "Overdue payments",
            subtitle = "When a bill's due date has passed",
            checked = state.settings.notifyOverdue,
            onCheckedChange = viewModel::setNotifyOverdue,
        )
        SettingsSwitch(
            title = "Low balance",
            subtitle = "When an account drops below the level you set for it",
            checked = state.settings.notifyLowBalance,
            onCheckedChange = viewModel::setNotifyLowBalance,
        )
        SettingsSwitch(
            title = "Savings milestones",
            subtitle = "At a quarter, half, three quarters and complete",
            checked = state.settings.notifyGoals,
            onCheckedChange = viewModel::setNotifyGoals,
        )

        SettingsGroupHeader("When to check")
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            DropdownField(
                label = "Daily check at",
                options = (0..23).toList(),
                selected = state.settings.reminderHour,
                onSelect = viewModel::setReminderHour,
                optionLabel = { hour -> "%02d:00".format(hour) },
            )
        }
        SettingsNote(
            "The app checks once a day at this time. It never wakes your phone more often " +
                "than that, which is why it uses so little battery.",
        )
    }
}

// -------------------------------------------------------------------- backup

@Composable
fun BackupSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    val createBackup = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(BackupFormat.MIME_TYPE),
    ) { uri -> uri?.let(viewModel::backupTo) }

    val openBackup = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::inspectBackup) }

    val chooseFolder = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            // Persist the grant, otherwise the automatic backup loses access as
            // soon as the app is restarted.
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        viewModel.setAutoBackupFolder(uri)
    }

    SettingsSubScreen("Backup and restore", onBack, snackbarHostState) {
        SettingsGroupHeader("Manual backup")
        SettingsItem(
            title = "Save a backup now",
            subtitle = "Choose where to put it — including Drive, OneDrive or Dropbox",
            onClick = { createBackup.launch(viewModel.suggestedBackupName()) },
        )
        SettingsItem(
            title = "Restore from a backup",
            subtitle = "Replaces everything currently in the app",
            onClick = { openBackup.launch(arrayOf(BackupFormat.MIME_TYPE, "application/*")) },
        )

        SettingsGroupHeader("Automatic backup")
        SettingsSwitch(
            title = "Back up automatically",
            subtitle = "Once a day, while charging and on a good connection",
            checked = state.settings.autoBackupEnabled,
            onCheckedChange = viewModel::setAutoBackupEnabled,
        )
        SettingsItem(
            title = "Backup folder",
            subtitle = state.settings.autoBackupFolderUri?.let { "A folder is chosen" }
                ?: "Not chosen yet",
            onClick = { chooseFolder.launch(null) },
        )
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            DropdownField(
                label = "Keep this many backups",
                options = listOf(3, 5, 10, 20, 50),
                selected = state.settings.autoBackupKeep,
                onSelect = viewModel::setAutoBackupKeep,
                optionLabel = { "$it backups" },
            )
        }

        SettingsNote(
            "A backup is a plain, readable JSON file containing every account, transaction, " +
                "regular payment and goal. Anyone who can open the file can read your " +
                "finances, so keep it somewhere private.",
        )
    }

    state.pendingRestore?.let { summary ->
        AlertDialog(
            onDismissRequest = viewModel::cancelRestore,
            title = { Text("Restore this backup?") },
            text = {
                Column {
                    Text(summary.fileName, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(summary.describe())
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Everything currently in the app will be replaced. Take a " +
                            "backup first if you are not sure.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRestore) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelRestore) { Text("Cancel") }
            },
        )
    }
}

// ------------------------------------------------------------- external data

@Composable
fun ExternalDataSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<ExternalDataKey?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    SettingsSubScreen("Rates and figures", onBack, snackbarHostState) {
        SettingsSwitch(
            title = "Update automatically",
            subtitle = "Once a day, only on wi-fi",
            checked = state.settings.externalDataEnabled,
            onCheckedChange = viewModel::setExternalDataEnabled,
        )
        SettingsItem(
            title = "Update now",
            subtitle = "Fetch the latest figures straight away",
            enabled = state.settings.externalDataEnabled,
            onClick = viewModel::refreshExternalData,
        )

        SettingsGroupHeader("Fetched automatically")
        state.externalData.automatic.forEach { item ->
            SettingsItem(
                title = item.key.displayName,
                subtitle = "${item.displayValue} · ${item.provenance}",
            )
        }

        SettingsGroupHeader("Entered by hand")
        SettingsNote(
            "These figures have no free public feed that can be relied on, so the app asks " +
                "you for them rather than showing a number that might quietly go stale. " +
                "Tap one to update it.",
        )
        state.externalData.manual.forEach { item ->
            SettingsItem(
                title = item.key.displayName,
                subtitle = "${item.displayValue} ${item.key.unit} · ${item.provenance}",
                onClick = { editing = item.key },
            )
        }
    }

    editing?.let { key ->
        ManualValueDialog(
            key = key,
            onConfirm = { value ->
                viewModel.setManualExternalValue(key, value)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun ManualValueDialog(
    key: ExternalDataKey,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(key.displayName) },
        text = {
            Column {
                LabelledTextField(
                    label = "Value (${key.unit})",
                    value = value,
                    onValueChange = { value = it },
                    keyboardType = KeyboardType.Decimal,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------- dashboard layout

@Composable
fun DashboardLayoutScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    SettingsSubScreen("Dashboard layout", onBack, snackbarHostState) {
        SettingsNote(
            "Turn cards on and off, and use the arrows to change the order they appear in " +
                "on your home screen.",
        )
        state.widgets.forEachIndexed { index, (entity, widget) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    SettingsSwitch(
                        title = widget.title,
                        checked = entity.isVisible,
                        onCheckedChange = { viewModel.setWidgetVisible(widget, it) },
                    )
                }
                IconButton(
                    onClick = { viewModel.moveWidget(widget, -1) },
                    enabled = index > 0,
                ) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                }
                IconButton(
                    onClick = { viewModel.moveWidget(widget, 1) },
                    enabled = index < state.widgets.lastIndex,
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
                }
            }
            HorizontalDivider()
        }
    }
}
