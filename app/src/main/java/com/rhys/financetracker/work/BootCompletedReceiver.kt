package com.rhys.financetracker.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Re-registers the background work after a reboot or an app update.
 *
 * WorkManager restores its own queue after a reboot, so this is belt and
 * braces — but a household budget that quietly stops updating itself would be
 * worse than a redundant re-schedule.
 */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject lateinit var workScheduler: WorkScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        // goAsync() would be tidier, but the scheduling call is quick and the
        // work itself is queued rather than executed here.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                workScheduler.scheduleAll()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
