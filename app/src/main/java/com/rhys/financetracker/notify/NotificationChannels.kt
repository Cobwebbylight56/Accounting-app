package com.rhys.financetracker.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService
import com.rhys.financetracker.R

/**
 * The notification channels the app uses.
 *
 * Separate channels let the user silence savings nudges while keeping bill
 * reminders, which is the difference between notifications being useful and
 * being turned off entirely.
 */
object NotificationChannels {

    const val BILLS = "bills"
    const val GOALS = "goals"
    const val BALANCE = "balance"
    const val SYSTEM = "system"

    fun createAll(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                BILLS,
                context.getString(R.string.channel_bills_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.channel_bills_desc) },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                GOALS,
                context.getString(R.string.channel_goals_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.channel_goals_desc) },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                BALANCE,
                context.getString(R.string.channel_balance_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.channel_balance_desc) },
        )
        manager.createNotificationChannel(
            // Quiet by design: the monthly rollover is housekeeping, not news.
            NotificationChannel(
                SYSTEM,
                context.getString(R.string.channel_system_name),
                NotificationManager.IMPORTANCE_MIN,
            ).apply { description = context.getString(R.string.channel_system_desc) },
        )
    }
}
