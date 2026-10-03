package com.rhys.financetracker.data.live

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Hears banking apps' alerts as they arrive and hands each to
 * [LivePaymentRepository], which adds the payment when there is one.
 *
 * Android only runs this once the user has given the app notification
 * access, and the Live payments switch decides whether anything is done with
 * what it hears. Alerts are read in memory and dropped; only a payment found
 * in one is kept.
 */
@AndroidEntryPoint
class BankAlertListener : NotificationListenerService() {

    @Inject lateinit var livePayments: LivePaymentRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val posted = sbn ?: return
        if (posted.packageName == packageName || posted.isOngoing) return
        val notification = posted.notification ?: return
        // A bundle's summary repeats the alerts inside it.
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            )?.toString()
        if (title.isNullOrBlank() && text.isNullOrBlank()) return

        val app = posted.packageName
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(app, 0)).toString()
        }.getOrDefault(BankApps.bankName(app) ?: app)

        scope.launch {
            runCatching { livePayments.record(app, label, title, text, posted.postTime) }
                .onFailure { Log.w(TAG, "Could not add a payment from an alert", it) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "BankAlertListener"
    }
}
