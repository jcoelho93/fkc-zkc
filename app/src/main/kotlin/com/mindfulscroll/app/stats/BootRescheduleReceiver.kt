package com.mindfulscroll.app.stats

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-enqueues the daily maintenance work after a reboot; WorkManager also persists periodic
 *  work across reboots on its own, so this is a defensive no-op in the common case
 *  (enqueueUniquePeriodicWork + KEEP makes it idempotent either way). */
class BootRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            WorkScheduler.scheduleDailyMaintenance(context)
        }
    }
}
