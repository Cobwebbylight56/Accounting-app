package com.rhys.financetracker.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.domain.model.LockMethod
import com.rhys.financetracker.domain.model.ThemeMode
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * User preferences.
 *
 * Preferences are kept apart from the database so that clearing or restoring
 * financial data never changes how the app looks or how it is locked.  Nothing
 * secret is stored here — the PIN hash lives in encrypted storage; see
 * [com.rhys.financetracker.security.PinStore].
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val context: Context,
) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val CURRENCY_CODE = stringPreferencesKey("currency_code")
        val LOCK_METHOD = stringPreferencesKey("lock_method")
        val AUTO_LOCK_MINUTES = intPreferencesKey("auto_lock_minutes")
        val NOTIFY_BILLS = booleanPreferencesKey("notify_bills")
        val NOTIFY_OVERDUE = booleanPreferencesKey("notify_overdue")
        val NOTIFY_GOALS = booleanPreferencesKey("notify_goals")
        val NOTIFY_LOW_BALANCE = booleanPreferencesKey("notify_low_balance")
        val REMINDER_HOUR = intPreferencesKey("reminder_hour")
        val AUTO_BACKUP_ENABLED = booleanPreferencesKey("auto_backup_enabled")
        val AUTO_BACKUP_FOLDER_URI = stringPreferencesKey("auto_backup_folder_uri")
        val AUTO_BACKUP_KEEP = intPreferencesKey("auto_backup_keep")
        val LAST_BACKUP_AT = longPreferencesKey("last_backup_at")
        val EXTERNAL_DATA_ENABLED = booleanPreferencesKey("external_data_enabled")
        val LAST_ROLLOVER_MONTH = stringPreferencesKey("last_rollover_month")
        val START_DESTINATION = stringPreferencesKey("start_destination")
        val WEEK_START_MONDAY = booleanPreferencesKey("week_start_monday")
        val SHOW_ARCHIVED = booleanPreferencesKey("show_archived")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val DEFAULT_ACCOUNT_ID = longPreferencesKey("default_account_id")
        val LARGE_TEXT = booleanPreferencesKey("large_text")
    }

    /** Defaults chosen so a fresh install is immediately usable and private. */
    object Defaults {
        val THEME_MODE = ThemeMode.SYSTEM
        const val DYNAMIC_COLOR = true
        val LOCK_METHOD = LockMethod.NONE
        const val AUTO_LOCK_MINUTES = 2
        const val NOTIFY_BILLS = true
        const val NOTIFY_OVERDUE = true
        const val NOTIFY_GOALS = false
        const val NOTIFY_LOW_BALANCE = true
        const val REMINDER_HOUR = 9
        const val AUTO_BACKUP_ENABLED = false
        const val AUTO_BACKUP_KEEP = 10
        const val EXTERNAL_DATA_ENABLED = false
        const val WEEK_START_MONDAY = true
        const val SHOW_ARCHIVED = false
        const val LARGE_TEXT = false
    }

    /**
     * A read that survives a corrupted preferences file: rather than crashing
     * on launch, the app falls back to defaults.
     */
    private val preferences: Flow<Preferences> = context.dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }

    val settings: Flow<AppSettings> = preferences.map { prefs ->
        AppSettings(
            themeMode = prefs[Keys.THEME_MODE]?.toThemeMode() ?: Defaults.THEME_MODE,
            useDynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: Defaults.DYNAMIC_COLOR,
            currencyCode = prefs[Keys.CURRENCY_CODE] ?: Money.DEFAULT_CURRENCY_CODE,
            lockMethod = prefs[Keys.LOCK_METHOD]?.toLockMethod() ?: Defaults.LOCK_METHOD,
            autoLockMinutes = prefs[Keys.AUTO_LOCK_MINUTES] ?: Defaults.AUTO_LOCK_MINUTES,
            notifyBills = prefs[Keys.NOTIFY_BILLS] ?: Defaults.NOTIFY_BILLS,
            notifyOverdue = prefs[Keys.NOTIFY_OVERDUE] ?: Defaults.NOTIFY_OVERDUE,
            notifyGoals = prefs[Keys.NOTIFY_GOALS] ?: Defaults.NOTIFY_GOALS,
            notifyLowBalance = prefs[Keys.NOTIFY_LOW_BALANCE] ?: Defaults.NOTIFY_LOW_BALANCE,
            reminderHour = prefs[Keys.REMINDER_HOUR] ?: Defaults.REMINDER_HOUR,
            autoBackupEnabled = prefs[Keys.AUTO_BACKUP_ENABLED] ?: Defaults.AUTO_BACKUP_ENABLED,
            autoBackupFolderUri = prefs[Keys.AUTO_BACKUP_FOLDER_URI],
            autoBackupKeep = prefs[Keys.AUTO_BACKUP_KEEP] ?: Defaults.AUTO_BACKUP_KEEP,
            lastBackupAt = prefs[Keys.LAST_BACKUP_AT],
            externalDataEnabled = prefs[Keys.EXTERNAL_DATA_ENABLED]
                ?: Defaults.EXTERNAL_DATA_ENABLED,
            lastRolloverMonth = prefs[Keys.LAST_ROLLOVER_MONTH],
            startDestination = prefs[Keys.START_DESTINATION],
            weekStartsMonday = prefs[Keys.WEEK_START_MONDAY] ?: Defaults.WEEK_START_MONDAY,
            showArchived = prefs[Keys.SHOW_ARCHIVED] ?: Defaults.SHOW_ARCHIVED,
            onboardingComplete = prefs[Keys.ONBOARDING_COMPLETE] ?: false,
            defaultAccountId = prefs[Keys.DEFAULT_ACCOUNT_ID]?.takeIf { it > 0L },
            largeText = prefs[Keys.LARGE_TEXT] ?: Defaults.LARGE_TEXT,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) = put(Keys.THEME_MODE, mode.name)
    suspend fun setDynamicColor(enabled: Boolean) = put(Keys.DYNAMIC_COLOR, enabled)
    suspend fun setCurrencyCode(code: String) = put(Keys.CURRENCY_CODE, code)
    suspend fun setLockMethod(method: LockMethod) = put(Keys.LOCK_METHOD, method.name)
    suspend fun setAutoLockMinutes(minutes: Int) =
        put(Keys.AUTO_LOCK_MINUTES, minutes.coerceIn(0, 120))

    suspend fun setNotifyBills(enabled: Boolean) = put(Keys.NOTIFY_BILLS, enabled)
    suspend fun setNotifyOverdue(enabled: Boolean) = put(Keys.NOTIFY_OVERDUE, enabled)
    suspend fun setNotifyGoals(enabled: Boolean) = put(Keys.NOTIFY_GOALS, enabled)
    suspend fun setNotifyLowBalance(enabled: Boolean) = put(Keys.NOTIFY_LOW_BALANCE, enabled)
    suspend fun setReminderHour(hour: Int) = put(Keys.REMINDER_HOUR, hour.coerceIn(0, 23))

    suspend fun setAutoBackupEnabled(enabled: Boolean) = put(Keys.AUTO_BACKUP_ENABLED, enabled)
    suspend fun setAutoBackupFolderUri(uri: String?) {
        context.dataStore.edit { prefs ->
            if (uri == null) prefs.remove(Keys.AUTO_BACKUP_FOLDER_URI)
            else prefs[Keys.AUTO_BACKUP_FOLDER_URI] = uri
        }
    }

    suspend fun setAutoBackupKeep(count: Int) = put(Keys.AUTO_BACKUP_KEEP, count.coerceIn(1, 100))
    suspend fun setLastBackupAt(timestamp: Long) = put(Keys.LAST_BACKUP_AT, timestamp)
    suspend fun setExternalDataEnabled(enabled: Boolean) = put(Keys.EXTERNAL_DATA_ENABLED, enabled)
    suspend fun setLastRolloverMonth(yearMonth: String) = put(Keys.LAST_ROLLOVER_MONTH, yearMonth)
    suspend fun setStartDestination(route: String) = put(Keys.START_DESTINATION, route)
    suspend fun setWeekStartsMonday(enabled: Boolean) = put(Keys.WEEK_START_MONDAY, enabled)
    suspend fun setShowArchived(enabled: Boolean) = put(Keys.SHOW_ARCHIVED, enabled)
    suspend fun setOnboardingComplete(complete: Boolean) = put(Keys.ONBOARDING_COMPLETE, complete)
    suspend fun setDefaultAccountId(id: Long?) = put(Keys.DEFAULT_ACCOUNT_ID, id ?: 0L)
    suspend fun setLargeText(enabled: Boolean) = put(Keys.LARGE_TEXT, enabled)

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    private fun String.toThemeMode(): ThemeMode =
        runCatching { ThemeMode.valueOf(this) }.getOrDefault(Defaults.THEME_MODE)

    private fun String.toLockMethod(): LockMethod =
        runCatching { LockMethod.valueOf(this) }.getOrDefault(Defaults.LOCK_METHOD)
}

