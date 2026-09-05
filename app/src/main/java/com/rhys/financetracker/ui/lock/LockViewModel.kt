package com.rhys.financetracker.ui.lock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.data.prefs.SettingsRepository
import com.rhys.financetracker.security.AppLockManager
import com.rhys.financetracker.security.PinCheck
import com.rhys.financetracker.security.PinStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import kotlin.math.ceil

/**
 * Checks the PIN and reports why an attempt failed.
 *
 * The wrong-PIN delay is enforced in [PinStore] rather than here, so it still
 * applies if the process is killed and restarted between attempts.
 */
@HiltViewModel
class LockViewModel @Inject constructor(
    private val pinStore: PinStore,
    private val appLockManager: AppLockManager,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val status = MutableStateFlow(LockStatus())

    val state: StateFlow<LockState> = combine(
        settingsRepository.settings,
        status,
        appLockManager.isLocked,
    ) { settings, currentStatus, isLocked ->
        LockState(
            requiresPin = settings.requiresPin || !settings.allowsBiometric,
            allowsBiometric = settings.allowsBiometric,
            isUnlocked = !isLocked,
            statusMessage = currentStatus.message
                ?: "Enter your PIN to see your finances",
            isError = currentStatus.isError,
            isLockedOut = currentStatus.isLockedOut,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LockState())

    fun unlockWithPin(pin: String) {
        when (val result = pinStore.checkPin(pin)) {
            PinCheck.Correct -> {
                status.value = LockStatus()
                appLockManager.unlock()
            }
            PinCheck.NotSet -> {
                // Nothing to check against: do not strand the user behind a lock
                // that cannot be opened.
                appLockManager.unlock()
            }
            is PinCheck.Wrong -> status.value = LockStatus(
                message = "That PIN is not right. " +
                    "${result.attemptsRemaining} " +
                    (if (result.attemptsRemaining == 1) "attempt" else "attempts") +
                    " before a short wait.",
                isError = true,
            )
            is PinCheck.TemporarilyLocked -> {
                val seconds = ceil(result.millisRemaining / 1000.0).toInt()
                status.value = LockStatus(
                    message = "Too many wrong attempts. Try again in $seconds seconds.",
                    isError = true,
                    isLockedOut = true,
                )
            }
        }
    }

    fun unlockWithBiometric() {
        status.value = LockStatus()
        appLockManager.unlock()
    }

    fun reportError(message: String) {
        status.value = LockStatus(message = message, isError = true)
    }

    fun clearError() {
        if (status.value.isError && !status.value.isLockedOut) status.value = LockStatus()
    }
}

private data class LockStatus(
    val message: String? = null,
    val isError: Boolean = false,
    val isLockedOut: Boolean = false,
)

data class LockState(
    val requiresPin: Boolean = true,
    val allowsBiometric: Boolean = false,
    val isUnlocked: Boolean = false,
    val statusMessage: String = "Enter your PIN to see your finances",
    val isError: Boolean = false,
    val isLockedOut: Boolean = false,
)
