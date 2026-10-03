package com.rhys.financetracker.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.domain.model.LockMethod
import com.rhys.financetracker.domain.model.ThemeMode
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

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
        val SHOW_INTRO = booleanPreferencesKey("show_intro")
        val SHARED_PEOPLE = stringPreferencesKey("shared_people")
        val PAYEES_KEPT = stringSetPreferencesKey("payees_kept_as_people")
        val PAYEES_HIDDEN = stringSetPreferencesKey("payees_not_people")
        val NOTIFICATIONS_ASKED = booleanPreferencesKey("notifications_asked")
        val HOME_TRIMMED = booleanPreferencesKey("home_trimmed")
        val CARD_VIEWS = stringPreferencesKey("card_views")
        val CATEGORY_PAYEES_CHOSEN = stringSetPreferencesKey("category_payees_chosen")
        val RESORTED_RULES_VERSION = intPreferencesKey("resorted_rules_version")
        val LAST_RESORT_SUMMARY = stringPreferencesKey("last_resort_summary")
        val LAST_RESORT_UNDO = stringPreferencesKey("last_resort_undo")
        val BACKUP_NUDGE_SNOOZED_UNTIL = longPreferencesKey("backup_nudge_snoozed_until")
        val LIVE_ALERTS = booleanPreferencesKey("live_alerts")
        val LIVE_ALERT_ACCOUNTS = stringPreferencesKey("live_alert_accounts")
        val LIVE_ALERT_APPS = stringSetPreferencesKey("live_alert_apps")
        val LIVE_ALERT_SEEN = stringSetPreferencesKey("live_alert_seen")
        val LIVE_ALERT_LAST = stringPreferencesKey("live_alert_last")
    }

    /** Defaults chosen so a fresh install is immediately usable and private. */
    object Defaults {
        val THEME_MODE = ThemeMode.SYSTEM

        // Off, so the app has its own look — the soft blue, sage and pink of
        // the home screen — rather than whatever the wallpaper suggests.
        const val DYNAMIC_COLOR = false
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
            showIntro = prefs[Keys.SHOW_INTRO] ?: true,
            sharedPeopleIds = prefs[Keys.SHARED_PEOPLE]?.let { stored ->
                stored.split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()
            },
            payeesKeptAsPeople = prefs[Keys.PAYEES_KEPT].orEmpty(),
            payeesNotPeople = prefs[Keys.PAYEES_HIDDEN].orEmpty(),
            notificationsAsked = prefs[Keys.NOTIFICATIONS_ASKED] ?: false,
            homeTrimmed = prefs[Keys.HOME_TRIMMED] ?: false,
            categoryPayeesChosen = prefs[Keys.CATEGORY_PAYEES_CHOSEN].orEmpty(),
            resortedRulesVersion = prefs[Keys.RESORTED_RULES_VERSION] ?: 0,
            lastResortSummary = prefs[Keys.LAST_RESORT_SUMMARY].orEmpty(),
            lastResortUndo = prefs[Keys.LAST_RESORT_UNDO].orEmpty(),
            cardViews = prefs[Keys.CARD_VIEWS].orEmpty().split(';')
                .mapNotNull { pair -> pair.split('=').takeIf { it.size == 2 }?.let { it[0] to it[1] } }
                .toMap(),
            backupNudgeSnoozedUntil = prefs[Keys.BACKUP_NUDGE_SNOOZED_UNTIL],
            liveAlerts = prefs[Keys.LIVE_ALERTS] ?: false,
            liveAlertAccounts = prefs[Keys.LIVE_ALERT_ACCOUNTS].orEmpty().split(';')
                .mapNotNull { pair ->
                    val parts = pair.split('=')
                    if (parts.size != 2) return@mapNotNull null
                    parts[1].toLongOrNull()?.let { parts[0] to it }
                }
                .toMap(),
            liveAlertApps = prefs[Keys.LIVE_ALERT_APPS].orEmpty(),
            liveAlertSeen = prefs[Keys.LIVE_ALERT_SEEN].orEmpty(),
            liveAlertLast = prefs[Keys.LIVE_ALERT_LAST].orEmpty(),
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

    suspend fun setShowIntro(enabled: Boolean) = put(Keys.SHOW_INTRO, enabled)

    /** Who the Shared tab on Home covers; see [AppSettings.sharedPeopleIds]. */
    suspend fun setSharedPeople(ids: Set<Long>) =
        put(Keys.SHARED_PEOPLE, ids.sorted().joinToString(","))

    /** Forgets the 1.34 list of payees the user filed, once it has been turned into marks. */
    suspend fun clearCategoryPayeesChosen() {
        context.dataStore.edit { it.remove(Keys.CATEGORY_PAYEES_CHOSEN) }
    }

    /**
     * Records a re-sort: which rules it used, and — when it moved anything —
     * what to say on Home and how to put it back.
     */
    suspend fun setResorted(rulesVersion: Int, summary: String, undo: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.RESORTED_RULES_VERSION] = rulesVersion
            if (summary.isNotEmpty()) {
                prefs[Keys.LAST_RESORT_SUMMARY] = summary
                prefs[Keys.LAST_RESORT_UNDO] = undo
            }
        }
    }

    /** Takes the re-sort note off Home. */
    suspend fun clearResortNote() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.LAST_RESORT_SUMMARY)
            prefs.remove(Keys.LAST_RESORT_UNDO)
        }
    }

    /** Remembers how a card is drawn — chart, bars, tiles or list — by the card's key. */
    suspend fun setCardView(card: String, view: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.CARD_VIEWS].orEmpty().split(';')
                .mapNotNull { pair -> pair.split('=').takeIf { it.size == 2 }?.let { it[0] to it[1] } }
                .toMap()
            prefs[Keys.CARD_VIEWS] = (current + (card to view)).entries.joinToString(";") { "${it.key}=${it.value}" }
        }
    }

    /** Records that Home's repeated cards have been switched off once; see DashboardViewModel. */
    suspend fun setHomeTrimmed() = put(Keys.HOME_TRIMMED, true)

    /** Hides the backup reminder on Home until [until] (epoch millis). */
    suspend fun snoozeBackupNudge(until: Long) = put(Keys.BACKUP_NUDGE_SNOOZED_UNTIL, until)

    /** Adding payments from banking apps' alerts, on or off. */
    suspend fun setLiveAlerts(enabled: Boolean) = put(Keys.LIVE_ALERTS, enabled)

    /** Which account [app]'s alerts go to; null goes back to choosing automatically. */
    suspend fun setLiveAlertAccount(app: String, accountId: Long?) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.LIVE_ALERT_ACCOUNTS].orEmpty().split(';')
                .mapNotNull { pair -> pair.split('=').takeIf { it.size == 2 }?.let { it[0] to it[1] } }
                .toMap()
            val updated = if (accountId == null) current - app else current + (app to accountId.toString())
            prefs[Keys.LIVE_ALERT_ACCOUNTS] = updated.entries.joinToString(";") { "${it.key}=${it.value}" }
        }
    }

    /** Lets alerts from [app], which is not a known bank, be read — or stops it. */
    suspend fun setLiveAlertAppAllowed(app: String, allowed: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.LIVE_ALERT_APPS].orEmpty()
            prefs[Keys.LIVE_ALERT_APPS] = if (allowed) current + app else current - app
        }
    }

    /**
     * Remembers that [app] (shown as [label]) sent something that read like
     * a payment, so Live payments can offer it. Only the app's name is kept,
     * never what the alert said.
     */
    suspend fun noteLiveAlertApp(app: String, label: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.LIVE_ALERT_SEEN].orEmpty()
            if (current.none { it.substringBefore('|') == app }) {
                prefs[Keys.LIVE_ALERT_SEEN] = current + "$app|${label.replace('|', ' ')}"
            }
        }
    }

    /** What was added last, for the Live payments page. */
    suspend fun setLiveAlertLast(summary: String) = put(Keys.LIVE_ALERT_LAST, summary)

    /** Records that Android has been asked once for permission to show reminders. */
    suspend fun setNotificationsAsked() = put(Keys.NOTIFICATIONS_ASKED, true)

    /**
     * Settles whether a payee is a person, overriding the app's own guess:
     * true keeps them on the people pages, false takes them off, null goes
     * back to guessing. [key] is PayeeNames.personKey of their name.
     */
    suspend fun setPayeeIsPerson(key: String, isPerson: Boolean?) {
        context.dataStore.edit { prefs ->
            val kept = prefs[Keys.PAYEES_KEPT].orEmpty() - key
            val hidden = prefs[Keys.PAYEES_HIDDEN].orEmpty() - key
            prefs[Keys.PAYEES_KEPT] = if (isPerson == true) kept + key else kept
            prefs[Keys.PAYEES_HIDDEN] = if (isPerson == false) hidden + key else hidden
        }
    }

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
    val useDynamicColor: Boolean = false,
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
    /** The short animation when the app opens. */
    val showIntro: Boolean = true,
    /**
     * The people the Shared tab on Home covers. Null until chosen, which
     * means everybody.
     */
    val sharedPeopleIds: Set<Long>? = null,
    /** Payees the user said are people, whatever the app guessed. */
    val payeesKeptAsPeople: Set<String> = emptySet(),
    /** Payees the user said are not people — PayPal, a shop. */
    val payeesNotPeople: Set<String> = emptySet(),
    /** True once Android has been asked for permission to show reminders. */
    val notificationsAsked: Boolean = false,
    /** True once Home's repeated cards have been switched off for this install. */
    val homeTrimmed: Boolean = false,
    /**
     * Payees, lowercase, the user filed in 1.34, before entries carried a
     * mark of their own. Turned into marks on the first re-sort, then cleared.
     */
    val categoryPayeesChosen: Set<String> = emptySet(),
    /** The MerchantCategoriser.RULES_VERSION everything was last re-sorted by. */
    val resortedRulesVersion: Int = 0,
    /** What the last automatic re-sort moved, for Home; see TidyUpRepository.ResortNote. */
    val lastResortSummary: String = "",
    /** "id:categoryId" pairs to put the last re-sort back. */
    val lastResortUndo: String = "",
    /** How each card is drawn, by card key; see setCardView. */
    val cardViews: Map<String, String> = emptyMap(),
    /** The backup reminder stays hidden until then (epoch millis). */
    val backupNudgeSnoozedUntil: Long? = null,
    /** Payments are added the moment a banking app's alert says money moved. */
    val liveAlerts: Boolean = false,
    /** The account each app's alerts go to, by package name; the rest are chosen automatically. */
    val liveAlertAccounts: Map<String, Long> = emptyMap(),
    /** Apps, by package name, the user allowed besides the known banks. */
    val liveAlertApps: Set<String> = emptySet(),
    /** "package|label" for other apps whose alerts read like payments. */
    val liveAlertSeen: Set<String> = emptySet(),
    /** What was last added from an alert. */
    val liveAlertLast: String = "",
) {
    val isLockEnabled: Boolean get() = lockMethod != LockMethod.NONE
    val requiresPin: Boolean
        get() = lockMethod == LockMethod.PIN || lockMethod == LockMethod.PIN_AND_BIOMETRIC
    val allowsBiometric: Boolean
        get() = lockMethod == LockMethod.BIOMETRIC || lockMethod == LockMethod.PIN_AND_BIOMETRIC
}
