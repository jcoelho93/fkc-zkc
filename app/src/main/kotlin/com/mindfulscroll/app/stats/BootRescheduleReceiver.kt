package com.mindfulscroll.app.stats

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mindfulscroll.app.data.AppSettings

/** Re-enqueues the daily maintenance work, and the weekly reflection job if its prompt is on,
 *  after a reboot. WorkManager also persists periodic work across reboots on its own, so this is
 *  a defensive no-op in the common case (enqueueUniquePeriodicWork + KEEP makes it idempotent
 *  either way). */
class BootRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            WorkScheduler.scheduleDailyMaintenance(context)
            // Not Hilt-injected: a receiver this small does not need the graph, and AppSettings
            // only reads SharedPreferences here.
            WorkScheduler.syncWeeklyReflection(
                context,
                AppSettings(context.applicationContext).weeklyReflectionPromptEnabledNow(),
            )
        }
    }
}
