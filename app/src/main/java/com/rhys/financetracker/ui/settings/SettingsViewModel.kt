package com.rhys.financetracker.ui.settings

import android.net.Uri
import androidx.compose.foundation.layout.size
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.data.backup.BackupManager
import com.rhys.financetracker.data.backup.BackupSummary
import com.rhys.financetracker.data.local.dao.DashboardWidgetDao
import com.rhys.financetracker.data.local.entity.DashboardWidgetEntity
import com.rhys.financetracker.data.prefs.AppSettings
import com.rhys.financetracker.data.prefs.SettingsRepository
import com.rhys.financetracker.data.remote.ExternalDataRepository
import com.rhys.financetracker.data.remote.ExternalDataSnapshot
import com.rhys.financetracker.data.repository.SeedRepository
import com.rhys.financetracker.domain.model.DashboardWidget
import com.rhys.financetracker.domain.model.ExternalDataKey
import com.rhys.financetracker.domain.model.LockMethod
import com.rhys.financetracker.domain.model.ThemeMode
import com.rhys.financetracker.domain.rollover.MonthlyRolloverEngine
import com.rhys.financetracker.security.AppLockManager
import com.rhys.financetracker.security.PinStore
import com.rhys.financetracker.work.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Everything under Settings.
 *
 * One ViewModel serves all the settings sub-screens: they share the same
 * preferences object and the same set of actions, and splitting them would mean
 * five almost identical classes.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val backupManager: BackupManager,
    private val seedRepository: SeedRepository,
    private val externalDataRepository: ExternalDataRepository,
    private val rolloverEngine: MonthlyRolloverEngine,
    private val workScheduler: WorkScheduler,
    private val pinStore: PinStore,
    private val appLockManager: AppLockManager,
    private val widgetDao: DashboardWidgetDao,
) : ViewModel() {

    private val message = MutableStateFlow<String?>(null)
    private val isBusy = MutableStateFlow(false)
    private val pendingRestore = MutableStateFlow<BackupSummary?>(null)
    private val pendingRestoreUri = MutableStateFlow<Uri?>(null)

    val state: StateFlow<SettingsState> = combine(
        settingsRepository.settings,
        externalDataRepository.observeGrouped(),
        widgetDao.observeAll(),
        combine(message, isBusy) { text, busy -> text to busy },
        pendingRestore,
    ) { settings, external, widgets, status, restore ->
        SettingsState(
            settings = settings,
            externalData = external,
            widgets = widgets.mapNotNull { entity ->
                DashboardWidget.fromKey(entity.widgetKey)?.let { entity to it }
            }.sortedBy { it.first.position },
            isPinSet = pinStore.isPinSet,
            message = status.first,
            isBusy = status.second,
            pendingRestore = restore,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsState())

    // ------------------------------------------------------------ appearance

    fun setThemeMode(mode: ThemeMode) = launch { settingsRepository.setThemeMode(mode) }
    fun setDynamicColor(enabled: Boolean) = launch {
        settingsRepository.setDynamicColor(enabled)
    }
    fun setLargeText(enabled: Boolean) = launch { settingsRepository.setLargeText(enabled) }
    fun setCurrency(code: String) = launch { settingsRepository.setCurrencyCode(code) }

    // -------------------------------------------------------------- security

    /**
     * Sets or changes the PIN.  When the lock is switched off the stored hash is
     * removed too, so nothing is left behind on disk.
     */
    fun setPin(pin: String) {
        pinStore.setPin(pin)
        message.value = "PIN saved"
    }

    fun clearPin() {
        pinStore.clearPin()
        appLockManager.releaseLock()
        launch { settingsRepository.setLockMethod(LockMethod.NONE) }
        message.value = "PIN removed"
    }

    fun setLockMethod(method: LockMethod) {
        if (method != LockMethod.NONE && !pinStore.isPinSet) {
            message.value = "Set a PIN first — it is the fallback when a fingerprint fails"
            return
        }
        launch { settingsRepository.setLockMethod(method) }
        if (method == LockMethod.NONE) appLockManager.releaseLock()
    }

    fun setAutoLockMinutes(minutes: Int) = launch {
        settingsRepository.setAutoLockMinutes(minutes)
    }

    fun lockNow() = appLockManager.lockNow()

    // --------------------------------------------------------- notifications

    fun setNotifyBills(enabled: Boolean) = launch { settingsRepository.setNotifyBills(enabled) }
    fun setNotifyOverdue(enabled: Boolean) = launch {
        settingsRepository.setNotifyOverdue(enabled)
    }
    fun setNotifyGoals(enabled: Boolean) = launch { settingsRepository.setNotifyGoals(enabled) }
    fun setNotifyLowBalance(enabled: Boolean) = launch {
        settingsRepository.setNotifyLowBalance(enabled)
    }

    fun setReminderHour(hour: Int) = launch {
        settingsRepository.setReminderHour(hour)
        workScheduler.scheduleReminders(hour)
    }

    // ---------------------------------------------------------------- backup

    fun backupTo(uri: Uri) = busy {
        when (val result = backupManager.backupToFile(uri)) {
            is AppResult.Success -> {
                settingsRepository.setLastBackupAt(System.currentTimeMillis())
                "Backup saved: ${result.data.fileName}"
            }
            is AppResult.Failure -> result.message
        }
    }

    /** Reads the backup's header first, so the user can confirm before it replaces anything. */
    fun inspectBackup(uri: Uri) = busy {
        when (val result = backupManager.inspect(uri)) {
            is AppResult.Success -> {
                pendingRestore.value = result.data
                pendingRestoreUri.value = uri
                null
            }
            is AppResult.Failure -> result.message
        }
    }

    fun confirmRestore() {
        val uri = pendingRestoreUri.value ?: return
        pendingRestore.value = null
        pendingRestoreUri.value = null
        busy {
            when (val result = backupManager.restore(uri)) {
                is AppResult.Success -> result.data.describe()
                is AppResult.Failure -> result.message
            }
        }
    }

    fun cancelRestore() {
        pendingRestore.value = null
        pendingRestoreUri.value = null
    }

    fun setAutoBackupEnabled(enabled: Boolean) = launch {
        settingsRepository.setAutoBackupEnabled(enabled)
        workScheduler.scheduleBackup(enabled)
    }

    fun setAutoBackupFolder(uri: Uri?) = launch {
        settingsRepository.setAutoBackupFolderUri(uri?.toString())
        message.value = if (uri == null) "Backup folder cleared" else "Backup folder set"
    }

    fun setAutoBackupKeep(count: Int) = launch { settingsRepository.setAutoBackupKeep(count) }

    fun suggestedBackupName(): String = backupManager.suggestedFileName()

    // --------------------------------------------------------- external data

    fun setExternalDataEnabled(enabled: Boolean) = launch {
        settingsRepository.setExternalDataEnabled(enabled)
        workScheduler.scheduleExternalData(enabled)
        if (enabled) externalDataRepository.refreshAll()
    }

    fun refreshExternalData() = busy {
        when (val result = externalDataRepository.refreshAll()) {
            is AppResult.Success -> "${result.data} figures updated"
            is AppResult.Failure -> result.message
        }
    }

    fun setManualExternalValue(key: ExternalDataKey, value: String) = busy {
        externalDataRepository.setManualValue(key, value).errorMessageOrNull() ?: "Saved"
    }

    // ------------------------------------------------------------------ data

    fun loadSampleData() = busy {
        when (val result = seedRepository.loadSampleData()) {
            is AppResult.Success -> "${result.data} example records added"
            is AppResult.Failure -> result.message
        }
    }

    fun clearAllData() = busy {
        seedRepository.clearAllData().errorMessageOrNull() ?: "All data cleared"
    }

    /** Re-archives every finished month from the current transactions. */
    fun rebuildArchive() = busy {
        when (val result = rolloverEngine.runRollover(rebuild = true)) {
            is AppResult.Success -> "${result.data.monthsArchived.size} months rebuilt"
            is AppResult.Failure -> result.message
        }
    }

    fun runMonthlyUpdate() = busy {
        when (val result = rolloverEngine.runRollover()) {
            is AppResult.Success ->
                if (result.data.didAnything) {
                    "${result.data.transactionsGenerated} entries added, " +
                        "${result.data.monthsArchived.size} months archived"
                } else {
                    "Everything is already up to date"
                }
            is AppResult.Failure -> result.message
        }
    }

    // ------------------------------------------------------------- dashboard

    fun setWidgetVisible(widget: DashboardWidget, visible: Boolean) = launch {
        widgetDao.setVisible(widget.key, visible)
    }

    fun moveWidget(widget: DashboardWidget, direction: Int) {
        viewModelScope.launch {
            val current = widgetDao.getAll().sortedBy { it.position }.toMutableList()
            val index = current.indexOfFirst { it.widgetKey == widget.key }
            val target = index + direction
            if (index < 0 || target !in current.indices) return@launch
            val moved = current.removeAt(index)
            current.add(target, moved)
            widgetDao.upsertAll(
                current.mapIndexed { position, entity -> entity.copy(position = position) },
            )
        }
    }

    fun clearMessage() {
        message.value = null
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    /** Runs a long action with a progress flag and reports its outcome once. */
    private fun busy(block: suspend () -> String?) {
        viewModelScope.launch {
            isBusy.value = true
            try {
                message.value = block()
            } finally {
                isBusy.value = false
            }
        }
    }
}

data class SettingsState(
    val settings: AppSettings = AppSettings(),
    val externalData: ExternalDataSnapshot = ExternalDataSnapshot(),
    val widgets: List<Pair<DashboardWidgetEntity, DashboardWidget>> = emptyList(),
    val isPinSet: Boolean = false,
    val message: String? = null,
    val isBusy: Boolean = false,
    val pendingRestore: BackupSummary? = null,
)