/** Every user preference in one immutable object. */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val useDynamicColor: Boolean = true,
    val currencyCode: String = Money.DEFAULT_CURRENCY_CODE,
    val lockMethod: LockMethod = LockMethod.NONE,
    val autoLockMinutes: Int = 2,
    val notifyBills: Boolean = true,
    val notifyOverdue: Boolean = true,
    val notifyGoals: Boolean = false,
    val notifyLowBalance: Boolean = true,
    val reminderHour: Int = 9,
    val autoBackupEnabled: Boolean = false,
    val autoBackupFolderUri: String? = null,
    val autoBackupKeep: Int = 10,
    val lastBackupAt: Long? = null,
    val externalDataEnabled: Boolean = false,
    val lastRolloverMonth: String? = null,
    val startDestination: String? = null,
    val weekStartsMonday: Boolean = true,
    val showArchived: Boolean = false,
    val onboardingComplete: Boolean = false,
    val defaultAccountId: Long? = null,
    val largeText: Boolean = false,
) {
    val isLockEnabled: Boolean get() = lockMethod != LockMethod.NONE
    val requiresPin: Boolean
        get() = lockMethod == LockMethod.PIN || lockMethod == LockMethod.PIN_AND_BIOMETRIC
    val allowsBiometric: Boolean
        get() = lockMethod == LockMethod.BIOMETRIC || lockMethod == LockMethod.PIN_AND_BIOMETRIC
}
