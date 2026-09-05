package com.rhys.financetracker.notify

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rhys.financetracker.MainActivity
import com.rhys.financetracker.R
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.time.DateUtils
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds and posts the app's notifications.
 *
 * Every method checks the POST_NOTIFICATIONS permission first: on Android 13
 * and above the user can refuse it, and posting without it silently fails —
 * checking makes that outcome explicit rather than mysterious.
 */
@Singleton
class Notifier @Inject constructor(
    private val context: Context,
) {

    private object Ids {
        const val UPCOMING_BILLS = 1001
        const val OVERDUE_BILLS = 1002
        const val LOW_BALANCE = 1003
        const val GOAL_PROGRESS = 1004
        const val ROLLOVER = 1005
        const val BACKUP = 1006
    }

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

    fun notifyUpcomingBills(count: Int, totalMinor: Long, nextDue: LocalDate) {
        if (count <= 0) return
        post(
            id = Ids.UPCOMING_BILLS,
            channel = NotificationChannels.BILLS,
            title = if (count == 1) "A bill is due soon" else "$count bills are due soon",
            text = "${Money.format(totalMinor)} due, starting ${DateUtils.relativeDescription(nextDue).lowercase()}",
        )
    }

    fun notifyOverdueBills(count: Int, totalMinor: Long) {
        if (count <= 0) return
        post(
            id = Ids.OVERDUE_BILLS,
            channel = NotificationChannels.BILLS,
            title = if (count == 1) "A payment is overdue" else "$count payments are overdue",
            text = "${Money.format(totalMinor)} was due and has not been recorded",
        )
    }

    fun notifyLowBalance(accountName: String, balanceMinor: Long) {
        post(
            id = Ids.LOW_BALANCE,
            channel = NotificationChannels.BALANCE,
            title = "$accountName is running low",
            text = "The balance is ${Money.format(balanceMinor)}",
        )
    }

    fun notifyGoalProgress(goalName: String, percent: Int) {
        post(
            id = Ids.GOAL_PROGRESS,
            channel = NotificationChannels.GOALS,
            title = if (percent >= 100) "$goalName is complete" else "$goalName is $percent% there",
            text = if (percent >= 100) {
                "You have reached your target. Well done."
            } else {
                "Keep going — your savings goal is on its way."
            },
        )
    }

    fun notifyRollover(monthsArchived: Int, transactionsCreated: Int) {
        if (monthsArchived == 0 && transactionsCreated == 0) return
        post(
            id = Ids.ROLLOVER,
            channel = NotificationChannels.SYSTEM,
            title = "Your new month is ready",
            text = buildString {
                if (transactionsCreated > 0) {
                    append("$transactionsCreated recurring ")
                    append(if (transactionsCreated == 1) "entry" else "entries")
                    append(" added")
                }
                if (monthsArchived > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("$monthsArchived ")
                    append(if (monthsArchived == 1) "month" else "months")
                    append(" archived")
                }
            },
        )
    }

    fun notifyBackup(success: Boolean, detail: String) {
        post(
            id = Ids.BACKUP,
            channel = NotificationChannels.SYSTEM,
            title = if (success) "Backup saved" else "Backup failed",
            text = detail,
        )
    }

    private fun post(id: Int, channel: String, title: String, text: String) {
        if (!hasPermission) return
        val manager = context.getSystemService<NotificationManager>() ?: return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        runCatching { manager.notify(id, notification) }
    }
}
