package com.rhys.financetracker.security

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.rhys.financetracker.data.prefs.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Decides when the app should be locked.
 *
 * It observes the *process* lifecycle rather than an activity's, so switching
 * to another app starts the timer and a configuration change does not.  When
 * the app returns to the foreground it locks if more than the configured
 * inactivity period has passed.
 *
 * Locking is a UI state, not a data state: nothing is decrypted or re-encrypted
 * here.  The lock exists to keep a shoulder-surfer or a borrowed phone out of
 * the household's finances.
 */
@Singleton
class AppLockManager @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val pinStore: PinStore,
) : DefaultLifecycleObserver {

    private val _isLocked = MutableStateFlow(false)

    /** True while the lock screen should be covering the app. */
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    private var backgroundedAt: Long? = null
    private var scope: CoroutineScope? = null

    /** Called once from `MainActivity`, which owns the coroutine scope. */
    fun attach(scope: CoroutineScope) {
        this.scope = scope
        scope.launch {
            // Lock immediately at launch if a lock is configured.
            val settings = settingsRepository.settings.first()
            _isLocked.value = settings.isLockEnabled && pinStore.isPinSet
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        backgroundedAt = System.currentTimeMillis()
    }

    override fun onStart(owner: LifecycleOwner) {
        val leftAt = backgroundedAt ?: return
        backgroundedAt = null
        scope?.launch {
            val settings = settingsRepository.settings.first()
            if (!settings.isLockEnabled || !pinStore.isPinSet) return@launch
            val idleMillis = System.currentTimeMillis() - leftAt
            val timeoutMillis = settings.autoLockMinutes * 60_000L
            // A timeout of zero means "lock the moment the app leaves the screen".
            if (settings.autoLockMinutes == 0 || idleMillis >= timeoutMillis) {
                _isLocked.value = true
            }
        }
    }

    /** Called by the lock screen once the PIN or biometric check has passed. */
    fun unlock() {
        _isLocked.value = false
        backgroundedAt = null
    }

    /** Locks straight away, from the "Lock now" action in Settings. */
    fun lockNow() {
        _isLocked.value = true
    }

    /** Called when the user turns the lock off, so the screen does not linger. */
    fun releaseLock() {
        _isLocked.value = false
    }
}
